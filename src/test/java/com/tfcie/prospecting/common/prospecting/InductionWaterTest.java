package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.enums.Bearing;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 感应探棒的水体去饱和（报告 18 §2.2、§P1 第 3 条）。
 *
 * <p>纯逻辑单测：只验证「哪种采样点计入积分」的纯函数判据，不启动游戏。
 * 关键断言：地下封闭淡水 {@code integrates() == false}，因此不会写进扇区 / 距离 / 深度统计；
 * 盐水是低档正响应且必须标干扰。
 */
public class InductionWaterTest {

    @Test
    public void undergroundFreshWaterOnlyFlagsInterference() {
        final InductionProbeProspector.SampleContribution water =
                InductionProbeProspector.SampleContribution.of(true, false, false, 1.0D);
        assertEquals(InductionProbeProspector.Contribution.WATER, water.kind());
        assertFalse("地下封闭水不得进入扇区/距离/深度积分", water.integrates());
        assertTrue("必须标记水体干扰", water.waterInterference());
        assertEquals(0.0D, water.unit(), 1.0E-12D);
    }

    @Test
    public void saltWaterGivesLowPositiveResponseAndInterference() {
        final InductionProbeProspector.SampleContribution salt =
                InductionProbeProspector.SampleContribution.of(true, true, false, 1.0D);
        assertEquals(InductionProbeProspector.Contribution.SIGNAL, salt.kind());
        assertTrue(salt.integrates());
        assertTrue(salt.waterInterference());
        assertEquals(InductionProbeProspector.SALT_WATER_RESPONSE, salt.unit(), 1.0E-12D);
        assertTrue("盐水是低档正响应，不能静默按 0 处理", salt.unit() > 0.0D && salt.unit() < 0.6D);
    }

    @Test
    public void oreAndLavaStillIntegrate() {
        final InductionProbeProspector.SampleContribution ore =
                InductionProbeProspector.SampleContribution.of(false, false, false, 1.0D);
        assertEquals(InductionProbeProspector.Contribution.SIGNAL, ore.kind());
        assertTrue(ore.integrates());
        assertEquals(1.0D, ore.unit(), 1.0E-12D);

        final InductionProbeProspector.SampleContribution lava =
                InductionProbeProspector.SampleContribution.of(false, false, true, 0.0D);
        assertEquals(InductionProbeProspector.Contribution.SIGNAL, lava.kind());
        assertTrue(lava.integrates());

        final InductionProbeProspector.SampleContribution none =
                InductionProbeProspector.SampleContribution.of(false, false, false, 0.0D);
        assertEquals(InductionProbeProspector.Contribution.NONE, none.kind());
        assertFalse(none.integrates());
    }

    /** 复刻 measure() 的扇区累加：只有 integrates() 为 true 的采样点才会写扇区。 */
    @Test
    public void waterNeverEntersSectors() {
        final double[] sectors = new double[Bearing.values().length];
        for (int i = 0; i < 100; i++) {
            final InductionProbeProspector.SampleContribution water =
                    InductionProbeProspector.SampleContribution.of(true, false, false, 1.0D);
            if (!water.integrates()) {
                continue;
            }
            sectors[Bearing.E.ordinal()] += water.unit();
        }
        assertEquals(0.0D, sectors[Bearing.E.ordinal()], 1.0E-12D);
    }
}
