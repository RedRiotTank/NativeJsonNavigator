# Native JSON Navigator

A high-performance, ultra-low-allocation JSON parser and navigator for modern Java. Built from the ground up using Java 21's **Foreign Function & Memory API (Project Panama)** to memory-map large JSON files off-heap and navigate them lazily using bit-packed primitive pointers.

## Documentation

* **Official Repository:** [NativeJsonNavigator](https://github.com/RedRiotTank/NativeJsonNavigator).
* **Java Version Required:** JDK 21+ (with `--enable-preview` if applicable for older JDK 21 builds, and `--add-opens java.base/java.lang=ALL-UNNAMED` for internal String access).

---

## 🚀 What It Does

Traditional JSON parsers (like Jackson or Gson) read JSON data and fully deserialize it into a heavy tree of Java objects (`JsonNode`, `Map`, `List`), causing massive garbage collector (GC) pressure when dealing with large files.

**Native JSON Navigator** takes a radically different approach: it maps the JSON file directly into off-heap native memory using `MemorySegment` and performs **lazy, non-destructive navigation**. Instead of allocating objects for every JSON node, it tracks coordinates inside the memory-mapped file using a single, bit-packed primitive 64-bit `long` pointer.

---

## ⚖️ Philosophy & Trade-offs: Jackson vs. Native

**This library is NOT a general-purpose replacement for Jackson or Gson.** In software engineering, extreme performance always comes at a cost. While Jackson prioritizes **developer convenience and safety**, Native JSON Navigator prioritizes **raw throughput and zero allocation**.

Understanding the balance between complexity and speed is essential for choosing the right tool:

### The Jackson Approach (High Abstraction)
* **Pros:** Bulletproof RFC compliance, rich error handling/validation, automatic POJO data-binding, and an intuitive, foolproof API.
* **Cons:** Massive object allocation, high CPU/memory overhead on large datasets, and heavy Garbage Collector pressure.

### The Native Approach (Low-Level Performance)
* **Pros:** Up to 10x faster execution, near-zero heap allocation, and immune to high GC pauses on gigabyte-sized files.
* **Cons:** Increased structural complexity. Navigation requires tracking primitive bit-packed pointers, data is processed raw in native space, and there is no automated reflection-based object mapping. It also assumes well-formed JSON payloads; invalid syntax can lead to unexpected pointer tracking instead of clean, structured exceptions.

> 💡 **Rule of thumb:** Use **Jackson** for standard REST APIs, configuration files, and complex business logic mapping. Use **Native JSON Navigator** as a specialized tool for high-throughput data ingestion, heavy log parsing pipelines, or memory-constrained environments where GC pauses are unacceptable.

---

### Key Features

* **Zero/Near-Zero Allocation:** Navigating objects and arrays does not allocate heap memory. It only allocates lightweight Java `record` nodes on demand when you explicitly request a value.
* **Memory-Mapped I/O:** Uses OS-level page caches through `FileChannel.MapMode.READ_ONLY` and Project Panama's `Arena.ofConfined()`, allowing instantaneous "loading" of multi-gigabyte files without memory overhead.
* **Bit-Packed Pointers:** Compresses both the `offset` (32 bits) and the `length` (32 bits) of a JSON node into a single primitive `long` to completely bypass wrapper object overhead.
* **Unsafe Reflection Optimizations:** Utilizes a highly optimized internal `VarHandle` to read the underlying `byte[]` of standard Java Strings directly, preventing array duplication during key-matching loops.

### Common Use Cases

* **Log and Event Processing:** Quick extraction of specific fields from massive, multi-gigabyte JSON log files.
* **Microservices under Tight Memory Constraints:** Parsing JSON payloads without triggering high Garbage Collection pauses or hitting heap limits.
* **High-Throughput Ingestion Pipelines:** Filtering and routing JSON messages at near-line rate speed.

---

## 🛠️ Core Interfaces & Components

The library hides the complexity of native memory tracking under a clean, public object-oriented API:

### `JsonValue` Interface

Represents a lazy reference to a JSON element (Object, Array, String, Number, Boolean, Null).

| Method | Return Type | Description |
| :--- | :--- | :--- |
| `asString()` | `String` | Materializes and returns the value as a Java String (with unescaping support). |
| `asInt()` | `int` | Parses the raw bytes directly into an integer **without allocating a String**. |
| `asBoolean()` | `boolean` | Checks byte-level equality against `"true"` or `"false"`. |
| `isNull()` | `boolean` | Checks byte-level equality against `"null"`. |
| `get(String key)` | `JsonValue` | *[TODO]* Finds a property within a JSON Object. |
| `getAt(int index)` | `JsonValue` | Extracts the element at the specified index from a JSON Array. |
| `findPath(String... keys)`| `JsonValue` | Performs deep-path navigation through nested objects. |

---

## 📋 Examples

Using Native JSON Navigator inside a `try-with-resources` block ensures that the native memory arena is safely closed and unmapped immediately after use.

**Example: Open a large JSON file, locate an array called "tags", and extract its third element.**

```java
import htt.JsonValue;
import htt.internal.NativeJsonDocument;

public class Main {
    public static void main(String[] args) {
        
        // The document automatically unmaps and releases native memory upon close
        try (NativeJsonDocument doc = new NativeJsonDocument("proyecto.json")) {

            // Search for the top-level key "tags"
            JsonValue tags = doc.find("tags");

            if (tags != null) {
                System.out.println("Found 'tags' key!");

                // Lazily navigate to index 2 (3rd element) inside the array
                JsonValue elemento = tags.getAt(2);

                if (elemento != null) {
                    String valor = elemento.asString();
                    System.out.println("Real val: " + valor); // Outputs the string value
                } else {
                    System.out.println("Index 2 doesn't exist.");
                }
            } else {
                System.out.println("Key 'tags' not found (404).");
            }

        } catch (Exception e) {
            System.err.println("Error processing JSON: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
```

## 📊 Performance & Benchmarks

To validate the efficiency of this approach, strict microbenchmarks were executed using **JMH (Java Microbenchmark Harness)**, comparing this implementation (`Native`) against the industry-standard **Jackson Databind (v2.17.0)**.

### Environment Specs
* **CPU:** AMD Ryzen 7 7800X3D 8-Core Processor (4.20 GHz)
* **OS:** Windows / Linux (Memory-Mapped I/O)
* **Runtime:** Java HotSpot(TM) 64-Bit Server VM, JDK 21.0.2

### ⏱️ Execution Time (Lower is Better)

| Dataset | Jackson Score (`ms/op`) | Native Score (`ms/op`) | Speedup Factor |
| :--- | :---: | :---: | :---: |
| `project.json` (Small) | ≈ 0.001 ms | **≈ 0.0001 ms** | **~ 10.0x Faster** |
| `medium_dataset.json` | 24.331 ms | **3.633 ms** | **~ 6.7x Faster** |
| `large_dataset.json` | 360.902 ms | **36.818 ms** | **~ 9.8x Faster** |

```
Execution Time Chart (Large Dataset)
Jackson: 
████████████████████████████████████████ 360.90 ms
Native:  
████ 36.81 ms (~10x faster)
```

### 🎛️ Memory Allocation Rate & GC Pressure

The core metric where this library shines is heap memory conservation. Because it processes bytes in native space, the Java Garbage Collector has practically zero work to do.

| Dataset | Jackson Heap Alloc (`B/op`) | Native Heap Alloc (`B/op`) | Allocation Reduction |
| :--- | :---: | :---: | :---: |
| `project.json` (Small) | 1,688 B | **128 B** | **- 92.4%** |
| `medium_dataset.json` | 56,474,447 B (~56.4 MB) | **140 B** | **- 99.99%** |
| `large_dataset.json` | 566,580,144 B (~566.5 MB) | **186 B** | **- 99.99%** |

```
Heap Memory Allocation (Large Dataset)
Jackson: 
████████████████████████████████████████ 566.5 Megabytes
Native:  
▏186 Bytes (Virtually Zero Allocation)
```
* **Jackson GC Overhead:** During the large dataset test, Jackson forced **302 GC collections**, stopping or slowing down the thread for a total of **10,865 ms** inside the benchmark window.
* **Native GC Overhead:** The Native implementation ran the entire large dataset benchmark with exactly **1 GC collection** and only **3 ms** total GC time.

---

## ⚠️ Disclaimers & Limitations

**JSON Compliance**
This is an experimental, highly optimized native navigator. It currently assumes well-formed JSON inputs and performs minimal validation to maintain its extreme throughput speeds. Malformed JSON payloads might cause unexpected tracking pointers.

**Concurrency**
The `NativeJsonDocument` binds itself to a single-threaded execution context if initialized via a confined `Arena` (defaulting to maximum performance). Do not share instances across multiple virtual or platform threads without coordinating synchronization or shifting to a shared arena.