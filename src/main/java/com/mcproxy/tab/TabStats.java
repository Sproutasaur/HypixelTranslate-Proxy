package com.mcproxy.tab;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcproxy.Config;
import com.mcproxy.chat.ChatText;
import com.mcproxy.proto.Buf;
import com.mcproxy.proto.VarInt;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tab 名字后面挂统计。
 *
 * 1.8 的玩家列表包（0x38）有一个独立字段 displayName，Tab 那一行是
 * "队伍前缀 + displayName + 队伍后缀"。所以只改 displayName 就能在名字后加字，
 * 而且不会影响头顶名牌（名牌用的是真实玩家名）。
 *
 * 数据来源：Hypixel 公共 API /v2/player?uuid=...（需要 API key）。
 */
public final class TabStats {

    private static final Logger log = LoggerFactory.getLogger(TabStats.class);

    /** 玩家列表里的一条"加入"记录 */
    public static final class Add {
        public final byte[] uuid;
        public final String name;
        public final String display;

        Add(byte[] uuid, String name, String display) {
            this.uuid = uuid;
            this.name = name;
            this.display = display;
        }
    }

    /** 从 Hypixel API 拿到的段位样式（Tab 名字前缀 + 名字颜色） */
    private record Rank(String prefix, String color) {}

    /** 数据来自哪一路，决定这份缓存还需不需要补查 Urchin */
    private enum Source { OFFICIAL, THIRD_PARTY }

    /** 查回来的结果：数值 + 段位 + 来源。数值为 null 表示这个玩家没这项数据 */
    private static final class Queried {
        final String value;
        final Rank rank;
        /** 第三方带回来的 Urchin 作弊标记，没有就是 null */
        final CheatFlag flag;
        final Source source;
        /** 这一次是否已经从第三方拿到 Urchin 结论（Not_Blacklisted 也算查过） */
        final boolean urchinChecked;
        /** 本该查的数据源是否都查到了；false = 中途有网络/额度失败，别当"查不到"缓存 */
        final boolean complete;

        /** 官方结果：不带 Urchin 字段 */
        Queried(String value, Rank rank) {
            this(value, rank, null, Source.OFFICIAL, false, true);
        }

        /** 第三方结果：一次请求就把 Urchin 一起带回来了 */
        Queried(String value, Rank rank, CheatFlag flag) {
            this(value, rank, flag, Source.THIRD_PARTY, true, true);
        }

        Queried(String value, Rank rank, CheatFlag flag, Source source, boolean urchinChecked, boolean complete) {
            this.value = value;
            this.rank = rank;
            this.flag = flag;
            this.source = source;
            this.urchinChecked = urchinChecked;
            this.complete = complete;
        }
    }

    /** Urchin 黑名单标记，字段原样保留 */
    public static final class CheatFlag {
        public final String status;
        public final String type;
        public final String reason;

        public CheatFlag(String status, String type, String reason) {
            this.status = status == null ? "" : status.trim();
            this.type = type == null ? "" : type.trim();
            this.reason = reason == null ? "" : reason.trim();
        }
    }

    private record Cached(String text, Rank rank, Add add, long at, CheatFlag flag,
                          boolean urchinChecked, boolean fromOfficial) {}

    private final Config cfg;
    private final TeamRegistry teams;
    private final ExecutorService exec;
    private final HttpClient http;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> inflight = new ConcurrentHashMap<>();

    /**
     * 因为每分钟额度用完而没查成的人，先放这儿过一会儿再试。
     * 大厅人一多额度就见底，进游戏换了一批人又全查不了 —— 表现就是"只有大厅有统计"。
     */
    private final Map<String, Add> retry = new ConcurrentHashMap<>();
    private final Map<String, Integer> retryTries = new ConcurrentHashMap<>();

    private final AtomicInteger done = new AtomicInteger();

    /**
     * 令牌桶。以前是"整分钟计数器"，一分钟的额度会被进服瞬间打光，
     * 之后所有人只能干等下个整分钟 —— 最多白等 59 秒，体感就是"特别慢"。
     * 现在按每分钟速率均匀放行，额度不会被瞬间吃掉，进对局时还剩得下。
     */
    private final Object gate = new Object();
    private double tokens = 8.0;
    private long lastRefill = System.currentTimeMillis();

    /** 被 429 之后进入冷却，冷却期内一个请求都不发，避免空转刷 limiter */
    private volatile long cooldownUntil = 0L;

    /** 把改写好的 displayName 发回客户端 */
    @FunctionalInterface
    public interface Sender {
        void send(byte[] uuid, String displayNameJson);
    }

    private final Sender sender;
    /** 调试用：往自己聊天栏发一行纯文本 */
    private final java.util.function.Consumer<String> chat;
    /** 带颜色的提示行：直接发 JSON 组件（文案里的 § 码在 text 字段里不生效） */
    private final java.util.function.Consumer<String> chatJson;
    private final AtomicInteger debugSent = new AtomicInteger();

    /** 本局已经提示过的玩家（按 uuid key 去重），换局或重连时清空 */
    private final java.util.Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicInteger warnSent = new AtomicInteger();
    /** 上一轮 Tab 里的人名，用来判断"换局"（大换血时重置提示计数） */
    private final java.util.Set<String> roundNames = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 会话结束时把线程和定时任务一起停掉，避免重连一次漏一组后台任务 */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final java.util.concurrent.ScheduledExecutorService ticker;
    /** 队伍包可能晚于玩家列表到达；合并短时间内的多次变化，只回刷一次 */
    private final AtomicBoolean teamRefreshQueued = new AtomicBoolean(false);

