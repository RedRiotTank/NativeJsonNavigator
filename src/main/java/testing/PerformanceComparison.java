package testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import htt.NativeJsonNavigator;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
public class PerformanceComparison {

    private static final String FILE_PATH = "proyecto.json";
    private NativeJsonNavigator nativeNavigator;
    private ObjectMapper jacksonMapper;
    private String jsonContent;

    @Setup
    public void setup() throws Exception {
        nativeNavigator = new NativeJsonNavigator(FILE_PATH);
        jacksonMapper = new ObjectMapper();
        jsonContent = Files.readString(Path.of(FILE_PATH));
    }

    @Benchmark
    public String testJackson() throws Exception {
        JsonNode node = jacksonMapper.readTree(jsonContent);
        return node.get("libreria").asText();
    }

    @Benchmark
    public String testNative() {
        long ptr = nativeNavigator.findValue("libreria");
        return nativeNavigator.extract(ptr);
    }

    @TearDown
    public void tearDown() {
        nativeNavigator.close();
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(PerformanceComparison.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}