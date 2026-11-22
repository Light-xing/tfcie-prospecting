package com.tfcie.prospecting.api;

/**
 * 带 wire 序列化名的枚举。JSON / NBT 中一律使用 {@link #wire()} 返回的
 * lowercase snake_case（方位用大写缩写），与 {@code prospecting_tools.json}
 * 的 {@code enums} 保持一致，供玩家消息与智能体接口共用同一份语义。
 */
public interface WireEnum {

    /** JSON / NBT 序列化名。 */
    String wire();

    /** 中文显示名（用于纯文本消息；玩家可见文本走 lang 键 + {@code Component}）。 */
    String zhName();

    /**
     * 按 wire 名反查枚举常量；未匹配返回 {@code fallback}。
     *
     * <p>数据文件里的枚举一律以 wire 名书写，本方法让各枚举不必各写一份反查。
     */
    static <E extends Enum<E> & WireEnum> E fromWire(Class<E> type, String wire, E fallback) {
        if (wire == null) {
            return fallback;
        }
        for (E candidate : type.getEnumConstants()) {
            if (candidate.wire().equals(wire)) {
                return candidate;
            }
        }
        return fallback;
    }
}
