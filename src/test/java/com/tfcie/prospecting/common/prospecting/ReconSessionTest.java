package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.enums.Confidence;
import com.tfcie.prospecting.api.enums.OreGroup;
import com.tfcie.prospecting.api.enums.Presence;
import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 踏勘镐收敛 / 饱和状态机（阶段 3 验收）。
 *
 * <p>默认参数：converge_samples = 3，saturation_after = 6（v1.3 要求后者必须大于前者，
 * 否则前 3 次采样中任一分歧就会永久饱和、永远无法收敛）。
 */
public class ReconSessionTest {

    private static final int CONVERGE = 3;
    private static final int SATURATE = 6;

    private static ReconSession accept(ReconSession s, String oreId, OreGroup g) {
        return s.accept(oreId, g, CONVERGE, SATURATE);
    }

    @Test
    public void threeConsecutiveConsistentSamplesConverge() {
        ReconSession s = ReconSession.fresh(0, 0);
        s = accept(s, "native_copper", OreGroup.METALLIC);
        assertFalse(s.converged());
        assertFalse(s.saturated());

        s = accept(s, "native_copper", OreGroup.METALLIC);
        assertFalse(s.converged());

        s = accept(s, "native_copper", OreGroup.METALLIC);
        assertTrue(s.converged());
        assertFalse(s.saturated());
        assertEquals("native_copper", s.suspectedOreId());
        assertEquals(Confidence.MEDIUM, s.confidence());
    }

    @Test
    public void saturationAtSixthValidSampleWithoutConvergence() {
        ReconSession s = ReconSession.fresh(0, 0);
        // 交替「矿族」才能阻止收敛 —— 判读一致的粒度是 ore_group，
        // 仅仅换矿名（如 native_copper ↔ magnetite）仍属同一矿族，第 3 次就会收敛。
        for (int i = 0; i < 5; i++) {
            final boolean metallic = i % 2 == 0;
            s = accept(s, metallic ? "native_copper" : "bituminous_coal",
                    metallic ? OreGroup.METALLIC : OreGroup.NON_METALLIC);
            assertFalse("第 " + (i + 1) + " 次不应饱和", s.saturated());
            assertFalse("第 " + (i + 1) + " 次不应收敛", s.converged());
        }
        assertEquals(5, s.validSamples());

        // 第 6 次仍未收敛 → 饱和
        s = accept(s, "native_copper", OreGroup.METALLIC);
        assertEquals(6, s.validSamples());
        assertTrue(s.saturated());
    }

    @Test
    public void disagreementResetsConsecutiveButNotValidCount() {
        ReconSession s = ReconSession.fresh(0, 0);
        s = accept(s, "native_copper", OreGroup.METALLIC);
        s = accept(s, "native_copper", OreGroup.METALLIC);
        assertEquals(2, s.consecutiveMatches());

        // 分歧：连续计数清零重计，但累计有效采样继续增长（不立即饱和）
        s = accept(s, "bituminous_coal", OreGroup.NON_METALLIC);
        assertEquals(1, s.consecutiveMatches());
        assertEquals(3, s.validSamples());
        assertFalse(s.saturated());
        assertEquals(OreGroup.NON_METALLIC, s.group());

        // 换族后连续 3 次一致同样可以收敛
        s = accept(s, "bituminous_coal", OreGroup.NON_METALLIC);
        s = accept(s, "bituminous_coal", OreGroup.NON_METALLIC);
        assertTrue(s.converged());
    }

    @Test
    public void v13DeadlockIsFixed() {
        // v1.3 修复前：converge == saturation == 3，前 3 次采样中任一分歧即永久饱和、永远无法收敛。
        // 现在 saturation(6) > converge(3)，一次分歧只会清零连续计数，不会锁死。
        ReconSession s = ReconSession.fresh(0, 0);
        s = accept(s, "native_copper", OreGroup.METALLIC);
        s = accept(s, "tetrahedrite", OreGroup.METALLIC);       // 同矿族 → 视为一致
        assertEquals(2, s.consecutiveMatches());

        s = accept(s, "bituminous_coal", OreGroup.NON_METALLIC); // 分歧
        assertEquals(1, s.consecutiveMatches());
        assertEquals(3, s.validSamples());
        assertFalse("分歧不应导致立即饱和", s.saturated());
        assertFalse(s.converged());

        // 分歧后仍能正常收敛
        s = accept(s, "bituminous_coal", OreGroup.NON_METALLIC);
        s = accept(s, "bituminous_coal", OreGroup.NON_METALLIC);
        assertTrue(s.converged());
    }

