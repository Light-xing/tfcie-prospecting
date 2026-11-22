package com.tfcie.prospecting.common.durability;

import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 独立耐久机制的核心行为（阶段 2 验收：默认参数物品耐久可耗尽 → 报废）。
 * 这些是不依赖游戏运行时的纯函数。
 */
public class ToolWearTest {

    private static final int MAX = 256;

    @Test
    public void pristineStateByDefault() {
        assertEquals(ToolWear.State.PRISTINE, ToolWear.fromTag(null));
        assertEquals(0, ToolWear.State.PRISTINE.wear());
        assertFalse(ToolWear.State.PRISTINE.broken());
    }

    @Test
    public void wearAccumulatesAndIsClampedAtMax() {
        ToolWear.State s = ToolWear.State.PRISTINE;
        s = ToolWear.apply(s, 100, MAX);
        assertEquals(100, s.wear());
        assertFalse(s.broken());

        s = ToolWear.apply(s, 100, MAX);
        assertEquals(200, s.wear());
        assertFalse(s.broken());

        // 越过上限：磨损被夹到上限并转入报废（工具不消失）
        s = ToolWear.apply(s, 100, MAX);
        assertEquals(MAX, s.wear());
        assertTrue(s.broken());
    }

    @Test
    public void reachingMaxExactlyScraps() {
        ToolWear.State s = ToolWear.apply(ToolWear.State.PRISTINE, MAX, MAX);
        assertEquals(MAX, s.wear());
        assertTrue(s.broken());
    }

    @Test
    public void scrappedToolStaysScrapped() {
        ToolWear.State scrap = new ToolWear.State(MAX, true);
        assertEquals(scrap, ToolWear.apply(scrap, 1, MAX));
    }

    @Test
    public void zeroMaxMeansNoDurabilityConsumption() {
        // 感应探棒 / 磁力探针：纯耗能或耗材型，不消耗耐久
        ToolWear.State s = ToolWear.apply(ToolWear.State.PRISTINE, 5, 0);
        assertEquals(ToolWear.State.PRISTINE, s);
    }

    @Test
    public void remainingIsClampedToZero() {
        assertEquals(256, ToolWear.remaining(new ToolWear.State(0, false), MAX));
        assertEquals(0, ToolWear.remaining(new ToolWear.State(MAX, true), MAX));
        assertEquals(0, ToolWear.remaining(new ToolWear.State(0, false), 0));
    }

    @Test
    public void repairRestoresAndClearsScrappedFlag() {
        ToolWear.State scrap = new ToolWear.State(MAX, true);
        ToolWear.State fixed = ToolWear.repair(scrap, 56, MAX);
        assertEquals(200, fixed.wear());
        assertFalse(fixed.broken());

        // 修复量不足时不解除报废
        ToolWear.State stillScrap = ToolWear.repair(scrap, 0, MAX);
        assertTrue(stillScrap.broken());
    }

    @Test
    public void tagRoundTrips() {
        ToolWear.State src = new ToolWear.State(42, false);
        CompoundTag tag = ToolWear.toTag(src);
        assertEquals(src, ToolWear.fromTag(tag));

        ToolWear.State scrap = new ToolWear.State(MAX, true);
        assertEquals(scrap, ToolWear.fromTag(ToolWear.toTag(scrap)));
    }

    @Test
    public void emptyTagIsPristine() {
        assertEquals(ToolWear.State.PRISTINE, ToolWear.fromTag(new CompoundTag()));
    }
}
