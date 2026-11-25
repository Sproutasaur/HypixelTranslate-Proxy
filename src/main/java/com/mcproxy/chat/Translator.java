package com.mcproxy.chat;

import com.mcproxy.Config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 翻译队列。
 *
 * 设计取舍：并发执行（4 线程）+ LRU 缓存。
 * 顺序由 ChatOutbox 的 seq 保证，跟翻译完成的先后无关；
 * 缓存负责吃掉刷屏时重复内容（"gg"、"ez"、"selling X" 这类）。
 * 队列满了就丢，宁可少一行译文，也不让聊天卡住。
 */
public final class Translator {

    private final Config cfg;
    private final LlmClient llm;
    private final ExecutorService exec;
    private final Map<String, String> cache;

    /**
     * 自己刚发出去的译文。服务器通常会把这句话再广播回来一条，
     * 那一条不该再翻一遍——不然你说"你好"，聊天框会多出一行"你好"的译文。
     * key = 去掉颜色码和说话人之后的正文，value = 发出时间。
     */
    private final Map<String, Echo> echo = new java.util.concurrent.ConcurrentHashMap<>();

    /** 自己发出去的一句：换成了什么外语、原文是什么、什么时候发的 */
    private record Echo(String origin, long at) { }

    private void rememberEcho(String origin, String sent) {
        String k = normBody(sent);
        if (k.isEmpty()) return;
        if (echo.size() > 64) echo.clear();
        echo.put(k, new Echo(origin, System.currentTimeMillis()));
    }

    /**
     * 这条聊天是不是自己刚发出去那句的回声；是就返回当初打的中文原文。
     *
     * 用在"自己说的话也想知道原文"的场合：服务器把英文回声回来时，
     * 聊天框上面是英文（队友看到的），下面补一行你当时打的中文。
     */
    public String echoOrigin(String plain) {
        if (echo.isEmpty() || plain == null) return null;
        Echo e = echo.get(normBody(plain));
        if (e == null) return null;
        if (System.currentTimeMillis() - e.at > 60_000L) {
            echo.remove(normBody(plain));
            return null;
        }
        return e.origin;
    }

    /** 这条内容是不是自己最近发出去的那句（服务器回声） */
    private boolean isEcho(String plain) {
        return echoOrigin(plain) != null;
    }

    /** 去掉颜色码和说话人，只留正文，用于比对"这句是不是我刚发的" */
    private static String normBody(String s) {
        if (s == null) return "";
        String t = ChatText.stripFormatting(s);
        java.util.regex.Matcher any = SPEAKER_ANY.matcher(t);
        int cut = -1;
        while (any.find()) {
            if (!NOT_A_NAME.contains(any.group(1).toLowerCase(java.util.Locale.ROOT))) cut = any.end();
        }
        String body = cut >= 0 ? t.substring(cut) : stripNamePrefix(t);
        // 自己发出去的是 "/pc 译文"，回声里带着命令前缀，比对时先去掉
        body = body.replaceFirst("^/[A-Za-z]{1,12}\\s+", "");
        return body.replace('\u00a0', ' ').trim().toLowerCase(java.util.Locale.ROOT);
    }
    /** 最近出现过的原文 → 时间戳，用来压掉服务器反复推送的同一句提示 */
    private final Map<String, Long> recent;
    private final Set<String> skipWords;
    /** skipWords 去掉连续重复字母后的版本，用来认 "gggg" "ezzz" 这类刷屏变体 */
    private final Set<String> skipWordsSqueezed;

    /** 代理自己的账号名（小写），用来识别"自己说的话" */
    private volatile String ownName;
    private volatile String ownNameRaw;

