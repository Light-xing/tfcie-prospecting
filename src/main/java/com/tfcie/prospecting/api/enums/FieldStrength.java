package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 磁场强度档（磁力探针的 field_strength）。 */
public enum FieldStrength implements WireEnum {
    BACKGROUND("background", "本底"),
    WEAK("weak", "弱"),
    MEDIUM("medium", "中"),
    STRONG("strong", "强");

    private final String wire;
    private final String zhName;

    FieldStrength(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }

    /** 由归一化响应（0~1+）分档。 */
    public static FieldStrength of(double normalized) {
        if (normalized < 0.08D) {
            return BACKGROUND;
        }
        if (normalized < 0.33D) {
            return WEAK;
        }
        return normalized < 0.66D ? MEDIUM : STRONG;
    }
}
