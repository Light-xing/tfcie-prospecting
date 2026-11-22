package com.tfcie.prospecting.api.enums;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 物探工具的档位与方位量化（纯数学，阶段 4 的判读基础）。
 *
 * <p>坐标约定：+X 为东、-Z 为北（Minecraft 世界坐标），顺时针为正。
 */
public class ProspectingMathTest {

    // ---------------- Bearing：八向粗方位 ----------------

    @Test
    public void bearingQuantizesCardinalDirections() {
        assertEquals(Bearing.N, Bearing.fromVector(0, -1));
        assertEquals(Bearing.E, Bearing.fromVector(1, 0));
        assertEquals(Bearing.S, Bearing.fromVector(0, 1));
        assertEquals(Bearing.W, Bearing.fromVector(-1, 0));
    }

    @Test
    public void bearingQuantizesIntercardinals() {
        assertEquals(Bearing.NE, Bearing.fromVector(1, -1));
        assertEquals(Bearing.SE, Bearing.fromVector(1, 1));
        assertEquals(Bearing.SW, Bearing.fromVector(-1, 1));
        assertEquals(Bearing.NW, Bearing.fromVector(-1, -1));
    }

    @Test
    public void bearingSectorBoundaries() {
        // 22.5° 为扇区边界：刚好在边界上应归入下一扇区（实现取 floor((angle+22.5)/45)）
        assertEquals(Bearing.N, Bearing.fromVector(Math.sin(Math.toRadians(22.4)), -Math.cos(Math.toRadians(22.4))));
        assertEquals(Bearing.NE, Bearing.fromVector(Math.sin(Math.toRadians(22.6)), -Math.cos(Math.toRadians(22.6))));
    }

    // ---------------- Strike：四向走向 ----------------

    @Test
    public void strikeQuantizesFourDirections() {
        assertEquals(Strike.N_S, Strike.fromVector(0, -1));
        assertEquals(Strike.NE_SW, Strike.fromVector(1, -1));
        assertEquals(Strike.E_W, Strike.fromVector(1, 0));
        assertEquals(Strike.NW_SE, Strike.fromVector(-1, -1));
    }

    @Test
    public void strikeIsDirectionless() {
        // 走向无方向性：反向应得到同一条走向
        assertEquals(Strike.N_S, Strike.fromVector(0, 1));
        assertEquals(Strike.E_W, Strike.fromVector(-1, 0));
    }

    // ---------------- 距离 / 强度 / 延展分档 ----------------

    @Test
    public void distanceBands() {
        assertEquals(DistanceBand.NEAR, DistanceBand.of(0, 24));
        assertEquals(DistanceBand.NEAR, DistanceBand.of(7, 24));
        assertEquals(DistanceBand.MEDIUM, DistanceBand.of(12, 24));
        assertEquals(DistanceBand.FAR, DistanceBand.of(20, 24));
        assertEquals(DistanceBand.NEAR, DistanceBand.of(5, 0));
    }

    @Test
    public void fieldStrengthBands() {
        assertEquals(FieldStrength.BACKGROUND, FieldStrength.of(0.0D));
        assertEquals(FieldStrength.BACKGROUND, FieldStrength.of(0.05D));
        assertEquals(FieldStrength.WEAK, FieldStrength.of(0.2D));
        assertEquals(FieldStrength.MEDIUM, FieldStrength.of(0.5D));
        assertEquals(FieldStrength.STRONG, FieldStrength.of(0.9D));
    }

    @Test
    public void extentBands() {
        assertEquals(Extent.SMALL, Extent.ofSpan(2, 10, 24));
        assertEquals(Extent.MEDIUM, Extent.ofSpan(15, 10, 24));
        assertEquals(Extent.LARGE, Extent.ofSpan(30, 10, 24));
    }

    @Test
    public void magnetismWeightsReflectCalibration() {
        assertEquals(0.0D, Magnetism.NONE.weight(), 1.0E-9D);
        assertEquals(1.0D, Magnetism.STRONG.weight(), 1.0E-9D);
        // 弱磁（赤铁矿）显著低于强磁（磁铁矿），但非零
        assertEquals(0.35D, Magnetism.WEAK.weight(), 1.0E-9D);
    }

    @Test
    public void densityDeviationSigns() {
        assertEquals(1.0D, DensityClass.HIGH.deviation(), 1.0E-9D);
        assertEquals(0.0D, DensityClass.NORMAL.deviation(), 1.0E-9D);
        assertEquals(-1.0D, DensityClass.LOW.deviation(), 1.0E-9D);
    }
}