    public TabStats(Config cfg, TeamRegistry teams, Sender sender,
                    java.util.function.Consumer<String> chat,
                    java.util.function.Consumer<String> chatJson) {
        this.cfg = cfg;
        this.teams = teams;
        this.sender = sender;
        this.chat = chat;
        this.chatJson = chatJson;
        // 一个玩家一个请求，串行查 80 个人要一分多钟，并发才跟得上 Tab 刷新
        int threads = Math.max(cfg.tab.concurrency, 1);
        this.exec = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "tabstats");
            t.setDaemon(true);
            return t;
        });
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        // 每隔几秒把上一轮没排上队的人捞出来重试，额度一恢复就能补上
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tabstats-retry");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(this::drainRetry, 2, 2, java.util.concurrent.TimeUnit.SECONDS);

        // 队伍信息晚到（Hypixel 常见）时，已经挂上去的白名字要重新上色
        this.teams.setChangeListener(this::onTeamsChanged);
    }

    private void onTeamsChanged() {
        if (closed.get() || !cfg.tab.enabled) return;
        if (!teamRefreshQueued.compareAndSet(false, true)) return;
        try {
            ticker.schedule(() -> {
                teamRefreshQueued.set(false);
                refreshTeamDisplays();
            }, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            teamRefreshQueued.set(false);
        }
    }

    /** 队伍前缀/颜色/后缀变了，重发所有已有统计的 Tab 行。 */
    private void refreshTeamDisplays() {
        if (closed.get() || !cfg.tab.enabled) return;
        for (Cached c : cache.values()) {
            if (c.add == null || c.text == null) continue;
            if ("?".equals(c.text) && !cfg.tab.showUnknown) continue;
            try {
                assertDisplay(c.add, c.text, c.rank);
            } catch (Throwable t) {
                log.debug("[tab] 回刷 {} 失败: {}", c.add.name, t.toString());
            }
        }
    }

    private void drainRetry() {
        if (closed.get() || retry.isEmpty()) return;
        List<Add> batch;
        synchronized (retry) {
            if (retry.isEmpty()) return;
            batch = new ArrayList<>(retry.values());
            retry.clear();
        }
        onAdds(batch);
    }

    // ---------------------------------------------------------------- 解析

    /** 解析 0x38 的 action=0（玩家加入），返回里面所有玩家的 uuid / 名字 / displayName。 */
    public static List<Add> parseAdds(ByteBuf b) {
        List<Add> out = new ArrayList<>();
        int action = VarInt.read(b);
        if (action != 0) return out;

        int n = VarInt.read(b);
        for (int i = 0; i < n; i++) {
            byte[] uuid = new byte[16];      // 1.8 的 UUID 是裸 16 字节，没有长度前缀
            b.readBytes(uuid);
            String name = Buf.readString(b);
            int props = VarInt.read(b);
            for (int p = 0; p < props; p++) {
                Buf.readString(b);
                Buf.readString(b);
                if (b.readBoolean()) Buf.readString(b);
            }
            VarInt.read(b);                 // gamemode
            VarInt.read(b);                 // ping
            boolean hasDisplay = b.readBoolean();
            String display = hasDisplay ? Buf.readString(b) : "";
            out.add(new Add(uuid, name, display));
        }
        return out;
    }

    /** 解析 0x38 的 action=3（上游更新显示名），返回被更新到的 uuid。 */
    public static List<byte[]> parseDisplayUpdate(ByteBuf b) {
        List<byte[]> out = new ArrayList<>();
        int action = VarInt.read(b);
        if (action != 3) return out;

        int n = VarInt.read(b);
        for (int i = 0; i < n; i++) {
            byte[] uuid = new byte[16];
            b.readBytes(uuid);
            if (b.readBoolean()) Buf.readString(b);   // 新的显示名，内容不用看
            out.add(uuid);
        }
        return out;
    }

    // ---------------------------------------------------------------- 处理

    public void onAdds(List<Add> adds) {
        if (closed.get()) return;
        if (adds == null || adds.isEmpty()) return;
        if (!cfg.tab.enabled) return;

        String mode = dataSource();
        // 只有"纯官方"模式才硬性要求 key；auto/thirdparty 没 key 也能用第三方
        if ("official".equals(mode) && !hasOfficialKey()) return;

        maybeNewRound(adds);
        debugLog("=== 玩家列表 " + adds.size() + " 人 | " + dataSource() + " | " + teams.stats());

        for (Add a : adds) {
            if (closed.get()) return;
            if (npcLike(a)) continue;        // Tab 里的 NPC 也不挂统计、不查接口
            String cacheKey = uuidKey(a.uuid);
            String apiUuid = hexUuid(a.uuid);
            if (cacheKey == null || apiUuid == null || a.name == null || a.name.isEmpty()) continue;

            Cached c = cache.get(cacheKey);
            if (c != null && fresh(c)) {
                // "?" 是查不到（nick / 隐藏统计）的缓存：showUnknown 关着就别再往名字上挂
                if (cfg.tab.enabled && (!"?".equals(c.text) || cfg.tab.showUnknown)) {
                    assertDisplay(a, c.text, c.rank);
                }
                // auto 下官方额度已经用完时，官方缓存里没有 Urchin，得单独补查一次第三方。
                // 否则同一个人会在整个缓存期内一直"有统计但永远不提示作弊"。
                if (cheatWarnOn() && !c.urchinChecked && "auto".equals(mode) && !officialAvailableNow()) {
                    requestUrchin(a, cacheKey);
                } else {
                    maybeWarn(a, c.urchinChecked ? c.flag : null);
                }
                continue;
            }
            if (inflight.putIfAbsent(cacheKey, Boolean.TRUE) != null) continue;
            // 队列已经排很长时先放回重试队列，别让任务无限堆积（大厅人一多就会）
            if (exec instanceof java.util.concurrent.ThreadPoolExecutor tpe
                    && tpe.getQueue().size() > Math.max(cfg.tab.concurrency, 1) * 24) {
                inflight.remove(cacheKey);
                enqueueRetry(cacheKey, a);
                continue;
            }
            // 先占个位，不然新人进 Tab 后要干等好几秒才出现统计
            if (cfg.tab.enabled && cfg.tab.showPlaceholder) assertDisplay(a, "…", null);

            try {
                exec.execute(() -> {
                    try {
                        if (closed.get()) return;
                        // 官方冷却中（限速/当日额度用完）时，纯官方模式才停；
                        // auto 模式要照常走第三方，不然额度一满就"只有大厅有统计"。
                        if (System.currentTimeMillis() < cooldownUntil && "official".equals(mode)) {
                            return;
                        }
                        if (!takeBudget(1500)) {
                            // 额度一时没到不能就这么算了：排到重试队列，等令牌补上再查
                            log.debug("[tab] 额度未轮到，{} 稍后重试", a.name);
                            enqueueRetry(cacheKey, a);
                            return;
                        }
                        Queried q = fetchStat(apiUuid, a.name);
                        if (q == null) {
                            enqueueRetry(cacheKey, a);
                            return;
                        }
                        retryTries.remove(cacheKey);
                        if (q.value == null) {
                            // 本该查的那一路中途失败了（比如官方没数据、第三方正好断网），
                            // 不能当"两个源都查不到"缓存 3 小时，放回重试队列。
                            if (!q.complete) {
                                enqueueRetry(cacheKey, a);
                                return;
                            }
                            // 确认两个源都没有 = nick / 隐藏统计 / 没这游戏的数据。
                            // 也缓存起来，免得 Tab 每刷一次就为同一个 nick 白查两个源。
                            cache.put(cacheKey, new Cached("?", q.rank, a, System.currentTimeMillis(),
                                    q.flag, q.urchinChecked, q.source == Source.OFFICIAL));
                            if (cfg.tab.enabled && cfg.tab.showUnknown) assertDisplay(a, "?", q.rank);
                            maybeWarn(a, q.flag);
                            return;
                        }
                        cache.put(cacheKey, new Cached(q.value, q.rank, a, System.currentTimeMillis(),
                                q.flag, q.urchinChecked, q.source == Source.OFFICIAL));
                        if (cfg.tab.enabled) assertDisplay(a, q.value, q.rank);
                        maybeWarn(a, q.flag);
                        done.incrementAndGet();
                    } catch (Exception e) {
                        log.debug("[tab] 查询 {} 失败: {}", a.name, e.toString());
                        noteFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
                    } finally {
                        inflight.remove(cacheKey);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // 会话已关闭 / 线程池已停：撤销占位，别留一个永远不回来的 inflight
                inflight.remove(cacheKey);
            }
        }
    }

    /**
     * 官方已经在用（有 key 且不在 429 冷却里）→ auto 不会落到第三方，
     * 这时候没必要额外发一次第三方请求。
     */
    private boolean officialAvailableNow() {
        return hasOfficialKey() && System.currentTimeMillis() >= cooldownUntil;
    }

    /**
     * 官方缓存补查 Urchin：只查第三方、不重新打官方，查到后只更新作弊标记，
     * 统计数字和段位沿用原来官方那份。
     */
    private void requestUrchin(Add a, String cacheKey) {
        if (closed.get()) return;
        if (inflight.putIfAbsent(cacheKey, Boolean.TRUE) != null) return;
        try {
            exec.execute(() -> {
                try {
                    if (closed.get()) return;
                    if (!takeBudget(1500)) return;   // Tab 下次刷新还会再问，不必堆重试
                    Queried q = fetchThirdParty(a.name);
                    if (q == null || !q.urchinChecked) return;
                    Cached cur = cache.get(cacheKey);
                    if (cur == null) return;         // 已经被换掉/清掉了，以当时那份为准
                    cache.put(cacheKey, new Cached(cur.text, cur.rank, cur.add, cur.at,
                            q.flag, true, cur.fromOfficial));
                    maybeWarn(a, q.flag);
                } catch (Exception e) {
                    log.debug("[tab] 补查 Urchin 失败 {}: {}", a.name, e.toString());
                } finally {
                    inflight.remove(cacheKey);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            inflight.remove(cacheKey);
        }
    }

    /** 排队重试。次数不多，剩下靠 Tab 下次刷新自然补，避免队列越攒越长。 */
    private void enqueueRetry(String cacheKey, Add a) {
        int tries = retryTries.merge(cacheKey, 1, Integer::sum);
        if (tries <= 6) retry.put(cacheKey, a);
    }

    /**
     * 上游改了某个玩家的 displayName（action=3）时调这里：
     * 如果这个人我们挂过统计，就按缓存重新发一次，把被顶掉的字补回去。
     */
    public void onDisplayUpdate(List<byte[]> uuids) {
        if (uuids == null || uuids.isEmpty()) return;
        for (byte[] uuid : uuids) {
            String key = uuidKey(uuid);
            if (key == null) continue;
            Cached c = cache.get(key);
            if (c == null || c.add == null || !fresh(c)) continue;
            if (!cfg.tab.enabled) continue;
            if ("?".equals(c.text) && !cfg.tab.showUnknown) continue;
            assertDisplay(c.add, c.text, c.rank);
        }
    }

    /**
     * Tab 里的假人：Hypixel 会把 NPC、商店、任务发布者塞进玩家列表，
     * 它们要么没 API 数据、要么白占每分钟的请求额度。
     * 判断复用 translate.skipPrefixes（默认 "[NPC]"），名字和显示名都看。
     */
    private boolean npcLike(Add a) {
        String[] prefixes = cfg.translate == null ? null : cfg.translate.skipPrefixes;
        if (prefixes == null || prefixes.length == 0) return false;

        String name = ChatText.stripFormatting(a.name == null ? "" : a.name).trim();
        String display = a.display == null || a.display.isEmpty()
                ? "" : ChatText.stripFormatting(ChatText.toPlain(a.display)).trim();

        for (String p : prefixes) {
            if (p == null) continue;
            String want = ChatText.stripFormatting(p).trim();
            if (want.isEmpty()) continue;
            if (name.regionMatches(true, 0, want, 0, want.length())) return true;
            if (display.regionMatches(true, 0, want, 0, want.length())) return true;
        }
        return false;
    }

    private boolean fresh(Cached c) {
        return (System.currentTimeMillis() - c.at) < Math.max(cfg.tab.cacheMinutes, 1) * 60_000L;
    }

    /**
     * 在名字后面追加统计，名字本体一个字都不动。
     *
     * 1.8.9 客户端画 Tab 行是有条件的：玩家信息里有 displayName 就直接用，
     * 没有才去套 scoreboard 队伍（前缀 + 名字 + 后缀，名字染队伍颜色）。
     * Hypixel 这类服的名字颜色和队伍标签就放在队伍里，displayName 是空的。
     * 我们为了挂统计必须下发 displayName，一下发客户端就不套队伍了 ——
     * 所以名字会变纯白、队伍前缀全丢。这里分两种情况兜住。
     */
    /** MC 的 16 个颜色名，下标就是协议里的颜色值 */
    private static final String[] COLOR_NAMES = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white"
    };

    /** "§b" → "aqua"；认不出就当白色 */
    private static String colorName(String code) {
        if (code == null || code.length() < 2) return "white";
        int idx = "0123456789abcdef".indexOf(Character.toLowerCase(code.charAt(code.length() - 1)));
        return idx >= 0 ? COLOR_NAMES[idx] : "white";
    }

    private void assertDisplay(Add a, String value, Rank rank) {
        List<JsonElement> pieces = new ArrayList<>(namePieces(a, rank));
        // 统计段一律用结构化的 color 字段，不靠文本里的 § 码
        addColored(pieces, cfg.tab.separator);
        addColored(pieces, cfg.tab.valueColor + (value == null ? "?" : value));
        addColored(pieces, " §7" + cfg.tab.label);

        JsonObject root = new JsonObject();
        root.addProperty("text", "");
        JsonArray extra = new JsonArray();
        for (JsonElement p : pieces) extra.add(p);
        root.add("extra", extra);

        String json = root.toString();
        debugLog("[tab] " + a.name
                + " | 上游display=" + a.display
                + " | 队伍前缀=" + teams.prefixOf(a.name) + " 队伍色=" + teams.colorOf(a.name)
                + " | rank=" + (rank == null ? "null" : rank.prefix + "|" + rank.color)
                + " | 发出=" + json);
        if (cfg.debugTab && chat != null && debugSent.getAndIncrement() < 3) {
            chat.accept("[d] " + a.name + " 上游=" + a.display
                    + " 队色=" + teams.colorOf(a.name)
                    + " rank=" + (rank == null ? "-" : rank.color));
        }
        sender.send(a.uuid, json);
    }

    // ------------------------------------------------------------ 作弊提醒

    /**
     * 换局判定：一次 Tab 里有 8 人以上、且认识的名字不到四分之一，就当"大换血"= 新一局，
     * 把提示去重集合和计数清空。否则一局刷满上限后，后面几局会一直静音。
     */
    private void maybeNewRound(List<Add> adds) {
        if (adds.size() < 8) return;
        int known = 0;
        for (Add a : adds) {
            if (a.name != null && roundNames.contains(a.name.toLowerCase(java.util.Locale.ROOT))) known++;
        }
        if (known * 4 <= adds.size() && (!warned.isEmpty() || warnSent.get() > 0)) {
            warned.clear();
            warnSent.set(0);
            log.debug("[warn] 检测到换局（{} 人里只认识 {} 个），重置提示计数", adds.size(), known);
        }
        roundNames.clear();
        for (Add a : adds) {
            if (a.name != null && !a.name.isEmpty()) {
                roundNames.add(a.name.toLowerCase(java.util.Locale.ROOT));
            }
        }
    }

    private boolean cheatWarnOn() {
        return cfg.tab.enabled && cfg.tab.cheatWarn != null && cfg.tab.cheatWarn.enabled;
    }

    /** 同一个人每局只提示一次，且总数不超过 maxPerSession。 */
    private void maybeWarn(Add a, CheatFlag f) {
        if (f == null || !cheatWarnOn()) return;
        String type = f.type == null ? "" : f.type.toLowerCase(java.util.Locale.ROOT);
        if ("confirmed".equalsIgnoreCase(cfg.tab.cheatWarn.minLevel)
                && !type.contains("confirmed")) {
            return;
        }
        String key = uuidKey(a.uuid);
        if (key == null) return;
        if (!warned.add(key)) return;
        if (warnSent.get() >= Math.max(cfg.tab.cheatWarn.maxPerSession, 1)) return;
        warnSent.incrementAndGet();
        log.debug("[warn] {} 被 Urchin 标记：{} / {} / {}", a.name, f.status, f.type, f.reason);
        if (chatJson != null) chatJson.accept(warnJson(a.name, f));
    }

    /**
     * 提示行（只发到自己聊天栏）：
     * [Urchin] Steve [作弊确认] confirmed_cheater · legitscaff · Blacklisted
     * 注意：1.8 的聊天组件不解析 text 里的 § 码，所以这里拼成 extra 数组、用 color 字段上色。
     */
    static String warnJson(String name, CheatFlag f) {
        List<JsonElement> pieces = new ArrayList<>();
        addColored(pieces, "§8[§bUrchin§8] ");
        addColored(pieces, "§f" + ChatText.stripFormatting(name == null ? "" : name));
        addColored(pieces, " " + cheatTag(f));

        StringBuilder detail = new StringBuilder();
        if (!f.type.isEmpty()) detail.append("§7").append(f.type);
        if (!f.reason.isEmpty()) {
            if (detail.length() > 0) detail.append(" §8· ");
            detail.append("§7").append(f.reason);
        }
        if (!f.status.isEmpty()) {
            if (detail.length() > 0) detail.append(" §8· ");
            detail.append("§c").append(f.status);
        }
        if (detail.length() > 0) {
            addColored(pieces, " ");
            addColored(pieces, detail.toString());
        }

        JsonArray extra = new JsonArray();
        for (JsonElement p : pieces) extra.add(p);
        JsonObject root = new JsonObject();
        root.addProperty("text", "");
        root.add("extra", extra);
        return root.toString();
    }

    private static String cheatTag(CheatFlag f) {
        String type = f.type == null ? "" : f.type.toLowerCase(java.util.Locale.ROOT);
        if (type.contains("confirmed")) return "§4[作弊确认]";
        if (type.isEmpty()) return "§e[有标记]";
        return "§c[疑似作弊]";
    }

    /** 把带 § 码的字符串拆成若干"带 color 字段"的小组件 */
    private static void addColored(List<JsonElement> out, String raw) {
        if (raw == null || raw.isEmpty()) return;
        String color = null;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§' && i + 1 < raw.length()) {
                if (buf.length() > 0) {
                    out.add(coloredText(buf.toString(), color));
                    buf.setLength(0);
                }
                color = colorName("§" + raw.charAt(i + 1));
                i++;
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) out.add(coloredText(buf.toString(), color));
    }

    private static JsonObject coloredText(String text, String color) {
        JsonObject o = new JsonObject();
        o.addProperty("text", text == null ? "" : text);
        if (color != null) o.addProperty("color", color);
        return o;
    }

    /**
     * 名字本体。原则：服务器给什么就用什么，一个字都不改，只在后面接统计。
     * 只有服务器完全没给名字（displayName 为空）时才自己补：
     * 队伍前缀 → 段位颜色 → 裸名字。
     */
    private List<JsonElement> namePieces(Add a, Rank rank) {
        List<JsonElement> out = new ArrayList<>();

        // ① 服务器给了名字，原样用。颜色、前缀、点击事件全在里面，动它就容易掉色
        JsonElement upstream = parseComponent(a.display);
        if (upstream != null) {
            // 上游只给了一个裸名字（不管有没有写 color），就按队伍/段位重新上色并补队伍前后缀。
            // 之前只认“完全没有 color 的裸名字”，Hypixel 下发 {"text":"Steve","color":"white"}
            // 时会直接原样保留，结果整行一直是白的。
            JsonObject plain = plainName(upstream, a.name);
            if (plain != null) {
                addTeamWrapped(out, a, plain, rank);
                return out;
            }
            out.add(upstream);
            return out;
        }

        // ② 没给名字，但服务器用队伍上色（对局里的红蓝队就是这么来的）
        String prefix = teams.prefixOf(a.name);
        String suffix = teams.suffixOf(a.name);
        if (!prefix.isEmpty() || !suffix.isEmpty()) {
            JsonElement p = parseComponent(prefix);
            if (p != null) out.add(p);
            String teamColor = teams.colorOf(a.name);
            String color = !teamColor.isEmpty() ? teamColor : (rank == null ? "" : rank.color);
            out.add(coloredText(a.name, color.isEmpty() ? "white" : colorName(color)));
            JsonElement s = parseComponent(suffix);
            if (s != null) out.add(s);
            return out;
        }

        // ③ 什么都没有：最多按段位给个颜色，不加 [VIP] 这种标签
        if (rank != null) {
            if (cfg.tab.showRankPrefix && !rank.prefix.isEmpty()) addColored(out, rank.prefix);
            out.add(coloredText(a.name, colorName(rank.color)));
            return out;
        }

        out.add(coloredText(a.name, "white"));
        return out;
    }

    /**
     * 判断上游 displayName 是不是“只有玩家名”的裸组件。
     * 带队伍/段位前缀、带 siblings 的复杂组件不算，避免把本该保留的格式拆坏。
     */
    private static JsonObject plainName(JsonElement el, String name) {
        String expected = ChatText.stripFormatting(name == null ? "" : name).trim();
        if (expected.isEmpty()) return null;

        if (el.isJsonPrimitive()) {
            String raw = el.getAsString();
            return ChatText.stripFormatting(raw).trim().equals(expected)
                    ? text(ChatText.stripFormatting(raw)) : null;
        }
        if (!el.isJsonObject()) return null;

        JsonObject o = el.getAsJsonObject();
        if (o.has("extra")) {
            if (!o.get("extra").isJsonArray() || o.getAsJsonArray("extra").size() > 0) return null;
        }
        if (!o.has("text") || !o.get("text").isJsonPrimitive()) return null;

        String raw = o.get("text").getAsString();
        if (!ChatText.stripFormatting(raw).trim().equals(expected)) return null;

        // 裸名字里如果夹着 § 颜色码，保留下来会和组件 color 打架；这里去掉颜色码，
        // 让下面统一按队伍/段位色渲染。
        if (raw.indexOf('§') >= 0) o.addProperty("text", ChatText.stripFormatting(raw));
        return o;
    }

    /**
     * 裸名字 + 队伍前缀/后缀重新拼一行。队伍色优先，没有队伍才用段位色。
     */
    private void addTeamWrapped(List<JsonElement> out, Add a, JsonObject nameEl, Rank rank) {
        String teamColor = teams.colorOf(a.name);
        String color = !teamColor.isEmpty() ? teamColor : (rank == null ? "" : rank.color);
        if (!color.isEmpty()) {
            nameEl.addProperty("color", colorName(color));
        }

        String prefix = teams.prefixOf(a.name);
        String suffix = teams.suffixOf(a.name);
        if (!prefix.isEmpty() || !suffix.isEmpty()) {
            JsonElement p = parseComponent(prefix);
            if (p != null) out.add(p);
            out.add(nameEl);
            JsonElement s = parseComponent(suffix);
            if (s != null) out.add(s);
        } else {
            out.add(nameEl);
        }
    }

    /** 开着 debugTab 时把每次改写记下来，方便对着看是哪一步丢了颜色。 */
    private void debugLog(String line) {
        TabDebug.log(line);
    }

    /** 解析一段聊天组件 JSON；空 / 无效 / 没有内容的一律当"没有"处理。 */
    private static JsonElement parseComponent(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "{}".equals(s) || "\"\"".equals(s) || "null".equals(s)) return null;
        try {
            JsonElement el = JsonParser.parseString(s);
            if (el == null || el.isJsonNull()) return null;
            if (el.isJsonArray()) return el.getAsJsonArray().size() == 0 ? null : el;
            if (el.isJsonPrimitive()) {
                return el.getAsString().isEmpty() ? null : el;
            }
            JsonObject o = el.getAsJsonObject();
            if (!o.has("text") && !o.has("extra") && !o.has("translate")) return null;
            if (o.has("text") && !o.has("extra") && o.get("text").isJsonPrimitive()
                    && o.get("text").getAsString().isEmpty()) {
                return null;
            }
            return o;
        } catch (Exception ignored) {
            // 不是合法 JSON，按纯文本塞回去，至少名字还在
            return text(s);
        }
    }

    private static JsonObject text(String s) {
        JsonObject o = new JsonObject();
        o.addProperty("text", s == null ? "" : s);
        return o;
    }

    /**
     * 取一个请求额度。拿不到就最多等一会儿，还不来就放弃、放回重试队列，
     * 免得一直占着线程空耗。
     */
    private boolean takeBudget(long maxWaitMs) {
        int perMinute = Math.max(cfg.tab.maxRequestsPerMinute, 1);
        double ratePerMs = perMinute / 60_000.0;
        // 允许一点突发（8 个并发同时起），但不至于一口气把额度全用掉
        double capacity = Math.max(4.0, Math.min(perMinute / 6.0, 20.0));
        long deadline = System.currentTimeMillis() + maxWaitMs;

        synchronized (gate) {
            while (true) {
                long now = System.currentTimeMillis();
                if (now > lastRefill) {
                    tokens = Math.min(capacity, tokens + (now - lastRefill) * ratePerMs);
                    lastRefill = now;
                }
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return true;
                }
                long need = (long) Math.ceil((1.0 - tokens) / ratePerMs);
                long wait = Math.min(need, deadline - now);
                if (wait <= 0) return false;
                try {
                    gate.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    // ---------------------------------------------------------------- 网络

    /** 数据源模式：写错或未配置一律按 auto */
    private String dataSource() {
        String s = cfg.tab.dataSource == null ? "" : cfg.tab.dataSource.trim().toLowerCase(java.util.Locale.ROOT);
        if ("official".equals(s) || "thirdparty".equals(s) || "third_party".equals(s)) {
            return "official".equals(s) ? s : "thirdparty";
        }
        return "auto";
    }

    private boolean hasOfficialKey() {
        return cfg.tab.hypixelApiKey != null && !cfg.tab.hypixelApiKey.trim().isEmpty();
    }

    /**
     * auto：官方有值就用官方；官方 429 / 没 key / 查不到就退第三方。
     * 两个源都没值 → 返回 value=null，由调用方按 nick / 隐藏统计处理。
     */
    private Queried fetchStat(String undashedUuid, String name) throws Exception {
        String mode = dataSource();
        if ("thirdparty".equals(mode)) return fetchThirdParty(name);
        if (!"official".equals(mode)) {
            Queried official = hasOfficialKey() ? fetchOfficial(undashedUuid, name) : null;
            if (official != null && official.value != null) return official;
            Queried third = fetchThirdParty(name);
            if (third != null) return third;
            // 官方查到了玩家但没这项数据，第三方这一趟又失败了：
            // 返回 official 但标记 incomplete，让调用方走重试，不要缓存成"查不到"。
            if (official != null) {
                return new Queried(official.value, official.rank, official.flag,
                        Source.OFFICIAL, false, false);
            }
            return null;
        }
        return fetchOfficial(undashedUuid, name);
    }

    /** Hypixel 官方 /v2/player，按 UUID 查。null = 限流/网络失败，交给重试队列。 */
    private Queried fetchOfficial(String undashedUuid, String name) throws Exception {
        if (!hasOfficialKey() || System.currentTimeMillis() < cooldownUntil) return null;
        String url = cfg.tab.apiBase.replaceAll("/+$", "") + "/player?uuid=" + undashedUuid;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("API-Key", cfg.tab.hypixelApiKey.trim())
                // 一个人的完整数据动辄几百 KB，不压缩的话下载这一步就够慢的
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();

        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() == 429) {
            // Hypixel 会直接告诉我们还要等多久（Retry-After，秒），按它来最准。
            // 拿不到才退回自己估：每日额度用完就等到 UTC 零点，普通限速等一分钟。
            long waitSec = resp.headers().firstValueAsLong("Retry-After").orElse(-1L);
            String why = decodeBody(resp).toLowerCase(java.util.Locale.ROOT);
            if (waitSec > 0) {
                cooldownUntil = System.currentTimeMillis() + waitSec * 1000L;
                log.warn("[tab] Hypixel 限流：这个 key 的额度用完了，还要等 {} 才恢复（{} 前不再请求）",
                        humanWait(waitSec), java.time.LocalDateTime.now().plusSeconds(waitSec)
                                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")));
            } else if (why.contains("daily")) {
                cooldownUntil = nextUtcMidnight();
                log.warn("[tab] 这个 key 今天的额度用完了，明天 8:00（北京时间）自动恢复。"
                        + "想现在用就进游戏输 /api new 换一个新 key。");
            } else {
                cooldownUntil = System.currentTimeMillis() + 60_000L;
                log.warn("[tab] 被 Hypixel 限速（429），暂停 60 秒");
            }
            return null;
        }
        if (resp.statusCode() != 200) {
            log.debug("[tab] {} 返回 {}", name, resp.statusCode());
            if (resp.statusCode() == 403) noteFailure("HTTP 403 —— API key 无效或没创建 App");
            else if (resp.statusCode() == 404) noteFailure("HTTP 404 —— apiBase 写错了");
            else noteFailure("HTTP " + resp.statusCode());
            return null;
        }

        resp.headers().firstValueAsLong("ratelimit-remaining").ifPresent(this::noteRemaining);

        JsonElement el = JsonParser.parseString(decodeBody(resp));
        if (!el.isJsonObject()) return null;
        JsonObject root = el.getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean()) return null;
        if (!root.has("player") || root.get("player").isJsonNull()) return null;

        JsonObject player = root.getAsJsonObject("player");
        return new Queried(pick(player, cfg.tab.stat), rankOf(player));
    }

    /**
     * 第三方统计站：POST {base}/stats，body {"username":名字,"fetch_type":"hypixel"}。
     * 只用名字查，所以按 UUID 查不到的 nick 在这里也一样查不到（status=NICK）。
     * 网络/HTTP 失败返回 null 走重试；明确查不到返回 value=null 显示 "?"。
     */
    private Queried fetchThirdParty(String name) throws Exception {
        String base = cfg.tab.thirdPartyBaseUrl == null || cfg.tab.thirdPartyBaseUrl.isBlank()
                ? Config.Tab.DEFAULT_THIRD_PARTY_BASE_URL : cfg.tab.thirdPartyBaseUrl.trim();
        String url = base.replaceAll("/+$", "") + "/stats";
        JsonObject body = new JsonObject();
        body.addProperty("username", ChatText.stripFormatting(name == null ? "" : name).trim());
        body.addProperty("fetch_type", "hypixel");

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            log.debug("[tab] 第三方统计 {} 返回 {}", name, resp.statusCode());
            return null;
        }

        String text = new String(resp.body() == null ? new byte[0] : resp.body(), StandardCharsets.UTF_8);
        JsonElement el = JsonParser.parseString(text);
        if (!el.isJsonObject()) return null;
        JsonObject root = el.getAsJsonObject();
        JsonObject hy = root.has("hypixel") && root.get("hypixel").isJsonObject()
                ? root.getAsJsonObject("hypixel") : null;
        if (hy == null) return null;
        String status = hy.has("status") && hy.get("status").isJsonPrimitive()
                ? hy.get("status").getAsString() : "";
        // status=NICK：IGN 不存在/昵称；error：服务侧失败，交给重试队列
        if ("NICK".equalsIgnoreCase(status)) {
            return new Queried(null, null, null, Source.THIRD_PARTY, true, true);
        }
        if (!"ok".equalsIgnoreCase(status)) return null;

        CheatFlag flag = cheatFlagOf(hy);
        String field = thirdPartyField(cfg.tab.stat);
        if (field == null) return new Queried(null, null, flag, Source.THIRD_PARTY, true, true);
        JsonElement v = hy.get(field);
        if (v == null || !v.isJsonPrimitive() || v.getAsString().isBlank()) {
            return new Queried(null, null, flag, Source.THIRD_PARTY, true, true);
        }
        return new Queried(v.getAsString(), null, flag, Source.THIRD_PARTY, true, true);
    }

    /** 只有 Blacklisted 才当标记；Not_Blacklisted / 缺字段一律 null。 */
    static CheatFlag cheatFlagOf(JsonObject hypixel) {
        if (hypixel == null) return null;
        String black = fieldText(hypixel, "urchin_blacklist_status");
        if (!"Blacklisted".equalsIgnoreCase(black)) return null;
        return new CheatFlag(black, fieldText(hypixel, "urchin_type"), fieldText(hypixel, "urchin_reason"));
    }

    private static String fieldText(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** 第三方只保证 FKDR；其它 stat 没有对应字段，就当查不到 */
    private static String thirdPartyField(String stat) {
        return "bedwars_fkdr".equalsIgnoreCase(stat == null ? "" : stat) ? "core_fkdr" : null;
    }

    /** 服务器可能回 gzip（我们主动要了），也可能原样明文，两种都认。 */
    private static String decodeBody(HttpResponse<byte[]> resp) throws Exception {
        byte[] raw = resp.body();
        if (raw == null) return "";
        boolean gzip = resp.headers().firstValue("Content-Encoding")
                .map(v -> v.toLowerCase(java.util.Locale.ROOT).contains("gzip"))
                .orElse(false)
                || (raw.length > 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b);
        if (!gzip) return new String(raw, StandardCharsets.UTF_8);
        try (java.util.zip.GZIPInputStream in =
                     new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 头里写了 gzip 却解不开，退回按明文读，别让整条查询白白失败
            return new String(raw, StandardCharsets.UTF_8);
        }
    }

    /** 把秒数说成人话：70100 → "19 小时 28 分" */
    private static String humanWait(long sec) {
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        if (h > 0) return h + " 小时 " + m + " 分";
        if (m > 0) return m + " 分钟";
        return sec + " 秒";
    }

    /** 额度快见底时提醒一次，让用户知道该省着用或者换 key */
    private volatile long lastQuotaWarnAt = 0L;

    private void noteRemaining(long left) {
        remaining.set((int) left);
        if (left > 20) return;
        long now = System.currentTimeMillis();
        if (now - lastQuotaWarnAt < 300_000L) return;
        lastQuotaWarnAt = now;
        log.warn("[tab] 这个 key 今天只剩 {} 次查询额度了（每天总共 300 次），省着点用，用完了要等第二天",
                left);
    }

    /** 今天还剩多少次查询额度，-1 表示还没查过 */
    private final AtomicInteger remaining = new AtomicInteger(-1);

    /** 下一个 UTC 零点（= 北京时间早上 8 点），Hypixel 的每日配额在这个点重置 */
    private static long nextUtcMidnight() {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .plusDays(1)
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli() + 60_000L;
    }

    /** 失败原因只在 30 秒内提示一次，避免刷屏 */
    private volatile long lastWarnAt = 0L;

    private void noteFailure(String why) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < 30_000L) return;
        lastWarnAt = now;
        log.warn("[tab] 查询失败: {}（检查 hypixelApiKey 是否有效、apiBase 是否正确）", why);
    }

    /**
     * 从 API 数据里认出段位，给 Tab 名字上色。
     *
     * 之所以要靠 API：有些服（Hypixel 就是）的 Tab 名字颜色既不在 displayName 里，
     * 也不一定能在队伍包里拿到，光靠上游数据补不出来，那就自己按段位染。
     */
    private static Rank rankOf(JsonObject player) {
        String rank = str(player, "rank");
        String monthly = str(player, "monthlyPackageRank");
        String pkg = str(player, "newPackageRank");
        if (pkg.isEmpty()) pkg = str(player, "packageRank");

        switch (rank) {
            case "ADMIN": return new Rank("§c[ADMIN] ", "§c");
            case "MOD": return new Rank("§2[MOD] ", "§2");
            case "HELPER": return new Rank("§9[HELPER] ", "§9");
            case "YOUTUBER": return new Rank("§c[YT] ", "§c");
            case "BUILD_TEAM": return new Rank("§3[BUILD] ", "§3");
            default: break;
        }
        if ("SUPERSTAR".equals(monthly)) return new Rank("§6[MVP§c++§6] ", "§6");
        switch (rank) {
            case "MVP_PLUS_PLUS": return new Rank("§6[MVP§c++§6] ", "§6");
            case "MVP_PLUS": return new Rank("§b[MVP§c+§b] ", "§b");
            case "MVP": return new Rank("§b[MVP] ", "§b");
            case "VIP_PLUS": return new Rank("§a[VIP§6+§a] ", "§a");
            case "VIP": return new Rank("§a[VIP] ", "§a");
            default: break;
        }
        switch (pkg) {
            case "MVP_PLUS": return new Rank("§b[MVP§c+§b] ", "§b");
            case "MVP": return new Rank("§b[MVP] ", "§b");
            case "VIP_PLUS": return new Rank("§a[VIP§6+§a] ", "§a");
            case "VIP": return new Rank("§a[VIP] ", "§a");
            default: return new Rank("", "§7");   // 普通玩家，Hypixel 里就是灰色
        }
    }

    private static String str(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String pick(JsonObject player, String stat) {
        JsonObject stats = player.has("stats") && player.get("stats").isJsonObject()
                ? player.getAsJsonObject("stats") : null;
        if (stats == null) return null;

        switch (stat == null ? "" : stat.toLowerCase()) {
            case "bedwars_fkdr": {
                JsonObject bw = obj(stats, "Bedwars");
                if (bw == null) return null;
                double fk = dbl(bw, "final_kills_bedwars");
                double fd = dbl(bw, "final_deaths_bedwars");
                return fmt(fk / Math.max(fd, 1.0));
            }
            case "bedwars_wins": {
                JsonObject bw = obj(stats, "Bedwars");
                return bw == null ? null : integer(bw, "wins_bedwars");
            }
            case "bedwars_final_kills": {
                JsonObject bw = obj(stats, "Bedwars");
                return bw == null ? null : integer(bw, "final_kills_bedwars");
            }
            case "bedwars_ws":
            case "bedwars_winstreak": {
                JsonObject bw = obj(stats, "Bedwars");
                return bw == null ? null : integer(bw, "winstreak");
            }
            case "skywars_kdr": {
                JsonObject sw = obj(stats, "SkyWars");
                if (sw == null) return null;
                double k = dbl(sw, "kills");
                double d = dbl(sw, "deaths");
                return fmt(k / Math.max(d, 1.0));
            }
            default:
                return null;
        }
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static double dbl(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static String integer(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive()
                    ? String.valueOf((long) o.get(k).getAsDouble()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static String uuidKey(byte[] uuid) {
        if (uuid == null || uuid.length != 16) return null;
        return Base64.getEncoder().withoutPadding().encodeToString(uuid);
    }

    /** 16 字节 UUID → 32 位十六进制（Hypixel API 要求的格式）。 */
    private static String hexUuid(byte[] uuid) {
        if (uuid == null || uuid.length != 16) return null;
        StringBuilder sb = new StringBuilder(32);
        for (byte b : uuid) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    public String stats() {
        int left = remaining.get();
        return "tab_done=" + done.get() + " cache=" + cache.size()
                + (left >= 0 ? " 今日剩余额度=" + left : "");
    }

    public void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        // 先停调度器：它每 2 秒还会调 drainRetry，只停查询池会留下一个空转线程
        ticker.shutdownNow();
        exec.shutdownNow();
        retry.clear();
        retryTries.clear();
        inflight.clear();
        warned.clear();
        roundNames.clear();
        cache.clear();
    }
}