    /** [段位] / [MVP+] 这类前缀，可能叠好几个 */
    private static final Pattern TAG_PREFIX = Pattern.compile("^\\[[^\\]]{0,20}\\]\\s*");
    /** Steve: / <Steve> 这类名字前缀 */
    private static final Pattern NAME_PREFIX = Pattern.compile("^<?([A-Za-z0-9_]{1,16})>?\\s*[:>]\\s*");
    /** 整行任意位置找 "名字: " 这样的说话人（Hypixel 会有 "Party > [MVP+] Steve: hi"） */
    private static final Pattern SPEAKER_ANY =
            Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z0-9_]{2,16})\\s*[:>]\\s*(?=\\S)");

    /** 这些"说话人"其实是系统提示的前缀，不是玩家 */
    private static final java.util.Set<String> NOT_A_NAME = java.util.Set.of(
            "reward", "tip", "note", "warning", "warn", "error", "info", "notice",
            "quest", "challenge", "objective", "objectives", "progress", "time", "mode",
            "map", "server", "level", "exp", "coins", "coin", "stats", "score", "event",
            "daily", "weekly", "mystery", "loot", "shop", "store", "team", "lobby",
            "game", "match", "round", "bed", "kill", "kills", "death", "deaths", "final",
            "cache", "status", "update", "alert", "message", "party", "guild", "friend",
            "friends", "new", "next", "current", "total", "rank", "season", "prestige",
            "achievement", "bonus", "streak", "winstreak", "karma", "token", "tokens");

    /** 切词：非字母数字下划线都当分隔符 */
    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}_]+");

    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();

    public Translator(Config cfg) {
        this.cfg = cfg;
        // 并发翻译：之前是单线程串行，刷屏时后面的消息要排队等前面一条翻完，
        // 延迟会越积越长（大厅每秒几条时能拖到好几秒）。
        // 顺序不用靠单线程保证——ChatOutbox 按 seq 出队，译文乱序回来也会按原文顺序发。
        this.exec = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "translate");
            t.setDaemon(true);
            return t;
        });
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.llm = new LlmClient(
                cfg.translate.baseUrl,
                cfg.translate.apiKey,
                cfg.translate.model,
                cfg.translate.systemPrompt,
                cfg.translate.timeoutMs,
                http);
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > Math.max(cfg.translate.cacheSize, 16);
            }
        });

        this.recent = Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > 512;
            }
        });

        Set<String> words = new HashSet<>();
        if (cfg.translate.skipWords != null) {
            for (String w : cfg.translate.skipWords) {
                if (w != null && !w.isBlank()) words.add(w.trim().toLowerCase());
            }
        }
        this.skipWords = Collections.unmodifiableSet(words);

        Set<String> squeezed = new HashSet<>();
        for (String w : words) squeezed.add(squeeze(w));
        this.skipWordsSqueezed = Collections.unmodifiableSet(squeezed);
    }

    /** "gggg" → "g"、"loll" → "lol"；用来把刷屏变体收敛回原形 */
    private static String squeeze(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) != c) sb.append(c);
        }
        return sb.toString();
    }

    /** 这个词算不算黑话（含 gggg / ggs 这类变形） */
    private boolean isSkipWord(String token) {
        String t = token.toLowerCase(java.util.Locale.ROOT);
        if (skipWords.contains(t)) return true;
        if (skipWordsSqueezed.contains(squeeze(t))) return true;
        // ggs / wpggs 这种带复数尾的
        if (t.length() > 2 && t.endsWith("s") && skipWords.contains(t.substring(0, t.length() - 1))) return true;
        return false;
    }

    /**
     * 短句直接放过。
     * 聊天里大半是 "nice" "gj" "lol rip" 这类，翻出来只是多一行，没人看。
     * 阈值由 config 的 skipShortMessages 控制，0 = 关闭。
     */
    private boolean tooShortToTranslate(String plain) {
        Integer limit = cfg.translate.skipShortMessages;
        if (limit == null || limit <= 0) return false;
        String body = stripNamePrefix(plain);
        int letters = 0;
        for (int i = 0; i < body.length(); i++) {
            if (Character.isLetter(body.charAt(i)) && ++letters > limit) return false;
        }
        return true;
    }

    /** 提交一条待翻译文本；结果通过回调返回（在翻译线程里执行，写 channel 是线程安全的）。 */
    public void submit(String plain, Consumer<String> callback) {
        if (!cfg.translate.enabled || callback == null) return;
        if (plain == null) return;

        String key = plain.replace('\u00a0', ' ').trim();
        if (key.isEmpty() || key.length() > 800) return;
        if (isEcho(key)) return;
        if (nothingToTranslate(key)) return;
        if (tooShortToTranslate(key)) return;
        if (cfg.translate.skipOwnMessages && isOwnMessage(key)) return;

        if (cfg.translate.skipIfAlreadyTarget && isTargetLanguage(key)) return;

        String cached = cache.get(key);
        if (cached != null) {
            callback.accept(cached);
            return;
        }

        if (inflight.incrementAndGet() > Math.max(cfg.translate.maxQueue, 1)) {
            inflight.decrementAndGet();
            dropped.incrementAndGet();
            return;
        }

        exec.execute(() -> {
            try {
                String translated = llm.translate(key, cfg.translate.targetLanguage);
                if (translated == null) return;
                if (translated.equals(key)) return;
                cache.put(key, translated);
                String brief = key.length() > 90 ? key.substring(0, 90) + "…" : key;
                System.out.println("[译] " + brief + "  =>  " + translated);
                callback.accept(translated);
                done.incrementAndGet();
            } finally {
                inflight.decrementAndGet();
            }
        });
    }

    /**
     * 把自己发出去的消息翻成外语（默认英文），让队友看得懂。
     *
     * 方向和收消息相反，所以不能走 submit 那套过滤：
     * 那些规则会认为"中文已经是目标语言"直接跳过。这里只做长度和队列保护。
     */
    public void submitOutgoing(String plain, Consumer<String> callback) {
        if (!cfg.translate.enabled || callback == null || plain == null) return;

        String key = plain.replace('\u00a0', ' ').trim();
        if (key.isEmpty() || key.length() > 100) return;

        String lang = cfg.translate.outgoingTargetLanguage;
        if (lang == null || lang.isBlank()) lang = "en";
        // 缓存键带方向前缀，免得和收消息方向的译文互相顶掉
        String cacheKey = "out:" + lang + "\u0000" + key;

        String cached = cache.get(cacheKey);
        if (cached != null) {
            callback.accept(cached);
            return;
        }

        if (inflight.incrementAndGet() > Math.max(cfg.translate.maxQueue, 1)) {
            inflight.decrementAndGet();
            dropped.incrementAndGet();
            return;
        }

        final String target = lang;
        exec.execute(() -> {
            try {
                String translated = llm.translate(key, target);
                if (translated == null || translated.equals(key)) return;
                cache.put(cacheKey, translated);
                rememberEcho(key, translated);
                System.out.println("[译出] " + key + "  =>  " + translated);
                callback.accept(translated);
                done.incrementAndGet();
            } finally {
                inflight.decrementAndGet();
            }
        });
    }

    /**
     * 这条是不是玩家发的聊天。
     * 认得出 "名字: 内容" 才算；系统提示认不出，就不翻。
     */
    public boolean isPlayerChat(String plain) {
        if (plain == null) return false;
        String t = ChatText.stripFormatting(plain).trim();
        if (t.isEmpty()) return false;
        java.util.regex.Matcher m = SPEAKER_ANY.matcher(t);
        while (m.find()) {
            if (!NOT_A_NAME.contains(m.group(1).toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    /** 这条内容是否会被直接跳过（已经是目标语言，或者只有黑话）。悬停模式下要先问一下，避免白等超时。 */
    public boolean wouldSkip(String plain) {
        if (plain == null) return true;
        String key = plain.trim();
        if (key.isEmpty()) return true;
        if (hitsSkipPrefix(key)) return true;
        if (isEcho(key)) return true;
        if (nothingToTranslate(key)) return true;
        if (tooShortToTranslate(key)) return true;
        if (cfg.translate.skipOwnMessages && isOwnMessage(key)) return true;
        return cfg.translate.skipIfAlreadyTarget && isTargetLanguage(key);
    }

    /**
     * 同一句话在 dedupSeconds 秒内又出现，就不再翻。
     *
     * Hypixel 会把 "You are AFK"、"You will be teleported..." 这类提示反复推，
     * 每一条都追加一行译文，聊天就被刷满了，而且你早就看懂了。
     */
    public boolean isRepeated(String plain) {
        Integer secs = cfg.translate.dedupSeconds;
        if (secs == null || secs <= 0 || plain == null) return false;
        String key = plain.replace('\u00a0', ' ').trim();
        if (key.isEmpty()) return false;

        long now = System.currentTimeMillis();
        Long last = recent.get(key);
        recent.put(key, now);
        return last != null && now - last < secs * 1000L;
    }

    /**
     * 命中 config 里的 skipPrefixes（默认 "[NPC]"）就跳过。
     * NPC 名字里常夹着 § 颜色码，所以先剥掉再比；大小写也不计较。
     */
    private boolean hitsSkipPrefix(String plain) {
        String[] prefixes = cfg.translate.skipPrefixes;
        if (prefixes == null || prefixes.length == 0) return false;
        String text = ChatText.stripFormatting(plain).trim().toLowerCase(java.util.Locale.ROOT);
        for (String p : prefixes) {
            if (p == null) continue;
            String want = ChatText.stripFormatting(p).trim().toLowerCase(java.util.Locale.ROOT);
            if (!want.isEmpty() && text.startsWith(want)) return true;
        }
        return false;
    }

    /**
     * 只由黑话/语气词、名字、数字、标点组成的消息，不值得翻译。
     * "Steve: gg" → 跳过；"gg but our bed is gone" → 要翻（有表外的词）。
     */
    public boolean nothingToTranslate(String plain) {
        if (plain == null) return true;
        String body = stripNamePrefix(plain.trim());
        if (body.isEmpty()) return true;

        for (String token : WORD_SPLIT.split(body)) {
            if (token.isEmpty()) continue;
            boolean hasLetter = false;
            for (int i = 0; i < token.length(); i++) {
                if (Character.isLetter(token.charAt(i))) {
                    hasLetter = true;
                    break;
                }
            }
            if (!hasLetter) continue;              // 纯数字 / 符号，没内容
            if (isSkipWord(token)) continue;
            return false;                          // 找到一个要翻的词
        }
        return true;
    }

    /** 去掉 "Steve: " / "<Steve> " / "[MVP+] Steve: " 这类前缀，只看真正说的内容。 */
    private static String stripNamePrefix(String text) {
        String t = ChatText.stripFormatting(text == null ? "" : text);
        while (true) {
            java.util.regex.Matcher m = TAG_PREFIX.matcher(t);
            if (!m.find()) break;
            t = t.substring(m.end());
        }
        java.util.regex.Matcher m = NAME_PREFIX.matcher(t);
        if (m.find()) t = t.substring(m.end());
        return t.trim();
    }

    /** 取出说话人名字；认不出返回 null。 */
    /**
     * 取说话人名字。
     *
     * 必须先去掉 § 颜色码：Hypixel 下发的行常是 "§6神秘之人 §a[VIP] YousLucky§f: Hello"，
     * 带着 § 的话行首正则全部落空、返回 null，"自己的话"就认不出来，会被当成别人的
     * 消息再翻一遍——聊天框里就出现自己那句的原文加译文两份。
     */
    private static String speakerOf(String text) {
        String t = ChatText.stripFormatting(text).trim();
        while (true) {
            java.util.regex.Matcher m = TAG_PREFIX.matcher(t);
            if (!m.find()) break;
            t = t.substring(m.end());
        }
        java.util.regex.Matcher m = NAME_PREFIX.matcher(t);
        if (m.find()) return m.group(1);

        // 名字前面还挂着称号（"神秘之人 [VIP] Steve: hi"），行首匹配不上，
        // 退一步在整行里找，取最后一个像人名的
        String last = null;
        java.util.regex.Matcher any = SPEAKER_ANY.matcher(t);
        while (any.find()) {
            String cand = any.group(1);
            if (!NOT_A_NAME.contains(cand.toLowerCase(java.util.Locale.ROOT))) last = cand;
        }
        return last;
    }

    /** 代理账号自己说的话？ */
    public boolean isOwnMessage(String plain) {
        String me = ownName;
        if (me == null || me.isEmpty() || plain == null) return false;
        String who = speakerOf(plain);
        return who != null && who.toLowerCase().equals(me);
    }

    /** 登录成功后设置代理自己的账号名，用于识别"自己说的话"。 */
    public void setOwnName(String name) {
        this.ownName = name == null ? null : name.trim().toLowerCase();
        this.ownNameRaw = name;
    }

    private boolean isTargetLanguage(String text) {
        String target = cfg.translate.targetLanguage == null ? "" : cfg.translate.targetLanguage.toLowerCase();
        if (target.startsWith("zh") || target.startsWith("ja") || target.startsWith("ko")) {
            return ChatText.isMostlyCjk(text);
        }
        // 目标语言是英文时：纯英文聊天本来就不用翻。
        // 不跳过的话，Hypixel 里绝大多数英文句子都会被送进模型翻成同样的英文，
        // 既花 token 又要多等一个 appendWaitMs，英文版配置会显得又慢又贵。
        if (target.startsWith("en")) {
            return ChatText.isEnglishOnly(text);
        }
        return false;
    }

    public String stats() {
        return "done=" + done.get() + " cache=" + cache.size() + " queue=" + inflight.get() + " dropped=" + dropped.get();
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