    @Test
    public void falseNegativeIsNotCounted() {
        // 假阴性由调用方直接跳过 accept：状态必须原样保留（不计数、不中断）
        ReconSession s = ReconSession.fresh(0, 0);
        s = accept(s, "native_copper", OreGroup.METALLIC);
        s = accept(s, "native_copper", OreGroup.METALLIC);

        ReconSession afterFalseNegative = s; // 假阴性不调用 accept
        assertEquals(2, afterFalseNegative.consecutiveMatches());
        assertEquals(2, afterFalseNegative.validSamples());

        // 假阴性之后继续一致采样，仍能在第 3 次达成收敛（说明没被打断）
        ReconSession done = accept(afterFalseNegative, "native_copper", OreGroup.METALLIC);
        assertTrue(done.converged());
    }

    @Test
    public void continuedSamplingAfterConvergenceSaturates() {
        ReconSession s = ReconSession.fresh(0, 0);
        s = accept(s, "diamond", OreGroup.GEM);
        s = accept(s, "diamond", OreGroup.GEM);
        s = accept(s, "diamond", OreGroup.GEM);
        assertTrue(s.converged());
        assertFalse(s.saturated());

        ReconSession after = accept(s, "diamond", OreGroup.GEM);
        assertTrue(after.saturated());
        assertTrue(after.converged());
        // 结论不变
        assertEquals("diamond", after.suspectedOreId());
    }

    @Test
    public void saturatedSessionIgnoresFurtherSamples() {
        ReconSession s = ReconSession.fresh(0, 0);
        for (int i = 0; i < 6; i++) {
            final boolean metallic = i % 2 == 0;
            s = accept(s, metallic ? "native_copper" : "bituminous_coal",
                    metallic ? OreGroup.METALLIC : OreGroup.NON_METALLIC);
        }
        assertTrue(s.saturated());
        assertFalse("饱和来自累计采样数，而非收敛", s.converged());

        ReconSession after = accept(s, "diamond", OreGroup.GEM);
        assertEquals(s, after);
    }

    @Test
    public void suspectedNameUsesMajorityVote() {
        ReconSession s = ReconSession.fresh(0, 0);
        s = accept(s, "native_copper", OreGroup.METALLIC);
        s = accept(s, "tetrahedrite", OreGroup.METALLIC);
        s = accept(s, "native_copper", OreGroup.METALLIC);
        assertTrue(s.converged());
        assertEquals("native_copper", s.suspectedOreId());
    }

    @Test
    public void confidenceNeverExceedsMedium() {
        ReconSession s = ReconSession.fresh(0, 0);
        assertEquals(Confidence.LOW, s.confidence());
        s = accept(s, "diamond", OreGroup.GEM);
        s = accept(s, "diamond", OreGroup.GEM);
        s = accept(s, "diamond", OreGroup.GEM);
        assertEquals(Confidence.MEDIUM, s.confidence());
    }

    @Test
    public void presenceThresholds() {
        assertEquals(Presence.NONE, ReconSession.presenceOf(0, 5));
        assertEquals(Presence.WEAK, ReconSession.presenceOf(1, 5));
        assertEquals(Presence.WEAK, ReconSession.presenceOf(4, 5));
        assertEquals(Presence.STRONG, ReconSession.presenceOf(5, 5));
    }

    @Test
    public void tagRoundTrips() {
        ReconSession s = ReconSession.fresh(12, -30);
        s = accept(s, "native_copper", OreGroup.METALLIC);
        s = accept(s, "tetrahedrite", OreGroup.METALLIC);

        CompoundTag tag = s.toTag();
        ReconSession back = ReconSession.fromTag(tag);
        assertEquals(s, back);
        assertEquals(-30, back.chunkZ());
    }

    @Test
    public void emptyTagYieldsEmptySession() {
        ReconSession s = ReconSession.fromTag(new CompoundTag());
        assertNull(s.group());
        assertEquals(0, s.validSamples());
        assertFalse(s.converged());
        assertFalse(s.saturated());
    }

    @Test
    public void chunkSwitchResetsSession() {
        ReconSession s = ReconSession.fresh(1, 1);
        assertTrue(s.belongsTo(1, 1));
        assertFalse(s.belongsTo(2, 1));
    }
}
