package com.mcproxy.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcproxy.Config;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微软账号认证链：
 *   device code → MSA token → Xbox Live → XSTS → Minecraft services → profile
 * 以及每次连接的 sessionserver join。
 *
 * 令牌缓存在 auth.json 里，只需要第一次扫码。
 */
public final class AuthService {

    private static final String CLIENT_ID = "00000000402b5328";
    private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    // 旧版 MSA 应用（00000000402b5328）的 Azure v2 设备码端点已经不再注册，
    // 现在仍然可用的是 login.live.com 的 v1 设备码流程。
    private static final String DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf";
    private static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile";
    private static final String SESSION_JOIN_URL =
            "https://sessionserver.mojang.com/session/minecraft/join";

    private final Config cfg;
    private final HttpClient http;
    private final Path cacheFile;
    private final Gson gson = new Gson();
    private final boolean offline;

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long expiresAt;
    private volatile String uuid;   // 无横线
    private volatile String name;

    public AuthService(Config cfg) {
        this.cfg = cfg;
        this.offline = cfg.debugSkipAuth;
        this.cacheFile = Paths.get(cfg.authFile);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String name() {
        return name;
    }

    /** 启动时调用：有缓存就刷新，没有就走设备码登录（阻塞直到完成）。 */
    public synchronized void ensureLogin() throws IOException, InterruptedException {
        if (offline) {
            this.name = cfg.debugName;
            this.uuid = "00000000000000000000000000000001";
            this.accessToken = "offline";
            System.out.println("[auth] 调试模式：跳过微软登录，身份 = " + name);
            return;
        }
        loadCache();
        if (!cacheFile.toFile().exists()) {
            System.out.println("[auth] 未找到 " + cacheFile.toAbsolutePath() + "，开始微软账号登录。");
        }
        if (refreshToken != null && refreshTokenQuietly()) {
            System.out.println("[auth] 已登录: " + name + " (" + uuid + ")");
            return;
        }
        deviceCodeLogin();
        System.out.println("[auth] 登录成功: " + name + " (" + uuid + ")");
    }

    // ------------------------------------------------------------ sessionserver

    /** 客户端拿到 EncryptionRequest 后必须调用这里，否则服务器 hasJoined 校验会失败。 */
    public synchronized void joinSession(String serverHash) throws IOException, InterruptedException {
        if (cfg.debugSkipSessionJoin || offline) {
            System.out.println("[auth] 调试模式：跳过 sessionserver join，hash=" + serverHash);
            return;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            if (accessToken == null) throw new IllegalStateException("尚未完成登录");

            JsonObject body = new JsonObject();
            body.addProperty("accessToken", accessToken);
            body.addProperty("selectedProfile", uuid);
            body.addProperty("serverId", serverHash);

            HttpRequest req = HttpRequest.newBuilder(URI.create(SESSION_JOIN_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 204 || code == 200) return;

            if (code == 401 || code == 403) {
                if (refreshTokenQuietly()) continue;
            }
            throw new IOException("sessionserver join 失败: HTTP " + code + " " + brief(resp.body()));
        }
        throw new IOException("sessionserver join 失败（重试后仍不通过）");
    }

    // ------------------------------------------------------------ 登录流程

    private void deviceCodeLogin() throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("scope", SCOPE);
        form.put("response_type", "device_code");

        JsonObject dc = postForm(DEVICE_CODE_URL, form, false);
        String deviceCode = dc.get("device_code").getAsString();
        String userCode = dc.get("user_code").getAsString();
        String verifyUri = dc.has("verification_uri") ? dc.get("verification_uri").getAsString()
                : "https://www.microsoft.com/link";
        int interval = dc.has("interval") ? dc.get("interval").getAsInt() : 5;

        System.out.println();
        System.out.println("================ 微软账号登录 ================");
        System.out.println(" 打开浏览器访问: " + verifyUri);
        System.out.println(" 输入代码:       " + userCode);
        System.out.println("=============================================");
        System.out.println();

        if (cfg.openBrowserOnLogin != null && cfg.openBrowserOnLogin) {
            openBrowser(verifyUri);
        }
        if (cfg.copyCodeToClipboardOnLogin != null && cfg.copyCodeToClipboardOnLogin) {
            copyToClipboard(userCode);
        }

        long deadline = System.currentTimeMillis() + 15 * 60 * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(Math.max(interval, 3) * 1000L);

            Map<String, String> poll = new LinkedHashMap<>();
            poll.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            poll.put("client_id", CLIENT_ID);
            poll.put("device_code", deviceCode);
            JsonObject tokenResp = postForm(TOKEN_URL, poll, true);

            if (tokenResp.has("access_token")) {
                finishLogin(
                        tokenResp.get("access_token").getAsString(),
                        tokenResp.has("refresh_token") ? tokenResp.get("refresh_token").getAsString() : null);
                return;
            }

            String error = tokenResp.has("error") ? tokenResp.get("error").getAsString() : "";
            if ("authorization_pending".equals(error)) continue;
            if ("slow_down".equals(error)) {
                interval += 5;
                continue;
            }
            throw new IOException("设备码登录失败: " + error + " " + brief(tokenResp.toString()));
        }
        throw new IOException("设备码登录超时（15 分钟）");
    }

