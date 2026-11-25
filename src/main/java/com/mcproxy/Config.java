package com.mcproxy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置文件模型。首次运行时会在工作目录生成 config.json。
 */
public final class Config {

    /** 监听地址。单机自用填 127.0.0.1；要给别人连填 0.0.0.0（注意账号风险）。 */
    public String listenHost = "127.0.0.1";
    public int listenPort = 25565;

    /** 上游地址：可以是加速IP，也可以直接是 mc.hypixel.net */
    public String upstreamHost = "mc.hypixel.net";
    public int upstreamPort = 25565;

    /**
     * 握手包里写的服务器地址。
     * Hypixel 走 BungeeCord 强路由，认的是这个域名，所以即使 upstreamHost 是加速IP，
     * 这里也必须写 mc.hypixel.net。
     */
    public String handshakeHost = "mc.hypixel.net";
    public int handshakePort = 25565;

    /** 1.8.9 的协议版本号 */
    public int protocolVersion = 47;

    /** 认证缓存文件（保存 refresh token，避免每次扫码） */
    public String authFile = "auth.json";

    /** 设备码登录时是否自动用默认浏览器打开微软验证页。无桌面环境会自动跳过。 */
    public Boolean openBrowserOnLogin = true;

    /** 设备码登录时是否自动把代码复制到系统剪贴板。失败会自动降级成手动复制。 */
    public Boolean copyCodeToClipboardOnLogin = true;

    /** 网络空闲超时（秒）。任一侧超过该时间没有数据就断开，防止残留会话。 */
    public int idleTimeoutSeconds = 60;

    /** 调试用：跳过微软登录，直接用一个假身份连离线服务器。正式服不要开。 */
    public boolean debugSkipAuth = false;
    public String debugName = "ProxyBot";

    /** 调试用：跳过 sessionserver join（配合本机的测试服务器）。 */
    public boolean debugSkipSessionJoin = false;

    /** 调试用：把 Tab 相关原始包打到日志（摸清真实服务器的 Tab 结构用） */
    public boolean debugTab = false;

    public Translate translate = new Translate();

    public Tab tab = new Tab();

    /**
     * 作弊提醒（挂在 tab 下面，必须 tab.enabled = true 才生效）。
     * 数据只有第三方接口带（urchin_* 字段），官方 /player 没有这组东西。
     */
    public static final class CheatWarn {
        /** 总开关。关掉就完全不查、不提示 */
        public boolean enabled = true;

        /** any = 有标记就提示；confirmed = 只提示 confirmed_cheater（已确认作弊） */
        public String minLevel = "any";

        /** 单局最多提示几条，防大服刷屏 */
        public int maxPerSession = 20;
    }

    /** 服务器列表里显示的 MOTD */
    public Motd motd = new Motd();

    public static final class Motd {
        /** 支持 § 颜色码和 \n 换行 */
        public String text = "HypixelTranslate-Proxy · 本地翻译代理";
        /** 列表里显示的在线人数（只是显示，实际仍是一账号一会话） */
        public int online = 1;
        public int max = 1;
    }

    /** Tab 玩家名后面挂统计。数据来自 Hypixel 公共 API。 */
    public static final class Tab {
        /** 第三方统计接口默认根地址。改这里即可，不要在调用侧再写一份。 */
        public static final String DEFAULT_THIRD_PARTY_BASE_URL = "https://api.khadow.lol";

        public boolean enabled = false;

        /** Hypixel API key（在 developer.hypixel.net 创建 App 获取）。 */
        public String hypixelApiKey = "";
        public String apiBase = "https://api.hypixel.net/v2";

        /**
         * 统计数据源：
         *   official   —— 只用 Hypixel 官方 API（要 key，每天 300 次）
         *   thirdparty —— 只用第三方统计站（按用户名查，不消耗官方额度）
         *   auto       —— 先官方，查不到/429/没 key 时自动切第三方（默认）
         */
        public String dataSource = "auto";
        /** 第三方统计接口根地址，POST {base}/stats，body {"username":...,"fetch_type":"hypixel"} */
        public String thirdPartyBaseUrl = DEFAULT_THIRD_PARTY_BASE_URL;

        /** 要显示的统计：bedwars_fkdr / bedwars_wins / bedwars_final_kills / skywars_kdr */
        public String stat = "bedwars_fkdr";

        /** 显示在数字后面的标签文字 */
        public String label = "FKDR";

