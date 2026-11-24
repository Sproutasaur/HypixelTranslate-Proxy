package com.mcproxy.proto;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tab 探针：把服务端发来的"玩家列表 / 队伍 / 顶底标题"原样打到日志。
 * 只读不改，用来摸清真实服务器上 Tab 到底长什么样，再据此写改写规则。
 */
public final class TabDump {

    private static final Logger log = LoggerFactory.getLogger(TabDump.class);

    private TabDump() {}

    public static void dump(int id, ByteBuf b) {
        try {
            switch (id) {
                case Ids.PLAY_PLAYER_LIST_ITEM -> dumpPlayerList(b);
                case Ids.PLAY_TEAMS -> dumpTeam(b);
                case Ids.PLAY_HEADER_FOOTER -> dumpHeaderFooter(b);
                default -> { }
            }
        } catch (Throwable t) {
            log.debug("[tab] 解析失败 id=0x{} : {}", Integer.toHexString(id), t.toString());
        }
    }

    private static void dumpPlayerList(ByteBuf b) {
        int action = VarInt.read(b);
        if (action == 0) {
            int n = VarInt.read(b);
            StringBuilder sb = new StringBuilder("玩家加入 ").append(n).append(" 条：");
            for (int i = 0; i < n; i++) {
                byte[] uuid = new byte[16];     // 1.8：裸 16 字节
                b.readBytes(uuid);
                String name = Buf.readString(b);
                int props = VarInt.read(b);
                for (int p = 0; p < props; p++) {
                    Buf.readString(b);              // 属性名
                    Buf.readString(b);              // 属性值
                    if (b.readBoolean()) Buf.readString(b);   // 签名
                }
                int gamemode = VarInt.read(b);
                int ping = VarInt.read(b);
                boolean hasDisplay = b.readBoolean();
                String display = hasDisplay ? Buf.readString(b) : "";
                sb.append("\n   ").append(name)
                        .append("  uuid=").append(hex(uuid))
                        .append("  ping=").append(ping)
                        .append("  display=").append(display);
            }
            log.info("[tab] {}", sb);
        } else if (action == 4) {
            int n = VarInt.read(b);
            StringBuilder sb = new StringBuilder("玩家离开 ").append(n).append(" 条：");
            for (int i = 0; i < n; i++) sb.append(' ').append(Buf.readString(b));
            log.info("[tab] {}", sb);
        } else {
            log.debug("[tab] PlayerListItem action={}", action);
        }
    }

    private static void dumpTeam(ByteBuf b) {
        String team = Buf.readString(b);
        int mode = b.readByte() & 0xFF;
        switch (mode) {
            case 0, 2 -> {
                String display = Buf.readString(b);
                String prefix = Buf.readString(b);
                String suffix = Buf.readString(b);
                int flags = b.readByte() & 0xFF;
                log.info("[tab] 队伍 {} mode={} 前缀={} 后缀={} 显示名={} flags={}",
                        team, mode, prefix, suffix, display, flags);
            }
            case 3, 4 -> {
                int n = VarInt.read(b);
                StringBuilder sb = new StringBuilder("队伍 ").append(team)
                        .append(mode == 3 ? " 绑定成员：" : " 解绑成员：");
                for (int i = 0; i < n; i++) sb.append(' ').append(Buf.readString(b));
                log.info("[tab] {}", sb);
            }
            default -> log.info("[tab] 队伍 {} mode={}（移除）", team, mode);
        }
    }

    private static void dumpHeaderFooter(ByteBuf b) {
        String header = Buf.readString(b);
        String footer = Buf.readString(b);
        log.info("[tab] 顶底标题 header={} footer={}", header, footer);
    }

    private static String hex(byte[] uuid) {
        StringBuilder sb = new StringBuilder(36);
        for (int i = 0; i < uuid.length; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-');
            sb.append(String.format(java.util.Locale.ROOT, "%02x", uuid[i]));
        }
        return sb.toString();
    }
}
