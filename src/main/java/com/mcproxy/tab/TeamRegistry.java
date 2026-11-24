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
        return t == null ? "" : t.color;
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
