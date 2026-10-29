package testing;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redriottank.nativejson.JsonDocument;
import io.github.redriottank.nativejson.JsonValue;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import org.openjdk.jmh.results.format.ResultFormatType;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
public class PerformanceComparison {

    @Param({
            "project.json",          // ~150 bytes
            "medium_dataset.json",   // ~10 Megabytes
            "large_dataset.json"     // ~100 Megabytes
    })
    private String currentFile;

    private JsonDocument nativeDocument;
    private ObjectMapper jacksonMapper;
    private JsonFactory jsonFactory;
    private String jsonContent;

    private String targetKey;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        ensureFileExists(currentFile);

        nativeDocument = JsonDocument.open(currentFile);

        jacksonMapper = new ObjectMapper();
        jsonFactory = new JsonFactory();
        jsonContent = Files.readString(Path.of(currentFile));

        if (currentFile.equals("project.json")) {
            targetKey = "library";
        } else {
            targetKey = "target_data";
        }
    }

    @Benchmark
    public String testJackson() throws Exception {
        JsonNode node = jacksonMapper.readTree(jsonContent);
        JsonNode result = node.get(targetKey);
        return result != null ? result.asText() : null;
    }

    @Benchmark
    public String testJacksonStreaming() throws Exception {
        try (JsonParser parser = jsonFactory.createParser(jsonContent)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME && targetKey.equals(parser.currentName())) {
                    parser.nextToken();
                    return parser.getText();
                }
            }
        }
        return null;
    }

    @Benchmark
    public String testNative() {
        JsonValue node = nativeDocument.find(targetKey);
        return node != null ? node.asString() : null;
    }

    @Benchmark
    public String testJacksonFile() throws Exception {
        JsonNode node = jacksonMapper.readTree(Path.of(currentFile).toFile());
        JsonNode result = node.get(targetKey);
        return result != null ? result.asText() : null;
    }

    @Benchmark
    public String testJacksonStreamingFile() throws Exception {
        try (JsonParser parser = jsonFactory.createParser(Path.of(currentFile).toFile())) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME && targetKey.equals(parser.currentName())) {
                    parser.nextToken();
                    return parser.getText();
                }
            }
        }
        return null;
    }

    @Benchmark
    public String testNativeFileOpenAndFind() throws Exception {
        try (JsonDocument doc = JsonDocument.open(currentFile)) {
            JsonValue node = doc.find(targetKey);
            return node != null ? node.asString() : null;
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (nativeDocument != null) {
            nativeDocument.close();
        }
        jsonContent = null;
        System.gc();
    }

    // --- UTILS ---

    private void ensureFileExists(String fileName) throws Exception {
        Path path = Path.of(fileName);
        if (Files.exists(path)) return;

        System.out.println("\nGenerating " + fileName + " for the first time. Please wait...");
        if (fileName.equals("project.json")) {
            Files.writeString(path, "{\n  \"id\": 999,\n  \"developer\": \"Alberto\",\n  \"library\": \"NativeJsonNavigator\",\n  \"version\": \"1.0-alpha\",\n  \"tags\": [\"performance\", \"low-level\", \"java21\"]\n}");
        } else if (fileName.equals("medium_dataset.json")) {
            generateLargeJson(path, 100_000);
        } else if (fileName.equals("large_dataset.json")) {
            generateLargeJson(path, 1_000_000);
        }
    }

    private void generateLargeJson(Path path, int numRecords) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("{\n  \"metadata\": {\"created\": \"2026-02-26\", \"type\": \"benchmark\"},\n  \"users\": [\n");
            for (int i = 0; i < numRecords; i++) {
                writer.write("    { \"id\": " + i + ", \"name\": \"User_" + i + "\", \"active\": true, \"balance\": 123.45 }");
                if (i < numRecords - 1) writer.write(",");
                writer.write("\n");
            }
            writer.write("  ],\n  \"target_data\": \"Found it!\"\n}");
        }
    }

    public static void main(String[] args) throws Exception {
        String resultFile = System.getProperty("jmh.resultFile", "jmh-result.json");
        Options opt = new OptionsBuilder()
                .include(PerformanceComparison.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .result(resultFile)
                .resultFormat(ResultFormatType.JSON)
                .build();
        new Runner(opt).run();
    }
}