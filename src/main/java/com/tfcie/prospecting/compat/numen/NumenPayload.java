package com.tfcie.prospecting.compat.numen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tfcie.prospecting.api.ProspectingResult;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

/**
 * numen 侧的载荷编解码（纯逻辑，不依赖 MC 运行时，可单测）。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>入参</b>：从工具参数里取可选的 {@code target_x / target_y / target_z}；</li>
 *   <li><b>出参</b>：把 {@link ProspectingResult} 转成 numen 的 {@code TaskResult}
 *       JSON 形状（{@code success / message / data}），让模型读到的结果与 numen
 *       自家工具完全一致。</li>
 * </ol>
 *
 * <p>出参复用 {@link ProspectingResult#toJson()} 的序列化结果再重塑外形，
 * 而不是重新遍历 data —— 否则枚举的 wire 名要在两处各写一遍，早晚会漂移。
 */
public final class NumenPayload {

    /** 工具参数中目标坐标的三个键。 */
    public static final String KEY_X = "target_x";
    public static final String KEY_Y = "target_y";
    public static final String KEY_Z = "target_z";
    /** 钻探取样器可选的目标深度键（格）。 */
    public static final String KEY_DEPTH = "depth";

    /**
     * 读取目标方块坐标；三个键缺任意一个即视为「对空使用」，返回 {@code null}。
     */
    @Nullable
    public static BlockPos readTarget(@Nullable JsonObject args) {
        if (args == null
                || !args.has(KEY_X) || !args.has(KEY_Y) || !args.has(KEY_Z)
                || args.get(KEY_X).isJsonNull() || args.get(KEY_Y).isJsonNull() || args.get(KEY_Z).isJsonNull()) {
            return null;
        }
        return new BlockPos(args.get(KEY_X).getAsInt(),
                args.get(KEY_Y).getAsInt(),
                args.get(KEY_Z).getAsInt());
    }

    /** 读取可选的钻探深度（格）；未提供时返回 {@code null}（沿用工具当前档位）。 */
    @Nullable
    public static Integer readDepth(@Nullable JsonObject args) {
        if (args == null || !args.has(KEY_DEPTH) || args.get(KEY_DEPTH).isJsonNull()) {
            return null;
        }
        try {
            return args.get(KEY_DEPTH).getAsInt();
        } catch (RuntimeException bad) {
            return null;
        }
    }

    /** {@link ProspectingResult} → numen {@code TaskResult} 的 JSON 形状。 */
    public static String toTaskResult(ProspectingResult result) {
        final JsonObject source = JsonParser.parseString(result.toJson()).getAsJsonObject();

        final JsonObject root = new JsonObject();
        root.addProperty("success", source.has("success") && source.get("success").getAsBoolean());
        root.addProperty("message", source.has("message") ? source.get("message").getAsString() : "");

        final JsonObject data = source.has("data") && source.get("data").isJsonObject()
                ? source.getAsJsonObject("data")
                : new JsonObject();
        data.addProperty("tool", result.tool());
        root.add("data", data);
        return root.toString();
    }

    /** 失败结果（同样按 {@code TaskResult} 形状）。 */
    public static String failure(String message) {
        final JsonObject root = new JsonObject();
        root.addProperty("success", false);
        root.addProperty("message", message);
        root.add("data", new JsonObject());
        return root.toString();
    }

    /**
     * 新版 numen（0.1.4.1 / 0.1.5）{@code ToolOutcome} 信封：
     * 新版 {@code ToolOutcome.modelText(result)} 会剥离外层信封、只取 {@code message} 字符串交给模型与 MCP 客户端。
     * 因此外层信封放 {@code success} 供 {@code ToolOutcome.failed} 判成败，{@code message} 内放完整的
     * {@link #toTaskResult(ProspectingResult)} JSON 串，确保 {@code data} 字段不丢失。
     */
    public static String toApiOutcome(ProspectingResult result) {
        final String taskJson = toTaskResult(result);
        final JsonObject source = JsonParser.parseString(taskJson).getAsJsonObject();
        final boolean ok = source.has("success") && source.get("success").getAsBoolean();
        final JsonObject envelope = new JsonObject();
        envelope.addProperty("success", ok);
        envelope.addProperty("message", taskJson);
        return envelope.toString();
    }

    /** 新版 numen（0.1.4.1 / 0.1.5）失败信封。 */
    public static String apiFailure(String message) {
        final String taskJson = failure(message);
        final JsonObject envelope = new JsonObject();
        envelope.addProperty("success", false);
        envelope.addProperty("message", taskJson);
        return envelope.toString();
    }

    private NumenPayload() {}
}
