package com.mcproxy;

import com.mcproxy.auth.AuthService;
import com.mcproxy.chat.ChatOutbox;
import com.mcproxy.chat.ChatText;
import com.mcproxy.chat.Translator;
import com.mcproxy.proto.Buf;
import com.mcproxy.proto.ClientHandler;
import com.mcproxy.proto.CompressionCodec;
import com.mcproxy.proto.EncryptionCodec;
import com.mcproxy.proto.FrameCodec;
import com.mcproxy.proto.Ids;
import com.mcproxy.proto.ServerHandler;
import com.mcproxy.proto.VarInt;
import com.mcproxy.tab.TabStats;
import com.mcproxy.tab.TeamRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次游戏会话 = 两条 TCP 连接绑成一条命。
 *
 * 你的客户端 --(明文/无加密)--> 本代理 --(加密)--> 上游(加速IP/Hypixel)
 *
 * 客户端侧故意不启用加密：客户端不会验票，代理直接读明文，最省事。
 * 上游侧是真正的登录，用配置里的微软账号。
 */
public final class Session {

    private static final Logger log = LoggerFactory.getLogger(Session.class);

    private final Config cfg;
    private final AuthService auth;
    private final Translator translator;
    private final Channel client;
    private final ChatOutbox outbox;
    private final TeamRegistry teams;
    private final TabStats tabStats;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Channel server;
    private volatile ClientHandler clientHandler;
    private volatile String incomingName;

    public Session(Config cfg, AuthService auth, Translator translator, Channel client) {
        this.cfg = cfg;
        this.auth = auth;
        this.translator = translator;
        this.client = client;
        // 译文一律"原文下面多一行"。只压很短一下（默认 250ms）就放原文，体感更跟手，
        // 译文晚到会单独补一行，不会丢。
        this.outbox = new ChatOutbox(client, cfg.translate.appendWaitMs);
        this.teams = new TeamRegistry();
        this.tabStats = new TabStats(cfg, teams, this::sendTabDisplayName, this::sendSelfChat,
                this::sendSelfChatJson);
        client.closeFuture().addListener(f -> closeAll());
    }

    public Config config() {
        return cfg;
    }

    public AuthService auth() {
        return auth;
    }

    public Translator translator() {
        return translator;
    }

    public ChatOutbox outbox() {
        return outbox;
    }

    public TabStats tabStats() {
        return tabStats;
    }

    public TeamRegistry teams() {
        return teams;
    }

    /** 给某个玩家的 Tab 行设置 displayName（action=3），只影响 Tab，不影响名牌。 */
    public void sendTabDisplayName(byte[] uuid, String displayNameJson) {
        if (!client.isActive() || uuid == null || uuid.length != 16) return;
        ByteBuf out = client.alloc().buffer();
        VarInt.write(out, Ids.PLAY_PLAYER_LIST_ITEM);
        VarInt.write(out, 3);            // action = update display name
        VarInt.write(out, 1);            // 玩家数量
        out.writeBytes(uuid);            // 裸 16 字节 UUID
        out.writeBoolean(true);          // Has Display Name —— 协议要求这一位，漏了客户端会错位
        Buf.writeString(out, displayNameJson);
        client.writeAndFlush(out);
    }

    /** 只在调试时用：往自己的聊天栏发一行，方便截图看诊断。 */
    /** 直接发一段 JSON 聊天组件（带颜色的提示行走这里） */
    public void sendSelfChatJson(String json) {
        if (!client.isActive()) return;
        ByteBuf out = client.alloc().buffer();
        VarInt.write(out, Ids.PLAY_CHAT_CLIENTBOUND);
        Buf.writeString(out, json);
        out.writeByte(Ids.CHAT_POSITION_CHAT);
        client.writeAndFlush(out);
    }

    public void sendSelfChat(String text) {
        if (!client.isActive()) return;
        ByteBuf out = client.alloc().buffer();
        VarInt.write(out, Ids.PLAY_CHAT_CLIENTBOUND);
        Buf.writeString(out, ChatText.toComponent(text, "gray"));
        out.writeByte(Ids.CHAT_POSITION_CHAT);
        client.writeAndFlush(out);
    }

    public void setClientHandler(ClientHandler h) {
        this.clientHandler = h;
    }

    // ------------------------------------------------------------ 登录

