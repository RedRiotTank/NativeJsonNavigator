package io.github.redriottank.nativejson;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JsonDocumentTest {

    @TempDir
    Path tempDir;

    private JsonDocument document;

    private static final String JSON = """
            {
              "stringKey": "Hello World",
              "emptyString": "",
              "intKey": 12345,
              "negativeInt": -42,
              "zero": 0,
              "longKey": 9223372036854775807,
              "doubleKey": 3.14159,
              "expKey": 1.5e3,
              "boolTrue": true,
              "boolFalse": false,
              "nullKey": null,
              "spacedKey"   :    "valueWithSpaces",
              "escaped": "line1\\nline2\\t\\"quoted\\"\\\\slash\\u00e9\\uD83D\\uDE00",
              "unicode": "héllo 😀",
              "arrayKey": [ 10, "text", true, null, [1, 2] ],
              "nestedObj": { "innerKey": "innerValue", "innerInt": 99 },
              "objArray": [{"id":1,"name":"a"},{"id":2,"name":"b"}],
              "emptyObj": {},
              "emptyArray": [],
              "dup": 1,
              "dup": 2,
              "tricky": "the key \\"innerKey\\" is in a string",
              "escapedKey\\u0041": "escaped-key-value",
              "innerKey": 777
            }
            """;

    @BeforeEach
    void setUp() throws Exception {
        Path file = tempDir.resolve("test_data.json");
        Files.writeString(file, JSON);
        document = JsonDocument.open(file);
    }

    @AfterEach
    void tearDown() {
        if (document != null) {
            document.close();
        }
    }

    private static Path writeJson(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void extractsStrings() {
        assertEquals("Hello World", document.find("stringKey").asString());
        assertEquals("", document.find("emptyString").asString());
        assertEquals("valueWithSpaces", document.find("spacedKey").asString());
    }

    @Test
    void decodesEscapeSequences() {
        assertEquals("line1\nline2\t\"quoted\"\\slashé😀", document.find("escaped").asString());
    }

    @Test
    void decodesRawUnicode() {
        assertEquals("héllo 😀", document.find("unicode").asString());
    }

    @Test
    void extractsNumbers() {
        assertEquals(12345, document.find("intKey").asInt());
        assertEquals(-42, document.find("negativeInt").asInt());
        assertEquals(0, document.find("zero").asInt());
        assertEquals(Long.MAX_VALUE, document.find("longKey").asLong());
        assertEquals(3.14159, document.find("doubleKey").asDouble(), 1e-12);
        assertEquals(1500.0, document.find("expKey").asDouble(), 1e-12);
    }

    @Test
    void rejectsNumbersThatDoNotFit() {
        assertThrows(NumberFormatException.class, () -> document.find("longKey").asInt());
        assertThrows(NumberFormatException.class, () -> document.find("doubleKey").asInt());
        assertThrows(NumberFormatException.class, () -> document.find("doubleKey").asLong());
    }

    @Test
    void extractsBooleans() {
        assertTrue(document.find("boolTrue").asBoolean());
        assertFalse(document.find("boolFalse").asBoolean());
    }

    @Test
    void extractsNull() {
        JsonValue value = document.find("nullKey");
        assertNotNull(value);
        assertEquals(JsonType.NULL, value.type());
        assertTrue(value.isNull());
        assertEquals("null", value.asString());
    }

    @Test
    void reportsTypes() {
        assertEquals(JsonType.STRING, document.find("stringKey").type());
        assertEquals(JsonType.NUMBER, document.find("intKey").type());
        assertEquals(JsonType.BOOLEAN, document.find("boolTrue").type());
        assertEquals(JsonType.NULL, document.find("nullKey").type());
        assertEquals(JsonType.OBJECT, document.find("nestedObj").type());
        assertEquals(JsonType.ARRAY, document.find("arrayKey").type());

        assertTrue(document.find("stringKey").isString());
        assertTrue(document.find("intKey").isNumber());
        assertTrue(document.find("boolTrue").isBoolean());
        assertTrue(document.find("nestedObj").isObject());
        assertTrue(document.find("arrayKey").isArray());
    }

    @Test
    void typeMismatchesThrow() {
        assertThrows(IllegalStateException.class, () -> document.find("stringKey").asInt());
        assertThrows(IllegalStateException.class, () -> document.find("stringKey").asBoolean());
        assertThrows(IllegalStateException.class, () -> document.find("intKey").asBoolean());
        assertThrows(IllegalStateException.class, () -> document.find("nullKey").asInt());
        assertThrows(IllegalStateException.class, () -> document.find("stringKey").size());
        assertThrows(IllegalStateException.class, () -> document.find("stringKey").keys());
    }

    @Test
    void returnsNullForMissingKeys() {
        assertNull(document.find("doesNotExist"));
    }

    @Test
    void lookupIsScopedToTheCurrentObject() {
        // "innerKey" exists both at the top level and inside nestedObj.
        assertEquals(777, document.find("innerKey").asInt());
        assertEquals("innerValue", document.find("nestedObj").get("innerKey").asString());
        assertEquals(99, document.find("nestedObj").get("innerInt").asInt());
    }

    @Test
    void keyTextInsideStringsDoesNotConfuseLookups() {
        assertTrue(document.find("tricky").asString().contains("\"innerKey\""));
        assertEquals(777, document.find("innerKey").asInt());
    }

    @Test
    void navigatesArrays() {
        JsonValue array = document.find("arrayKey");
        assertEquals(10, array.getAt(0).asInt());
        assertEquals("text", array.getAt(1).asString());
        assertTrue(array.getAt(2).asBoolean());
        assertTrue(array.getAt(3).isNull());
        assertTrue(array.getAt(4).isArray());
        assertEquals(2, array.getAt(4).size());
        assertEquals("[1, 2]", array.getAt(4).raw());
    }

    @Test
    void arrayBoundsAreHandled() {
        JsonValue array = document.find("arrayKey");
        assertNull(array.getAt(99));
        assertNull(array.getAt(-1));
        assertNull(document.find("stringKey").getAt(0));
    }

    @Test
    void navigatesNestedObjectsAndArrays() {
        assertEquals("innerValue", document.root().findPath("nestedObj", "innerKey").asString());
        assertEquals("b", document.root().findPath("objArray", "1", "name").asString());
        assertEquals(2, document.root().findPath("objArray", "1", "id").asInt());
        assertNull(document.root().findPath("objArray", "9", "name"));
        assertNull(document.root().findPath("objArray", "not-an-index", "name"));
    }

    @Test
    void emptyContainersHaveSizeZero() {
        assertEquals(0, document.find("emptyObj").size());
        assertEquals(0, document.find("emptyArray").size());
        assertTrue(document.find("emptyObj").keys().isEmpty());
    }

    @Test
    void returnsObjectSizesAndKeys() {
        assertEquals(2, document.find("nestedObj").size());
        assertEquals(List.of("innerKey", "innerInt"), document.find("nestedObj").keys());
        assertEquals(5, document.find("arrayKey").size());
    }

    @Test
    void firstDuplicateKeyWins() {
        assertEquals(1, document.find("dup").asInt());
    }

    @Test
    void matchesEscapedKeys() {
        assertEquals("escaped-key-value", document.find("escapedKeyA").asString());
    }

    @Test
    void rawReturnsVerbatimJson() {
        assertEquals("\"Hello World\"", document.find("stringKey").raw());
        assertEquals("12345", document.find("intKey").raw());
        assertTrue(document.find("nestedObj").raw().contains("\"innerInt\": 99"));
        assertEquals(JsonType.OBJECT, document.root().type());
        assertTrue(document.root().raw().startsWith("{"));
    }

    @Test
    void supportsDocumentMetadata() throws Exception {
        assertEquals(Files.size(tempDir.resolve("test_data.json")), document.byteSize());
        assertEquals(tempDir.resolve("test_data.json"), document.path());
    }

    @Test
    void supportsRootArraysAndScalars() throws Exception {
        Path arrayFile = writeJson(tempDir, "array.json", "[1, 2, 3]");
        try (JsonDocument arrayDoc = JsonDocument.open(arrayFile)) {
            assertEquals(JsonType.ARRAY, arrayDoc.root().type());
            assertEquals(3, arrayDoc.root().size());
            assertEquals(2, arrayDoc.root().getAt(1).asInt());
            assertNull(arrayDoc.find("anything"));
        }

        Path scalarFile = writeJson(tempDir, "scalar.json", "42");
        try (JsonDocument scalarDoc = JsonDocument.open(scalarFile)) {
            assertEquals(42, scalarDoc.root().asInt());
        }
    }

    @Test
    void skipsUtf8ByteOrderMark() throws Exception {
        byte[] json = JSON.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[json.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(json, 0, withBom, 3, json.length);
        Path file = tempDir.resolve("bom.json");
        Files.write(file, withBom);

        try (JsonDocument bomDoc = JsonDocument.open(file)) {
            assertEquals("Hello World", bomDoc.find("stringKey").asString());
        }
    }

    @Test
    void rejectsEmptyDocuments() throws Exception {
        Path empty = writeJson(tempDir, "empty.json", "");
        assertThrows(JsonParseException.class, () -> JsonDocument.open(empty));

        Path whitespace = writeJson(tempDir, "whitespace.json", "  \n\t ");
        assertThrows(JsonParseException.class, () -> JsonDocument.open(whitespace));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        assertMalformed("{\"a\": }");
        assertMalformed("{\"a\": 1,}");
        assertMalformed("{\"a\": tru}");
        assertMalformed("{\"a\": 01}");
        assertMalformed("{\"a\": 1.}");
        assertMalformed("{\"a\": 1e}");
        assertMalformed("{\"a\": \"unterminated}");
        assertMalformed("{\"a\": \"bad\\q\"}");
        assertMalformed("{\"a\": 1} trailing");
        assertMalformed("[1, 2");
        assertMalformed("{\"a\" 1}");
        assertMalformed("{a: 1}");
    }

    private void assertMalformed(String content) throws Exception {
        Path file = writeJson(tempDir, "malformed-" + Math.abs(content.hashCode()) + ".json", content);
        try (JsonDocument malformed = JsonDocument.open(file)) {
            assertThrows(JsonParseException.class, () -> malformed.root().raw());
        }
    }

    @Test
    void rejectsPathologicallyDeepNesting() throws Exception {
        String deep = "[".repeat(1_500) + "]".repeat(1_500);
        Path file = writeJson(tempDir, "deep.json", deep);
        try (JsonDocument deepDoc = JsonDocument.open(file)) {
            assertThrows(JsonParseException.class, () -> deepDoc.root().raw());
        }
    }

    @Test
    void closeIsIdempotentAndInvalidatesValues() {
        JsonValue value = document.find("stringKey");
        document.close();
        document.close();
        assertThrows(IllegalStateException.class, value::asString);
        assertThrows(IllegalStateException.class, value::findPath);
        assertThrows(IllegalStateException.class, document::root);
        assertThrows(IllegalStateException.class, document::validate);
        document = null;
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> JsonDocument.open((Path) null));
        assertThrows(NullPointerException.class, () -> JsonDocument.open((String) null));
        assertThrows(IllegalArgumentException.class, () -> document.root().get(null));
        assertThrows(IllegalArgumentException.class, () -> document.root().findPath((String[]) null));
        assertThrows(IllegalArgumentException.class, () -> document.root().findPath((String) null));
    }

    @Test
    void failedOpensReleaseTheArena() throws Exception {
        Path empty = writeJson(tempDir, "leak-empty.json", "");
        Path whitespace = writeJson(tempDir, "leak-whitespace.json", "  \n\t ");
        Path bomOnly = tempDir.resolve("leak-bom.json");
        Files.write(bomOnly, new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        Path invalidStart = writeJson(tempDir, "leak-invalid.json", "}");

        for (Path file : List.of(empty, whitespace, bomOnly, invalidStart)) {
            TrackingArena arena = new TrackingArena();
            assertThrows(JsonParseException.class, () -> new JsonNavigator(file, arena), file.toString());
            assertTrue(arena.isClosed(), "arena should be closed after rejecting " + file);
        }
    }

    @Test
    void failedCloseLeavesTheNavigatorUsableAndCanBeRetried() throws Exception {
        Path file = writeJson(tempDir, "retry-close.json", "{\"a\":1}");
        FailingArena arena = new FailingArena(1);
        JsonNavigator navigator = new JsonNavigator(file, arena);
        try {
            assertThrows(IllegalStateException.class, navigator::close);

            // The failed close must not mark the document closed or lose the mapping.
            assertEquals(JsonType.OBJECT, navigator.typeOf(navigator.rootPointer()));

            navigator.close();
            navigator.close();
            assertTrue(arena.isClosed());
            assertEquals(2, arena.closeAttempts(), "close should be retried exactly once");
            assertThrows(IllegalStateException.class, () -> navigator.typeOf(navigator.rootPointer()));
        } finally {
            if (!arena.isClosed()) {
                navigator.close();
            }
        }
    }

    @Test
    void validateChecksTheWholeDocumentWithoutRaw() throws Exception {
        document.validate();
        document.root().validate();

        Path malformed = writeJson(tempDir, "validate-bad.json", "{\"a\":1,}");
        try (JsonDocument malformedDoc = JsonDocument.open(malformed)) {
            assertThrows(JsonParseException.class, malformedDoc::validate);
            assertThrows(JsonParseException.class, () -> malformedDoc.root().validate());
        }
    }

    @Test
    void validateCanCheckASingleSubtree() throws Exception {
        Path file = writeJson(tempDir, "validate-subtree.json", "{\"ok\":1,\"bad\":}");
        try (JsonDocument doc = JsonDocument.open(file)) {
            doc.root().get("ok").validate();
            assertThrows(JsonParseException.class, doc::validate);
        }
    }

    @Test
    void rejectsInvalidUtf8InUnescapedStrings() throws Exception {
        assertInvalidUtf8(bytes("{\"s\":\"", new byte[] {(byte) 0xC3, 0x28}, "\"}"));
        assertInvalidUtf8(bytes("{\"s\":\"", new byte[] {(byte) 0xC0, (byte) 0xAF}, "\"}"));
        assertInvalidUtf8(bytes("{\"s\":\"", new byte[] {(byte) 0xE2, (byte) 0x82}, "\"}"));
        assertInvalidUtf8(bytes("{\"s\":\"", new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, "\"}"));
    }

    @Test
    void rejectsInvalidUtf8InEscapedStrings() throws Exception {
        assertInvalidUtf8(bytes("{\"s\":\"", new byte[] {(byte) 0xC3, 0x28}, "\\u0041\"}"));
        assertInvalidUtf8(bytes("{\"s\":\"\\u0041", new byte[] {(byte) 0xC3, 0x28}, "\"}"));
    }

    private void assertInvalidUtf8(byte[] content) throws Exception {
        Path file = tempDir.resolve("invalid-utf8-" + Arrays.hashCode(content) + ".json");
        Files.write(file, content);
        try (JsonDocument doc = JsonDocument.open(file)) {
            assertThrows(JsonParseException.class, doc::validate);
            assertThrows(JsonParseException.class, () -> doc.root().raw());
            assertThrows(JsonParseException.class, () -> doc.find("s").asString());
        }
    }

    private static byte[] bytes(String prefix, byte[] invalid, String suffix) {
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefixBytes.length + invalid.length + suffixBytes.length];
        System.arraycopy(prefixBytes, 0, result, 0, prefixBytes.length);
        System.arraycopy(invalid, 0, result, prefixBytes.length, invalid.length);
        System.arraycopy(suffixBytes, 0, result, prefixBytes.length + invalid.length, suffixBytes.length);
        return result;
    }

    @Test
    void validateScansLargeDocumentsWithoutCopyingThem() throws Exception {
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        assumeTrue(Files.isExecutable(javaBinary), "java executable not available");

        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }

        Path file = tempDir.resolve("large-validate.json");
        writeLargeJson(file, 64L * 1024 * 1024);

        Process process = new ProcessBuilder(
                javaBinary.toString(),
                "-Xmx64m",
                "-cp",
                classpath,
                LargeDocumentValidationMain.class.getName(),
                file.toString())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(finished, "large-document validation timed out");
        assertEquals(0, process.exitValue(), output);
    }

    private static void writeLargeJson(Path file, long payloadBytes) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("{\"payload\":\"");
            char[] chunk = new char[64 * 1024];
            Arrays.fill(chunk, 'a');
            long remaining = payloadBytes;
            while (remaining > 0) {
                int count = (int) Math.min(chunk.length, remaining);
                writer.write(chunk, 0, count);
                remaining -= count;
            }
            writer.write("\"}");
        }
    }

    private static final class TrackingArena implements Arena {

        private final Arena delegate = Arena.ofShared();
        private volatile boolean closed;

        @Override
        public MemorySegment allocate(long byteSize, long byteAlignment) {
            return delegate.allocate(byteSize, byteAlignment);
        }

        @Override
        public MemorySegment.Scope scope() {
            return delegate.scope();
        }

        @Override
        public void close() {
            delegate.close();
            closed = true;
        }

        boolean isClosed() {
            return closed;
        }
    }

    private static final class FailingArena implements Arena {

        private final Arena delegate = Arena.ofShared();
        private int remainingFailures;
        private int closeAttempts;
        private boolean closed;

        FailingArena(int failures) {
            this.remainingFailures = failures;
        }

        @Override
        public MemorySegment allocate(long byteSize, long byteAlignment) {
            return delegate.allocate(byteSize, byteAlignment);
        }

        @Override
        public MemorySegment.Scope scope() {
            return delegate.scope();
        }

        @Override
        public void close() {
            closeAttempts++;
            if (remainingFailures > 0) {
                remainingFailures--;
                throw new IllegalStateException("injected close failure");
            }
            if (!closed) {
                delegate.close();
                closed = true;
            }
        }

        boolean isClosed() {
            return closed;
        }

        int closeAttempts() {
            return closeAttempts;
        }
    }

    @Test
    void supportsConcurrentReads() throws Exception {
        int threads = 4;
        int iterations = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            Callable<String> task = () -> {
                for (int i = 0; i < iterations; i++) {
                    assertEquals("innerValue", document.root().findPath("nestedObj", "innerKey").asString());
                    assertEquals(2, document.root().findPath("objArray", "1", "id").asInt());
                }
                return "ok";
            };
            List<Future<String>> results = executor.invokeAll(
                    java.util.Collections.nCopies(threads, task));
            for (Future<String> result : results) {
                assertEquals("ok", result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
