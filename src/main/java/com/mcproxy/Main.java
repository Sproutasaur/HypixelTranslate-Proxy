package com.mcproxy;

import com.mcproxy.auth.AuthService;
import com.mcproxy.chat.Translator;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class Main {

    public static void main(String[] args) throws Exception {
        Path configPath = Paths.get(args.length > 0 ? args[0] : "config.json");

        System.out.println("[main] HypixelTranslate-Proxy · MC 1.8.9 · build 1124aa");

        Config cfg = Config.load(configPath);

        if (cfg.debugTab) {
            com.mcproxy.tab.TabDebug.setEnabled(true);
            // Tab 诊断文件每次启动重来，免得越积越大
            try {
                java.nio.file.Files.deleteIfExists(Paths.get("tab-debug.txt"));
            } catch (Exception ignore) { }
            System.out.println("[main] debugTab 已开，诊断会写到 tab-debug.txt");
        }

        if (cfg.translate.enabled && (cfg.translate.apiKey == null || cfg.translate.apiKey.isBlank())) {
            System.out.println("[warn] translate.apiKey 为空，翻译请求会失败。填好 config.json 后重启。");
        }

        // Tab 状态直接打在启动日志里：不显示统计时一眼就知道是没开、没填 key 还是配置被覆盖了
        if (!cfg.tab.enabled) {
            System.out.println("[tab] 未开启（config 里 tab.enabled = false）");
        } else if (cfg.tab.hypixelApiKey == null || cfg.tab.hypixelApiKey.isBlank()) {
            System.out.println("[warn] tab.enabled 已开，但 hypixelApiKey 是空的，不会查任何数据");
        } else {
            System.out.println("[tab] 已开启 · " + cfg.tab.stat + " · 并发 " + cfg.tab.concurrency
                    + " · 上限 " + cfg.tab.maxRequestsPerMinute + "/分钟 · key "
                    + cfg.tab.hypixelApiKey.trim().substring(0,
                        Math.min(6, cfg.tab.hypixelApiKey.trim().length())) + "…");
        }

        AuthService auth = new AuthService(cfg);
        auth.ensureLogin();

        Translator translator = new Translator(cfg);
        translator.setOwnName(auth.name());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[main] 退出，翻译统计: " + translator.stats());
            translator.shutdown();
        }));

        ProxyServer.start(cfg, auth, translator);
    }
}
