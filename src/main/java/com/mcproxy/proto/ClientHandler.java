package com.mcproxy.proto;

import com.mcproxy.Config;
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

    public ClientHandler(Session session) {
        this.session = session;
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
            if (id == Ids.PLAY_TAB_COMPLETE_SERVERBOUND) {
                String text = Buf.readString(copy);
                java.util.List<String> matches = htpTabCompletions(text);
                if (matches != null) {
                    session.sendTabComplete(matches);
                    buf.release();
                    return;
                }
            }
            if (id == Ids.PLAY_CHAT_SERVERBOUND) {
                String message = Buf.readString(copy);

                // /htp 系列是代理自己的本地指令，永远不发给服务器，也不受翻译开关影响
                if (handleLocalCommand(message)) {
                    buf.release();
                    return;
                }

                // 没开翻译、没填 key 就别扣消息：扣了也只能等超时再把原文发出去
                if (session.config().translate.translateOutgoing
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

    private static final java.util.List<String> FEATURES =
            java.util.List.of("translate", "outgoing", "tab", "cheat", "status");
    private static final java.util.List<String> VALUES =
            java.util.List.of("on", "off");

    /**
     * 处理代理本地指令。返回 true 表示这条已经被消费，不能再发往服务器。
     * 每个功能一条：/htp <feature> on|off。
     */
    private boolean handleLocalCommand(String message) {
        if (message == null) return false;
        String command = message.trim();
        if (!command.startsWith("/")) return false;
        String[] parts = command.substring(1).trim().split("\\s+");
        if (parts.length == 0 || !"htp".equalsIgnoreCase(parts[0])) return false;

        String feature = parts.length >= 2 ? parts[1].toLowerCase(java.util.Locale.ROOT) : "";
        if ("status".equals(feature)) {
            notifyStatus();
            return true;
        }
        if (parts.length != 3 || !FEATURES.contains(feature)) {
            notifyUsage();
            return true;
        }

        Boolean on = parseOnOff(parts[2]);
        if (on == null) {
            notifyUsage();
            return true;
        }
        setFeature(feature, on);
        return true;
    }

    private static Boolean parseOnOff(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "on" -> Boolean.TRUE;
            case "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    private void setFeature(String feature, boolean on) {
        Config cfg = session.config();
        switch (feature) {
            case "translate" -> cfg.translate.enabled = on;
            case "outgoing" -> cfg.translate.translateOutgoing = on;
            case "tab" -> cfg.tab.enabled = on;
            case "cheat" -> cfg.tab.cheatWarn.enabled = on;
            default -> { }
        }
        notifyFeature(feature, on);
    }

    private void notifyFeature(String feature, boolean on) {
        String label = switch (feature) {
            case "translate" -> "翻译总开关";
            case "outgoing" -> "发消息翻译";
            case "tab" -> "Tab 统计";
            case "cheat" -> "作弊提醒";
            default -> feature;
        };
        String text = "[HTP] " + label + (on ? "已开启" : "已关闭");
        String color = on ? "green" : "red";

        if ("outgoing".equals(feature) && on && !session.config().translate.enabled) {
            text += "（翻译总开关关闭，实际不会生效）";
            color = "yellow";
        } else if ("cheat".equals(feature) && on && !session.config().tab.enabled) {
            text += "（Tab 未开启，作弊提醒不会生效）";
            color = "yellow";
        } else if ("tab".equals(feature) && !on) {
            text += "（作弊提醒也随之不查）";
            color = "yellow";
        }
        session.sendSelfChatJson("{\"text\":\"" + text + "\",\"color\":\"" + color + "\"}");
    }

    private void notifyStatus() {
        Config cfg = session.config();
        sendLocal("yellow", "[HTP] 当前开关");
        sendLocal("gray", "翻译：" + onOff(cfg.translate.enabled));
        sendLocal("gray", "发消息：" + onOff(cfg.translate.translateOutgoing));
        sendLocal("gray", "Tab：" + onOff(cfg.tab.enabled));
        sendLocal("gray", "作弊提醒：" + onOff(cfg.tab.cheatWarn.enabled));
        sendLocal("gray", "队伍数据：" + session.teams().stats());
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    private void notifyUsage() {
        sendLocal("yellow", "[HTP] 用法");
        sendLocal("aqua", "/htp translate on/off - 翻译总开关");
        sendLocal("aqua", "/htp outgoing on/off - 发消息前翻译");
        sendLocal("aqua", "/htp tab on/off - Tab 统计");
        sendLocal("aqua", "/htp cheat on/off - 作弊提醒");
        sendLocal("aqua", "/htp status - 查看当前开关");
    }

    /** 一行一条发到自己聊天栏；1.8 的 text 字段不解析 § 码，颜色走 color 字段。 */
    private void sendLocal(String color, String line) {
        session.sendSelfChatJson("{\"text\":\"" + line + "\",\"color\":\"" + color + "\"}");
    }

    /**
     * /htp 的 Tab 补全。返回 null 表示不是 /htp，交给上游处理；返回空列表表示没有候选。
     */
    private static java.util.List<String> htpTabCompletions(String text) {
        if (text == null) return null;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int start;
        if (lower.startsWith("/htp")) start = 4;
        else if (lower.startsWith("htp")) start = 3;
        else return null;

        String rest = text.substring(start);
        // 光标还在命令本身：给整条候选，让客户端先补到 "/htp " 并列出功能。
        // 已经进入参数后只能给"当前这个词"的片段，否则客户端会把整条再拼一遍，
        // 结果就是 "/htp /htp translate"。
        if (rest.isEmpty()) return commandCandidates();

        if (!rest.startsWith(" ")) {
            return featureMatches(rest);
        }
        String afterSpace = rest.substring(1);
        int sp = afterSpace.indexOf(' ');
        if (sp < 0) {
            java.util.List<String> matches = featureMatches(afterSpace);
            // 功能名已经完整时补一个空格，按 Tab 直接进参数位，不用再手打空格。
            if (matches.size() == 1 && matches.get(0).equalsIgnoreCase(afterSpace)
                    && !"status".equalsIgnoreCase(afterSpace)) {
                return java.util.List.of(afterSpace.toLowerCase(java.util.Locale.ROOT) + " ");
            }
            return matches;
        }

        String feature = afterSpace.substring(0, sp).toLowerCase(java.util.Locale.ROOT);
        String partial = afterSpace.substring(sp + 1);
        if (partial.indexOf(' ') >= 0 || "status".equals(feature) || !FEATURES.contains(feature)) {
            return java.util.List.of();
        }
        return valueMatches(feature, partial);
    }

    /** 还在命令本身时给整条候选，客户端会把公共前缀补成 "/htp " 并列出来。 */
    private static java.util.List<String> commandCandidates() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String feature : FEATURES) out.add("/htp " + feature);
        return out;
    }

    /** 参数位置只返回要补的那个词，客户端自己会保留前面的 "/htp "。 */
    private static java.util.List<String> featureMatches(String partial) {
        String p = partial.toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String feature : FEATURES) {
            if (feature.startsWith(p)) out.add(feature);
        }
        return out;
    }

    /** 同上：只补 on/off，不把 "/htp tab " 再塞回去。 */
    private static java.util.List<String> valueMatches(String feature, String partial) {
        String p = partial.toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String value : VALUES) {
            if (value.startsWith(p)) out.add(value);
        }
        return out;
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
