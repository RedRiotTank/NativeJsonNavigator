package htt;

public interface JsonValue {
    String asString();
    int asInt();
    boolean asBoolean();
    boolean isNull();

    JsonValue get(String key);
    JsonValue getAt(int index);
    JsonValue findPath(String... keys);
}