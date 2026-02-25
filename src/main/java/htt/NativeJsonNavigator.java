package htt;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class NativeJsonNavigator implements AutoCloseable {

    private final Arena arena;
    private final MemorySegment segment;

    public NativeJsonNavigator(String filePath) throws Exception {
        this.arena = Arena.ofConfined();

        Path path = Path.of(filePath);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            this.segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
        }
    }

    public long findValue(String key) {
        byte[] keyBytes = ("\"" + key + "\"").getBytes();
        long size = segment.byteSize();

        for (long i = 0; i < size - keyBytes.length; i++) {
            if (match(i, keyBytes)) {

                long current = i + keyBytes.length;

                while (current < size && segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, current) != ':') {
                    current++;
                }

                current++;
                while (current < size && isWhitespace(segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, current))) {
                    current++;
                }

                long startValue = current;
                long endValue = findValueEnd(startValue);

                return JsonPointer.pack(startValue, (int) (endValue - startValue));
            }
        }
        return -1;
    }

    private boolean match(long offset, byte[] target) {
        for (int i = 0; i < target.length; i++) {
            if (segment.get(ValueLayout.JAVA_BYTE, offset + i) != target[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean isWhitespace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t';
    }

    public String extract(long pointer) {
        if (pointer == -1) return null;
        int offset = JsonPointer.unpackOffset(pointer);
        int length = JsonPointer.unpackLength(pointer);

        byte[] bytes = new byte[length];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, bytes, 0, length);
        return new String(bytes).trim().replace("\"", "");
    }

    private long findValueEnd(long start) {
        long current = start;
        long size = segment.byteSize();
        boolean inQuotes = false;
        int bracketDepth = 0;
        int braceDepth = 0;

        while (current < size) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, current);

            if (b == '"' && (current == 0 || segment.get(ValueLayout.JAVA_BYTE, current - 1) != '\\')) {
                inQuotes = !inQuotes;
            }

            if (!inQuotes) {
                if (b == '[') bracketDepth++;
                else if (b == ']') bracketDepth--;
                else if (b == '{') braceDepth++;
                else if (b == '}') braceDepth--;

                if (bracketDepth <= 0 && braceDepth <= 0) {
                    if (b == ',' || b == '}' || b == ']' || b == '\n') break;
                }
            }
            current++;
        }
        return current;
    }

    public int getAsInt(long pointer) {
        int offset = JsonPointer.unpackOffset(pointer);
        int length = JsonPointer.unpackLength(pointer);

        int result = 0;
        for (int i = 0; i < length; i++) {
            byte b = segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, offset + i);
            if (b >= '0' && b <= '9') {
                result = result * 10 + (b - '0');
            }
        }
        return result;
    }

    public long getElementAt(long arrayPointer, int index) {
        int offset = JsonPointer.unpackOffset(arrayPointer);
        int length = JsonPointer.unpackLength(arrayPointer);

        long current = offset + 1;
        long end = offset + length;
        int currentIndex = 0;

        while (current < end) {
            while (current < end && isWhitespace(segment.get(ValueLayout.JAVA_BYTE, current))) current++;

            if (currentIndex == index) {
                long startValue = current;
                long endValue = findValueEnd(startValue);
                return JsonPointer.pack(startValue, (int) (endValue - startValue));
            }

            current = findValueEnd(current);
            while (current < end && segment.get(ValueLayout.JAVA_BYTE, current) != ',') current++;
            current++;
            currentIndex++;
        }
        return -1;
    }

    public long findPath(String... keys) {
        if (keys.length == 0) return -1;

        long currentPtr = findValue(keys[0]);

        for (int i = 1; i < keys.length; i++) {
            if (currentPtr == -1) return -1;

            int offset = JsonPointer.unpackOffset(currentPtr);
            int length = JsonPointer.unpackLength(currentPtr);

            currentPtr = findValueWithin(keys[i], offset, length);
        }
        return currentPtr;
    }
    private long findValueWithin(String key, int startOffset, int rangeLength) {
        byte[] keyBytes = ("\"" + key + "\"").getBytes();
        long limit = (long) startOffset + rangeLength;

        for (long i = startOffset; i < limit - keyBytes.length; i++) {
            if (match(i, keyBytes)) {
                long current = i + keyBytes.length;
                while (current < limit && segment.get(ValueLayout.JAVA_BYTE, current) != ':') current++;
                current++;
                while (current < limit && isWhitespace(segment.get(ValueLayout.JAVA_BYTE, current))) current++;

                long startValue = current;
                long endValue = findValueEnd(startValue);
                return JsonPointer.pack(startValue, (int) (endValue - startValue));
            }
        }
        return -1;
    }

    public boolean equals(long pointer, String expected) {
        if (pointer == -1) return false;
        int offset = JsonPointer.unpackOffset(pointer);
        int length = JsonPointer.unpackLength(pointer);

        long start = offset;
        int len = length;
        if (segment.get(ValueLayout.JAVA_BYTE, start) == '"') {
            start++;
            len -= 2;
        }

        byte[] expectedBytes = expected.getBytes();
        if (len != expectedBytes.length) return false;

        for (int i = 0; i < len; i++) {
            if (segment.get(ValueLayout.JAVA_BYTE, start + i) != expectedBytes[i]) {
                return false;
            }
        }
        return true;
    }

    public boolean isNull(long pointer) {
        return equals(pointer, "null");
    }

    public boolean getAsBoolean(long pointer) {
        return equals(pointer, "true");
    }

    @Override
    public void close() {
        arena.close();
    }
}