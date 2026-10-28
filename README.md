<div align="center">

<img src="logo.png" alt="Native JSON Navigator Logo" width="200"/>

# Native JSON Navigator

**Zero-heap-allocation, memory-mapped JSON navigation for modern Java.**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.redriottank/native-json-navigator.svg?label=Maven%20Central&style=flat-square&color=blue)](https://central.sonatype.com/artifact/io.github.redriottank/native-json-navigator)
[![Java Version](https://img.shields.io/badge/Java-22%2B-orange.svg?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](LICENSE)
[![Zero Runtime Dependencies](https://img.shields.io/badge/runtime%20dependencies-0-brightgreen.svg?style=flat-square)](#installation)
[![CI Status](https://img.shields.io/github/actions/workflow/status/RedRiotTank/NativeJsonNavigator/ci.yml?branch=master&label=CI&style=flat-square)](https://github.com/RedRiotTank/NativeJsonNavigator/actions/workflows/ci.yml)

<p align="center">
  <a href="#quick-start">Quick Start</a> •
  <a href="#why-native-json-navigator">Why Native JSON?</a> •
  <a href="#benchmarks">Benchmarks</a> •
  <a href="#how-it-works">Architecture</a> •
  <a href="#api-reference">API Reference</a> •
  <a href="#license">License</a>
</p>

</div>

---

## Why Native JSON Navigator?

Traditional JSON parsers (Jackson, Gson, Fastjson) deserialize the complete document into an in-memory object graph (`JsonNode`, `Map`, `List`). On gigabyte-scale datasets or in high-throughput streaming environments, this causes massive heap churn, severe CPU cache pollution, and unpredictable Stop-The-World (STW) garbage collection pauses.

**Native JSON Navigator** takes an off-heap, lazy approach backed by Java 22's finalized **Foreign Function & Memory API (JEP 454)**:

* **Off-Heap Virtual Memory (`mmap`):** Maps and queries multi-megabyte/gigabyte files directly via the OS page cache without reading file content into the JVM heap.
* **Near-Zero Heap Allocations:** Allocates **~400 B to ~2 KB** per query on 10 MB to 100+ MB payloads, bypassing the 56 MB to 560+ MB of `JsonNode` tree objects instantiated by traditional parsers.
* **Eliminates GC Pressure:** In benchmarks querying a 100 MB JSON payload, Jackson paused for **~1.9 seconds** in garbage collection, while Native JSON Navigator completed with **under 10 ms** total pause time.
* **Zero Runtime Dependencies:** Pure Java standard library with no external dependencies or native JNI binaries.

---

### When to Use & When NOT to Use

| Ideal Use Cases | When to Use Alternatives |
| :--- | :--- |
| **Large-scale Ingestion & ETL:** Scanning gigabyte logs or bulk data dumps where only specific fields are needed. | **POJO / DTO Data Binding:** If your primary workflow is auto-mapping JSON into record/class instances, use Jackson. |
| **Latency-Critical Services:** High-frequency trading, real-time analytics, and microservices sensitive to GC pauses. | **JSON Mutation & Serialization:** Writing, updating, or generating JSON payloads. |
| **Point Queries in Nested Documents:** Pulling deeply nested tokens (e.g., `doc.root().findPath("users", "0", "address", "city")`). | **Untrusted / Hostile Inputs:** Untrusted payloads requiring strict defensive schema validation or non-UTF-8 encodings. |

---

## Installation

Native JSON Navigator requires **Java 22 or newer** (the Foreign Function & Memory API is final in standard Java SE; **no `--enable-preview`** or `--add-opens` flags required).

### Maven

```xml
<dependency>
    <groupId>io.github.redriottank</groupId>
    <artifactId>native-json-navigator</artifactId>
    <version>1.0.1</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
implementation("io.github.redriottank:native-json-navigator:1.0.1")
```

### Gradle (Groovy DSL)

```groovy
implementation 'io.github.redriottank:native-json-navigator:1.0.1'
```

---

## Quick Start

`JsonDocument` maps the underlying file into off-heap memory and implements `AutoCloseable`. Always manage it inside a `try-with-resources` block.

```java
import io.github.redriottank.nativejson.JsonDocument;
import io.github.redriottank.nativejson.JsonValue;
import java.nio.file.Path;

public class Application {
    public static void main(String[] args) throws Exception {
        Path jsonPath = Path.of("large_telemetry.json");

        try (JsonDocument doc = JsonDocument.open(jsonPath)) {
            // 1. Point navigation: Mix object keys and array indices without intermediate objects
            JsonValue user = doc.root().findPath("data", "users", "0");
            
            if (user != null && user.isObject()) {
                String name = user.get("name").asString();
                int age = user.get("age").asInt(); // Direct primitive extraction, zero boxing
                System.out.printf("User: %s (Age: %d)%n", name, age);
            }

            // 2. Safe Array Traversal
            JsonValue tags = doc.find("tags"); // Shorthand for doc.root().get("tags")
            if (tags != null && tags.isArray()) {
                for (int i = 0; i < tags.size(); i++) {
                    JsonValue tag = tags.getAt(i);
                    System.out.println("Tag: " + tag.asString());
                }
            }

            // 3. Null-safe checks & raw JSON slices
            JsonValue metadata = doc.root().get("metadata");
            if (metadata != null && !metadata.isNull()) {
                System.out.println("Raw slice: " + metadata.raw());
            }
        } // Off-heap mapping is cleanly unmapped when exiting try-with-resources
    }
}
```

---

## Benchmarks

Microbenchmarks were conducted using [JMH (Java Microbenchmark Harness 1.37)](https://github.com/openjdk/jmh) with the `-prof gc` profiler, comparing **Native JSON Navigator** against **Jackson Databind 2.17.0**.

Raw JMH JSON output reports are checked into this repository:
* [**Java 22 Results (Temurin 22.0.2)**](benchmarks/jmh-result-java22.json)
* [**Java 27 Results (Temurin 27-ea)**](benchmarks/jmh-result-java27.json)

> **What is measured:** This benchmark evaluates two distinct access patterns:
> 1. **Lazy Point-Navigation:** Navigating an off-heap mapped document directly to a target key without building an object graph (`doc.find(targetKey)`) vs. Jackson parsing the entire payload into an in-memory `JsonNode` tree (`jacksonMapper.readTree(jsonContent)`).
> 2. **End-to-End File Query:** Opening and mapping the file from disk to extract the field (`JsonDocument.open().find()`) vs. Jackson reading and parsing the file from disk (`jacksonMapper.readTree(file)`).

* **Hardware:** AMD Ryzen 7 7800X3D (8 Cores / 16 Threads @ 4.20 GHz)
* **OS / Runtime:** Linux 6.x (64-bit Server VM)
* **JVMs Tested:** OpenJDK 22.0.2 & OpenJDK 27-ea

---

### 1. Lazy Point-Navigation (In-Memory)

Navigating to a scalar field in a pre-loaded document without building an intermediate object graph:

#### Execution Time (Lower is better)

| Dataset | File Size | Jackson 2.17.0 (JDK 22) | Native Navigator (JDK 22) | Speedup (JDK 22) | Native Navigator (JDK 27) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `project.json` (small) | ~150 B | 0.0008 ms | **0.0004 ms** | **~2.0x** | **0.0005 ms** |
| `medium_dataset.json` | ~10 MB | 40.790 ms | **33.349 ms** | **~1.2x** | **34.541 ms** |
| `large_dataset.json` | ~100 MB | 910.199 ms | **311.570 ms** | **~2.9x** | **314.781 ms** |

#### Heap Allocation & GC Time per Operation (Lower is better)

| Dataset | Jackson Heap Alloc | Native Heap Alloc | Heap Reduction | Jackson GC Pause | Native GC Pause |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `project.json` | 1,688 B | **128 B** | **-92.4%** | 37.0 ms | **18.0 ms** |
| `medium_dataset.json` | ~56.5 MB | **405 B** | **-99.999%** | 58.0 ms | **10.0 ms** |
| `large_dataset.json` | ~566.6 MB | **2,129 B** | **-99.999%** | 1,964.0 ms | **8.0 ms** |

---

### 2. End-to-End File Ingestion (From Disk)

Opening the file from storage/OS cache and extracting a target field:

| Dataset | Jackson (`readTree(File)`) | Native (`open().find()`) | Speedup | Native Heap Alloc |
| :--- | :--- | :--- | :--- | :--- |
| `project.json` | 0.0068 ms | **0.0463 ms** *(mmap overhead)* | — | 1,282 B |
| `medium_dataset.json` | 64.217 ms | **30.819 ms** | **~2.1x faster** | **1,626 B** (vs 56.5 MB) |
| `large_dataset.json` | 1,537.803 ms | **310.570 ms** | **~5.0x faster** | **3,174 B** (vs 566.6 MB) |

> On large files, Jackson's GC pause time alone reached **1,615 ms**, while Native JSON Navigator performed the full mapping and traversal with only **8 ms** total GC activity.

---

### Reproducing Benchmarks Locally

Clone the repository and run the JMH benchmark harness:

```bash
# Compile test classes and benchmark harnesses
./mvnw -q test-compile

# Execute JMH with GC profiling and JSON report export
./mvnw -q exec:java -Dexec.classpathScope=test -Dexec.mainClass=testing.PerformanceComparison -Djmh.resultFile=benchmarks/my-jmh-result.json
```

---

## How It Works

Traditional parsers convert an entire byte stream into heaps of `String`, `Map$Node`, and wrapper instances. Native JSON Navigator shifts the work off-heap:

```
+--------------------------------------------------------------------+
|  OS Virtual Memory / Page Cache (Kernel Space)                     |
+--------------------------------------------------------------------+
                                   │
                     mmap (FileChannel.map)
                                   ▼
+--------------------------------------------------------------------+
|  Foreign Function & Memory API: java.lang.foreign.MemorySegment    |
|  Shared Arena (Off-Heap, Concurrent Safe, Zero-Copy)               |
+--------------------------------------------------------------------+
                                   │
              Bit-Packed Primitive Offsets (long: 64-bit)
               [ 32-bit byte offset  |  32-bit byte length ]
                                   ▼
+--------------------------------------------------------------------+
|  Transient JsonValue Flyweight Views (Only allocated on read)      |
+--------------------------------------------------------------------+
```

1. **Memory-Mapped I/O (`MemorySegment`):** Maps the JSON file into off-heap memory via `Arena.ofShared()`. The operating system page cache transparently loads only the pages touched during queries.
2. **Bit-Packed Primitive Pointers:** Navigation coordinates are stored internally inside an unboxed `long` (32-bit offset + 32-bit length). No intermediate pointer chains or node instances are instantiated during tree walking.
3. **On-Demand Value Decoding:** Numbers, booleans, and nulls are evaluated directly on the raw bytes without intermediate `java.lang.String` construction.
4. **Shared Arena Lifecycle:** Multiple threads can read concurrently without locks. Invoking `close()` synchronizes with in-flight readers via reader-writer semantics and safely releases the off-heap mapping.

### Computational Complexity & Allocations

* **Linear Scans ($O(n)$):** Container operations (`get`, `getAt`, `keys`, and `size`) perform a linear scan from the start of the container. Repeated random access via `getAt(i)` rescans preceding elements.
* **Path Navigation:** `findPath(segments...)` has a complexity of $O(\sum \text{container scan lengths})$ across the traversed segments.
* **Allocation Profile:**
  - **Zero heap allocations:** `asInt()`, `asLong()`, `asBoolean()`, `isNull()`, `type()`, `size()`, `validate()`.
  - **Lightweight views:** `get(key)`, `getAt(index)`, and each step of `findPath(...)` allocate a single ephemeral `JsonValue` flyweight object (wrapping primitive pointer offsets).
  - **Materializations:** `asString()` and `raw()` allocate a `String`; `keys()` allocates an `ArrayList<String>`.
* **Duplicate Keys:** `get(key)` returns the **first occurrence**; `keys()` and `size()` preserve and count all duplicate occurrences.

---

## Technical Specifications & Limits

| Constraint / Attribute | Specification |
| :--- | :--- |
| **Java Baseline** | Java 22+ (Strict Standard SE; no preview flags required) |
| **Maximum File Size** | **2 GiB** (backed by 32-bit packed pointer offsets) |
| **Encoding** | UTF-8 only (Optional UTF-8 BOM is detected and skipped) |
| **Access Model** | Read-only |
| **Thread Safety** | **Thread-safe for concurrent reads.** `close()` safely drains active readers before unmapping. |
| **Memory Model** | Off-heap (`Arena.ofShared()`). Unaffected by JVM `-Xmx` boundaries. |

---

## API Reference

### `JsonDocument`

| Method | Return Type | Description |
| :--- | :--- | :--- |
| `open(Path path)` / `open(String path)` | `JsonDocument` | Memory-maps a UTF-8 JSON file. |
| `root()` | `JsonValue` | Returns the root value (`OBJECT`, `ARRAY`, etc.). |
| `find(String key)` | `JsonValue` | Shorthand for `root().get(key)` ($O(n)$ scan). |
| `byteSize()` | `long` | Total byte size of the mapped file. |
| `path()` | `Path` | Source file path. |
| `validate()` | `void` | Structural validation pass across the entire document without copying. |
| `close()` | `void` | Releases memory mapping; idempotent and safe to retry. |

### `JsonValue`

| Method | Return Type | Description |
| :--- | :--- | :--- |
| `type()` | `JsonType` | `OBJECT`, `ARRAY`, `STRING`, `NUMBER`, `BOOLEAN`, or `NULL`. |
| `get(String key)` | `JsonValue` | Linear scan ($O(n)$); returns first occurrence of key or `null`. Allocates a view. |
| `getAt(int index)` | `JsonValue` | Linear scan ($O(n)$); returns element at index or `null`. Allocates a view. |
| `findPath(String... tokens)` | `JsonValue` | Multi-hop traversal ($O(\sum \text{scans})$). Allocates intermediate views. |
| `asString()` | `String` | Decodes JSON string (unescaping characters) or returns raw token text (allocates `String`). |
| `asInt()` / `asLong()` | `int` / `long` | Direct byte-to-integer parsing (zero allocation). |
| `asDouble()` | `double` | Parses floating point value. |
| `asBoolean()` | `boolean` | Direct boolean extraction (`true`/`false`, zero allocation). |
| `isNull()` | `boolean` | `true` if current token is JSON `null` (zero allocation). |
| `isObject()` / `isArray()` | `boolean` | Type predicate helpers. |
| `size()` | `int` | Element count for arrays or member count for objects ($O(n)$, zero allocation). |
| `keys()` | `List<String>` | List of object keys preserved in document order ($O(n)$, allocates `List<String>`). |
| `raw()` | `String` | Returns verbatim JSON slice (allocates `String`). |
| `validate()` | `void` | Validates current value and subtree without memory allocation. |

---

## Building from Source

Ensure you have **JDK 22+** installed and active in your shell:

```bash
# Clone the repository
git clone https://github.com/RedRiotTank/NativeJsonNavigator.git
cd NativeJsonNavigator

# Run clean build, checkstyle, and surefire test suites
./mvnw verify
```

The build produces the release jar, sources jar, and Javadoc jars inside `target/`.

---

## Contributing & Community

Contributions are welcome! Please feel free to submit pull requests or open issues:

* **Bug Reports & Feature Requests:** [GitHub Issues](https://github.com/RedRiotTank/NativeJsonNavigator/issues)
* **Discussions:** [GitHub Discussions](https://github.com/RedRiotTank/NativeJsonNavigator/discussions)

Please ensure all PRs include corresponding unit tests and pass `./mvnw verify`.

---

## License

Native JSON Navigator is open-source software licensed under the **[Apache License, Version 2.0](LICENSE)**.
