package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 与空腔相邻的流体种类（回声探针）。 */
public enum FluidKind implements WireEnum {
    NONE("none", "无"),
    WATER("water", "水"),
    LAVA("lava", "岩浆");

    private final String wire;
    private final String zhName;

    FluidKind(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }
}
