package com.mcproxy.tab;

import com.mcproxy.proto.Buf;
import com.mcproxy.proto.VarInt;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录服务器下发的队伍前缀 / 后缀 / 颜色。
 *
 * 为什么非要这个：
 * 1.8.9 客户端画 Tab 那一行时是有条件的——玩家信息里有 displayName 就直接用，
 * 没有才去套 scoreboard 队伍（team.getPrefix + 名字 + team.getSuffix，名字再染 team 的颜色）。
 *
 * Hypixel 这类服务器的名字颜色和队伍标签恰好是放在队伍里的，displayName 是空的。
 * 我们为了在名字后面挂 FKDR，必须给客户端下发一个 displayName；
 * 一旦下发，客户端就再也不会去套队伍了 —— 结果就是名字全白、前缀和队伍颜色全消失。
 *
 * 所以这里把队伍信息抄一份，挂统计的时候自己拼回去。
 */
public final class TeamRegistry {

    private static final Logger log = LoggerFactory.getLogger(TeamRegistry.class);

    private static final String[] COLOR_CODES = {
            "§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7",
            "§8", "§9", "§a", "§b", "§c", "§d", "§e", "§f"
    };

    private static final class Team {
        final String prefix;
        final String suffix;
        final String color;

        Team(String prefix, String suffix, String color) {
            this.prefix = prefix == null ? "" : prefix;
            this.suffix = suffix == null ? "" : suffix;
            this.color = color == null ? "" : color;
        }
    }

    private final Map<String, Team> teams = new ConcurrentHashMap<>();
    private final Map<String, String> playerTeam = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger parsed = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger broken = new java.util.concurrent.atomic.AtomicInteger();
    private volatile Runnable changeListener;

    /** 队伍信息变化后通知 TabStats 回刷已经发出去的 displayName。 */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    private void notifyChanged() {
        Runnable listener = changeListener;
        if (listener == null) return;
        try {
            listener.run();
        } catch (Throwable t) {
            log.debug("[team] 回刷通知失败: {}", t.toString());
        }
    }

    /** 解析 0x3E。传进来的是 duplicate，哪怕解析炸了也影响不到原包转发。 */
    public void handle(ByteBuf b) {
        try {
            String teamName = Buf.readString(b);
            int mode = b.readByte();
            switch (mode) {
                case 0, 2 -> {
                    Buf.readString(b);                          // displayName，用不上
                    String prefix = Buf.readString(b);
                    String suffix = Buf.readString(b);
                    b.readByte();                               // friendly flags
                    Buf.readString(b);                          // name tag visibility
                    int color = b.readByte();                   // 1.8: 0-15，-1 表示无色
                    String code = color >= 0 && color < COLOR_CODES.length ? COLOR_CODES[color] : "";
                    teams.put(teamName, new Team(prefix, suffix, code));
                    TabDebug.log("[team] " + teamName + " mode=" + mode
                            + " prefix=" + prefix + " suffix=" + suffix + " color=" + color);
                    if (mode == 0) readPlayers(b, teamName, true);
                }
                case 1 -> {
                    teams.remove(teamName);
                    playerTeam.values().removeIf(teamName::equals);
                }
                case 3 -> readPlayers(b, teamName, true);
                case 4 -> readPlayers(b, teamName, false);
                default -> { }
            }
            parsed.incrementAndGet();
            notifyChanged();
        } catch (Throwable t) {
            // 队伍包格式对不上就跳过这一包，绝不影响转发
            broken.incrementAndGet();
            log.debug("[team] 解析失败: {}", t.toString());
        }
    }

    private void readPlayers(ByteBuf b, String teamName, boolean add) {
        int n = VarInt.read(b);
        for (int i = 0; i < n; i++) {
            String player = Buf.readString(b);
            if (add) {
                playerTeam.put(player, teamName);
            } else if (teamName.equals(playerTeam.get(player))) {
                playerTeam.remove(player);
            }
        }
    }

    /** 队伍前缀（一段聊天组件 JSON），没有就返回空串。 */
    public String prefixOf(String player) {
        Team t = team(player);
        return t == null ? "" : t.prefix;
    }

    public String suffixOf(String player) {
        Team t = team(player);
        return t == null ? "" : t.suffix;
    }

    /** 队伍颜色对应的 § 码，没有就返回空串。 */
    public String colorOf(String player) {
        Team t = team(player);
        if (t == null) return "";

        String fromPrefix = firstColor(t.prefix);

        // Hypixel 的部分队伍会把协议 color 固定发成 15（白色），
        // 但真正的玩家/队伍颜色放在 prefix 里（例如只有 §a 或 §c）。
        // 如果仍然无条件相信 §f，TabStats 写入 displayName 后就会把名字染白，
        // 原本应有的绿色/红色等前缀颜色也无法传递到玩家名上。
        // 非白色的协议队伍颜色仍优先，避免覆盖真正的队伍颜色。
        if ("§f".equals(t.color) && !fromPrefix.isEmpty()) return fromPrefix;

        if (!t.color.isEmpty()) return t.color;

        // color=-1（无队伍颜色）时，继续从前缀/后缀取颜色。
        return !fromPrefix.isEmpty() ? fromPrefix : firstColor(t.suffix);
    }

    /** 取字符串里第一个颜色码（跳过 §l/§r 这类格式码）；也认 JSON 里的 "color":"red"。 */
    private static String firstColor(String s) {
        if (s == null) return "";
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) != '§') continue;
            char c = Character.toLowerCase(s.charAt(i + 1));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) return "§" + c;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"color\"\\s*:\\s*\"([a-z_]+)\"")
                .matcher(s);
        if (m.find()) {
            String code = switch (m.group(1)) {
                case "black" -> "§0";
                case "dark_blue" -> "§1";
                case "dark_green" -> "§2";
                case "dark_aqua" -> "§3";
                case "dark_red" -> "§4";
                case "dark_purple" -> "§5";
                case "gold" -> "§6";
                case "gray" -> "§7";
                case "dark_gray" -> "§8";
                case "blue" -> "§9";
                case "green" -> "§a";
                case "aqua" -> "§b";
                case "red" -> "§c";
                case "light_purple" -> "§d";
                case "yellow" -> "§e";
                case "white" -> "§f";
                default -> "";
            };
            if (!code.isEmpty()) return code;
        }
        return "";
    }

    private Team team(String player) {
        if (player == null) return null;
        String teamName = playerTeam.get(player);
        return teamName == null ? null : teams.get(teamName);
    }

    public String stats() {
        return "队伍=" + teams.size() + " 队员=" + playerTeam.size()
                + " 包ok=" + parsed.get() + " 包错=" + broken.get();
    }
}
