package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 密度异常档：只给异常符号，不给物质（多解）。 */
public enum DensityAnomaly implements WireEnum {
    LOW_DENSITY("low_density", "低密度"),
    HIGH_DENSITY("high_density", "高密度"),
    NONE("none", "无异常");

    private final String wire;
    private final String zhName;

    DensityAnomaly(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }
}