    /**
     * 尝试用系统默认浏览器打开验证页。
     * 不用 java.awt.Desktop：在无头 Linux（DISPLAY 指向失效的 X11）上它会抛 AWTError，
     * 那是 Error 不是 Exception，会直接中断登录。改用系统命令，失败也只是打印提示。
     */
    private void openBrowser(String uri) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd;
        if (os.contains("win")) {
            cmd = new String[]{"cmd", "/c", "start", "", uri};
        } else if (os.contains("mac")) {
            cmd = new String[]{"open", uri};
        } else {
            cmd = new String[]{"xdg-open", uri};
        }
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).start();
            System.out.println("[auth] 已尝试自动打开浏览器；若没弹出，请手动访问上面的地址。");
        } catch (Throwable t) {
            System.out.println("[auth] 自动打开浏览器失败（" + t.getMessage()
                    + "），请手动访问上面的地址，不影响继续登录。");
        }
    }

    /**
     * 尝试把设备码复制到系统剪贴板：Windows 用 clip，macOS 用 pbcopy，Linux 用 xclip/xsel。
     * 这些命令都从 stdin 读取文本，写完必须关闭流并等待，否则命令会一直挂起。
     * 失败（没装 xclip、无图形会话等）只打印提示，不抛异常、不中断登录。
     */
    private void copyToClipboard(String text) {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean linux = !(os.contains("win") || os.contains("mac"));
        String[][] candidates;
        if (os.contains("win")) {
            candidates = new String[][]{{"cmd", "/c", "clip"}};
        } else if (os.contains("mac")) {
            candidates = new String[][]{{"pbcopy"}};
        } else {
            candidates = new String[][]{
                    {"xclip", "-selection", "clipboard"},
                    {"xsel", "--clipboard", "--input"}
            };
        }
        for (String[] cmd : candidates) {
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                // 写入 stdin 并关闭，命令才能读到完整内容
                try (java.io.OutputStream out = p.getOutputStream()) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    continue;
                }
                if (p.exitValue() == 0) {
                    System.out.println("[auth] 已把代码 " + text + " 复制到剪贴板，到浏览器里直接粘贴即可。");
                    return;
                }
            } catch (Throwable ignore) {
                // 换下一个候选命令 / 最终降级
            }
        }
        System.out.println("[auth] 未能自动复制代码（" + (linux ? "本机可能没装 xclip/xsel 或无图形会话" : "剪贴板命令失败")
                + "），请手动复制上面的代码 " + text + "，不影响继续登录。");
    }

    private boolean refreshTokenQuietly() {
        if (refreshToken == null) return false;
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("client_id", CLIENT_ID);
            form.put("refresh_token", refreshToken);
            form.put("scope", SCOPE);

            JsonObject resp = postForm(TOKEN_URL, form, true);
            if (!resp.has("access_token")) return false;

            String newRefresh = resp.has("refresh_token")
                    ? resp.get("refresh_token").getAsString() : refreshToken;
            finishLogin(resp.get("access_token").getAsString(), newRefresh);
            return true;
        } catch (Exception e) {
            System.out.println("[auth] 刷新令牌失败: " + e.getMessage());
            return false;
        }
    }

    private void finishLogin(String msaAccessToken, String msaRefreshToken)
            throws IOException, InterruptedException {
        String xblToken = xboxLive(msaAccessToken);
        String[] xsts = xsts(xblToken);
        String mcToken = minecraftLogin(xsts[0], xsts[1]);
        JsonObject profile = minecraftProfile(mcToken);

        this.accessToken = mcToken;
        this.refreshToken = msaRefreshToken;
        this.expiresAt = System.currentTimeMillis() + 6L * 60 * 60 * 1000;
        this.uuid = profile.get("id").getAsString();
        this.name = profile.get("name").getAsString();
        saveCache();
    }

    private String xboxLive(String msaAccessToken) throws IOException, InterruptedException {
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msaAccessToken);

        JsonObject body = new JsonObject();
        body.add("Properties", props);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");

        return postJson(XBL_URL, body, false).get("Token").getAsString();
    }

    /** @return [xstsToken, userHash] */
    private String[] xsts(String xblToken) throws IOException, InterruptedException {
        JsonObject props = new JsonObject();
        props.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(xblToken);
        props.add("UserTokens", userTokens);

        JsonObject body = new JsonObject();
        body.add("Properties", props);
        body.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        body.addProperty("TokenType", "JWT");

        JsonObject resp = postJson(XSTS_URL, body, true);
        if (!resp.has("Token")) {
            String xerr = resp.has("XErr") ? resp.get("XErr").getAsString() : "?";
            throw new IOException("XSTS 校验失败 XErr=" + xerr + "（" + xstsHint(xerr) + "）");
        }

        String uhs = "";
        JsonObject claims = resp.getAsJsonObject("DisplayClaims");
        if (claims != null) {
            JsonArray xui = claims.getAsJsonArray("xui");
            if (xui != null && !xui.isEmpty()) {
                JsonObject first = xui.get(0).getAsJsonObject();
                if (first.has("uhs")) uhs = first.get("uhs").getAsString();
            }
        }
        return new String[]{resp.get("Token").getAsString(), uhs};
    }

    private String minecraftLogin(String xstsToken, String userHash)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);

        JsonObject resp = postJson(MC_LOGIN_URL, body, false);
        if (!resp.has("access_token")) {
            throw new IOException("Minecraft 登录失败: " + brief(resp.toString()));
        }
        return resp.get("access_token").getAsString();
    }

    private JsonObject minecraftProfile(String mcToken) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(MC_PROFILE_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + mcToken)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("获取 MC profile 失败: HTTP " + resp.statusCode()
                    + "（这个账号可能没有购买 Minecraft Java 版）");
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    // ------------------------------------------------------------ HTTP 小工具

    private JsonObject postForm(String url, Map<String, String> form, boolean pollMode)
            throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "HypixelTranslate-Proxy/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (!pollMode && resp.statusCode() >= 400) {
            throw new IOException("HTTP " + resp.statusCode() + " " + brief(resp.body()));
        }
        return safeParse(resp.body());
    }

    private JsonObject postJson(String url, JsonObject body, boolean allowError)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "HypixelTranslate-Proxy/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (!allowError && resp.statusCode() >= 400) {
            throw new IOException("HTTP " + resp.statusCode() + " " + brief(resp.body()));
        }
        return safeParse(resp.body());
    }

    private static JsonObject safeParse(String s) {
        try {
            JsonElement el = JsonParser.parseString(s);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ');
        return t.length() > 180 ? t.substring(0, 180) + "..." : t;
    }

    private static String xstsHint(String xerr) {
        switch (xerr) {
            case "2148916233": return "这个微软账号还没创建 Xbox 档案，先去 xbox.com 登录一次";
            case "2148916235": return "所在地区不支持 Xbox Live";
            case "2148916238": return "账号是未成年人，需要加入家庭组";
            default: return "见 https://wiki.vg/Microsoft_Authentication_Scheme";
        }
    }

    // ------------------------------------------------------------ 缓存

    private void loadCache() {
        try {
            if (!Files.exists(cacheFile)) return;
            String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("refreshToken")) refreshToken = o.get("refreshToken").getAsString();
            if (o.has("uuid")) uuid = o.get("uuid").getAsString();
            if (o.has("name")) name = o.get("name").getAsString();
            if (o.has("expiresAt")) expiresAt = o.get("expiresAt").getAsLong();
            if (o.has("accessToken")) accessToken = o.get("accessToken").getAsString();
        } catch (Exception e) {
            System.out.println("[auth] 读取缓存失败，将重新登录: " + e.getMessage());
        }
    }

    private void saveCache() {
        try {
            JsonObject o = new JsonObject();
            if (refreshToken != null) o.addProperty("refreshToken", refreshToken);
            if (accessToken != null) o.addProperty("accessToken", accessToken);
            o.addProperty("expiresAt", expiresAt);
            if (uuid != null) o.addProperty("uuid", uuid);
            if (name != null) o.addProperty("name", name);
            Files.writeString(cacheFile, gson.toJson(o), StandardCharsets.UTF_8);
            // 令牌是明文，至少把文件权限收紧成只有自己能读
            try {
                Files.setPosixFilePermissions(cacheFile,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignore) {
                // Windows 等不支持 POSIX 权限，跳过
            }
            System.out.println("[auth] 已保存令牌到 " + cacheFile.toAbsolutePath() + "（权限 600）");
        } catch (Exception e) {
            System.out.println("[auth] 保存令牌失败: " + e.getMessage());
        }
    }
}
