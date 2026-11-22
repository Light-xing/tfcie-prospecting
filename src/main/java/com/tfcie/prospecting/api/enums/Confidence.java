package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 置信度档：综合采样次数、一致性与干扰程度的可信度，不改变读数本身。 */
public enum Confidence implements WireEnum {
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高");

    private final String wire;
    private final String zhName;

    Confidence(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }
}
