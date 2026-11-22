package com.tfcie.prospecting.common.energy;

import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** 能量缓冲的纯逻辑与 NBT 桥接（阶段 7 收尾：为所有工具留出电力接口）。 */
public class ToolEnergyTest {

    private static final int CAP = 1000;

    @Test
    public void emptyIsZero() {
        assertEquals(0, ToolEnergy.State.EMPTY.stored());
        assertTrue(ToolEnergy.isEmpty(ToolEnergy.State.EMPTY));
    }

    @Test
    public void receiveAccumulates() {
        ToolEnergy.State s = ToolEnergy.receive(ToolEnergy.State.EMPTY, 400, CAP);
        assertEquals(400, s.stored());
        s = ToolEnergy.receive(s, 300, CAP);
        assertEquals(700, s.stored());
    }

    @Test
    public void receiveClampsAtCapacity() {
        final ToolEnergy.State s = ToolEnergy.receive(new ToolEnergy.State(900), 500, CAP);
        assertEquals(CAP, s.stored());
    }

    @Test
    public void receiveOnZeroCapacityDoesNothing() {
        // capacity <= 0 表示该工具不储能，任何充能都应被忽略
        assertSame(ToolEnergy.State.EMPTY, ToolEnergy.receive(ToolEnergy.State.EMPTY, 500, 0));
    }

    @Test
    public void receiveIgnoresNonPositiveAmount() {
        final ToolEnergy.State s = new ToolEnergy.State(100);
        assertSame(s, ToolEnergy.receive(s, 0, CAP));
        assertSame(s, ToolEnergy.receive(s, -50, CAP));
    }

    @Test
    public void receiveReturnsSameInstanceWhenAlreadyFull() {
        // 已满时不应产生新对象，避免无谓的 NBT 写入
        final ToolEnergy.State full = new ToolEnergy.State(CAP);
        assertSame(full, ToolEnergy.receive(full, 100, CAP));
    }

    @Test
    public void extractReducesStored() {
        assertEquals(300, ToolEnergy.extract(new ToolEnergy.State(500), 200).stored());
    }

    @Test
    public void extractBeyondStoredIsRefusedNotClamped() {
        // 关键语义：不足时「原样返回」而不是扣到 0 —— 否则调用方无法区分
        // 「付得起」与「付不起」，会白送一次使用
        final ToolEnergy.State s = new ToolEnergy.State(100);
        assertSame(s, ToolEnergy.extract(s, 101));
    }

    @Test
    public void canExtractMatchesAvailability() {
        final ToolEnergy.State s = new ToolEnergy.State(100);
        assertTrue(ToolEnergy.canExtract(s, 100));
        assertTrue(ToolEnergy.canExtract(s, 0));
        assertFalse(ToolEnergy.canExtract(s, 101));
    }

    @Test
    public void extractExactAmountLeavesEmpty() {
        final ToolEnergy.State s = ToolEnergy.extract(new ToolEnergy.State(100), 100);
        assertEquals(0, s.stored());
        assertTrue(ToolEnergy.isEmpty(s));
    }

    // ---------------- NBT 往返 ----------------

    @Test
    public void nbtRoundTrip() {
        final ToolEnergy.State original = new ToolEnergy.State(1234);
        assertEquals(original, ToolEnergy.fromTag(ToolEnergy.toTag(original)));
    }

    @Test
    public void emptyIsTheRoundTripOfNothing() {
        assertEquals(ToolEnergy.State.EMPTY, ToolEnergy.fromTag(null));
        assertEquals(ToolEnergy.State.EMPTY, ToolEnergy.fromTag(new CompoundTag()));
    }

    @Test
    public void negativeStoredInTagIsClamped() {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("stored", -500);
        assertEquals(0, ToolEnergy.fromTag(tag).stored());
    }
}
