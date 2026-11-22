package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 导电档：相对本底的电性异常强度。 */
public enum Conductivity implements WireEnum {
    NONE("none", "无"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高");

    private final String wire;
    private final String zhName;

    Conductivity(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }
}
