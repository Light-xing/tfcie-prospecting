package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/**
 * 矿种的密度档（物性表字段，非读数）。
 *
 * <p>用于密度探锤（重力法）：只有「符号」可判，具体物质无法区分 ——
 * 煤 / 盐 / 石膏 / 硼砂 / 空洞 / 水囊 / 岩浆腔都表现为低密度异常。
 */
public enum DensityClass implements WireEnum {
    LOW("low", "低密度"),
    NORMAL("normal", "常密度"),
    HIGH("high", "高密度");

    private final String wire;
    private final String zhName;

    DensityClass(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }

    /** 相对围岩的密度偏差权重（有符号）。 */
    public double deviation() {
        return switch (this) {
            case HIGH -> 1.0D;
            case NORMAL -> 0.0D;
            case LOW -> -1.0D;
        };
    }
}
