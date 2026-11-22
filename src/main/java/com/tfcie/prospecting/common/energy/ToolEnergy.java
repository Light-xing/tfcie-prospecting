package com.tfcie.prospecting.common.energy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 工具的内置能量缓冲 —— 与 {@link com.tfcie.prospecting.common.durability.ToolWear} <b>同构</b>。
 *
 * <h2>为什么自建缓冲而不是直接接 IE 的 FE</h2>
 * 本阶段的范围决策（D3）是「预留能量抽象接口，本次不接沉浸工程 FE」。因此这里只做
 * <b>存储与收支</b>这一层纯粹的东西：容量来自材质参数，存量存于自有 NBT 根。
 * 真正的 FE 对接只需在其上再挂一个 Capability 适配器（把 {@code receive/extract}
 * 转发到本类），本类无需改动。
 *
 * <h2>三层结构与 ToolWear 对齐</h2>
 * <ol>
 *   <li><b>纯函数</b>：{@link #receive}/{@link #extract}/{@link #canExtract} 不依赖游戏运行时，
 *       可被单测直接覆盖；</li>
 *   <li><b>NBT 桥接</b>：{@link #fromTag}/{@link #toTag}；</li>
 *   <li><b>ItemStack 桥接</b>：{@link #read}/{@link #write}/{@link #clear}。</li>
 * </ol>
 *
 * <p>状态里只存 {@code stored}，<b>不存容量</b>：容量属于材质参数（同一件物品换个材质就换容量），
 * 存进物品 NBT 会让「改材质」变成脏数据迁移。
 */
public final class ToolEnergy {

    /** 独立于任何外部能量系统的 NBT 根键。 */
    public static final String ROOT = "tfcie_prospecting:tool_energy";
    private static final String KEY_STORED = "stored";

    /** 能量状态（不可变）。 */
    public record State(int stored) {

        /** 空缓冲。 */
        public static final State EMPTY = new State(0);
    }

    // ---------------- 纯函数（可脱离游戏运行时单测） ----------------

    /** 充能：不超过容量，返回新状态。 */
    public static State receive(State current, int amount, int capacity) {
        if (amount <= 0 || capacity <= 0) {
            return current;
        }
        final int next = Math.min(capacity, current.stored() + amount);
        return next == current.stored() ? current : new State(next);
    }

    /** 放电：不低于 0。不足时<b>原样返回</b>，调用方应以 {@link #canExtract} 先探。 */
    public static State extract(State current, int amount) {
        if (amount <= 0 || current.stored() < amount) {
            return current;
        }
        return new State(current.stored() - amount);
    }

    /** 是否够放电 {@code amount}。 */
    public static boolean canExtract(State current, int amount) {
        return amount <= 0 || current.stored() >= amount;
    }

    /** 存量。 */
    public static int stored(State current) {
        return current.stored();
    }

    /** 是否为空。 */
    public static boolean isEmpty(State current) {
        return current.stored() <= 0;
    }

    // ---------------- NBT 桥接 ----------------

    /** 从 NBT 根读取；缺失即为空缓冲。 */
    public static State fromTag(@Nullable CompoundTag root) {
        if (root == null || !root.contains(KEY_STORED)) {
            return State.EMPTY;
        }
        return new State(Math.max(0, root.getInt(KEY_STORED)));
    }

    /** 状态写入 NBT 根。 */
    public static CompoundTag toTag(State state) {
        final CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_STORED, state.stored());
        return tag;
    }

    // ---------------- ItemStack 桥接 ----------------

    public static State read(ItemStack stack) {
        return fromTag(stack.getTagElement(ROOT));
    }

    public static void write(ItemStack stack, State state) {
        if (state.equals(State.EMPTY)) {
            clear(stack);   // 空缓冲不占 NBT，避免每个物品都挂一个 0
            return;
        }
        stack.getOrCreateTag().put(ROOT, toTag(state));
    }

    /** 清除能量状态（回到空缓冲）。 */
    public static void clear(ItemStack stack) {
        final CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(ROOT);
        }
    }

    private ToolEnergy() {}
}
