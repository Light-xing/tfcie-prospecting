package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 迹象三档（踏勘镐 presence）：反映矿量/丰度，不反映距离与方位。 */
public enum Presence implements WireEnum {
    NONE("none", "无"),
    WEAK("weak", "微弱"),
    STRONG("strong", "明显");

    private final String wire;
    private final String zhName;

    Presence(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }
}
