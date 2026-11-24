package com.mcproxy.tab;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Tab 诊断输出。只在 config 里打开 debugTab 时写文件，平时零开销。
 * 排查"名字为什么白了"这种问题时，比看控制台刷屏好用：文件里逐行写清了
 * 服务器给了什么、我们用的是哪条分支、最后发出去的是什么。
 */
public final class TabDebug {

    private static volatile boolean enabled;

    private TabDebug() {}

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void log(String line) {
        if (!enabled) return;
        try {
            Files.writeString(Path.of("tab-debug.txt"), line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignore) {
            // 写不了就算了，绝不能影响游戏
        }
    }
}
