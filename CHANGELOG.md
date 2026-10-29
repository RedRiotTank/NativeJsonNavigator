# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Documentation
- Updated JMH benchmark tables in README to include Jackson Streaming comparisons.
- Corrected `JsonValue.get` documentation: this library returns the first occurrence of duplicate keys, while `keys()` and `size()` preserve duplicates.
- Documented linear-scan complexities ($O(n)$) and allocation profiles for `get`, `getAt`, `findPath`, `keys`, and `size`.
- Described exact JMH benchmark methodology (comparing off-heap lazy point navigation against full in-memory tree deserialization).
- Updated release documentation and verified Maven Central metadata checks.

### Quality & Hardening
- Added Jackson Streaming benchmarks (`testJacksonStreaming` and `testJacksonStreamingFile`) to JMH benchmark harness (`PerformanceComparison.java`).
- Expanded CI matrix across multi-OS (Linux, Windows, macOS) on JDK 22 and JDK 25 LTS.
- Added SpotBugs (`spotbugs-maven-plugin`) for static bytecode analysis with zero tolerance for high/medium bugs.
- Added japicmp (`japicmp-maven-plugin`) to enforce binary and source backward compatibility on public APIs against release 1.0.1.
- Added JaCoCo (`jacoco-maven-plugin`) for automated test suite coverage generation.
- Added `.gitattributes` for consistent cross-platform line ending normalization.
- Switched SCM connection from `git://` to secure `https://`.
- Hardened release automation: require master branch and validated release tags for workflow dispatch.
- Enforced validation of `<scm><tag>` matching release version `v$VERSION` in `validate-release.sh`.
- Added SHA-256 supply-chain checksum verification for Maven wrapper distribution.
- Improved release script portability (removed GNU `sort -V` and `seq` dependencies) and added prerelease flag support.
- Added JPMS module path verification integration test (`ModulePathIntegrationTest`).
- Cleaned tracked IDE metadata (`.idea/`) and relocated sample datasets to `src/test/resources`.

## [1.0.1] - 2026-10-28

### Added

- `JsonDocument.validate()` and `JsonValue.validate()` for full validation
  without materialising or copying the document. This replaces the previous
  `root().raw()` recommendation, which allocated the entire document and could
  exhaust the heap on large files.

### Fixed

- Native mappings are now released when `JsonDocument.open` rejects an empty,
  whitespace-only, BOM-only or otherwise invalid initial document.
- Invalid UTF-8 in strings is rejected consistently whether the string contains
  escape sequences or not. Invalid, overlong, truncated and surrogate byte
  sequences now throw `JsonParseException` instead of being replaced with
  U+FFFD.
- `JsonDocument.close()` now waits for in-flight reads instead of racing them,
  and only marks the document closed after the mapping is released. A failed
  `close()` leaves the document usable and can be retried instead of leaking
  the mapping permanently.

### Changed

- `JsonParseException` and README guidance now point to `validate()` for full
  validation of large documents.
- Documented the thread-safety and retry guarantees of `close()`.

## [1.0.0] - 2026-10-27

### Added

- Public `JsonDocument` entry point with `open(String)`, `open(Path)`,
  `find`, `root`, `byteSize`, `path`, and idempotent `close`.
- Complete `JsonValue` API: `type`, `asString`, `raw`, `asInt`, `asLong`,
  `asDouble`, `asBoolean`, type predicates, `size`, `keys`, `get`, `getAt`
  and `findPath`.
- Correct, scope-aware object and array navigation (keys inside strings or
  nested objects no longer confuse lookups).
- Full JSON string decoding for escapes, Unicode escapes and UTF-8, including
  escaped object keys.
- Structural JSON validation with byte offsets via `JsonParseException`.
- Nesting-depth guard (1,000 levels) against stack exhaustion.
- UTF-8 BOM support and clear errors for empty/oversized files.
- Thread-safe concurrent reads using a shared memory arena.
- Java Platform Module System descriptor (`io.github.redriottank.nativejson`).
- Maven Central publishing setup: sources, Javadoc, GPG signatures, Central
  Portal plugin and a GitHub Actions release workflow.

### Changed

- Coordinates are now `io.github.redriottank:native-json-navigator`.
- Packages moved from `htt` to `io.github.redriottank.nativejson`.
- Minimum runtime is Java 22 (no `--enable-preview` or `--add-opens` needed).
- The published jar has no runtime dependencies (JMH and Jackson are test-only).
- `find` is now scoped to the document root; use `findPath` for nested access.

### Fixed

- Array/object truncation during value scanning.
- Negative integer parsing.
- Memory and reference leaks on mapping failures.
- Key matching for non-ASCII keys and escaped keys.

[1.0.1]: https://github.com/RedRiotTank/NativeJsonNavigator/releases/tag/v1.0.1
[1.0.0]: https://github.com/RedRiotTank/NativeJsonNavigator/releases/tag/v1.0.0
