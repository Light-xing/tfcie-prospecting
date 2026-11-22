package com.tfcie.prospecting.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一读数结果 DTO。所有工具读数收敛为此结构，同时驱动：
 * <ul>
 *   <li>玩家聊天 / HUD 文本（{@link #message()}）；</li>
 *   <li>numen {@code TaskResult.data}（智能体接口）；</li>
 *   <li>未来日志 / 存档。</li>
 * </ul>
 * 单一事实来源，避免三处文案漂移。字段名采用 lowercase snake_case。
 */
public final class ProspectingResult {

    private static final Gson GSON = new Gson();

    private final String tool;
    private final boolean success;
    private final String message;
    private final Map<String, Object> data;

    private ProspectingResult(String tool, boolean success, String message, Map<String, Object> data) {
        this.tool = tool;
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static ProspectingResult ok(String tool, String message) {
        return new ProspectingResult(tool, true, message, new LinkedHashMap<>());
    }

    public static ProspectingResult ok(String tool, String message, Map<String, Object> data) {
        return new ProspectingResult(tool, true, message, new LinkedHashMap<>(data));
    }

    public static ProspectingResult fail(String tool, String message) {
        return new ProspectingResult(tool, false, message, new LinkedHashMap<>());
    }

    public String tool() { return tool; }

    public boolean success() { return success; }

    public String message() { return message; }

    public Map<String, Object> data() { return data; }

    /**
     * 序列化为 JSON 字符串。枚举（{@link WireEnum}）以 {@link WireEnum#wire()}
     * 输出；集合 / 映射经 Gson 展开；键保持 lowercase snake_case。
     */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("tool", tool);
        root.addProperty("success", success);
        root.addProperty("message", message == null ? "" : message);
        if (!data.isEmpty()) {
            JsonObject dataObj = new JsonObject();
            for (Map.Entry<String, Object> e : data.entrySet()) {
                dataObj.add(e.getKey(), toJsonElement(e.getValue()));
            }
            root.add("data", dataObj);
        }
        return root.toString();
    }

    private static JsonElement toJsonElement(Object v) {
        if (v == null) return JsonNull.INSTANCE;
        if (v instanceof Number n) return new JsonPrimitive(n);
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Character c) return new JsonPrimitive(c);
        if (v instanceof WireEnum we) return new JsonPrimitive(we.wire());
        if (v instanceof Collection<?> || v instanceof Map<?, ?>) return GSON.toJsonTree(v);
        return new JsonPrimitive(String.valueOf(v));
    }
}
