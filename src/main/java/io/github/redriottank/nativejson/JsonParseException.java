package io.github.redriottank.nativejson;

/**
 * Thrown when the underlying document is not valid JSON.
 *
 * <p>The parser validates only the parts of the document that are actually
 * navigated. Call {@link JsonDocument#root()} followed by
 * {@link JsonValue#raw()} to force a full validation pass.</p>
 *
 * @since 1.0.0
 */
public class JsonParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long offset;

    /**
     * Creates an exception without a byte offset.
     *
     * @param message the detail message
     */
    public JsonParseException(String message) {
        this(message, -1);
    }

    /**
     * Creates an exception for a problem found at the given byte offset.
     *
     * @param message the detail message
     * @param offset  the zero-based byte offset in the document, or {@code -1}
     */
    public JsonParseException(String message, long offset) {
        super(offset >= 0 ? message + " (at byte offset " + offset + ")" : message);
        this.offset = offset;
    }

    /**
     * Returns the byte offset at which the problem was detected.
     *
     * @return the zero-based byte offset, or {@code -1} when unavailable
     */
    public long offset() {
        return offset;
    }
}
