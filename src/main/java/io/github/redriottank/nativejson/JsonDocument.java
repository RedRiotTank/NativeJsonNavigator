package io.github.redriottank.nativejson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A read-only JSON document backed by a memory-mapped file.
 *
 * <p>{@code JsonDocument} maps the file into off-heap memory and navigates it
 * lazily. Opening a multi-gigabyte document is fast because no parsing or
 * object allocation happens up front; values are validated and materialised
 * only when they are read.</p>
 *
 * <p>Instances are thread-safe for concurrent reads. {@link #close()} is
 * idempotent: it waits for in-flight reads to finish and releases the native
 * mapping immediately afterwards. Reads that start after the document is
 * closed throw {@link IllegalStateException}. If the mapping cannot be
 * released, {@code close()} throws and the document remains open, so the call
 * can be retried.</p>
 *
 * <pre>{@code
 * try (JsonDocument doc = JsonDocument.open(Path.of("data.json"))) {
 *     String name = doc.root().findPath("users", "0", "name").asString();
 *     System.out.println(name);
 * }
 * }</pre>
 *
 * @since 1.0.0
 */
public final class JsonDocument implements AutoCloseable {

    private final JsonNavigator navigator;
    private final Path path;
    private final JsonValue root;

    private JsonDocument(JsonNavigator navigator, Path path) {
        this.navigator = navigator;
        this.path = path;
        this.root = new JsonValueImpl(navigator, navigator.rootPointer());
    }

    /**
     * Opens and memory-maps a JSON document.
     *
     * @param path the path to a UTF-8 encoded JSON file
     * @return the opened document
     * @throws IOException           if the file cannot be opened or mapped
     * @throws JsonParseException    if the file is empty, contains only whitespace or does not start with a JSON value
     * @throws IllegalArgumentException if the file is larger than 2 GiB
     */
    public static JsonDocument open(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        return new JsonDocument(new JsonNavigator(path), path);
    }

    /**
     * Opens and memory-maps a JSON document.
     *
     * @param filePath the path to a UTF-8 encoded JSON file
     * @return the opened document
     * @throws IOException           if the file cannot be opened or mapped
     * @throws JsonParseException    if the file is empty, contains only whitespace or does not start with a JSON value
     * @throws IllegalArgumentException if the file is larger than 2 GiB
     */
    public static JsonDocument open(String filePath) throws IOException {
        return open(Path.of(Objects.requireNonNull(filePath, "filePath must not be null")));
    }

    /**
     * Returns the root value of the document.
     *
     * @return the root JSON value
     * @throws IllegalStateException if the document has been closed
     */
    public JsonValue root() {
        navigator.ensureOpen();
        return root;
    }

    /**
     * Looks up a top-level member of this document.
     *
     * <p>This is equivalent to {@code root().get(key)}. Use
     * {@link JsonValue#findPath(String...)} for nested navigation.</p>
     *
     * @param key the top-level member name
     * @return the member value, or {@code null} if the document root is not an
     *         object or the key does not exist
     * @throws IllegalStateException if the document has been closed
     */
    public JsonValue find(String key) {
        return root().get(key);
    }

    /**
     * Validates the complete document without materialising it.
     *
     * <p>This performs a full structural pass over the mapped bytes, including
     * UTF-8 validation of string contents, without copying the document into
     * the Java heap. It is the preferred way to verify a large file; calling
     * {@link JsonValue#raw()} on the root achieves the same validation but
     * also allocates a {@code String} containing the whole document.</p>
     *
     * @throws JsonParseException    if the document is not valid JSON
     * @throws IllegalStateException if the document has been closed
     * @since 1.0.1
     */
    public void validate() {
        navigator.validate(navigator.rootPointer());
    }

    /**
     * Returns the size of the mapped file in bytes.
     *
     * @return the file size
     */
    public long byteSize() {
        return navigator.byteSize();
    }

    /**
     * Returns the path of the mapped file.
     *
     * @return the file path
     */
    public Path path() {
        return path;
    }

    /**
     * Releases the memory mapping.
     *
     * <p>This method is idempotent and waits for in-flight reads to finish.
     * If releasing the mapping fails, the document stays open and this method
     * can be called again.</p>
     */
    @Override
    public void close() {
        navigator.close();
    }
}
