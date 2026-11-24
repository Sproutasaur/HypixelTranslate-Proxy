package com.mcproxy.proto;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * MC 的压缩层（Set Compression 之后生效）。
 *
 * 包体格式：[VarInt 未压缩长度][数据]
 *   - 长度 = 0  → 数据是未压缩的原文
 *   - 长度 > 0  → 数据是 zlib 压缩后的原文
 *
 * 生效时机：服务器发 Set Compression 的那个包本身是不压缩的，
 * 之后的包才启用。调用方在读到 Set Compression 后调用 {@link #setEnabled(int)}。
 */
public final class CompressionCodec extends ByteToMessageCodec<ByteBuf> {

    private volatile boolean enabled;
    private int threshold = 256;

    /** 单个包解压后允许的最大长度，防 zip bomb */
    private static final int MAX_UNCOMPRESSED = 4 * 1024 * 1024;

    private final Inflater inflater = new Inflater();
    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
    private final byte[] scratch = new byte[8192];

    public void setEnabled(int threshold) {
        this.threshold = Math.max(threshold, 0);
        this.enabled = true;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws DataFormatException {
        if (!enabled) {
            if (in.isReadable()) {
                out.add(in.readRetainedSlice(in.readableBytes()));
            }
            return;
        }

        int start = in.readerIndex();
        int uncompressedLen = VarInt.readSafe(in);
        if (uncompressedLen < 0) {          // 不该发生：帧已经保证完整
            in.readerIndex(start);
            return;
        }
        // 解压炸弹防护：声明长度离谱就直接拒绝，不给 inflater 分配内存
        if (uncompressedLen > MAX_UNCOMPRESSED) {
            throw new DataFormatException("解压声明的长度过大: " + uncompressedLen);
        }
        if (uncompressedLen == 0) {
            if (in.isReadable()) {
                out.add(in.readRetainedSlice(in.readableBytes()));
            }
            return;
        }

        byte[] compressed = new byte[in.readableBytes()];
        in.readBytes(compressed);
        inflater.reset();
        inflater.setInput(compressed);
        byte[] result = new byte[uncompressedLen];
        int n = 0;
        while (n < uncompressedLen && !inflater.finished()) {
            int r = inflater.inflate(result, n, uncompressedLen - n);
            if (r == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break;
            }
            n += r;
        }
        if (n != uncompressedLen) {
            throw new DataFormatException("解压后长度不符: " + n + " / " + uncompressedLen);
        }
        out.add(Unpooled.wrappedBuffer(result));
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        // 还没收到 Set Compression 时，线上根本没有"未压缩长度"这个标记，
        // 必须原样写出去，否则每个包都会多一个 0x00。
        if (!enabled) {
            out.writeBytes(msg);
            return;
        }

        int size = msg.readableBytes();
        if (size < threshold) {
            VarInt.write(out, 0);
            out.writeBytes(msg);
            return;
        }
        byte[] raw = ByteBufUtil.getBytes(msg);
        deflater.reset();
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(size / 2 + 16);
        while (!deflater.finished()) {
            int n = deflater.deflate(scratch);
            if (n <= 0) break;
            bos.write(scratch, 0, n);
        }
        VarInt.write(out, size);
        out.writeBytes(bos.toByteArray());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        inflater.end();
        deflater.end();
    }
}
