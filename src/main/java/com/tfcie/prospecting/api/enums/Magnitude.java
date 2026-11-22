package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 强度档（磁性强度 / 异常强度等）。 */
public enum Magnitude implements WireEnum {
    WEAK("weak", "微弱"),
    MEDIUM("medium", "中等"),
    STRONG("strong", "强");

    private final String wire;
    private final String zhName;

    Magnitude(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }
}
