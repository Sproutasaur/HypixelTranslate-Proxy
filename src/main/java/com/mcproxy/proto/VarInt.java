package com.mcproxy.proto;

import io.netty.buffer.ByteBuf;

/** Minecraft 的 VarInt 读写。 */
public final class VarInt {

    private VarInt() {}

    /** 读取 VarInt；数据不足时抛出异常（用于已经确定完整的包）。 */
    public static int read(ByteBuf buf) {
        int value = 0;
        int i = 0;
        while (true) {
            if (!buf.isReadable()) throw new IllegalStateException("VarInt 被截断");
            byte b = buf.readByte();
            value |= (b & 0x7F) << (i++ * 7);
            if ((b & 0x80) == 0) return value;
            if (i > 4) throw new IllegalStateException("VarInt 过长");
        }
    }

    /**
     * 读取 VarInt，但遇到数据不足时会把 readerIndex 还原并返回 -1，
     * 用于流式解码时判断"这个包还没收完"。
     */
    public static int readSafe(ByteBuf buf) {
        int start = buf.readerIndex();
        int value = 0;
        int i = 0;
        while (true) {
            if (!buf.isReadable()) {
                buf.readerIndex(start);
                return -1;
            }
            byte b = buf.readByte();
            value |= (b & 0x7F) << (i++ * 7);
            if ((b & 0x80) == 0) return value;
            if (i > 4) throw new IllegalStateException("VarInt 过长");
        }
    }

    public static void write(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
