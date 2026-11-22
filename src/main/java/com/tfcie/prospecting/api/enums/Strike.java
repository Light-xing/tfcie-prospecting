package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/**
 * 矿脉走向（磁力探针的 strike）。
 *
 * <p>设计文档 v1.3：走向判定需至少 2 个测点；单点仅给方位角（内置磁梯度传感器，16 向）与强度。
 */
public enum Strike implements WireEnum {
    N_S("N-S", "南北"),
    NE_SW("NE-SW", "东北-西南"),
    E_W("E-W", "东西"),
    NW_SE("NW-SE", "西北-东南");

    private final String wire;
    private final String zhName;

    Strike(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }

    /** 由两测点连线方向量化到四向走向。 */
    public static Strike fromVector(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) {
            angle += 180.0D; // 走向无方向性，取 0~180
        }
        int sector = (int) Math.floor((angle + 22.5D) / 45.0D) % 4;
        return switch (sector) {
            case 0 -> N_S;
            case 1 -> NE_SW;
            case 2 -> E_W;
            default -> NW_SE;
        };
    }
}
