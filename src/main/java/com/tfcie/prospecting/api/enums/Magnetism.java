package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/**
 * 矿种的磁性（物性表字段，非读数）。
 *
 * <p>依据《TFC岩石与矿脉生成机制.md》的标定结论：TFC 的三种铁矿物磁性分化明显 ——
 * 磁铁矿（Magnetite）强磁、赤铁矿（Hematite）弱磁、褐铁矿（Limonite）基本无磁；
 * 硅镁镍矿（Garnierite）是非磁性风化硅酸盐，属物探盲区。
 */
public enum Magnetism implements WireEnum {
    NONE("none", "无磁性"),
    WEAK("weak", "弱磁性"),
    STRONG("strong", "强磁性");

    private final String wire;
    private final String zhName;

    Magnetism(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }

    public boolean detectable() {
        return this != NONE;
    }

    /** 磁法响应权重。 */
    public double weight() {
        return switch (this) {
            case STRONG -> 1.0D;
            case WEAK -> 0.35D;
            case NONE -> 0.0D;
        };
    }
}
