package com.mcproxy.proto;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * AES/CFB8 加解密层。1.8.9 里加密是在"帧"之前发生的：
 *   线上字节 = encrypt( 长度前缀 + 包体 )
 * 所以这个 handler 必须放在 pipeline 最前面（入站先解密，出站最后加密）。
 *
 * 收发各用一个 Cipher 实例，密钥和 IV 都是那 16 字节 shared secret。
 */
public final class EncryptionCodec extends ByteToMessageCodec<ByteBuf> {

    private volatile boolean enabled;
    private Cipher decryptCipher;
    private Cipher encryptCipher;

    public void enable(byte[] sharedSecret) {
        try {
            SecretKeySpec key = new SecretKeySpec(sharedSecret, "AES");
            IvParameterSpec iv = new IvParameterSpec(sharedSecret);

            decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
            decryptCipher.init(Cipher.DECRYPT_MODE, key, iv);

            encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
            encryptCipher.init(Cipher.ENCRYPT_MODE, key, iv);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法初始化 AES/CFB8", e);
        }
        this.enabled = true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws GeneralSecurityException {
        if (!enabled) {
            if (in.isReadable()) {
                out.add(in.readRetainedSlice(in.readableBytes()));
            }
            return;
        }
        byte[] data = new byte[in.readableBytes()];
        in.readBytes(data);   // ByteBufUtil.getBytes 不会移动读指针，这里必须自己消费掉
        if (data.length == 0) return;
        byte[] plain = new byte[data.length];
        decryptCipher.update(data, 0, data.length, plain, 0);
        out.add(Unpooled.wrappedBuffer(plain));
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws GeneralSecurityException {
        if (!enabled) {
            out.writeBytes(msg);
            return;
        }
        byte[] data = new byte[msg.readableBytes()];
        msg.readBytes(data);
        byte[] cipherText = new byte[data.length];
        encryptCipher.update(data, 0, data.length, cipherText, 0);
        out.writeBytes(cipherText);
    }
}
