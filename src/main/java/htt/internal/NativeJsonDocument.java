package htt.internal;

import htt.JsonValue;

public class NativeJsonDocument implements AutoCloseable {

    private final NativeJsonNavigator internalNavigator;

    public NativeJsonDocument(String filePath) throws Exception {
        this.internalNavigator = new NativeJsonNavigator(filePath);
    }

    public JsonValue find(String key) {
        long pointer = internalNavigator.findValue(key);
        if (pointer == -1) return null;
        return new JsonValueNode(internalNavigator, pointer);
    }

    @Override
    public void close() throws Exception {
        internalNavigator.close();
    }

    private record JsonValueNode(NativeJsonNavigator nav, long pointer) implements JsonValue {

        @Override
        public String asString() {
            return nav.extract(pointer);
        }

        @Override
        public int asInt() {
            return nav.getAsInt(pointer);
        }

        @Override
        public boolean asBoolean() {
            return nav.getAsBoolean(pointer);
        }

        @Override
        public boolean isNull() {
            return nav.isNull(pointer);
        }

        @Override
        public JsonValue get(String key) {
            //TODO
            return null;
        }

        @Override
        public JsonValue getAt(int index) {
            long newPtr = nav.getElementAt(pointer, index);
            return newPtr == -1 ? null : new JsonValueNode(nav, newPtr);
        }

        @Override
        public JsonValue findPath(String... keys) {
            return null;
        }
    }
}