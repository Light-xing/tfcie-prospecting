package com.tfcie.prospecting.common.prospecting;

/**
 * 踏勘镐的干扰来源（造成假阳性的机制）。
 *
 * <p>设计文档 v1.3 明确：踏勘镐的干扰源是「敲岩目视」这一类手工采样才会有的事物 ——
 * 异地漂砾、相似矿物混淆、覆盖层污染；而浅水层、弱磁性岩层属于物探干扰，与本工具无关。
 */
public enum Interference {
    /** 无干扰。 */
    NONE("none"),
    /** 异地漂砾：命中的只是被冰川/河流搬运来的地表矿屑，不代表本地有矿体。 */
    BOULDER("boulder"),
    /**
     * 相似矿物混淆：矿种物性表中声明了 {@code confused_with} 的矿种被误报为其目标矿种。
     * 1.20 的官方原生依据是黄铁矿（愚人金，{@code fake_native_gold} 矿脉）被误判为自然金。
     */
    ORE_CONFUSION("ore_confusion"),
    /** 覆盖层污染：覆盖层下的迹象被上层物质稀释或搬运。 */
    OVERBURDEN("overburden");

    private final String wire;

    Interference(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 是否构成假阳性。 */
    public boolean isFalsePositive() {
        return this != NONE;
    }
}
