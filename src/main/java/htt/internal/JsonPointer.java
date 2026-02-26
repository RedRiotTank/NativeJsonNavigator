package htt.internal;

class JsonPointer {

    private JsonPointer() {}

    public static long pack(long offset, int length) {
        return (offset << 32) | (length & 0xFFFFFFFFL);
    }

    public static int unpackOffset(long packed) {
        return (int) (packed >>> 32);
    }

    public static int unpackLength(long packed) {
        return (int) packed;
    }
}