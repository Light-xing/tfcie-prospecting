package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/**
 * 矿族大类：按地质经济用途分类。
 * 判定优先级自上而下：GEM &gt; METALLIC &gt; NON_METALLIC &gt; UNKNOWN。
 */
public enum OreGroup implements WireEnum {
    GEM("gem", "宝石"),
    METALLIC("metallic", "金属矿"),
    NON_METALLIC("non_metallic", "非金属矿"),
    UNKNOWN("unknown", "未知");

    private final String wire;
    private final String zhName;

    OreGroup(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }

    /** 按 wire 名反查；未匹配返回 {@code null}。 */
    public static OreGroup fromWire(String wire) {
        for (OreGroup g : values()) {
            if (g.wire.equals(wire)) {
                return g;
            }
        }
        return null;
    }
}
