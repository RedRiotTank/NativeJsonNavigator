package io.github.redriottank.nativejson;

/**
 * The JSON value types understood by this library.
 *
 * @since 1.0.0
 */
public enum JsonType {

    /** A JSON object, for example {@code {"key": 1}}. */
    OBJECT,

    /** A JSON array, for example {@code [1, 2, 3]}. */
    ARRAY,

    /** A JSON string, for example {@code "hello"}. */
    STRING,

    /** A JSON number, for example {@code 42}, {@code -1.5} or {@code 1e10}. */
    NUMBER,

    /** The JSON literals {@code true} and {@code false}. */
    BOOLEAN,

    /** The JSON literal {@code null}. */
    NULL
}
