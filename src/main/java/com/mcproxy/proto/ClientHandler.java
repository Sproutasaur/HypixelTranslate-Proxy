package com.mcproxy.proto;

import com.mcproxy.Session;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端侧：处理手写和登录，之后转纯中继。
 * 客户端看到的"服务器"就是这里，而且我们不发 EncryptionRequest，
 * 所以客户端这一侧全程明文。
 */
public final class ClientHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    private static final int STATE_HANDSHAKE = 0;
    private static final int STATE_STATUS = 3;
    private static final int STATE_LOGIN = 1;
    private static final int STATE_PLAY = 2;

    private final Session session;
    private int state = STATE_HANDSHAKE;
    /** 发送翻译开关，只对当前会话生效；重连后回到 config 里的初始值 */
    private final java.util.concurrent.atomic.AtomicBoolean outgoingTranslation;

    public ClientHandler(Session session) {
        this.session = session;
        this.outgoingTranslation = new java.util.concurrent.atomic.AtomicBoolean(
                session.config().translate.translateOutgoing);
    }

    public void enterPlay() {
        this.state = STATE_PLAY;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            io.netty.util.ReferenceCountUtil.release(msg);
            return;
        }
        ByteBuf buf = (ByteBuf) msg;
        boolean handedOff = false;
        try {
            if (state == STATE_PLAY) {
                handlePlay(buf);
                handedOff = true;   // 所有权已转交，不释放
                return;
            }

            int id = VarInt.read(buf);
            if (state == STATE_HANDSHAKE) {
                if (id != Ids.HANDSHAKE) {
                    log.warn("[client] 期望握手包，收到 0x{}", Integer.toHexString(id));
                    return;
                }
                int protocol = VarInt.read(buf);
                String address = Buf.readString(buf);
                int port = buf.readUnsignedShort();
                int nextState = VarInt.read(buf);

                if (protocol != session.config().protocolVersion) {
                    log.warn("[client] 客户端协议 {} 不受支持（只支持 {}）", protocol, session.config().protocolVersion);
                    session.disconnectClient("本代理只支持 1.8.9（协议 47），你用的是 " + protocol
                            + "。请在启动器里选择 1.8.9。");
                    return;
                }
                // 只有真正登录（nextState=2）才打；服务器列表刷新（nextState=1）不打，避免刷屏
                if (nextState != 1) {
                    log.info("[client] handshake protocol={} address={}:{} next={}", protocol, address, port, nextState);
                }

                if (nextState == 1) {
                    // 服务器列表 ping：回一个状态就够，不必去连上游
                    state = STATE_STATUS;
                    return;
                }
                state = STATE_LOGIN;
                return;
            }

            if (state == STATE_STATUS) {
                if (id == 0x00) {
                    replyStatus(ctx);
                } else if (id == 0x01) {
                    long payload = buf.readLong();
                    ByteBuf pong = ctx.alloc().buffer();
                    VarInt.write(pong, 0x01);
                    pong.writeLong(payload);
                    ctx.writeAndFlush(pong).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
                }
                return;
            }

            if (state == STATE_LOGIN && id == Ids.LOGIN_START) {
                String name = Buf.readString(buf);
                state = STATE_LOGIN;
                session.beginLogin(name, ctx);
            }
        } catch (Throwable t) {
            log.error("[client] 处理失败: {}", t.toString());
            session.closeAll();
        } finally {
            if (!handedOff && buf.refCnt() > 0) {
                buf.release();
            }
        }
    }

    private void handlePlay(ByteBuf buf) {
        ByteBuf copy = buf.duplicate();
        try {
            int id = VarInt.read(copy);
            if (id == Ids.PLAY_CHAT_SERVERBOUND) {
                String message = Buf.readString(copy);

                // /htp 系列是代理自己的本地指令，永远不发给服务器，也不受翻译开关影响
                if (handleLocalCommand(message)) {
                    buf.release();
                    return;
                }

                // 没开翻译、没填 key 就别扣消息：扣了也只能等超时再把原文发出去
                if (outgoingTranslation.get()
                        && session.config().translate.enabled
                        && session.config().translate.apiKey != null
                        && !session.config().translate.apiKey.isBlank()) {
                    // 只把中文翻成外语。普通聊天整句翻；/pc、/msg 这类聊天命令只翻正文，
                    // 命令本身和玩家名原样带过去；其它命令（/spawn、/gift）一律不动。
                    if (message.length() <= 100 && !message.startsWith("!")) {
                        String[] split = splitChatCommand(message);
                        if (split != null && com.mcproxy.chat.ChatText.hasCjk(split[1])) {
                            sendWithTranslation(split[1], split[0], buf);
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败就原样转发，绝不因为本地指令 / 翻译判断弄丢消息
            log.debug("[client] 出站聊天解析失败: {}", e.toString());
        }
        session.toServer(buf);
    }

    /**
     * 处理代理本地指令。返回 true 表示这条已经被消费，不能再发往服务器。
     *
     *   /htp                    切换发送翻译
     *   /htp on|off|toggle      切换发送翻译
     *   /htp outgoing on|off    明确开关
     *   /htp status             查看当前状态
     */
    private boolean handleLocalCommand(String message) {
        if (message == null) return false;
        String command = message.trim();
        if (!command.startsWith("/")) return false;
        String[] parts = command.substring(1).trim().split("\\s+");
        if (parts.length == 0 || !"htp".equalsIgnoreCase(parts[0])) return false;

        if (parts.length == 1) {
            setOutgoingTranslation(!outgoingTranslation.get());
            return true;
        }

        String sub = parts[1].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "on" -> {
                setOutgoingTranslation(true);
                return true;
            }
            case "off" -> {
                setOutgoingTranslation(false);
                return true;
            }
            case "toggle" -> {
                setOutgoingTranslation(!outgoingTranslation.get());
                return true;
            }
            case "status" -> {
                notifyOutgoingStatus();
                return true;
            }
            case "outgoing" -> {
                if (parts.length == 2) {
                    setOutgoingTranslation(!outgoingTranslation.get());
                } else {
                    String value = parts[2].toLowerCase(java.util.Locale.ROOT);
                    if ("on".equals(value)) {
                        setOutgoingTranslation(true);
                    } else if ("off".equals(value)) {
                        setOutgoingTranslation(false);
                    } else if ("toggle".equals(value)) {
                        setOutgoingTranslation(!outgoingTranslation.get());
                    } else {
                        notifyUsage();
                    }
                }
                return true;
            }
            default -> {
                notifyUsage();
                return true;
            }
        }
    }

    private void setOutgoingTranslation(boolean on) {
        outgoingTranslation.set(on);
        boolean ready = session.config().translate.enabled
                && session.config().translate.apiKey != null
                && !session.config().translate.apiKey.isBlank();
        String text = "[HTP] 发送翻译" + (on ? "已开启" : "已关闭");
        String color = on ? "green" : "red";
        if (on && !ready) {
            text = "[HTP] 发送翻译已开启，但翻译总开关或 API key 未配置，暂不会生效";
            color = "yellow";
        }
        String json = "{\"text\":\"" + text + "\",\"color\":\"" + color + "\"}";
        session.sendSelfChatJson(json);
    }

    private void notifyOutgoingStatus() {
        boolean on = outgoingTranslation.get();
        String json = "{\"text\":\"[HTP] 发送翻译当前："
                + (on ? "开启" : "关闭") + "\",\"color\":\"" + (on ? "green" : "red") + "\"}";
        session.sendSelfChatJson(json);
    }

    private void notifyUsage() {
        session.sendSelfChatJson("{\"text\":\"[HTP] 用法：/htp、/htp on|off|toggle、"
                + "/htp outgoing on|off|toggle、/htp status\",\"color\":\"yellow\"}");
    }

    /** 服务器列表里的 MOTD。文本/人数从配置读，支持 § 颜色码和 \n 换行。 */
    private void replyStatus(ChannelHandlerContext ctx) {
        com.google.gson.JsonObject description = new com.google.gson.JsonObject();
        description.addProperty("text", session.config().motd.text);
        com.google.gson.JsonObject players = new com.google.gson.JsonObject();
        players.addProperty("max", session.config().motd.max);
        players.addProperty("online", session.config().motd.online);
        com.google.gson.JsonObject version = new com.google.gson.JsonObject();
        version.addProperty("name", "1.8.9");
        version.addProperty("protocol", session.config().protocolVersion);

        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("version", version);
        root.add("players", players);
        root.add("description", description);
        String json = root.toString();

        ByteBuf out = ctx.alloc().buffer();
        VarInt.write(out, 0x00);
        Buf.writeString(out, json);
        ctx.writeAndFlush(out);
    }

    /** 这些命令后面跟的是人话（队伍、公会、私聊），可以翻；其它命令不碰 */
    private static final java.util.Set<String> CHAT_COMMANDS = java.util.Set.of(
            "pc", "ac", "gc", "sc", "shout", "p", "party", "g", "guild", "o", "officers",
            "msg", "m", "w", "tell", "whisper", "r", "reply", "me", "channel", "c");

    /**
     * 把一条待发聊天拆成"命令前缀 + 正文"。返回 null 表示这条不翻。
     *
     *   "/msg Steve 你好"  → {"/msg Steve ", "你好"}
     *   "/pc 你好"         → {"/pc ", "你好"}
     *   "你好"             → {"", "你好"}
     *   "/spawn"           → null（不是聊天命令）
     *   "/gift Steve 钻石"  → null（不在白名单，别去动命令参数）
     */
    private static String[] splitChatCommand(String message) {
        if (message == null || message.isEmpty()) return null;
        if (!message.startsWith("/")) return new String[]{"", message};

        int sp = message.indexOf(' ');
        if (sp < 0) return null;
        String cmd = message.substring(1, sp).toLowerCase(java.util.Locale.ROOT);
        if (!CHAT_COMMANDS.contains(cmd)) return null;

        // 命令名和玩家名都是 ASCII，正文从第一个汉字开始
        for (int i = sp; i < message.length(); i++) {
            if (com.mcproxy.chat.ChatText.hasCjk(String.valueOf(message.charAt(i)))) {
                String prefix = message.substring(0, i);
                String body = message.substring(i).trim();
                return body.isEmpty() ? null : new String[]{prefix, body};
            }
        }
        return null;          // 后面全是英文，本来就不用翻
    }

    /**
     * 先扣住原包等译文，但绝不无限期扣着：
     * 译文回来 → 发译文；超时/失败/队列满 → 原包照发。
     * 原来直接吞掉原包的写法会在翻译失败时把玩家的消息永久弄丢。
     */
    private void sendWithTranslation(String message, String prefix, ByteBuf buf) {
        final ByteBuf original = buf.retainedDuplicate();
        buf.release();

        final java.util.concurrent.atomic.AtomicBoolean sent =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        session.translator().submitOutgoing(message, t -> {
            if (sent.compareAndSet(false, true)) {
                session.sendOutgoingChat((prefix == null ? "" : prefix) + t);
                if (original.refCnt() > 0) original.release();
            }
        });

        session.schedule(session.config().translate.timeoutMs + 300L, () -> {
            if (sent.compareAndSet(false, true)) {
                session.toServer(original);      // 兜底：原文发出去
                log.debug("[client] 译文未及时返回，已按原文发送");
            }
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        session.closeAll();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("[client] 异常: {}", cause.toString());
        session.closeAll();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            log.info("[client] 客户端空闲超时");
            session.closeAll();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }
}
