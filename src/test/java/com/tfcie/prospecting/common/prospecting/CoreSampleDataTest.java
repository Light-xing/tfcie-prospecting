package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.enums.TerminatedBy;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 钻探样本的数据结构与 NBT 往返（阶段 5）。 */
public class CoreSampleDataTest {

    private static CoreSampleData sample() {
        return new CoreSampleData(
                new BlockPos(120, 74, -330),
                ResourceLocation.withDefaultNamespace("overworld"),
                74, 57, 17,
                TerminatedBy.MAX_DEPTH,
                0.92D,
                List.of(
                        new CoreSampleData.Layer("tfc:dirt", "泥土", 5, 74, 70, true),
                        new CoreSampleData.Layer("tfc:rock/diorite", "闪长岩", 10, 69, 60, true),
                        new CoreSampleData.Layer("tfc:dirt", "泥土", 3, 59, 57, false)),
                List.of(new CoreSampleData.OreHit("tfc:ore/native_copper/granite", "自然铜", 62, 2)),
                "泥土 5 闪长岩 10 泥土 3",
                1700000000L);
    }

    @Test
    public void tagRoundTrips() {
        final CoreSampleData src = sample();
        final CoreSampleData back = CoreSampleData.fromTag(src.toTag());

        assertEquals(src.origin(), back.origin());
        assertEquals(src.dimension(), back.dimension());
        assertEquals(src.startY(), back.startY());
        assertEquals(src.endY(), back.endY());
        assertEquals(src.depth(), back.depth());
        assertEquals(src.terminatedBy(), back.terminatedBy());
        assertEquals(src.recoveryRate(), back.recoveryRate(), 1.0E-9D);
        assertEquals(src.summary(), back.summary());
        assertEquals(src.recordedAt(), back.recordedAt());
        assertEquals(src.runs().size(), back.runs().size());
        assertEquals(src.oreHits().size(), back.oreHits().size());
    }

    @Test
    public void adjacentSameBlocksStaySeparateLayersWhenNotAdjacent() {
        // 设计文档示例里「泥土」出现两次（y74→y70 与 y59→y57），
        // 说明只有相邻同类才合并；两个泥土段必须各自保留
        final CoreSampleData data = sample();
        assertEquals(3, data.runs().size());
        assertEquals("泥土", data.runs().get(0).display());
        assertEquals("泥土", data.runs().get(2).display());
        assertEquals(5, data.runs().get(0).thickness());
        assertEquals(3, data.runs().get(2).thickness());
    }

    @Test
    public void uncoredLayersAreFlagged() {
        final CoreSampleData data = sample();
        assertTrue(data.runs().get(0).cored());
        assertTrue(data.runs().get(1).cored());
        assertFalse("未取芯的段应被标记", data.runs().get(2).cored());
        assertTrue(data.hasUncored());

        // 标记必须经过 NBT 往返保留
        assertFalse(CoreSampleData.fromTag(data.toTag()).runs().get(2).cored());
    }

    @Test
    public void oreLayersAreCounted() {
        assertEquals(1, sample().oreLayerCount());
        assertFalse(CoreSampleData.empty().oreLayerCount() > 0);
    }

    @Test
    public void emptySampleIsSafe() {
        final CoreSampleData empty = CoreSampleData.empty();
        assertTrue(empty.runs().isEmpty());
        assertTrue(empty.oreHits().isEmpty());
        assertFalse(empty.hasUncored());
        // 空样本也必须能安全往返
        final CoreSampleData back = CoreSampleData.fromTag(empty.toTag());
        assertEquals(empty.depth(), back.depth());
        assertEquals(TerminatedBy.MAX_DEPTH, back.terminatedBy());
    }

    @Test
    public void unknownTerminationFallsBackSafely() {
        final CompoundTag tag = sample().toTag();
        tag.putString("terminated_by", "not_a_real_reason");
        assertEquals(TerminatedBy.MAX_DEPTH, CoreSampleData.fromTag(tag).terminatedBy());
    }
}
