package com.mcproxy.proto;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.TooLongFrameException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * MC 的包帧：VarInt 长度前缀 + 包体（包 id + 字段）。
 * 收发都在同一个 handler 里完成：入站切帧，出站加前缀。
 */
public final class FrameCodec extends ByteToMessageCodec<ByteBuf> {

    private static final int MAX_PACKET = 2 * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (true) {
            int start = in.readerIndex();
            int len = VarInt.readSafe(in);
            if (len < 0) {
                in.readerIndex(start);
                return;
            }
            if (len == 0) {
                continue; // 空包，跳过
            }
            if (len > MAX_PACKET) {
                throw new TooLongFrameException("包过大: " + len);
            }
            if (in.readableBytes() < len) {
                in.readerIndex(start);
                return;
            }
            out.add(in.readRetainedSlice(len));
        }
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        VarInt.write(out, msg.readableBytes());
        out.writeBytes(msg);
    }

    /** 给业务代码用的小工具：把一段文本写成 JSON 字符串字段。 */
    public static String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    static {
        // 确保字符串编码常量被引用（避免 IDE 误删 import）
        StandardCharsets.UTF_8.name();
    }
}
