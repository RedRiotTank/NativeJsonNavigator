package io.github.redriottank.nativejson;

import java.util.List;

/**
 * Default {@link JsonValue} implementation backed by a packed pointer into the
 * memory-mapped document.
 */
final class JsonValueImpl implements JsonValue {

    private final JsonNavigator navigator;
    private final long pointer;

    JsonValueImpl(JsonNavigator navigator, long pointer) {
        this.navigator = navigator;
        this.pointer = pointer;
    }

    @Override
    public JsonType type() {
        return navigator.typeOf(pointer);
    }

    @Override
    public String asString() {
        return navigator.asString(pointer);
    }

    @Override
    public String raw() {
        return navigator.raw(pointer);
    }

    @Override
    public void validate() {
        navigator.validate(pointer);
    }

    @Override
    public int asInt() {
        return navigator.asInt(pointer);
    }

    @Override
    public long asLong() {
        return navigator.asLong(pointer);
    }

    @Override
    public double asDouble() {
        return navigator.asDouble(pointer);
    }

    @Override
    public boolean asBoolean() {
        return navigator.asBoolean(pointer);
    }

    @Override
    public boolean isNull() {
        return navigator.isNull(pointer);
    }

    @Override
    public boolean isObject() {
        return type() == JsonType.OBJECT;
    }

    @Override
    public boolean isArray() {
        return type() == JsonType.ARRAY;
    }

    @Override
    public boolean isString() {
        return type() == JsonType.STRING;
    }

    @Override
    public boolean isNumber() {
        return type() == JsonType.NUMBER;
    }

    @Override
    public boolean isBoolean() {
        return type() == JsonType.BOOLEAN;
    }

    @Override
    public int size() {
        return navigator.sizeOf(pointer);
    }

    @Override
    public List<String> keys() {
        return navigator.keysOf(pointer);
    }

    @Override
    public JsonValue get(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        long child = navigator.findKey(pointer, key);
        return child == -1 ? null : new JsonValueImpl(navigator, child);
    }

    @Override
    public JsonValue getAt(int index) {
        long child = navigator.elementAt(pointer, index);
        return child == -1 ? null : new JsonValueImpl(navigator, child);
    }

    @Override
    public JsonValue findPath(String... path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        JsonValue current = this;
        for (String segment : path) {
            if (segment == null) {
                throw new IllegalArgumentException("path segments must not be null");
            }
            if (current.type() == JsonType.ARRAY) {
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException e) {
                    return null;
                }
                current = current.getAt(index);
            } else {
                current = current.get(segment);
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    @Override
    public String toString() {
        return raw();
    }
}
