package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.enums.Magnitude;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 传感器体积积分与量程归一化（报告 16 §1.4 ②④）—— 纯函数级单测，不触世界。
 *
 * <p>只断言公式在给定输入下单调、阈值可达，不声称任何「站在某深度矿体上会报什么档」
 * 这类需要世界才能验证的结论。
 */
public class SensorMathTest {

    // ---------------- 磁法：单点权重 = unit · sx·sy·sz / (1 + d²) ----------------

    @Test
    public void magneticWeightIncludesVoxelVolume() {
        // 近距逐格：体素体积 1
        assertEquals(1.0D / (1.0D + 10.0D * 10.0D),
                MagneticNeedleProspector.magneticWeight(1.0D, 10.0D, 1, 1, 1), 1.0E-12D);
        // 中距 2×2、垂向 1：该点代表 4 格，权重乘 4
        assertEquals(4.0D / (1.0D + 10.0D * 10.0D),
                MagneticNeedleProspector.magneticWeight(1.0D, 10.0D, 2, 1, 2), 1.0E-12D);
        // 远距 4×4 → 16 格
        assertEquals(16.0D / (1.0D + 50.0D * 50.0D),
                MagneticNeedleProspector.magneticWeight(1.0D, 50.0D, 4, 1, 4), 1.0E-12D);
    }

    @Test
    public void magneticWeightDecaysWithDistanceAndGrowsWithVolume() {
        final double near = MagneticNeedleProspector.magneticWeight(1.0D, 11.0D, 2, 1, 2);
        final double far = MagneticNeedleProspector.magneticWeight(1.0D, 24.0D, 2, 1, 2);
        assertTrue("同一体素下距离越远读数越弱", near > far);
        // 同距离、水平采样面积翻倍 → 权重翻倍（体积单调）
        assertEquals(2.0D * MagneticNeedleProspector.magneticWeight(1.0D, 15.0D, 1, 1, 1),
                MagneticNeedleProspector.magneticWeight(1.0D, 15.0D, 2, 1, 1), 1.0E-12D);
    }

    // ---------------- 感应探棒：体积积分（报告 17 §7.2 的 P0 漏改） ----------------

    @Test
    public void inductionWeightIncludesVoxelVolume() {
        assertEquals(1.0D / (1.0D + 10.0D),
                InductionProbeProspector.inductionWeight(1.0D, 10.0D, 1, 1, 1), 1.0E-12D);
        // 中距 2×2、垂向 1：该点代表 4 格
        assertEquals(4.0D / (1.0D + 10.0D),
                InductionProbeProspector.inductionWeight(1.0D, 10.0D, 2, 1, 2), 1.0E-12D);
        // 远距 4×4：16 格
        assertEquals(16.0D / (1.0D + 24.0D),
                InductionProbeProspector.inductionWeight(1.0D, 24.0D, 4, 1, 4), 1.0E-12D);
    }

    @Test
    public void inductionWeightDecaysWithDistanceAndGrowsWithVolume() {
        final double near = InductionProbeProspector.inductionWeight(1.0D, 9.0D, 2, 1, 2);
        final double far = InductionProbeProspector.inductionWeight(1.0D, 24.0D, 2, 1, 2);
        assertTrue("同一体素下距离越远读数越弱", near > far);
        assertEquals(2.0D * InductionProbeProspector.inductionWeight(1.0D, 12.0D, 1, 1, 1),
                InductionProbeProspector.inductionWeight(1.0D, 12.0D, 2, 1, 1), 1.0E-12D);
    }

    // ---------------- 磁针两点法：跨在矿脉两侧时不能互相抵消 ----------------

    @Test
    public void alignedFieldDoesNotCancelAcrossStations() {
        // 同侧：原样相加
        final double[] same = MagneticNeedleProspector.alignedField(3.0D, 4.0D, 1.0D, 1.0D);
        assertEquals(4.0D, same[0], 1.0E-12D);
        assertEquals(5.0D, same[1], 1.0E-12D);
        // 异侧：两个向量反号，对齐后应同向相加而不是互相抵消
        final double[] across = MagneticNeedleProspector.alignedField(3.0D, 4.0D, -3.0D, -4.0D);
        assertEquals(6.0D, across[0], 1.0E-12D);
        assertEquals(8.0D, across[1], 1.0E-12D);
        assertTrue("异侧合成后不能接近零", Math.hypot(across[0], across[1]) > 1.0E-6D);
    }

    // ---------------- 重力法：1.5 分母让合理读数够得到 MEDIUM ----------------

    @Test
    public void densityMagnitudeIsReachableForAReasonableBucket() {
        // 单列 8 格桶、每格 deviation = 1、以 1/(1+d) 加权：bestAbs = Σ_{d=1..8} 1/(1+d)
        double bestAbs = 0.0D;
        for (int d = 1; d <= 8; d++) {
            bestAbs += 1.0D / (1.0D + d);
        }
        assertEquals(1.8289682539682538D, bestAbs, 1.0E-12D);
        // 旧分母 8.0：normalized ≈ 0.25 < 0.33 → 永远 WEAK（报告 16 §1.4 ④ 的原始现象）
        assertTrue(bestAbs / (bestAbs + 8.0D) < 0.33D);
        // 新分母 1.5：normalized ≈ 0.549 → MEDIUM
        assertEquals(Magnitude.MEDIUM, DensityPlumbProspector.magnitudeOf(bestAbs));
    }

    @Test
    public void densityMagnitudeIsMonotonic() {
        assertEquals(Magnitude.WEAK, DensityPlumbProspector.magnitudeOf(0.2D));
        assertEquals(Magnitude.MEDIUM, DensityPlumbProspector.magnitudeOf(1.0D));
        assertEquals(Magnitude.STRONG, DensityPlumbProspector.magnitudeOf(4.0D));
        assertTrue(DensityPlumbProspector.magnitudeOf(4.0D).ordinal()
                > DensityPlumbProspector.magnitudeOf(1.0D).ordinal());
    }
}
