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

    /** 各家关思考的字段名不同，按 baseUrl 判断走哪一套 */
    enum Provider { DEEPSEEK, OPENROUTER, GROQ, OPENAI, OTHER }

    private final Provider provider;
    /** 这个服务/模型明确拒绝过"关思考"参数：之后不再带，避免每条消息都先失败一次 */
    private volatile boolean reasoningOffRejected = false;

    public LlmClient(String baseUrl, String apiKey, String model, String systemPrompt,
                     int timeoutMs, HttpClient http) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.endpoint = base.isEmpty() ? "" : base + "/chat/completions";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 200));
        this.http = http;
        this.provider = providerOf(base);
    }

    static Provider providerOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return Provider.OTHER;
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            if (host != null) {
                host = host.toLowerCase(java.util.Locale.ROOT);
                if (host.endsWith("deepseek.com")) return Provider.DEEPSEEK;
                if (host.endsWith("openrouter.ai")) return Provider.OPENROUTER;
                if (host.endsWith("groq.com")) return Provider.GROQ;
                if (host.equals("api.openai.com") || host.endsWith(".openai.com")) return Provider.OPENAI;
                return Provider.OTHER;
            }
        } catch (Exception ignore) {
            // baseUrl 不合法时退回字符串判断，至少不影响别的服务
        }
        String s = baseUrl.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("deepseek")) return Provider.DEEPSEEK;
        if (s.contains("openrouter")) return Provider.OPENROUTER;
        if (s.contains("groq")) return Provider.GROQ;
        if (s.contains("api.openai.com")) return Provider.OPENAI;
        return Provider.OTHER;
    }

    /**
     * 各家"最低思考"不是同一个值：
     *  - DeepSeek         thinking = disabled（二元开关）
     *  - OpenRouter       reasoning.enabled = false（强制推理的模型会拒，自动降级）
     *  - OpenAI gpt-5.1+  reasoning_effort = none
     *  - OpenAI 老推理    reasoning_effort = low（none 不被支持，会 400）
     *  - Groq qwen3       reasoning_effort = none
     *  - Groq gpt-oss     reasoning_effort = low（不支持 none）
     *  - 未知兼容接口     先试 reasoning_effort = none（不支持就自动摘）
     *  - 普通模型（gpt-4o-mini 之类）本来就不思考，不加任何参数
     * 返回 null 表示这家没有安全的关思考写法。
     */
    static String reasoningOffKind(Provider provider, String model) {
        String m = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        switch (provider) {
            case DEEPSEEK:
                return "thinking";
            case OPENROUTER:
                return "reasoning";
            case GROQ:
                if (m.contains("qwen")) return "reasoning_effort:none";
                if (m.contains("gpt-oss")) return "reasoning_effort:low";
                return null;
            case OPENAI:
                if (m.contains("pro")) return null;                       // pro 档只允许高强度，改不了
                if (m.matches("gpt-5\\.[1-9].*") || m.startsWith("gpt-6")) {
                    return "reasoning_effort:none";                       // 5.1 起才支持真正关掉
                }
                if (m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")
                        || m.startsWith("gpt-5")) {
                    return "reasoning_effort:low";                        // 老推理模型最低只能 low
                }
                return null;                                              // 非推理模型，无需参数
            default:
                // 未知的 OpenAI 兼容服务：先发最通用的 reasoning_effort=none。
                // 不支持会返回 400/422，translate() 会自动去掉并重试一次。
                return "reasoning_effort:none";
        }
    }

    private boolean wantsReasoningOff() {
        return !reasoningOffRejected && reasoningOffKind(provider, model) != null;
    }

    private static void applyReasoningOff(JsonObject body, Provider provider, String model) {
        String kind = reasoningOffKind(provider, model);
        if (kind == null) return;
        switch (kind) {
            case "thinking" -> {
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "disabled");
                body.add("thinking", thinking);
            }
            case "reasoning" -> {
                JsonObject reasoning = new JsonObject();
                reasoning.addProperty("enabled", false);
                body.add("reasoning", reasoning);
            }
            case "reasoning_effort:none" -> body.addProperty("reasoning_effort", "none");
            case "reasoning_effort:low" -> body.addProperty("reasoning_effort", "low");
            default -> { }
        }
    }

    /** 服务明确因为 reasoning/thinking 参数拒绝时才降级重试 */
    private static boolean looksLikeReasoningRejection(String body) {
        if (body == null) return false;
        String s = body.toLowerCase(java.util.Locale.ROOT);
        boolean mentions = s.contains("reasoning") || s.contains("thinking");
        boolean rejected = s.contains("unrecognized") || s.contains("unknown")
                || s.contains("unsupported") || s.contains("not support")
                || s.contains("invalid") || s.contains("cannot be disabled")
                || s.contains("mandatory") || s.contains("must be one of")
                || s.contains("only supports") || s.contains("supported values");
        return mentions && rejected;
    }

    private JsonObject buildBody(JsonArray messages, boolean reasoningOff) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.2);
        // 长聊天句翻成中文可能好几百 token，原来 320 会硬截断导致译文不完整，给到 1024
        body.addProperty("max_tokens", 1024);
        if (reasoningOff) {
            applyReasoningOff(body, provider, model);
        }
        body.add("messages", messages);
        return body;
    }

    private HttpResponse<String> send(JsonObject body) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (!apiKey.isEmpty()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }
        return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * @return 译文；失败返回 null（调用方直接放弃这一行，不影响聊天）。
     */
    public String translate(String text, String targetLanguage) {
        if (endpoint.isEmpty()) return null;     // baseUrl 留空 = 不翻译，别直接 NPE
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

            boolean reasoningOff = wantsReasoningOff();
            HttpResponse<String> resp = send(buildBody(messages, reasoningOff));

            // 有的模型强制思考（如 OpenRouter 的 Claude、Groq 的 gpt-oss 只能是 low），
            // 或者老模型不认 none：去掉关思考参数重试一次，保证翻译本身不挂。
            // 未知兼容接口的报错文案不可预测，400/422 一律降级一次。
            if ((resp.statusCode() == 400 || resp.statusCode() == 422)
                    && reasoningOff
                    && (provider == Provider.OTHER || looksLikeReasoningRejection(resp.body()))) {
                reasoningOffRejected = true;
                System.err.println("[translate] 该模型不支持关思考，已降级重试: "
                        + brief(resp.body()));
                resp = send(buildBody(messages, false));
            }

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
