package io.github.redriottank.nativejson;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Memory-mapped, lazily validated JSON reader.
 *
 * <p>This class is an implementation detail. Use {@link JsonDocument} as the
 * public entry point.</p>
 */
final class JsonNavigator implements AutoCloseable {

    private static final byte QUOTE = '"';
    private static final byte BACKSLASH = '\\';
    private static final byte COLON = ':';
    private static final byte COMMA = ',';
    private static final byte OPEN_BRACE = '{';
    private static final byte CLOSE_BRACE = '}';
    private static final byte OPEN_BRACKET = '[';
    private static final byte CLOSE_BRACKET = ']';

    /** Guards against stack exhaustion on pathologically nested documents. */
    private static final int MAX_NESTING_DEPTH = 500;

    private final Arena arena;
    private final MemorySegment segment;
    private final int size;
    private final int rootOffset;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile boolean closed;

    JsonNavigator(Path path) throws IOException {
        this(path, Arena.ofShared());
    }

    /**
     * Creates a navigator over {@code path}, taking ownership of {@code arena}.
     *
     * <p>The supplied arena is closed if construction fails, and on
     * {@link #close()} otherwise. Package-private so that the lifecycle tests
     * can observe both paths.</p>
     */
    JsonNavigator(Path path, Arena arena) throws IOException {
        Objects.requireNonNull(arena, "arena must not be null");
        try {
            long fileSize = Files.size(path);
            if (fileSize == 0) {
                throw new JsonParseException("JSON document is empty: " + path);
            }
            if (fileSize > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "JSON documents larger than 2 GiB are not supported (file is " + fileSize + " bytes): " + path);
            }

            MemorySegment segment;
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena);
            }

            this.arena = arena;
            this.segment = segment;
            this.size = (int) fileSize;

            int start = 0;
            if (size >= 3
                    && byteAt(0) == (byte) 0xEF
                    && byteAt(1) == (byte) 0xBB
                    && byteAt(2) == (byte) 0xBF) {
                start = 3;
            }
            start = skipWhitespace(start, size);
            if (start >= size) {
                throw new JsonParseException("JSON document contains only whitespace: " + path, start);
            }
            byte first = byteAt(start);
            if (!startsValue(first)) {
                throw new JsonParseException("Unexpected character '" + printable(first) + "' at start of document", start);
            }
            this.rootOffset = start;
        } catch (IOException | RuntimeException | Error failure) {
            closeArena(arena, failure);
            throw failure;
        }
    }

    long rootPointer() {
        return PackedPointer.pack(rootOffset, size - rootOffset);
    }

    int byteSize() {
        return size;
    }

    void ensureOpen() {
        lock.readLock().lock();
        try {
            checkOpen();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The JsonDocument has already been closed");
        }
    }

    JsonType typeOf(long pointer) {
        lock.readLock().lock();
        try {
            checkOpen();
            return typeOfLocked(pointer);
        } finally {
            lock.readLock().unlock();
        }
    }

    private JsonType typeOfLocked(long pointer) {
        int offset = offset(pointer);
        int end = end(pointer);
        int position = skipWhitespace(offset, end);
        if (position >= end) {
            throw new JsonParseException("Unexpected end of input", position);
        }
        byte b = byteAt(position);
        return switch (b) {
            case QUOTE -> JsonType.STRING;
            case OPEN_BRACE -> JsonType.OBJECT;
            case OPEN_BRACKET -> JsonType.ARRAY;
            case 't', 'f' -> JsonType.BOOLEAN;
            case 'n' -> JsonType.NULL;
            default -> {
                if (b == '-' || isDigit(b)) {
                    yield JsonType.NUMBER;
                }
                throw new JsonParseException("Unexpected character '" + printable(b) + "'", position);
            }
        };
    }

    long findPath(long pointer, String... path) {
        lock.readLock().lock();
        try {
            checkOpen();
            return findPathLocked(pointer, path);
        } finally {
            lock.readLock().unlock();
        }
    }

    private long findPathLocked(long rootPointer, String... path) {
        long current = rootPointer;
        for (String segment : path) {
            if (segment == null) {
                throw new IllegalArgumentException("path segments must not be null");
            }
            JsonType type = typeOfLocked(current);
            if (type == JsonType.ARRAY) {
                int index = parseIndex(segment);
                if (index < 0) {
                    return -1;
                }
                current = elementAtLocked(current, index);
            } else if (type == JsonType.OBJECT) {
                current = findKeyLocked(current, segment);
            } else {
                return -1;
            }
            if (current == -1) {
                return -1;
            }
        }
        return current;
    }

    private static int parseIndex(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    long findKey(long objectPointer, String key) {
        lock.readLock().lock();
        try {
            checkOpen();
            return findKeyLocked(objectPointer, key);
        } finally {
            lock.readLock().unlock();
        }
    }

    private long findKeyLocked(long objectPointer, String key) {
        int offset = offset(objectPointer);
        int end = end(objectPointer);
        int position = skipWhitespace(offset, end);
        if (position >= end || byteAt(position) != OPEN_BRACE) {
            return -1;
        }

        boolean ascii = isAscii(key);
        byte[] keyBytes = ascii ? null : key.getBytes(StandardCharsets.UTF_8);
        position = skipWhitespace(position + 1, end);
        if (position < end && byteAt(position) == CLOSE_BRACE) {
            return -1;
        }

        while (true) {
            if (position >= end || byteAt(position) != QUOTE) {
                throw new JsonParseException("Expected an object key", position);
            }
            int keyStart = position;
            int keyEnd = skipString(position, end);

            position = skipWhitespace(keyEnd, end);
            if (position >= end || byteAt(position) != COLON) {
                throw new JsonParseException("Expected ':' after object key", position);
            }
            position = skipWhitespace(position + 1, end);

            int valueStart = position;
            int valueEnd = skipValue(position, end);

            if (keyMatches(keyStart, keyEnd, key, ascii, keyBytes)) {
                return PackedPointer.pack(valueStart, valueEnd - valueStart);
            }

            position = skipWhitespace(valueEnd, end);
            if (position >= end) {
                throw new JsonParseException("Unterminated object", end);
            }
            byte separator = byteAt(position);
            if (separator == COMMA) {
                position = skipWhitespace(position + 1, end);
            } else if (separator == CLOSE_BRACE) {
                return -1;
            } else {
                throw new JsonParseException("Expected ',' or '}' in object", position);
            }
        }
    }

    long elementAt(long arrayPointer, int index) {
        lock.readLock().lock();
        try {
            checkOpen();
            return elementAtLocked(arrayPointer, index);
        } finally {
            lock.readLock().unlock();
        }
    }

    private long elementAtLocked(long arrayPointer, int index) {
        if (index < 0) {
            return -1;
        }
        int offset = offset(arrayPointer);
        int end = end(arrayPointer);
        int position = skipWhitespace(offset, end);
        if (position >= end || byteAt(position) != OPEN_BRACKET) {
            return -1;
        }

        position = skipWhitespace(position + 1, end);
        if (position < end && byteAt(position) == CLOSE_BRACKET) {
            return -1;
        }

        int currentIndex = 0;
        while (true) {
            int valueStart = position;
            int valueEnd = skipValue(position, end);
            if (currentIndex == index) {
                return PackedPointer.pack(valueStart, valueEnd - valueStart);
            }

            position = skipWhitespace(valueEnd, end);
            if (position >= end) {
                throw new JsonParseException("Unterminated array", end);
            }
            byte separator = byteAt(position);
            if (separator == COMMA) {
                position = skipWhitespace(position + 1, end);
                currentIndex++;
            } else if (separator == CLOSE_BRACKET) {
                return -1;
            } else {
                throw new JsonParseException("Expected ',' or ']' in array", position);
            }
        }
    }

    int sizeOf(long pointer) {
        lock.readLock().lock();
        try {
            checkOpen();
            return sizeOfLocked(pointer);
        } finally {
            lock.readLock().unlock();
        }
    }

    private int sizeOfLocked(long pointer) {
        JsonType type = typeOfLocked(pointer);
        int offset = offset(pointer);
        int end = end(pointer);
        int position = skipWhitespace(offset, end);

        if (type == JsonType.OBJECT) {
            position = skipWhitespace(position + 1, end);
            if (position < end && byteAt(position) == CLOSE_BRACE) {
                return 0;
            }
            int count = 0;
            while (true) {
                if (position >= end || byteAt(position) != QUOTE) {
                    throw new JsonParseException("Expected an object key", position);
                }
                position = skipWhitespace(skipString(position, end), end);
                if (position >= end || byteAt(position) != COLON) {
                    throw new JsonParseException("Expected ':' after object key", position);
                }
                position = skipWhitespace(skipValue(position + 1, end), end);
                count++;
                if (position >= end) {
                    throw new JsonParseException("Unterminated object", end);
                }
                byte separator = byteAt(position);
                if (separator == COMMA) {
                    position = skipWhitespace(position + 1, end);
                } else if (separator == CLOSE_BRACE) {
                    return count;
                } else {
                    throw new JsonParseException("Expected ',' or '}' in object", position);
                }
            }
        }

        if (type == JsonType.ARRAY) {
            position = skipWhitespace(position + 1, end);
            if (position < end && byteAt(position) == CLOSE_BRACKET) {
                return 0;
            }
            int count = 0;
            while (true) {
                position = skipWhitespace(skipValue(position, end), end);
                count++;
                if (position >= end) {
                    throw new JsonParseException("Unterminated array", end);
                }
                byte separator = byteAt(position);
                if (separator == COMMA) {
                    position = skipWhitespace(position + 1, end);
                } else if (separator == CLOSE_BRACKET) {
                    return count;
                } else {
                    throw new JsonParseException("Expected ',' or ']' in array", position);
                }
            }
        }

        throw new IllegalStateException("size() requires an object or array, but this value is " + type);
    }

    List<String> keysOf(long pointer) {
        lock.readLock().lock();
        try {
            checkOpen();
            return keysOfLocked(pointer);
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<String> keysOfLocked(long pointer) {
        if (typeOfLocked(pointer) != JsonType.OBJECT) {
            throw new IllegalStateException("keys() requires an object");
        }
        int offset = offset(pointer);
        int end = end(pointer);
        int position = skipWhitespace(offset, end);
        position = skipWhitespace(position + 1, end);
        if (position < end && byteAt(position) == CLOSE_BRACE) {
            return List.of();
        }

        List<String> keys = new ArrayList<>();
        while (true) {
            if (position >= end || byteAt(position) != QUOTE) {
                throw new JsonParseException("Expected an object key", position);
            }
            int keyStart = position;
            int keyEnd = skipString(position, end);
            keys.add(decodeString(keyStart, keyEnd));

            position = skipWhitespace(keyEnd, end);
            if (position >= end || byteAt(position) != COLON) {
                throw new JsonParseException("Expected ':' after object key", position);
            }
            position = skipWhitespace(skipValue(position + 1, end), end);
            if (position >= end) {
                throw new JsonParseException("Unterminated object", end);
            }
            byte separator = byteAt(position);
            if (separator == COMMA) {
                position = skipWhitespace(position + 1, end);
            } else if (separator == CLOSE_BRACE) {
                return List.copyOf(keys);
            } else {
                throw new JsonParseException("Expected ',' or '}' in object", position);
            }
        }
    }

    void validate(long pointer) {
        lock.readLock().lock();
        try {
            checkOpen();
            validatedValueEnd(pointer);
        } finally {
            lock.readLock().unlock();
        }
    }

    String raw(long pointer) {
        lock.readLock().lock();
        try {
            checkOpen();
            int start = skipWhitespace(offset(pointer), end(pointer));
            return copy(start, validatedValueEnd(pointer));
        } finally {
            lock.readLock().unlock();
        }
    }

    private int validatedValueEnd(long pointer) {
        int offset = offset(pointer);
        int end = end(pointer);
        int start = skipWhitespace(offset, end);
        int valueEnd = skipValue(start, end);
        if (skipWhitespace(valueEnd, end) != end) {
            throw new JsonParseException("Unexpected content after JSON value", valueEnd);
        }
        return valueEnd;
    }

    String asString(long pointer) {
        lock.readLock().lock();
        try {
            checkOpen();
            int offset = offset(pointer);
            int end = end(pointer);
            int start = skipWhitespace(offset, end);
            if (start < end && byteAt(start) == QUOTE) {
                return decodeString(start, skipString(start, end));
            }
            return raw(pointer);
        } finally {
            lock.readLock().unlock();
        }
    }

    boolean asBoolean(long pointer) {
        lock.readLock().lock();
        try {
            checkOpen();
            return asBooleanLocked(pointer);
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean asBooleanLocked(long pointer) {
        JsonType type = typeOfLocked(pointer);
        if (type != JsonType.BOOLEAN) {
            throw new IllegalStateException("asBoolean() requires a boolean, but this value is " + type);
        }
        if (matchesLiteral(pointer, "true")) {
            return true;
        }
        if (matchesLiteral(pointer, "false")) {
            return false;
        }
        throw new JsonParseException("Invalid boolean literal", offset(pointer));
    }

    boolean isNull(long pointer) {
        lock.readLock().lock();
        try {
            checkOpen();
            return typeOfLocked(pointer) == JsonType.NULL && matchesLiteral(pointer, "null");
        } finally {
            lock.readLock().unlock();
        }
    }

    int asInt(long pointer) {
        lock.readLock().lock();
        try {
            checkOpen();
            long value = parseLong(pointer);
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new NumberFormatException("Number does not fit in an int: " + raw(pointer));
            }
            return (int) value;
        } finally {
            lock.readLock().unlock();
        }
    }

    long asLong(long pointer) {
        lock.readLock().lock();
        try {
            checkOpen();
            return parseLong(pointer);
        } finally {
            lock.readLock().unlock();
        }
    }

    double asDouble(long pointer) {
        lock.readLock().lock();
        try {
            checkOpen();
            JsonType type = typeOfLocked(pointer);
            if (type != JsonType.NUMBER) {
                throw new IllegalStateException("asDouble() requires a number, but this value is " + type);
            }
            return Double.parseDouble(raw(pointer));
        } finally {
            lock.readLock().unlock();
        }
    }

    private long parseLong(long pointer) {
        JsonType type = typeOfLocked(pointer);
        if (type != JsonType.NUMBER) {
            throw new IllegalStateException("asLong() requires a number, but this value is " + type);
        }
        int offset = offset(pointer);
        int end = end(pointer);
        int position = skipWhitespace(offset, end);
        int numberEnd = skipNumber(position, end);
        ensureValueTerminator(numberEnd, end);

        boolean negative = false;
        if (byteAt(position) == '-') {
            negative = true;
            position++;
        }

        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multiplyMin = limit / 10;
        long result = 0;
        while (position < numberEnd) {
            int digit = byteAt(position) - '0';
            if (digit < 0 || digit > 9) {
                throw new NumberFormatException("Number is not an integer: " + copy(offset, numberEnd));
            }
            if (result < multiplyMin) {
                throw new NumberFormatException("Number overflows a long: " + copy(offset, numberEnd));
            }
            result *= 10;
            if (result < limit + digit) {
                throw new NumberFormatException("Number overflows a long: " + copy(offset, numberEnd));
            }
            result -= digit;
            position++;
        }
        return negative ? result : -result;
    }

    private boolean matchesLiteral(long pointer, String literal) {
        int offset = offset(pointer);
        int end = end(pointer);
        int start = skipWhitespace(offset, end);
        int literalEnd = start + literal.length();
        if (literalEnd > end) {
            return false;
        }
        for (int i = 0; i < literal.length(); i++) {
            if (byteAt(start + i) != (byte) literal.charAt(i)) {
                return false;
            }
        }
        return literalEnd == end || isWhitespace(byteAt(literalEnd));
    }

    private int skipValue(int position, int end) {
        try {
            return skipValue(position, end, 0);
        } catch (StackOverflowError e) {
            throw new JsonParseException("Maximum JSON nesting depth exceeded", position);
        }
    }

    private int skipValue(int position, int end, int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            throw new JsonParseException("Maximum JSON nesting depth of " + MAX_NESTING_DEPTH + " exceeded", position);
        }
        position = skipWhitespace(position, end);
        if (position >= end) {
            throw new JsonParseException("Unexpected end of input", position);
        }
        byte b = byteAt(position);
        int valueEnd = switch (b) {
            case QUOTE -> skipString(position, end);
            case OPEN_BRACE -> skipObject(position, end, depth + 1);
            case OPEN_BRACKET -> skipArray(position, end, depth + 1);
            case 't' -> skipLiteral(position, end, "true");
            case 'f' -> skipLiteral(position, end, "false");
            case 'n' -> skipLiteral(position, end, "null");
            default -> {
                if (b == '-' || isDigit(b)) {
                    yield skipNumber(position, end);
                }
                throw new JsonParseException("Unexpected character '" + printable(b) + "'", position);
            }
        };
        ensureValueTerminator(valueEnd, end);
        return valueEnd;
    }

    private int skipObject(int position, int end, int depth) {
        position = skipWhitespace(position + 1, end);
        if (position < end && byteAt(position) == CLOSE_BRACE) {
            return position + 1;
        }
        while (true) {
            if (position >= end || byteAt(position) != QUOTE) {
                throw new JsonParseException("Expected an object key", position);
            }
            position = skipWhitespace(skipString(position, end), end);
            if (position >= end || byteAt(position) != COLON) {
                throw new JsonParseException("Expected ':' after object key", position);
            }
            position = skipWhitespace(skipValue(position + 1, end, depth), end);
            if (position >= end) {
                throw new JsonParseException("Unterminated object", end);
            }
            byte separator = byteAt(position);
            if (separator == COMMA) {
                position = skipWhitespace(position + 1, end);
            } else if (separator == CLOSE_BRACE) {
                return position + 1;
            } else {
                throw new JsonParseException("Expected ',' or '}' in object", position);
            }
        }
    }

    private int skipArray(int position, int end, int depth) {
        position = skipWhitespace(position + 1, end);
        if (position < end && byteAt(position) == CLOSE_BRACKET) {
            return position + 1;
        }
        while (true) {
            position = skipWhitespace(skipValue(position, end, depth), end);
            if (position >= end) {
                throw new JsonParseException("Unterminated array", end);
            }
            byte separator = byteAt(position);
            if (separator == COMMA) {
                position = skipWhitespace(position + 1, end);
            } else if (separator == CLOSE_BRACKET) {
                return position + 1;
            } else {
                throw new JsonParseException("Expected ',' or ']' in array", position);
            }
        }
    }

    private int skipString(int position, int end) {
        int current = position + 1;
        while (current < end) {
            byte b = byteAt(current);
            if (b == QUOTE) {
                return current + 1;
            }
            if (b == BACKSLASH) {
                if (current + 1 >= end) {
                    throw new JsonParseException("Unterminated escape sequence", current);
                }
                byte escaped = byteAt(current + 1);
                switch (escaped) {
                    case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> current += 2;
                    case 'u' -> {
                        if (current + 5 >= end) {
                            throw new JsonParseException("Unterminated unicode escape", current);
                        }
                        for (int i = 1; i <= 4; i++) {
                            if (hexValue(byteAt(current + 1 + i)) < 0) {
                                throw new JsonParseException("Invalid unicode escape", current);
                            }
                        }
                        current += 6;
                    }
                    default -> throw new JsonParseException("Invalid escape sequence '\\" + printable(escaped) + "'", current);
                }
            } else if (b >= 0 && b < 0x20) {
                throw new JsonParseException("Unescaped control character in string", current);
            } else if (b >= 0) {
                current++;
            } else {
                int codePoint = codePointAt(current, end);
                current += utf8Length(codePoint);
            }
        }
        throw new JsonParseException("Unterminated string", position);
    }

    private int skipNumber(int position, int end) {
        int current = position;
        if (current < end && byteAt(current) == '-') {
            current++;
        }
        if (current >= end) {
            throw new JsonParseException("Invalid number", position);
        }
        if (byteAt(current) == '0') {
            current++;
            if (current < end && isDigit(byteAt(current))) {
                throw new JsonParseException("Leading zero in number", position);
            }
        } else if (isDigitOneToNine(byteAt(current))) {
            current++;
            while (current < end && isDigit(byteAt(current))) {
                current++;
            }
        } else {
            throw new JsonParseException("Invalid number", position);
        }

        if (current < end && byteAt(current) == '.') {
            current++;
            if (current >= end || !isDigit(byteAt(current))) {
                throw new JsonParseException("Invalid fractional part", position);
            }
            while (current < end && isDigit(byteAt(current))) {
                current++;
            }
        }

        if (current < end && (byteAt(current) == 'e' || byteAt(current) == 'E')) {
            current++;
            if (current < end && (byteAt(current) == '+' || byteAt(current) == '-')) {
                current++;
            }
            if (current >= end || !isDigit(byteAt(current))) {
                throw new JsonParseException("Invalid exponent", position);
            }
            while (current < end && isDigit(byteAt(current))) {
                current++;
            }
        }
        return current;
    }

    private int skipLiteral(int position, int end, String literal) {
        int literalEnd = position + literal.length();
        if (literalEnd > end) {
            throw new JsonParseException("Unexpected end of input", position);
        }
        for (int i = 0; i < literal.length(); i++) {
            if (byteAt(position + i) != (byte) literal.charAt(i)) {
                throw new JsonParseException("Invalid literal, expected '" + literal + "'", position);
            }
        }
        return literalEnd;
    }

    private void ensureValueTerminator(int position, int end) {
        if (position >= end) {
            return;
        }
        byte b = byteAt(position);
        if (!isWhitespace(b) && b != COMMA && b != CLOSE_BRACE && b != CLOSE_BRACKET) {
            throw new JsonParseException("Unexpected character '" + printable(b) + "' after value", position);
        }
    }

    private int skipWhitespace(int position, int end) {
        while (position < end) {
            byte b = byteAt(position);
            if (b != ' ' && b != '\n' && b != '\r' && b != '\t') {
                break;
            }
            position++;
        }
        return position;
    }

    private String decodeString(int start, int end) {
        int from = start + 1;
        int to = end - 1;
        if (to < from) {
            throw new JsonParseException("Unterminated string", start);
        }

        boolean escaped = false;
        for (int i = from; i < to; i++) {
            if (byteAt(i) == BACKSLASH) {
                escaped = true;
                break;
            }
        }
        if (!escaped) {
            return copy(from, to);
        }

        StringBuilder result = new StringBuilder(to - from);
        int position = from;
        while (position < to) {
            byte b = byteAt(position);
            if (b == BACKSLASH) {
                if (position + 1 >= to) {
                    throw new JsonParseException("Unterminated escape sequence", position);
                }
                byte escapedByte = byteAt(position + 1);
                switch (escapedByte) {
                    case '"' -> {
                        result.append('"');
                        position += 2;
                    }
                    case '\\' -> {
                        result.append('\\');
                        position += 2;
                    }
                    case '/' -> {
                        result.append('/');
                        position += 2;
                    }
                    case 'b' -> {
                        result.append('\b');
                        position += 2;
                    }
                    case 'f' -> {
                        result.append('\f');
                        position += 2;
                    }
                    case 'n' -> {
                        result.append('\n');
                        position += 2;
                    }
                    case 'r' -> {
                        result.append('\r');
                        position += 2;
                    }
                    case 't' -> {
                        result.append('\t');
                        position += 2;
                    }
                    case 'u' -> {
                        if (position + 5 >= to) {
                            throw new JsonParseException("Unterminated unicode escape", position);
                        }
                        int codePoint = 0;
                        for (int i = 1; i <= 4; i++) {
                            int hex = hexValue(byteAt(position + 1 + i));
                            if (hex < 0) {
                                throw new JsonParseException("Invalid unicode escape", position);
                            }
                            codePoint = (codePoint << 4) | hex;
                        }
                        result.append((char) codePoint);
                        position += 6;
                    }
                    default -> throw new JsonParseException(
                            "Invalid escape sequence '\\" + printable(escapedByte) + "'", position);
                }
            } else if (b >= 0) {
                result.append((char) b);
                position++;
            } else {
                int codePoint = codePointAt(position, to);
                result.appendCodePoint(codePoint);
                position += utf8Length(codePoint);
            }
        }
        return result.toString();
    }

    private int codePointAt(int position, int end) {
        int first = byteAt(position) & 0xFF;
        int length;
        int codePoint;
        int minimum;
        if ((first & 0xE0) == 0xC0) {
            length = 2;
            codePoint = first & 0x1F;
            minimum = 0x80;
        } else if ((first & 0xF0) == 0xE0) {
            length = 3;
            codePoint = first & 0x0F;
            minimum = 0x800;
        } else if ((first & 0xF8) == 0xF0) {
            length = 4;
            codePoint = first & 0x07;
            minimum = 0x10000;
        } else {
            throw new JsonParseException("Invalid UTF-8 sequence", position);
        }
        if (position + length > end) {
            throw new JsonParseException("Truncated UTF-8 sequence", position);
        }
        for (int i = 1; i < length; i++) {
            int continuation = byteAt(position + i) & 0xFF;
            if ((continuation & 0xC0) != 0x80) {
                throw new JsonParseException("Invalid UTF-8 continuation byte", position + i);
            }
            codePoint = (codePoint << 6) | (continuation & 0x3F);
        }
        if (codePoint < minimum || codePoint > 0x10FFFF || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new JsonParseException("Invalid UTF-8 code point", position);
        }
        return codePoint;
    }

    private int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    private boolean keyMatches(int keyStart, int keyEnd, String key, boolean ascii, byte[] keyBytes) {
        int from = keyStart + 1;
        int to = keyEnd - 1;
        int rawLen = to - from;
        int targetLen = ascii ? key.length() : keyBytes.length;

        if (rawLen < targetLen) {
            return false;
        }

        if (rawLen == targetLen) {
            boolean hasEscape = false;
            if (ascii) {
                for (int i = 0; i < rawLen; i++) {
                    byte b = byteAt(from + i);
                    if (b == BACKSLASH) {
                        hasEscape = true;
                        break;
                    }
                    if (b != (byte) key.charAt(i)) {
                        return false;
                    }
                }
            } else {
                for (int i = 0; i < rawLen; i++) {
                    byte b = byteAt(from + i);
                    if (b == BACKSLASH) {
                        hasEscape = true;
                        break;
                    }
                    if (b != keyBytes[i]) {
                        return false;
                    }
                }
            }
            if (!hasEscape) {
                return true;
            }
        } else {
            boolean hasEscape = false;
            for (int i = from; i < to; i++) {
                if (byteAt(i) == BACKSLASH) {
                    hasEscape = true;
                    break;
                }
            }
            if (!hasEscape) {
                return false;
            }
        }

        return decodeString(keyStart, keyEnd).equals(key);
    }

    private static boolean isAscii(String s) {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private String copy(int start, int end) {
        byte[] bytes = new byte[end - start];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, start, bytes, 0, bytes.length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private int offset(long pointer) {
        return PackedPointer.offset(pointer);
    }

    private int end(long pointer) {
        return PackedPointer.offset(pointer) + PackedPointer.length(pointer);
    }

    private byte byteAt(int position) {
        return segment.get(ValueLayout.JAVA_BYTE, position);
    }

    private static boolean startsValue(byte b) {
        return b == QUOTE || b == OPEN_BRACE || b == OPEN_BRACKET || b == '-' || b == 't' || b == 'f' || b == 'n'
                || isDigit(b);
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    private static boolean isDigitOneToNine(byte b) {
        return b >= '1' && b <= '9';
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t';
    }

    private static int hexValue(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }

    private static String printable(byte b) {
        return b >= 0x20 && b < 0x7F ? String.valueOf((char) b) : String.format("\\x%02X", b & 0xFF);
    }

    private static void closeArena(Arena arena, Throwable failure) {
        try {
            arena.close();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            // Only mark the document closed once the mapping is actually gone.
            // If close() throws, the navigator remains usable and a later
            // close() call can retry the release instead of leaking the arena.
            arena.close();
            closed = true;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
