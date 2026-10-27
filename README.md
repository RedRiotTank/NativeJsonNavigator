# Native JSON Navigator

[![Maven Central](https://img.shields.io/maven-central/v/io.github.redriottank/native-json-navigator.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.redriottank/native-json-navigator)
[![CI](https://github.com/RedRiotTank/NativeJsonNavigator/actions/workflows/ci.yml/badge.svg)](https://github.com/RedRiotTank/NativeJsonNavigator/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-22%2B-orange.svg)](https://openjdk.org/)

A high-performance, near-zero-allocation JSON reader for modern Java. It maps a
UTF-8 JSON file directly into off-heap memory with the **Foreign Function &
Memory API** and navigates it lazily using bit-packed primitive pointers,
without building a tree of Java objects.

## Installation

The library has **no runtime dependencies**. Java **22 or newer** is required
(the Foreign Function & Memory API is final since Java 22, so no preview flags
are needed).

### Maven

```xml
<dependency>
    <groupId>io.github.redriottank</groupId>
    <artifactId>native-json-navigator</artifactId>
    <version>1.0.1</version>
</dependency>
```

### Gradle

```kotlin
implementation("io.github.redriottank:native-json-navigator:1.0.1")
```

## Quick start

```java
import io.github.redriottank.nativejson.JsonDocument;
import io.github.redriottank.nativejson.JsonValue;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        try (JsonDocument doc = JsonDocument.open(Path.of("data.json"))) {
            JsonValue tags = doc.find("tags");

            if (tags != null && tags.isArray()) {
                JsonValue third = tags.getAt(2);
                System.out.println(third == null ? "index 2 does not exist" : third.asString());
            }

            // Nested navigation: object keys and array indices can be mixed.
            String firstUserName = doc.root().findPath("users", "0", "name").asString();
            System.out.println(firstUserName);
        }
    }
}
```

`JsonDocument` implements `AutoCloseable`: closing it releases the native
mapping as soon as any in-flight reads have finished.

## Why another JSON parser?

Traditional parsers deserialize the complete document into a tree of objects
(`JsonNode`, `Map`, `List`), allocating once per node. That is convenient, but
it creates GC pressure on large files.

Native JSON Navigator takes a different approach:

* **Memory-mapped I/O** — the OS page cache backs the file. Opening a
  multi-gigabyte document does not copy it onto the heap.
* **Lazy navigation** — only the values you actually read are decoded.
* **Packed pointers** — input positions are kept in a single `long` containing
  a 32-bit offset and a 32-bit length, so intermediate navigation state stays
  primitive. Only the small `JsonValue` view you request is allocated.
* **Direct byte parsing** — integers, booleans, null checks and key matching are
  performed on the raw bytes without intermediate `String` objects.

> **When not to use it:** if you need POJO binding, schema validation, JSON
> writing, or defensive handling of completely untrusted input, use Jackson or
> Gson. This library is a focused, read-only navigator for large or
> latency-sensitive workloads.

## Requirements and limits

| Item | Value |
| :--- | :--- |
| Java | 22+ (no `--enable-preview`, no `--add-opens`) |
| Encoding | UTF-8 (an optional UTF-8 BOM is accepted) |
| Maximum file size | 2 GiB (32-bit offsets) |
| Access | Read-only |
| Thread safety | Concurrent reads are safe; `close()` waits for in-flight reads |
| Thread confinement | None: documents use a shared arena |

## API overview

| Type / method | Description |
| :--- | :--- |
| `JsonDocument.open(String \| Path)` | Maps a JSON file. |
| `JsonDocument.find(String)` | Reads a top-level member (shorthand for `root().get(key)`). |
| `JsonDocument.root()` | Returns the root `JsonValue`. |
| `JsonDocument.byteSize()` / `path()` | File metadata. |
| `JsonDocument.close()` | Releases the mapping; idempotent and retry-safe. |
| `JsonDocument.validate()` | Validates the whole document without copying it. |
| `JsonValue.type()` | Returns `OBJECT`, `ARRAY`, `STRING`, `NUMBER`, `BOOLEAN` or `NULL`. |
| `asString()` | Decodes a string (including escapes), or returns the raw token for other types. |
| `raw()` | Returns the verbatim JSON text of the value (copies the value). |
| `validate()` | Validates a value and its descendants without copying them. |
| `asInt()` / `asLong()` / `asDouble()` | Parses numbers without intermediate strings (except `asDouble`). |
| `asBoolean()` | Reads `true` / `false`. |
| `isNull()` / `isObject()` / `isArray()` / `isString()` / `isNumber()` / `isBoolean()` | Type checks. |
| `size()` | Number of object members or array elements (O(n)). |
| `keys()` | Object member names in document order. |
| `get(String)` | Reads an object member; `null` when absent. |
| `getAt(int)` | Reads an array element; `null` when out of bounds. |
| `findPath(String...)` | Mixed key/index navigation, for example `findPath("users", "0", "name")`. |

### Error handling

* `JsonParseException` is thrown for malformed JSON. It exposes the byte
  `offset()` where the problem was found.
* Navigation is lazy, so only the traversed part of the document is validated.
  Call `doc.validate()` to force a full structural pass without copying the
  document, or `value.validate()` to validate a single value and its
  descendants.
* String contents are validated as UTF-8. Invalid, overlong, truncated or
  surrogate byte sequences throw `JsonParseException`, whether they appear in
  escaped or unescaped strings.
* Calling `asInt()`, `asBoolean()`, `size()`, etc. on the wrong type throws
  `IllegalStateException`.
* Reading a value after `close()` throws `IllegalStateException`. `close()`
  waits for in-flight reads; if releasing the mapping fails, the document stays
  open and `close()` can be retried.

## Performance

Microbenchmarks were run with **JMH** against **Jackson Databind 2.17.0**.

Environment: AMD Ryzen 7 7800X3D (4.20 GHz), JDK 21, Linux/Windows,
memory-mapped I/O. (The benchmarks were recorded on JDK 21 while the Foreign
Function &amp; Memory API was still in preview; the implementation is unchanged
apart from targeting the final API in Java 22.)

### Execution time (lower is better)

| Dataset | Jackson | Native | Speedup |
| :--- | ---: | ---: | ---: |
| `project.json` (small) | ≈ 0.001 ms | **≈ 0.0001 ms** | ≈ 10x |
| `medium_dataset.json` | 24.331 ms | **3.633 ms** | ≈ 6.7x |
| `large_dataset.json` | 360.902 ms | **36.818 ms** | ≈ 9.8x |

### Heap allocation per operation (lower is better)

| Dataset | Jackson | Native | Reduction |
| :--- | ---: | ---: | ---: |
| `project.json` (small) | 1,688 B | **128 B** | 92.4% |
| `medium_dataset.json` | ~56.4 MB | **140 B** | 99.99% |
| `large_dataset.json` | ~566.5 MB | **186 B** | 99.99% |

During the large-dataset run Jackson triggered 302 GC collections (10,865 ms
of GC time); the native navigator triggered 1 collection (3 ms).

Benchmarks live in `src/test/java/testing/PerformanceComparison.java` and can be
run with:

```bash
./mvnw -q test-compile
./mvnw -q exec:java -Dexec.classpathScope=test -Dexec.mainClass=testing.PerformanceComparison
```

## Building from source

```bash
./mvnw verify
```

The build enforces Java 22+, runs the test suite, and produces the main,
sources and Javadoc jars in `target/`.

## Releasing

See [RELEASING.md](RELEASING.md) for the Maven Central release process and the
GitHub Actions workflow.

## License

Apache License 2.0. See [LICENSE](LICENSE).
