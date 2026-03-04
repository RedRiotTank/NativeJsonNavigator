package testing;

import htt.JsonValue;
import htt.internal.NativeJsonDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NativeJsonDocumentTest {

    @TempDir
    Path tempDir;

    private NativeJsonDocument document;
    private Path jsonFilePath;

    @BeforeEach
    void setUp() throws Exception {
        String jsonContent = """
                {
                  "stringKey": "Hello World",
                  "emptyString": "",
                  "intKey": 12345,
                  "boolTrue": true,
                  "boolFalse": false,
                  "nullKey": null,
                  "spacedKey"   :    "valueWithSpaces",
                  "arrayKey": [ 10, "text", true, null, [1, 2] ],
                  "nestedObj": {
                    "innerKey": "innerValue",
                    "innerInt": 99
                  },
                  "key_with_escapes": "Line 1\\\\nLine 2\\""
                }
                """;

        jsonFilePath = tempDir.resolve("test_data.json");
        Files.writeString(jsonFilePath, jsonContent);

        document = new NativeJsonDocument(jsonFilePath.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (document != null) {
            document.close();
        }
    }

    @Test
    void testExtractString() {
        JsonValue value = document.find("stringKey");
        assertNotNull(value);
        assertEquals("Hello World", value.asString());
    }

    @Test
    void testExtractEmptyString() {
        JsonValue value = document.find("emptyString");
        assertNotNull(value);
        assertEquals("", value.asString());
    }

    @Test
    void testExtractInt() {
        JsonValue value = document.find("intKey");
        assertNotNull(value);
        assertEquals(12345, value.asInt());
    }

    @Test
    void testExtractBoolean() {
        JsonValue trueValue = document.find("boolTrue");
        assertNotNull(trueValue);
        assertTrue(trueValue.asBoolean());

        JsonValue falseValue = document.find("boolFalse");
        assertNotNull(falseValue);
        assertFalse(falseValue.asBoolean());
    }

    @Test
    void testExtractNull() {
        JsonValue value = document.find("nullKey");
        assertNotNull(value);
        assertTrue(value.isNull());
    }

    @Test
    void testWhitespacesAreIgnored() {
        JsonValue value = document.find("spacedKey");
        assertNotNull(value);
        assertEquals("valueWithSpaces", value.asString());
    }

    @Test
    void testNonExistentKeyReturnsNull() {
        JsonValue value = document.find("doesNotExist");
        assertNull(value, "Should return null if the key does not exist");
    }

    @Test
    void testArrayElements() {
        JsonValue arrayValue = document.find("arrayKey");
        assertNotNull(arrayValue);

        JsonValue elem0 = arrayValue.getAt(0);
        assertNotNull(elem0);
        assertEquals(10, elem0.asInt());

        JsonValue elem1 = arrayValue.getAt(1);
        assertNotNull(elem1);
        assertEquals("text", elem1.asString());

        JsonValue elem2 = arrayValue.getAt(2);
        assertNotNull(elem2);
        assertTrue(elem2.asBoolean());

        JsonValue elem3 = arrayValue.getAt(3);
        assertNotNull(elem3);
        assertTrue(elem3.isNull());

        JsonValue elem4 = arrayValue.getAt(4);
        assertNotNull(elem4);
        assertTrue(elem4.asString().replace(" ", "").contains("[1,2]"));
    }

    @Test
    void testArrayOutOfBoundsReturnsNull() {
        JsonValue arrayValue = document.find("arrayKey");
        JsonValue outOfBounds = arrayValue.getAt(99);
        assertNull(outOfBounds);
    }

    @Test
    void testNestedObjectExtractedAsString() {
        JsonValue value = document.find("nestedObj");
        assertNotNull(value);
        String rawString = value.asString();
        assertTrue(rawString.contains("\"innerKey\""));
        assertTrue(rawString.contains("\"innerValue\""));
    }
}