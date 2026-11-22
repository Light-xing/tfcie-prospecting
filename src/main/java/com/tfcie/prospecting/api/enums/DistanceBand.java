package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 距离档（感应探棒的距离判读）。 */
public enum DistanceBand implements WireEnum {
    NEAR("near", "近"),
    MEDIUM("medium", "中"),
    FAR("far", "远");

    private final String wire;
    private final String zhName;

    DistanceBand(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }

    /** 由距离与最大半径判定。 */
    public static DistanceBand of(double distance, double maxRadius) {
        if (maxRadius <= 0) {
            return NEAR;
        }
        double ratio = distance / maxRadius;
        if (ratio < 0.34D) {
            return NEAR;
        }
        return ratio < 0.67D ? MEDIUM : FAR;
    }
}
