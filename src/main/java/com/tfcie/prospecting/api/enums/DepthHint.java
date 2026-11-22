package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 深度档：粗粒度三档，精确深度由密度探锤区间或钻探给出。 */
public enum DepthHint implements WireEnum {
    SHALLOW("shallow", "浅"),
    MEDIUM("medium", "中"),
    DEEP("deep", "深");

    private final String wire;
    private final String zhName;

    DepthHint(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }
}
