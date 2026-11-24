package com.mcproxy.proto;

import com.mcproxy.Session;
import com.mcproxy.chat.ChatText;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;

/**
 * 上游侧：完整走一遍 1.8.9 登录（加密握手 → 压缩 → LoginSuccess），
 * 然后进入 PLAY 阶段做中继 + 聊天拦截。
 */
public final class ServerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ServerHandler.class);

    /** sessionserver 校验专用线程池，避免阻塞网络 eventLoop */
    private static final java.util.concurrent.ExecutorService JOIN_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "sessionserver-join");
                t.setDaemon(true);
                return t;
            });

    private enum State {LOGIN, PLAY}

    private final Session session;
    private final EncryptionCodec encryption;
    private final CompressionCodec compression;
    private State state = State.LOGIN;

    public ServerHandler(Session session, EncryptionCodec encryption, CompressionCodec compression) {
        this.session = session;
        this.encryption = encryption;
        this.compression = compression;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            io.netty.util.ReferenceCountUtil.release(msg);
            return;
        }
        ByteBuf buf = (ByteBuf) msg;
        try {
            if (state == State.LOGIN) {
                handleLogin(ctx, buf);
            } else {
                handlePlay(buf);
            }
        } catch (Throwable t) {
            log.error("[server] 处理失败: {}", t.toString());
            if (buf.refCnt() > 0) buf.release();
            session.closeAll();
        }
    }

    // ------------------------------------------------------------ 登录阶段

    private void handleLogin(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        int id = VarInt.read(buf);
        switch (id) {
            case Ids.DISCONNECT_LOGIN -> {
                String json = Buf.readString(buf);
                String plain = ChatText.toPlain(json);
                log.warn("[server] 上游拒绝登录: {}", plain);
                buf.release();
                session.disconnectClient("上游拒绝登录: " + plain);
                return;
            }
            case Ids.ENCRYPTION_REQUEST -> {
                String serverId = Buf.readString(buf);
                byte[] serverKey = Buf.readByteArray(buf);
                byte[] verifyToken = Buf.readByteArray(buf);
                handleEncryption(ctx, serverId, serverKey, verifyToken);
            }
            case Ids.SET_COMPRESSION -> {
                int threshold = VarInt.read(buf);
                compression.setEnabled(threshold);
                log.info("[server] 上游启用压缩 threshold={}", threshold);
            }
            case Ids.LOGIN_SUCCESS -> {
                String uuid = Buf.readString(buf);
                String name = Buf.readString(buf);
                state = State.PLAY;
                session.onServerLoginSuccess(uuid, name);
            }
            default -> log.warn("[server] 未处理的登录包 0x{}", Integer.toHexString(id));
        }
        buf.release();
    }

    private void handleEncryption(ChannelHandlerContext ctx, String serverId,
                                  byte[] serverKey, byte[] verifyToken) throws Exception {
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);

        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(serverKey));

        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedSecret = rsa.doFinal(secret);
        byte[] encryptedToken = rsa.doFinal(verifyToken);

        String hash = serverHash(serverId, secret, serverKey);

        // sessionserver join 是阻塞 HTTP，绝不能跑在 eventLoop 上：
        // 网络一抖动会把所有会话一起拖死。丢到独立线程，回来再切回 eventLoop 写包。
        JOIN_POOL.execute(() -> {
            try {
                session.auth().joinSession(hash);
            } catch (Throwable t) {
                log.error("[server] sessionserver 校验失败: {}", t.toString());
                ctx.executor().execute(() -> session.disconnectClient(
                        "代理账号会话校验失败：" + t.getMessage()));
                return;
            }
            ctx.executor().execute(() -> {
                if (!ctx.channel().isActive()) return;
                ByteBuf out = ctx.alloc().buffer();
                VarInt.write(out, Ids.ENCRYPTION_RESPONSE);
                Buf.writeByteArray(out, encryptedSecret);
                Buf.writeByteArray(out, encryptedToken);
                ctx.writeAndFlush(out);          // 这个响应本身必须明文发出
                encryption.enable(secret);       // 之后双向才加密
                log.info("[server] 加密握手完成");
            });
        });
    }

    /** 1.7+ 的 serverId 就是空串，哈希 = sha1(serverId + sharedSecret + publicKey)。 */
    private static String serverHash(String serverId, byte[] secret, byte[] publicKey) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(serverId.getBytes(StandardCharsets.UTF_8));
        sha1.update(secret);
        sha1.update(publicKey);
        return new BigInteger(sha1.digest()).toString(16);
    }

    // ------------------------------------------------------------ 游戏阶段

    private void handlePlay(ByteBuf buf) {
        com.mcproxy.Config cfg = session.config();

        java.util.List<com.mcproxy.tab.TabStats.Add> tabAdds = null;
        java.util.List<byte[]> tabUpdates = null;

        boolean cheatWarn = cfg.cheatWarn != null && cfg.cheatWarn.enabled;
        if (cfg.debugTab || cfg.tab.enabled || cheatWarn) {
            ByteBuf d = buf.duplicate();
            try {
                int id = VarInt.read(d);
                if (cfg.debugTab) TabDump.dump(id, d.duplicate());
                if (id == Ids.PLAY_TEAMS) {
                    // 抄一份队伍前缀：挂统计要下发 displayName，下发后客户端就不再套队伍了，
                    // 名字颜色和队伍标签得自己补回去
                    session.teams().handle(d);
                } else if ((cfg.tab.enabled || cheatWarn) && id == Ids.PLAY_PLAYER_LIST_ITEM) {
                    ByteBuf peek = d.duplicate();
                    int action = VarInt.read(peek);
                    if (action == 0) {
                        tabAdds = com.mcproxy.tab.TabStats.parseAdds(d);
                    } else if (action == 3 && cfg.tab.enabled) {
                        // 上游自己改显示名会把我们挂的统计顶掉，记下来待会儿补回去
                        tabUpdates = com.mcproxy.tab.TabStats.parseDisplayUpdate(d);
                    }
                }
            } catch (Exception e) {
                // 不再静默：解析失败要能看见，同时照常转发原包
                log.warn("[tab] 解析玩家列表失败（原包已正常转发）: {}", e.toString());
            }
        }

        if (cfg.translate.enabled) {
            ByteBuf copy = buf.duplicate();
            try {
                int id = VarInt.read(copy);
                if (id == Ids.PLAY_CHAT_CLIENTBOUND) {
                    String json = Buf.readString(copy);
                    int position = copy.readByte();
                    boolean wanted = position == Ids.CHAT_POSITION_CHAT
                            || (position == Ids.CHAT_POSITION_SYSTEM && cfg.translate.translateSystem);

                    if (wanted) {
                        String plain = ChatText.toPlain(json);
                        // 只要玩家发的聊天：系统提示不翻，原样显示
                        if (Boolean.TRUE.equals(cfg.translate.onlyPlayerChat)
                                && !session.translator().isPlayerChat(plain)) {
                            session.toClient(buf);
                            return;
                        }
                        if (!plain.isEmpty() && plain.length() < 800) {

                            // 自己刚发出去的那句（服务器回声）：上面是队友看到的英文，
                            // 下面补一行你当时打的中文，免得想不起自己说过什么
                            String mine = session.translator().echoOrigin(plain);
                            if (mine != null) {
                                long echoSeq = session.outbox().offer(json, position);
                                session.outbox().complete(echoSeq, mine);
                                buf.release();
                                return;
                            }

                            // 已经是目标语言：不用等，直接放行
                            if (session.translator().wouldSkip(plain)) {
                                session.toClient(buf);
                                return;
                            }

                            // 同一句提示反复推（"You are AFK" 之类）：只翻第一次
                            if (session.translator().isRepeated(plain)) {
                                session.toClient(buf);
                                return;
                            }

                            // 排队是为了让译文行紧跟各自原文，不会先后乱插。
                            long seq = session.outbox().offer(json, position);
                            session.translator().submit(plain, t -> session.outbox().complete(seq, t));
                            buf.release();
                            return;
                        }
                    }
                }
            } catch (Exception ignore) {
                // 解析失败就当作普通包转发，绝不因为翻译丢掉原消息
            }
        }

        session.toClient(buf);   // 原包立刻下发

        // 原包发完再补一条 displayName 更新，保证客户端先有玩家再加字
        if (tabAdds != null && !tabAdds.isEmpty()) {
            session.tabStats().onAdds(tabAdds);
        } else if (tabUpdates != null && !tabUpdates.isEmpty()) {
            session.tabStats().onDisplayUpdate(tabUpdates);
        }
    }
}
