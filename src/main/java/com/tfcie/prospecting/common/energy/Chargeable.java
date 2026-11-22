package com.tfcie.prospecting.common.energy;

import net.minecraft.world.item.ItemStack;

/**
 * 可充能工具的<b>统一能量契约</b> —— 为接入沉浸工程 FE 预留的接口面。
 *
 * <h2>为什么要有这一层</h2>
 * 若各工具直接读写 {@link ToolEnergy}，将来接 FE 时就得逐件工具去改。有了本接口，
 * 所有工具只认这四个方法，FE 对接只需写好一个 Capability 适配器：
 *
 * <pre>
 * // 未来接 IE / Forge Energy 时的适配器骨架（本次不实现）
 * public final class FeAdapter implements IEnergyStorage {
 *     private final ItemStack stack;
 *     private final Chargeable tool;
 *     public int receiveEnergy(int maxReceive, boolean simulate) {
 *         return tool.receiveEnergy(stack, maxReceive, simulate);
 *     }
 *     ...
 * }
 * </pre>
 *
 * <h2>本次的默认实现</h2>
 * {@link com.tfcie.prospecting.common.item.AbstractProspectingItem} 直接以
 * {@link ToolEnergy}（自有 NBT 缓冲）实现本接口。容量来自材质参数
 * （{@code ToolParameters#energyCapacity()}），因此同一件工具换材质即换容量。
 *
 * <h2>强制与否由配置决定</h2>
 * 本次默认<b>不强制</b>耗能（{@code power.enabled = false}），工具照常可用；
 * 打开开关后，耗能工具在能量不足时会拒绝使用。这样接口现在就能接好，
 * 而不会在没有充能手段的现阶段把工具锁死。
 */
public interface Chargeable {

    /** 能量上限；{@code <= 0} 表示该工具不储能、也不耗能。 */
    int energyCapacity(ItemStack stack);

    /** 当前存量。 */
    int energyStored(ItemStack stack);

    /** 剩余可放出的能量。 */
    default int energyRemaining(ItemStack stack) {
        return Math.max(0, energyCapacity(stack) - energyStored(stack));
    }

    /** 是否够放出 {@code amount}（{@code amount <= 0} 恒为真）。 */
    default boolean hasEnergy(ItemStack stack, int amount) {
        return amount <= 0 || energyStored(stack) >= amount;
    }

    /**
     * 充能。
     *
     * @param simulate 为真时只试算、不写入
     * @return 实际接受的量
     */
    int receiveEnergy(ItemStack stack, int amount, boolean simulate);

    /**
     * 放电。
     *
     * @param simulate 为真时只试算、不写入
     * @return 实际放出的量
     */
    int extractEnergy(ItemStack stack, int amount, boolean simulate);
}