        /** 名字和统计之间的分隔 */
        public String separator = " §7| ";

        /** 数字颜色（Minecraft 颜色代码） */
        public String valueColor = "§b";

        /**
         * 同一个人多久之内不重复查（分钟）。
         * Hypixel 每个 key 每天只有 300 次查询额度，缓存越久越划算（FKDR 也不会天天变）。
         */
        public int cacheMinutes = 180;

        /**
         * 每分钟最多发几个请求。
         * 注意真正的瓶颈是"每天 300 次"，不是每分钟：调太大只会几分钟把一天的额度烧光。
         */
        public int maxRequestsPerMinute = 30;

        /** 同时查几个人。Hypixel 每次只给一个人的数据，人多了并着查才快 */
        public int concurrency = 8;

        /** 还没查回来时先显示一个 … 占位，查到了再换成数字 */
        public boolean showPlaceholder = false;

        /** 查不到数据（昵称玩家/隐藏统计）时是否仍显示一个占位 */
        public boolean showUnknown = false;

        /** 作弊提醒，属于 Tab 模块：tab.enabled = false 时整块不生效 */
        public CheatWarn cheatWarn = new CheatWarn();

        /**
         * 名字前面是否补 [VIP] [MVP+] 这类段位标签。
         * 默认 false：Hypixel 对局里 Tab 本来就不显示段位（只有队伍颜色），
         * 补上去反而和原版不一样。
         */
        public boolean showRankPrefix = false;
    }

    public static final class Translate {
        public boolean enabled = true;

        /** 目标语言，例如 zh-CN / en / ja */
        public String targetLanguage = "zh-CN";

        /**
         * 原文最多压多久等译文（毫秒）。超时先把原文显示出来，译文晚到会补一行。
         * 调大会更整齐但打字感更卡；调小更跟手但译文可能掉到后面。
         */
        public int appendWaitMs = 250;

        /**
         * 你发出去的中文，自动翻成外语再送出去，队友能看懂。
         * 已经是外语的消息原样发出，不折腾。命令（/ 开头）和 ! 开头的一律不动。
         */
        public boolean translateOutgoing = true;

        /** 自己发出去的话翻成什么语言 */
        public String outgoingTargetLanguage = "en";

        /** 自己说的话不翻译（包括服务器把你消息回显给你的那条） */
        public boolean skipOwnMessages = true;

        /** 是否翻译系统消息（加入/退出/成就/死亡播报） */
        public boolean translateSystem = true;

        /**
         * 只翻译玩家发的聊天，系统提示一律原样显示。
         * 判定方式：消息里得能认出 "名字: 内容" 这种说话人格式；
         * "You are AFK"、"+10 Bed Wars EXP"、"Reward: ..." 这类都认不出，直接不翻。
         */
        public Boolean onlyPlayerChat = true;

        /** 已经是目标语言的内容直接跳过（中日韩按字符占比，英文按"纯 ASCII 字母"判断） */
        public boolean skipIfAlreadyTarget = true;

        /**
         * 同一句话这么多秒内重复出现，就不再翻（原文照常显示）。
         * 服务器反复推同一条提示时，逐条翻译会把聊天刷满。设 0 关闭。
         */
        public Integer dedupSeconds = 60;

        /**
         * 去掉玩家名之后，字母数不超过这个值的短句一律不翻。
         * 聊天里大半是 "nice" "gj" "lol rip" 这种，翻了只是多刷一行。设 0 关闭。
         */
        public Integer skipShortMessages = 10;

        /**
         * 以这些开头的消息直接跳过，不翻译。
         * 判断前会先去掉 § 颜色码、忽略大小写，所以 "[NPC]" 能命中 "§7[NPC] 银行家: ..."。
         * 默认跳过 NPC 对话，不想要就清空成 []。
         */
        public String[] skipPrefixes = {"[NPC]"};

        /**
         * 黑话 / 短语气词白名单：一条消息如果只由这些词（和名字、标点、数字）组成，就不送翻译。
         * 比如 "Steve: gg"、"Alex: lol"、"gg wp" 都会原样显示，不花 token。
         * 只要出现一个不在表里的词（比如 "gg but our bed gone"），整条照常翻译。
         * 想关掉这个功能就把它清空成 []。按需自己加，比如 "rofl"、"yikes"。
         */
        public String[] skipWords = {
                "gg", "ggwp", "wp", "ez", "ezpz", "l", "ll", "lol", "lmao", "lmfao", "rofl",
                "xd", "omg", "kek", "kekw", "rip", "gl", "hf", "glhf", "gf",
                "hi", "hey", "yo", "sup", "ty", "thx", "tyt", "brb", "afk", "gtg",
                "ok", "okay", "nvm", "o7", "salute", "f", "ff", "w", "pog"
        };

