package io.github.redriottank.nativejson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential test: random JSON documents are written with Jackson and read
 * back with Native JSON Navigator; every value in the tree must match.
 */
class DifferentialTest {

    private static final String[] STRINGS = {
            "", "hello", "with space", "quote\"inside", "back\\slash", "tab\there",
            "line\nbreak", "é", "日本語", "😀", "null", "true", "123", "{\"fake\":1}",
            "control\u0001char", "slash/inside"
    };

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void randomDocumentsMatchJackson() throws Exception {
        Random random = new Random(20261027L);
        for (int i = 0; i < 150; i++) {
            JsonNode tree = randomValue(random, 4);
            String compact = mapper.writeValueAsString(tree);
            String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);

            assertDocumentMatches(tree, compact, "compact-" + i);
            assertDocumentMatches(tree, pretty, "pretty-" + i);
        }
    }

    private void assertDocumentMatches(JsonNode expected, String json, String name) throws Exception {
        Path file = tempDir.resolve(name + ".json");
        Files.writeString(file, json);
        try (JsonDocument document = JsonDocument.open(file)) {
            assertMatches(expected, document.root(), "root");
        }
    }

    private void assertMatches(JsonNode expected, JsonValue actual, String path) {
        switch (expected.getNodeType()) {
            case OBJECT -> {
                assertTrue(actual.isObject(), path + " should be an object");
                assertEquals(expected.size(), actual.size(), path + " object size");
                expected.fieldNames().forEachRemaining(key -> {
                    JsonValue child = actual.get(key);
                    if (child == null) {
                        throw new AssertionError(path + ": missing key " + key);
                    }
                    assertMatches(expected.get(key), child, path + "." + key);
                });
            }
            case ARRAY -> {
                assertTrue(actual.isArray(), path + " should be an array");
                assertEquals(expected.size(), actual.size(), path + " array size");
                for (int i = 0; i < expected.size(); i++) {
                    assertMatches(expected.get(i), actual.getAt(i), path + "[" + i + "]");
                }
            }
            case STRING -> {
                assertTrue(actual.isString(), path + " should be a string");
                assertEquals(expected.textValue(), actual.asString(), path);
            }
            case BOOLEAN -> {
                assertTrue(actual.isBoolean(), path + " should be a boolean");
                assertEquals(expected.booleanValue(), actual.asBoolean(), path);
            }
            case NULL -> {
                assertTrue(actual.isNull(), path + " should be null");
                assertEquals("null", actual.asString(), path);
            }
            case NUMBER -> {
                assertTrue(actual.isNumber(), path + " should be a number");
                if (expected.isIntegralNumber()) {
                    assertEquals(expected.longValue(), actual.asLong(), path);
                    if (expected.canConvertToInt()) {
                        assertEquals(expected.intValue(), actual.asInt(), path);
                    }
                } else {
                    assertEquals(expected.doubleValue(), actual.asDouble(), 1e-12, path);
                }
            }
            default -> throw new AssertionError("Unsupported node type " + expected.getNodeType());
        }
    }

    private JsonNode randomValue(Random random, int depth) {
        int choice = depth <= 0 ? random.nextInt(5) : random.nextInt(7);
        return switch (choice) {
            case 0 -> mapper.getNodeFactory().textNode(randomString(random));
            case 1 -> randomInteger(random);
            case 2 -> mapper.getNodeFactory().numberNode(random.nextDouble() * 1_000_000 - 500_000);
            case 3 -> mapper.getNodeFactory().booleanNode(random.nextBoolean());
            case 4 -> mapper.getNodeFactory().nullNode();
            case 5 -> randomObject(random, depth);
            default -> randomArray(random, depth);
        };
    }

    private JsonNode randomInteger(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> mapper.getNodeFactory().numberNode(random.nextInt());
            case 1 -> mapper.getNodeFactory().numberNode(-random.nextInt(1, Integer.MAX_VALUE));
            case 2 -> mapper.getNodeFactory().numberNode(random.nextLong());
            default -> mapper.getNodeFactory().numberNode(-random.nextLong(1, Long.MAX_VALUE));
        };
    }

    private String randomString(Random random) {
        if (random.nextInt(4) == 0) {
            int length = random.nextInt(12);
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < length; i++) {
                char c = (char) random.nextInt(0x20, 0xD7FF);
                value.append(c);
            }
            return value.toString();
        }
        return STRINGS[random.nextInt(STRINGS.length)];
    }

    private ObjectNode randomObject(Random random, int depth) {
        ObjectNode object = mapper.createObjectNode();
        int members = random.nextInt(4);
        List<String> keys = new ArrayList<>();
        while (keys.size() < members) {
            String key = randomString(random);
            if (keys.add(key)) {
                object.set(key, randomValue(random, depth - 1));
            }
        }
        return object;
    }

    private ArrayNode randomArray(Random random, int depth) {
        ArrayNode array = mapper.createArrayNode();
        int elements = random.nextInt(4);
        for (int i = 0; i < elements; i++) {
            array.add(randomValue(random, depth - 1));
        }
        return array;
    }
}
