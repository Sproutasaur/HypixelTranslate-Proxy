package com.mcproxy.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 任意 OpenAI 兼容的 /chat/completions 接口。 */
public final class LlmClient {

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final String systemPrompt;
    private final Duration timeout;
    private final HttpClient http;

    public LlmClient(String baseUrl, String apiKey, String model, String systemPrompt,
                     int timeoutMs, HttpClient http) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = base + "/chat/completions";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 200));
        this.http = http;
    }

    /**
     * @return 译文；失败返回 null（调用方直接放弃这一行，不影响聊天）。
     */
    public String translate(String text, String targetLanguage) {
        try {
            JsonArray messages = new JsonArray();

            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", systemPrompt);
            messages.add(sys);

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", "目标语言: " + targetLanguage + "\n聊天内容:\n" + text);
            messages.add(user);

            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("temperature", 0.2);
            // 长聊天句翻成中文可能好几百 token，原来 320 会硬截断导致译文不完整，给到 1024
            body.addProperty("max_tokens", 1024);
            // 关闭思考（强度最低）：翻译不需要推理，关掉最快最省钱，也不会让思维链吃掉输出额度
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            body.add("thinking", thinking);
            body.add("messages", messages);

            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            if (!apiKey.isEmpty()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.err.println("[translate] HTTP " + resp.statusCode() + ": " + brief(resp.body()));
                return null;
            }

            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) return null;
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            if (message == null || !message.has("content")) return null;

            String out = message.get("content").getAsString().trim();
            out = stripWrapping(out);
            if (out.isEmpty()) return null;

            // 若仍因 max_tokens 被截断，日志里提示（便于判断是不是超长句）
            if (choice.has("finish_reason") && "length".equals(choice.get("finish_reason").getAsString())) {
                System.out.println("[译] 提示: 译文可能因超长被截断（finish_reason=length）");
            }
            return out;
        } catch (Exception e) {
            System.err.println("[translate] 失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    private static String stripWrapping(String s) {
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '“' && b == '”')) {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ');
        return t.length() > 120 ? t.substring(0, 120) + "..." : t;
    }
}