        /** OpenAI 兼容接口，例如 https://api.deepseek.com/v1 */
        public String baseUrl = "https://api.deepseek.com";
        public String apiKey = "";
        public String model = "deepseek-flash";

        /** 单条翻译超时（毫秒），超时直接放弃，不阻塞聊天 */
        public int timeoutMs = 1200;

        /** 队列上限，超过就丢弃（防止刷屏时把内存和额度打爆） */
        public int maxQueue = 40;

        /** 缓存的条目数上限 */
        public int cacheSize = 2000;

        public String systemPrompt =
                "你是 Minecraft 聊天翻译器。把用户给出的聊天内容翻译成目标语言。"
                        + "要求：只输出译文本身，不要解释、不要加引号；"
                        + "玩家名、队伍标签、数字、坐标、命令(/开头)、物品ID、颜色代码不要翻译；"
                        + "Minecraft 黑话按玩家圈习惯处理（gg=打得漂亮，ez=轻松，wp=打得不错，"
                        + "creeper=苦力怕，nether=下界，ender=末影）；"
                        + "保留原来的语气，口语化，简短。";
    }

    // ---------------------------------------------------------------- io

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String TEMPLATE_ZH = "/config.default.json";
    private static final String TEMPLATE_EN = "/config.en.json";

    /**
     * 首次生成配置时按系统语言选模板：
     *  - 仅 Windows 参与：中文（简体/繁体）→ 中文模板，其它语言 → 英文模板
     *  - 非 Windows 保持原行为，一律中文模板
     */
    static String templateFor(String osName, String language) {
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        String lang = language == null ? "" : language.toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win") && !lang.startsWith("zh")) return TEMPLATE_EN;
        return TEMPLATE_ZH;
    }

