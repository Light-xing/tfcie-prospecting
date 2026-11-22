package com.tfcie.prospecting.common.prospecting;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 流体判定的唯一入口：<b>一律走流体 Tag</b>，不用 {@code Fluids.WATER} 这类实例比较。
 *
 * <h2>为什么必须用 Tag</h2>
 * TFC 的水不止一种：{@code tfc:river_water}、{@code tfc:salt_water}、{@code tfc:spring_water}
 * 都是独立流体，但都在 {@code #minecraft:water} 流体 tag 里。用 {@code == Fluids.WATER}
 * 实例比较时它们全部漏判 —— 密度探锤把 TFC 河水当实心地面、感应探棒看不见河流海洋
 * （报告 18 §1 的 4b、§2.3）。改 Tag 后四种水一并识别，两件仪器共用这里的判据。
 *
 * <h2>盐水单独判</h2>
 * 海水/地下咸水在 {@code #minecraft:water} 里，物理上导电却不应被当成「矿」：
 * 感应探棒给它低档<b>正</b>响应并标干扰，而不是静默按 0 处理（报告 18 §P1 第 3 条）。
 */
final class ProspectingFluids {

    private ProspectingFluids() {}

    /** 是否为水（含 TFC 的河流 / 海洋 / 温泉水）。 */
    static boolean isWater(FluidState fluid) {
        return fluid.is(FluidTags.WATER);
    }

    static boolean isWater(BlockState state) {
        return isWater(state.getFluidState());
    }

    /** 是否为岩浆。 */
    static boolean isLava(FluidState fluid) {
        return fluid.is(FluidTags.LAVA);
    }

    static boolean isLava(BlockState state) {
        return isLava(state.getFluidState());
    }

    /** 是否为水或岩浆（密度探锤的「流体低密度」判据）。 */
    static boolean isWaterOrLava(BlockState state) {
        return isWater(state) || isLava(state);
    }

    /** 是否为盐水（海水 / 咸水）：靠流体注册名的 {@code salt_water} 识别，含流动形态。 */
    static boolean isSaltWater(BlockState state) {
        final FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) {
            return false;
        }
        final ResourceLocation key = ForgeRegistries.FLUIDS.getKey(fluid.getType());
        return key != null && key.getPath().contains("salt_water");
    }
}
