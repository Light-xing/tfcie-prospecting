package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;
import net.minecraft.core.Direction;

/**
 * 八向粗方位（感应探棒的 bearing）。
 *
 * <p>设计文档 v1.3：八向粗方位来自内置旋转接收线圈扫掠八个方位取最强响应；
 * 单点仅给粗方位与距离档，精确走向需多测点插测或配合磁力探针。
 */
public enum Bearing implements WireEnum {
    N("N", "北"),
    NE("NE", "东北"),
    E("E", "东"),
    SE("SE", "东南"),
    S("S", "南"),
    SW("SW", "西南"),
    W("W", "西"),
    NW("NW", "西北");

    private final String wire;
    private final String zhName;

    Bearing(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }

    /**
     * 由水平方向向量（dx, dz）量化到八向。
     * 角度以 +X 为东、+Z 为南（Minecraft 坐标），北为 -Z。
     */
    public static Bearing fromVector(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = 北，顺时针
        if (angle < 0) {
            angle += 360.0D;
        }
        int sector = (int) Math.floor((angle + 22.5D) / 45.0D) % 8;
        return switch (sector) {
            case 0 -> N;
            case 1 -> NE;
            case 2 -> E;
            case 3 -> SE;
            case 4 -> S;
            case 5 -> SW;
            case 6 -> W;
            default -> NW;
        };
    }

    /** 该方位的水平单位向量（x 向东，z 向南）。 */
    public double dx() {
        return switch (this) {
            case N -> 0.0D;
            case NE -> 0.7071D;
            case E -> 1.0D;
            case SE -> 0.7071D;
            case S -> 0.0D;
            case SW -> -0.7071D;
            case W -> -1.0D;
            case NW -> -0.7071D;
        };
    }

    public double dz() {
        return switch (this) {
            case N -> -1.0D;
            case NE -> -0.7071D;
            case E -> 0.0D;
            case SE -> 0.7071D;
            case S -> 1.0D;
            case SW -> 0.7071D;
            case W -> 0.0D;
            case NW -> -0.7071D;
        };
    }

    /** 由玩家朝向（水平）取方位，用于「仅沿玩家朝向」的兜底判据。 */
    public static Bearing fromDirection(Direction direction) {
        return switch (direction) {
            case NORTH -> N;
            case SOUTH -> S;
            case EAST -> E;
            case WEST -> W;
            default -> N;
        };
    }
}
