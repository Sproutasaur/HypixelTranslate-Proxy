package com.mcproxy.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft 1.8 的聊天是一段 JSON 文本组件。这里做两件事：
 *  1. 把组件拍平成纯文本（送去翻译）
 *  2. 把译文包装回一个合法的文本组件（塞回聊天栏）
 */
public final class ChatText {

    private ChatText() {}

    /** 组件 JSON → 纯文本。解析失败时原样返回。 */
    public static String toPlain(String json) {
        try {
            JsonElement el = JsonParser.parseString(json);
            StringBuilder sb = new StringBuilder();
            walk(el, sb);
            return sb.toString().trim();
        } catch (Exception e) {
            return json == null ? "" : json.trim();
        }
    }

    private static void walk(JsonElement el, StringBuilder sb) {
        if (el == null || el.isJsonNull()) return;

        if (el.isJsonPrimitive()) {
            sb.append(el.getAsString());
            return;
        }
        if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) walk(child, sb);
            return;
        }
        if (!el.isJsonObject()) return;

        JsonObject o = el.getAsJsonObject();

        if (o.has("text") && o.get("text").isJsonPrimitive()) {
            sb.append(o.get("text").getAsString());
        } else if (o.has("translate") && o.get("translate").isJsonPrimitive()) {
            String key = o.get("translate").getAsString();
            List<String> args = new ArrayList<>();
            if (o.has("with") && o.get("with").isJsonArray()) {
                for (JsonElement arg : o.getAsJsonArray("with")) {
                    StringBuilder a = new StringBuilder();
                    walk(arg, a);
                    args.add(a.toString());
                }
            }
            sb.append(applyTranslate(key, args));
        }

        // 鼠标悬浮里的文字不算聊天内容，跳过；extra 才是正文续接
        if (o.has("extra")) walk(o.get("extra"), sb);
    }

    private static String applyTranslate(String key, List<String> args) {
        switch (key) {
            case "chat.type.text":
                return arg(args, 0) + ": " + arg(args, 1);
            case "chat.type.emote":
                return "* " + arg(args, 0) + " " + arg(args, 1);
            case "chat.type.announcement":
                return "[" + arg(args, 0) + "] " + arg(args, 1);
            case "chat.type.admin":
                return "[" + arg(args, 0) + " -> " + arg(args, 1) + "] " + arg(args, 2);
            case "chat.type.team.text":
            case "chat.type.team.sent":
                return arg(args, 0) + " <" + arg(args, 1) + "> " + arg(args, 2);
            default:
                // 未知翻译键：把参数按顺序拼出来，总比丢掉强
                if (args.isEmpty()) return key;
                StringBuilder sb = new StringBuilder();
                for (String a : args) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(a);
                }
                return sb.toString();
        }
    }

    private static String arg(List<String> args, int i) {
        return i < args.size() ? args.get(i) : "";
    }

    /** 译文 → 一个灰色文本组件。 */
    public static String toComponent(String text, String color) {
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        if (color != null && !color.isEmpty()) {
            o.addProperty("color", color);
        }
        return o.toString();
    }

    /** 去掉 § 颜色/格式码（§ 加任意一个字符）。 */
    public static String stripFormatting(String s) {
        if (s == null) return "";
        if (s.indexOf('§') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') {
                i++;                       // 跳过码本身和后面的字符
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 判断文本是否已经是中/日/韩文，用于跳过不必要的翻译。 */
    /**
     * 句子里面有没有中日韩文字。
     * 判断"要不要把玩家说的话翻成英文"用这个，不用 isMostlyCjk：
     * "大家好我叫Steve" 这种中英混排，汉字占比刚好一半，按比例算会被判成英文。
     */
    public static boolean hasCjk(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetter(c)) continue;
            Character.UnicodeScript sc = Character.UnicodeScript.of(c);
            if (sc == Character.UnicodeScript.HAN
                    || sc == Character.UnicodeScript.HIRAGANA
                    || sc == Character.UnicodeScript.KATAKANA
                    || sc == Character.UnicodeScript.HANGUL) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMostlyCjk(String s) {
        if (s == null || s.isEmpty()) return false;
        int cjk = 0;
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                Character.UnicodeScript script = Character.UnicodeScript.of(c);
                if (script == Character.UnicodeScript.HAN
                        || script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA
                        || script == Character.UnicodeScript.HANGUL) {
                    cjk++;
                }
            }
        }
        return letters > 0 && (double) cjk / letters > 0.5;
    }
}
