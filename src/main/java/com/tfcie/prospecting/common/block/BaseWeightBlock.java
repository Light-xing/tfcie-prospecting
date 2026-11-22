package com.tfcie.prospecting.common.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * 基点砝码：密度探锤（重力法）的校准基准。
 *
 * <p>设计文档 T2 的成本机制：探锤须先放置基点砝码完成一次校准；连续使用会累积
 * <b>漂移</b>（每次 +{@code drift_per_use} 格），漂移使读数整体偏移，累计
 * {@code drift_reset_after_uses} 次后须返回基点复测才能继续可信读数
 * —— 对应现实中重力仪的零点漂移与基点重复测量。
 *
 * <p>本方块不保存状态（校准记录存于探锤自身的 NBT），故无需 BlockEntity：
 * 「在基点复测」＝在基点砝码附近使用探锤。
 */
public final class BaseWeightBlock extends Block {

    public static final String ID = "base_weight";

    /** 认定「处于基点」的水平半径（格）。 */
    public static final int BASE_RADIUS = 4;

    public BaseWeightBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .sound(SoundType.METAL)
                .strength(3.5F, 6.0F)
                .requiresCorrectToolForDrops());
    }
}
