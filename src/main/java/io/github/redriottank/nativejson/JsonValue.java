package io.github.redriottank.nativejson;

import java.util.List;

/**
 * A lazily evaluated JSON value.
 *
 * <p>Instances are lightweight views into the memory-mapped document. They do
 * not copy the underlying data and stay valid until the owning
 * {@link JsonDocument} is closed. Reading from a closed document throws
 * {@link IllegalStateException}.</p>
 *
 * <p>Unless stated otherwise, methods that do not apply to the current
 * {@link #type()} throw {@link IllegalStateException}. Container lookup
 * methods ({@link #get(String)}, {@link #getAt(int)},
 * {@link #findPath(String...)}) are the exception: they return {@code null}
 * when the requested member does not exist or the receiver has the wrong
 * type.</p>
 *
 * <p>String contents are validated as UTF-8. Invalid, overlong, truncated or
 * surrogate byte sequences throw {@link JsonParseException}, whether the
 * surrounding string uses escape sequences or not.</p>
 *
 * @since 1.0.0
 */
public interface JsonValue {

    /**
     * Returns the type of this value.
     *
     * @return the JSON type
     */
    JsonType type();

    /**
     * Returns this value as a Java string.
     *
     * <p>For {@link JsonType#STRING} values the JSON escapes are decoded. For
     * every other type the raw JSON token is returned, so numbers, booleans,
     * {@code null}, objects and arrays are returned verbatim (including their
     * quotes/brackets where applicable).</p>
     *
     * @return the decoded string or the raw JSON token
     */
    String asString();

    /**
     * Returns the raw JSON text of this value without decoding escapes or
     * removing any of its syntax.
     *
     * <p>This materialises the complete value as a Java {@link String}. To
     * validate a large value without copying it, use {@link #validate()}
     * instead.</p>
     *
     * @return the raw JSON text
     */
    String raw();

    /**
     * Validates this value and all of its descendants without materialising
     * them.
     *
     * <p>This performs a complete structural pass over the value, including
     * UTF-8 validation of string contents, but does not copy the JSON text
     * into the Java heap. Call it when the goal is to verify a value or a
     * whole document rather than read it.</p>
     *
     * @throws JsonParseException if the value is not valid JSON
     * @throws IllegalStateException if the owning document has been closed
     * @since 1.0.1
     */
    void validate();

    /**
     * Returns this value as an {@code int}.
     *
     * @return the parsed number
     * @throws IllegalStateException   if this value is not a {@link JsonType#NUMBER}
     * @throws NumberFormatException if the number is not an integer or overflows an {@code int}
     */
    int asInt();

    /**
     * Returns this value as a {@code long}.
     *
     * @return the parsed number
     * @throws IllegalStateException   if this value is not a {@link JsonType#NUMBER}
     * @throws NumberFormatException if the number is not an integer or overflows a {@code long}
     */
    long asLong();

    /**
     * Returns this value as a {@code double}.
     *
     * @return the parsed number
     * @throws IllegalStateException   if this value is not a {@link JsonType#NUMBER}
     * @throws NumberFormatException if the number cannot be parsed
     */
    double asDouble();

    /**
     * Returns this value as a {@code boolean}.
     *
     * @return {@code true} for the JSON literal {@code true}, {@code false} for {@code false}
     * @throws IllegalStateException if this value is not a {@link JsonType#BOOLEAN}
     */
    boolean asBoolean();

    /**
     * Returns whether this value is the JSON literal {@code null}.
     *
     * @return {@code true} when this is {@code null}
     */
    boolean isNull();

    /**
     * Returns whether this value is a JSON object.
     *
     * @return {@code true} for {@link JsonType#OBJECT}
     */
    boolean isObject();

    /**
     * Returns whether this value is a JSON array.
     *
     * @return {@code true} for {@link JsonType#ARRAY}
     */
    boolean isArray();

    /**
     * Returns whether this value is a JSON string.
     *
     * @return {@code true} for {@link JsonType#STRING}
     */
    boolean isString();

    /**
     * Returns whether this value is a JSON number.
     *
     * @return {@code true} for {@link JsonType#NUMBER}
     */
    boolean isNumber();

    /**
     * Returns whether this value is a JSON boolean.
     *
     * @return {@code true} for {@link JsonType#BOOLEAN}
     */
    boolean isBoolean();

    /**
     * Returns the number of members of an object or elements of an array.
     * This is an O(n) operation that linearly scans the container with zero
     * allocations.
     *
     * <p>For objects with duplicate keys, each key occurrence is counted.</p>
     *
     * @return the member/element count
     * @throws IllegalStateException if this value is not an object or array
     */
    int size();

    /**
     * Returns the member names of an object in document order.
     *
     * <p>This is an O(n) operation that scans the object and allocates a new
     * {@link List} containing decoded key strings. Duplicate keys are preserved.</p>
     *
     * @return an immutable list of field names; empty for an empty object
     * @throws IllegalStateException if this value is not an object
     */
    List<String> keys();

    /**
     * Looks up a member of this JSON object.
     *
     * <p>This is an O(n) linear scan from the beginning of the object. If
     * duplicate keys are present, this library returns the first occurrence;
     * {@link #keys()} and {@link #size()} preserve duplicates. Returns a newly
     * allocated {@link JsonValue} view instance, or {@code null} if the key is
     * absent or this value is not an object.</p>
     *
     * @param key the member name
     * @return the member value, or {@code null} if absent or this is not an object
     */
    JsonValue get(String key);

    /**
     * Returns the element at {@code index} of this JSON array.
     *
     * <p>This is an O(n) linear scan from the start of the array. Repeated random
     * access by index will rescan preceding elements. Returns a newly allocated
     * {@link JsonValue} view instance, or {@code null} if out of bounds or this is
     * not an array.</p>
     *
     * @param index the zero-based element index
     * @return the element, or {@code null} if out of bounds or this is not an array
     */
    JsonValue getAt(int index);

    /**
     * Navigates a chain of object keys and array indices.
     *
     * <p>Each path segment is used as an array index when the current value is
     * an array, and as an object key otherwise. For example
     * {@code doc.root().findPath("users", "0", "name")} reads the first user's
     * name.</p>
     *
     * <p>Complexity is O(sum of container scans) for the traversed segments.
     * An intermediate {@link JsonValue} view instance is allocated for each segment
     * in the path.</p>
     *
     * @param path the keys/indices to follow
     * @return the value at the path, or {@code null} if any segment is missing
     */
    JsonValue findPath(String... path);
}