    public static Config load(Path file) throws IOException {
        if (!Files.exists(file)) {
            // 优先释放 jar 内置的带备注配置模板，让用户拿到的第一份配置自带说明。
            // 读不到资源（比如源码直接跑、jar 被改过）再回退到硬编码默认值。
            String templateName = templateFor(
                    System.getProperty("os.name"),
                    System.getProperty("user.language"));
            boolean english = TEMPLATE_EN.equals(templateName);
            boolean released = false;
            try (InputStream in = Config.class.getResourceAsStream(templateName)) {
                if (in != null) {
                    String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (!template.isBlank()) {
                        Files.writeString(file, template, StandardCharsets.UTF_8);
                        released = true;
                    }
                }
            } catch (Exception ignore) { }
            if (!released) {
                new Config().save(file);
            }
            System.out.println("[config] 已生成" + (released ? "带备注的" : "") + "默认配置: "
                    + file.toAbsolutePath());
            System.out.println("[config] 模板: "
                    + (released ? (english ? "English" : "中文") : "内置默认（资源缺失）")
                    + " · 系统语言: " + System.getProperty("user.language", "")
                    + "-" + System.getProperty("user.country", ""));
            System.out.println("[config] 第一次运行需要填 translate.apiKey，并检查 upstreamHost。");
            // 不要直接返回内存里的默认对象：那是硬编码默认值，可能和刚写出的模板不一致
            // （曾导致首次启动读的还是内存默认值）。统一走下面的文件解析。
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        Config c;
        try {
            c = GSON.fromJson(json, Config.class);
        } catch (Exception e) {
            // 配置写坏了不能让程序起不来：留个备份，用默认值继续跑
            Path backup = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
            try {
                Files.copy(file, backup);
            } catch (IOException ignore) { }
            System.out.println("[config] 解析失败（" + e.getMessage() + "），已备份到 " + backup.getFileName()
                    + "，本次使用默认配置。");
            c = new Config();
        }
        if (c == null) c = new Config();
        if (c.translate == null) c.translate = new Translate();
        if (c.tab == null) c.tab = new Tab();
        // 补全旧配置缺失的字段
        Config defaults = new Config();
        if (c.motd == null) c.motd = defaults.motd;
        if (c.motd.text == null || c.motd.text.isBlank()) c.motd.text = defaults.motd.text;
        if (c.motd.online <= 0) c.motd.online = defaults.motd.online;
        if (c.motd.max < c.motd.online) c.motd.max = c.motd.online;
        if (c.listenHost == null) c.listenHost = defaults.listenHost;
        if (c.upstreamHost == null) c.upstreamHost = defaults.upstreamHost;
        if (c.handshakeHost == null) c.handshakeHost = defaults.handshakeHost;
        if (c.protocolVersion <= 0) c.protocolVersion = defaults.protocolVersion;
        if (c.idleTimeoutSeconds <= 0) c.idleTimeoutSeconds = defaults.idleTimeoutSeconds;
        if (c.openBrowserOnLogin == null) c.openBrowserOnLogin = defaults.openBrowserOnLogin;
        if (c.copyCodeToClipboardOnLogin == null) c.copyCodeToClipboardOnLogin = defaults.copyCodeToClipboardOnLogin;
        if (c.translate.appendWaitMs <= 0) c.translate.appendWaitMs = defaults.translate.appendWaitMs;
        if (c.translate.skipWords == null) c.translate.skipWords = defaults.translate.skipWords;
        if (c.translate.skipPrefixes == null) c.translate.skipPrefixes = defaults.translate.skipPrefixes;
        if (c.translate.dedupSeconds == null) c.translate.dedupSeconds = defaults.translate.dedupSeconds;
        if (c.translate.skipShortMessages == null) c.translate.skipShortMessages = defaults.translate.skipShortMessages;
        if (c.translate.onlyPlayerChat == null) c.translate.onlyPlayerChat = defaults.translate.onlyPlayerChat;
        if (c.translate.outgoingTargetLanguage == null || c.translate.outgoingTargetLanguage.isBlank()) {
            c.translate.outgoingTargetLanguage = defaults.translate.outgoingTargetLanguage;
        }

        // tab 这几项同样要补：旧配置里没有这些字段时，Gson 不会走字段初始值，
        // 拿到的是 0 / null —— maxRequestsPerMinute 变 0 就等于一分钟只准查一次，
        // stat 变 null 就一个数字都不显示。表现就是"开着 Tab 但什么都不出来"。
        if (c.tab.apiBase == null || c.tab.apiBase.isBlank()) c.tab.apiBase = defaults.tab.apiBase;
        if (c.tab.dataSource == null || c.tab.dataSource.isBlank()) c.tab.dataSource = defaults.tab.dataSource;
        if (c.tab.thirdPartyBaseUrl == null || c.tab.thirdPartyBaseUrl.isBlank()) {
            c.tab.thirdPartyBaseUrl = defaults.tab.thirdPartyBaseUrl;
        }
        if (c.tab.stat == null || c.tab.stat.isBlank()) c.tab.stat = defaults.tab.stat;
        if (c.tab.label == null) c.tab.label = defaults.tab.label;
        if (c.tab.separator == null) c.tab.separator = defaults.tab.separator;
        if (c.tab.valueColor == null || c.tab.valueColor.isEmpty()) c.tab.valueColor = defaults.tab.valueColor;
        if (c.tab.hypixelApiKey == null) c.tab.hypixelApiKey = "";
        if (c.tab.cacheMinutes <= 0) c.tab.cacheMinutes = defaults.tab.cacheMinutes;
        if (c.tab.maxRequestsPerMinute <= 0) c.tab.maxRequestsPerMinute = defaults.tab.maxRequestsPerMinute;
        if (c.tab.concurrency <= 0) c.tab.concurrency = defaults.tab.concurrency;

        // 作弊提醒：旧配置没有 cheatWarn 段时补默认值（enabled 默认 true，别被 Gson 的 false 顶掉）
        if (c.tab.cheatWarn == null) c.tab.cheatWarn = defaults.tab.cheatWarn;
        if (c.tab.cheatWarn.minLevel == null || c.tab.cheatWarn.minLevel.isBlank()) {
            c.tab.cheatWarn.minLevel = defaults.tab.cheatWarn.minLevel;
        }
        if (c.tab.cheatWarn.maxPerSession <= 0) {
            c.tab.cheatWarn.maxPerSession = defaults.tab.cheatWarn.maxPerSession;
        }
        return c;
    }

    public void save(Path file) throws IOException {
        Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    }
}
