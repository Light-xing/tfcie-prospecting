package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.ProspectingResult;
import com.tfcie.prospecting.api.enums.TerminatedBy;
import com.tfcie.prospecting.api.telemetry.ProspectLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 钻探取样器的产物策略（报告 18 §1 第 6 条）：空柱不产物品、满包前置拒绝、
 * prune 只删空样本、失败分类为 inventory_full。
 *
 * <p>纯决策级单测：真实背包/物品插入需要游戏运行时，这里验证被分支使用的纯函数；
 * 接线本身由编译期与游戏内验证覆盖（见任务报告）。
 */
public class CoreSamplePolicyTest {

    private static CoreSampleData core(boolean oreBearing) {
        final List<CoreSampleData.Layer> runs = List.of(
                new CoreSampleData.Layer("tfc:rock/raw/granite", "花岗岩", 8, 64, 57, true));
        final List<CoreSampleData.OreHit> hits = oreBearing
                ? List.of(new CoreSampleData.OreHit("tfc:ore/magnetite/granite", "磁铁矿", 60, 2))
                : List.of();
        return new CoreSampleData(new BlockPos(10, 64, -3),
                ResourceLocation.withDefaultNamespace("overworld"),
                64, 56, 8, TerminatedBy.MAX_DEPTH, 1.0D, runs, hits,
                "花岗岩 8", 1L);
    }

    @Test
    public void emptyCoreProducesNoItemButKeepsReadout() {
        final CoreSampleData empty = core(false);
        assertEquals(0, empty.oreLayerCount());
        assertFalse(CoreSamplerProspector.producesSample(empty));
        assertEquals(CoreSamplerProspector.SampleDecision.EMPTY_CORE,
                CoreSamplerProspector.decideSample(empty, true));
        // 读数本身照常带着完整层序（空柱只是不产实物样本）
        assertEquals(1, empty.runs().size());
        assertEquals(8, empty.depth());
    }

    @Test
    public void fullInventoryRejectsEvenWhenOreIsPresent() {
        assertEquals(CoreSamplerProspector.SampleDecision.INVENTORY_FULL,
                CoreSamplerProspector.decideSample(core(true), false));
    }

    @Test
    public void oreWithRoomIsStored() {
        assertEquals(CoreSamplerProspector.SampleDecision.STORE,
                CoreSamplerProspector.decideSample(core(true), true));
    }

    @Test
    public void pruneKeepsOreBearingSamplesOnly() {
        final List<CoreSampleData> slots = List.of(core(true), core(false), core(false), core(true));
        final List<CoreSampleData> kept = slots.stream()
                .filter(CoreSamplerProspector::producesSample)
                .toList();
        assertEquals(2, kept.size());
        assertTrue(kept.stream().allMatch(data -> data.oreLayerCount() > 0));
    }

    @Test
    public void inventoryFullFailureClassifiesAsInventoryFull() {
        final ProspectingResult failure = ProspectingResult.fail("core_sampler",
                "背包已满，无法存放钻探样本；请先调用 prospect.instrument.prune_samples() 或 numen.inv.destroy 清理空位");
        // classify 在满包分支提前返回，不需要玩家对象
        assertEquals("inventory_full", ProspectLedger.classify(null, "core_sampler", failure));
    }
}