    /** 客户端发来 LoginStart 后调用：连上游、走完整登录。 */
    public void beginLogin(String clientName, ChannelHandlerContext clientCtx) {
        this.incomingName = clientName;
        log.info("[session] 客户端 {} 接入，代理将以 {} 的身份登录上游", clientName, auth.name());

        Bootstrap b = new Bootstrap();
        b.group(client.eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 8000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        EncryptionCodec encryption = new EncryptionCodec();
                        CompressionCodec compression = new CompressionCodec();
                        ServerHandler sh = new ServerHandler(Session.this, encryption, compression);

                        ch.pipeline().addLast(encryption);          // 入站最先解密，出站最后加密
                        ch.pipeline().addLast(new FrameCodec());    // 切帧
                        ch.pipeline().addLast(compression);         // 解压 / 压缩
                        ch.pipeline().addLast(new IdleStateHandler(0, 0, cfg.idleTimeoutSeconds));
                        ch.pipeline().addLast(sh);

                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                log.info("[session] 上游断开");
                                closeAll();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                log.warn("[session] 上游异常: {}", cause.toString());
                                closeAll();
                            }

                            @Override
                            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                                if (evt instanceof IdleStateEvent) {
                                    log.info("[session] 上游空闲超时，主动断开");
                                    closeAll();
                                    return;
                                }
                                ctx.fireUserEventTriggered(evt);
                            }
                        });
                    }
                });

        b.connect(cfg.upstreamHost, cfg.upstreamPort).addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                Throwable cause = f.cause();
                log.error("[session] 连接上游 {}:{} 失败: {}", cfg.upstreamHost, cfg.upstreamPort,
                        cause == null ? "unknown" : cause.toString());
                disconnectClient("代理无法连接上游 " + cfg.upstreamHost + ":" + cfg.upstreamPort
                        + "（检查加速IP 是否可达）");
                return;
            }
            server = f.channel();
            log.info("[session] 已连接上游 {}:{}，开始握手", cfg.upstreamHost, cfg.upstreamPort);
            sendHandshake(server);
        });
    }

    private void sendHandshake(Channel ch) {
        ByteBuf handshake = ch.alloc().buffer();
        VarInt.write(handshake, Ids.HANDSHAKE);
        VarInt.write(handshake, cfg.protocolVersion);
        Buf.writeString(handshake, cfg.handshakeHost);   // Hypixel 强路由认这个域名
        handshake.writeShort(cfg.handshakePort);
        VarInt.write(handshake, 2);                      // next state = login
        ch.writeAndFlush(handshake);

        ByteBuf loginStart = ch.alloc().buffer();
        VarInt.write(loginStart, Ids.LOGIN_START);
        String name = auth.name() != null ? auth.name() : incomingName;
        Buf.writeString(loginStart, name);
        ch.writeAndFlush(loginStart);
    }

    /** 上游 LoginSuccess：把客户端也切进 PLAY，然后两边就是纯中继。 */
    public void onServerLoginSuccess(String uuidString, String name) {
        translator.setOwnName(name);

        ByteBuf out = client.alloc().buffer();
        VarInt.write(out, Ids.LOGIN_SUCCESS);
        Buf.writeString(out, uuidString);
        Buf.writeString(out, name);
        client.writeAndFlush(out);

        ClientHandler h = clientHandler;
        if (h != null) h.enterPlay();
        log.info("[session] 上游登录成功: {} ({}), 客户端已进入 PLAY", name, uuidString);
    }

    // ------------------------------------------------------------ 转发

    public void toServer(ByteBuf buf) {
        Channel s = server;
        if (s != null && s.isActive()) {
            s.writeAndFlush(buf);
        } else if (buf.refCnt() > 0) {
            buf.release();
        }
    }

    public void toClient(ByteBuf buf) {
        if (client.isActive()) {
            client.writeAndFlush(buf);
        } else if (buf.refCnt() > 0) {
            buf.release();
        }
    }

    /** 在本会话的 eventLoop 上延迟执行（用于"等译文超时就发原文"这类兜底）。 */
    public void schedule(long delayMs, Runnable task) {
        client.eventLoop().schedule(task, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------ 聊天注入

    /** 你发出的聊天被翻译后，替换原包发给上游。 */
    public void sendOutgoingChat(String translated) {
        Channel s = server;
        if (s == null || !s.isActive()) return;
        String text = translated.length() > 100 ? translated.substring(0, 100) : translated;
        ByteBuf out = s.alloc().buffer();
        VarInt.write(out, Ids.PLAY_CHAT_SERVERBOUND);
        Buf.writeString(out, text);
        s.writeAndFlush(out);
    }

    // ------------------------------------------------------------ 收尾

    public void disconnectClient(String reason) {
        if (!client.isActive()) return;
        ByteBuf out = client.alloc().buffer();
        VarInt.write(out, Ids.DISCONNECT_LOGIN);
        Buf.writeString(out, ChatText.toComponent(reason, "red"));
        // 先把断开原因发出去，再关连接；否则客户端只会看到"连接被重置"
        client.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 两条连接必须同生共死：
     * 客户端退出 → 立刻断上游（否则号还挂在服务器里）；
     * 上游踢人 → 立刻断客户端（否则本地卡在黑屏转圈）。
     */
    public void closeAll() {
        if (!closed.compareAndSet(false, true)) return;
        // Tab 查询线程和每 2 秒的重试调度器都属于这个会话，断线必须一起停：
        // 否则每重连一次就漏一组后台线程和缓存，还会继续查已断开会话的玩家名。
        tabStats.shutdown();
        Channel s = server;
        if (s != null && s.isActive()) s.close();
        if (client.isActive()) client.close();
    }
}
