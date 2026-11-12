package io.github.excalibase.http;

import io.netty.buffer.ByteBuf;

/** Searching a buffer for a byte sequence. */
final class ByteBufIndex {

    private ByteBufIndex() {
    }

    /** The index of the first occurrence of {@code needle} in the readable bytes, or -1. */
    static int of(ByteBuf haystack, byte[] needle) {
        int last = haystack.writerIndex() - needle.length;
        for (int start = haystack.readerIndex(); start <= last; start++) {
            if (matchesAt(haystack, start, needle)) {
                return start;
            }
        }
        return -1;
    }

    private static boolean matchesAt(ByteBuf haystack, int start, byte[] needle) {
        for (int offset = 0; offset < needle.length; offset++) {
            if (haystack.getByte(start + offset) != needle[offset]) {
                return false;
            }
        }
        return true;
    }
}
