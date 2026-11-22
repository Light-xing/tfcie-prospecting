package com.tfcie.prospecting.common.durability;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 独立工具耐久机制 —— <b>不复用原版耐久（damage）系统</b>。
 *
 * <h2>与原版耐久的区别</h2>
 * <ol>
 *   <li><b>存储独立</b>：磨损值存于自有 NBT 根 {@code tfcie_prospecting:tool_state}，
 *       绝不写原版 {@code Damage} 标签，也不调用 {@code ItemStack#hurtAndBreak}
 *       （后者会触发原版的「耗尽即消失 + 音效」语义）；</li>
 *   <li><b>上限可变</b>：耐久上限来自材质参数（同一物品、不同材质、各自独立上限），
 *       而非物品级的固定值；</li>
 *   <li><b>归零报废</b>：耐久耗尽时工具<b>不消失</b>，而是转入「报废」状态 ——
 *       保留在背包中、不可使用、可修复。</li>
 * </ol>
 *
 * <h2>状态模型</h2>
 * {@code wear}（已消耗耐久）自 0 增长到 {@code max}；{@code wear >= max} 即报废。
 * 剩余耐久 = {@code max - wear}。{@code max <= 0} 表示该工具不消耗耐久
 * （如感应探棒 / 磁力探针按设计为纯耗能或耗材型）。
 *
 * <p>{@link #apply}/{@link #repair}/{@link #remaining} 是不依赖游戏运行时的纯函数，
 * 因此该机制的核心行为可被单测直接覆盖。
 */
public final class ToolWear {

    /** 独立于原版 Damage 的 NBT 根键。 */
    public static final String ROOT = "tfcie_prospecting:tool_state";
    private static final String KEY_WEAR = "wear";
    private static final String KEY_BROKEN = "broken";

    /** 耐久状态（不可变）。 */
    public record State(int wear, boolean broken) {

        /** 全新状态：无磨损、未报废。 */
        public static final State PRISTINE = new State(0, false);
    }

    // ---------------- 纯函数（可脱离游戏运行时单测） ----------------

    /**
     * 施加磨损。磨损达到上限即转为报废 —— 工具保留，不消失。
     *
     * @param max 耐久上限；{@code <= 0} 表示不消耗耐久
     */
    public static State apply(State current, int amount, int max) {
        if (max <= 0 || amount <= 0 || current.broken()) {
            return current;
        }
        int next = current.wear() + amount;
        if (next >= max) {
            return new State(max, true);
        }
        return new State(next, false);
    }

    /** 修复：减少磨损；磨损回落到上限之下则解除报废。 */
    public static State repair(State current, int amount, int max) {
        if (max <= 0 || amount <= 0) {
            return current;
        }
        int next = Math.max(0, current.wear() - amount);
        return new State(next, next >= max);
    }

    /** 剩余耐久。 */
    public static int remaining(State state, int max) {
        if (max <= 0) {
            return 0;
        }
        return Math.max(0, max - state.wear());
    }

    // ---------------- NBT 桥接 ----------------

    /** 从 NBT 根读取状态；缺失即为全新。 */
    public static State fromTag(@Nullable CompoundTag root) {
        if (root == null || (!root.contains(KEY_WEAR) && !root.contains(KEY_BROKEN))) {
            return State.PRISTINE;
        }
        return new State(Math.max(0, root.getInt(KEY_WEAR)), root.getBoolean(KEY_BROKEN));
    }

    /** 状态写入 NBT 根。 */
    public static CompoundTag toTag(State state) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_WEAR, state.wear());
        tag.putBoolean(KEY_BROKEN, state.broken());
        return tag;
    }

    // ---------------- ItemStack 桥接 ----------------

    public static State read(ItemStack stack) {
        return fromTag(stack.getTagElement(ROOT));
    }

    public static void write(ItemStack stack, State state) {
        if (state.equals(State.PRISTINE)) {
            clear(stack);
            return;
        }
        stack.getOrCreateTag().put(ROOT, toTag(state));
    }

    public static boolean isBroken(ItemStack stack) {
        return read(stack).broken();
    }

    /** 清除耐久状态（回到全新）。 */
    public static void clear(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(ROOT);
        }
    }

    private ToolWear() {}
}
