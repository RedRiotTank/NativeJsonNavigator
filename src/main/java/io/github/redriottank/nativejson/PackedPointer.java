package io.github.redriottank.nativejson;

/**
 * Packs a 32-bit byte offset and a 32-bit length into a single {@code long}.
 * Using a primitive pointer keeps navigation allocation-free.
 */
final class PackedPointer {

    private PackedPointer() {
    }

    static long pack(int offset, int length) {
        return ((long) offset << 32) | (length & 0xFFFFFFFFL);
    }

    static int offset(long packed) {
        return (int) (packed >>> 32);
    }

    static int length(long packed) {
        return (int) packed;
    }
}
