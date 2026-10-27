# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/).

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

[1.0.0]: https://github.com/RedRiotTank/NativeJsonNavigator/releases/tag/v1.0.0
