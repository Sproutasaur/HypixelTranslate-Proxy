package com.mcproxy.proto;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/** 协议里的字符串 / 字节数组字段（都是 VarInt 长度前缀）。 */
public final class Buf {

    private Buf() {}

    public static String readString(ByteBuf buf) {
        int len = VarInt.read(buf);
        byte[] a = new byte[len];
        buf.readBytes(a);
        return new String(a, StandardCharsets.UTF_8);
    }

    public static void writeString(ByteBuf buf, String s) {
        byte[] a = s.getBytes(StandardCharsets.UTF_8);
        VarInt.write(buf, a.length);
        buf.writeBytes(a);
    }

    public static byte[] readByteArray(ByteBuf buf) {
        int len = VarInt.read(buf);
        byte[] a = new byte[len];
        buf.readBytes(a);
        return a;
    }

    public static void writeByteArray(ByteBuf buf, byte[] a) {
        VarInt.write(buf, a.length);
        buf.writeBytes(a);
    }
}
