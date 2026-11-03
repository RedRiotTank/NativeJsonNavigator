package testing;

import io.github.redriottank.nativejson.JsonDocument;
import io.github.redriottank.nativejson.JsonValue;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(0)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
public class ConcurrentBenchmark {

    private JsonDocument projectDoc;
    private JsonDocument mediumDoc;
    private JsonDocument projectDocImmutable;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        projectDoc = JsonDocument.open("project.json");
        mediumDoc = JsonDocument.open("medium_dataset.json");
        projectDocImmutable = JsonDocument.openImmutable("project.json");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (projectDoc != null) {
            projectDoc.close();
        }
        if (mediumDoc != null) {
            mediumDoc.close();
        }
        if (projectDocImmutable != null) {
            projectDocImmutable.close();
        }
    }

    @Benchmark
    @Threads(1)
    public String singleThreadProject() {
        return projectDoc.root().findPath("library").asString();
    }

    @Benchmark
    @Threads(4)
    public String parallel4ThreadsProject() {
        return projectDoc.root().findPath("library").asString();
    }

    @Benchmark
    @Threads(1)
    public String singleThreadProjectImmutable() {
        return projectDocImmutable.root().findPath("library").asString();
    }

    @Benchmark
    @Threads(4)
    public String parallel4ThreadsProjectImmutable() {
        return projectDocImmutable.root().findPath("library").asString();
    }

    @Benchmark
    @Threads(1)
    public String singleThreadMedium() {
        return mediumDoc.root().findPath("users", "0", "name").asString();
    }

    @Benchmark
    @Threads(4)
    public String parallel4ThreadsMedium() {
        return mediumDoc.root().findPath("users", "0", "name").asString();
    }

    @Benchmark
    @Threads(1)
    public String openFindCloseProject() throws Exception {
        try (JsonDocument doc = JsonDocument.open("project.json")) {
            return doc.root().findPath("library").asString();
        }
    }

    @Benchmark
    @Threads(1)
    public String openImmutableFindProject() throws Exception {
        JsonDocument doc = JsonDocument.openImmutable("project.json");
        return doc.root().findPath("library").asString();
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(ConcurrentBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
