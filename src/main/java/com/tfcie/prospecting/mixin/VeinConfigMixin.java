package com.tfcie.prospecting.mixin;

import com.tfcie.prospecting.config.ProspectingConfig;
import net.dries007.tfc.world.feature.vein.Indicator;
import net.dries007.tfc.world.feature.vein.VeinConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * 评估开关：让 TFC 矿脉不再生成小矿石指示块（{@code tfc:ore/small_*}）。
 *
 * <p>TFC 的矿脉在世界生成时经 {@code IVeinConfig#indicator()} → {@code VeinConfig#indicator()}
 * 取得指示物配置，随后在矿体附近的地表/地下放小矿石。这里在<b>取配置的那一口</b>上拦截：
 * 开关打开且该矿脉的指示物确实是矿石粒时，返回 {@link Optional#empty()}，于是
 * {@code VeinFeature#place} 看到 {@code indicator == null} 就整段跳过——矿脉本体照常生成，
 * 只是不再放指示块，等价于把矿脉 JSON 里的 {@code indicator} 字段去掉。
 *
 * <p>只按「指示物是小矿石」过滤，不是按矿脉名单过滤：高岭土这类拿植物当指示的矿脉不受影响。
 * 之所以不直接改数据包 / 注册表：configured feature 注册表在数据包载入后即冻结，
 * 而记录类 {@code VeinConfig} 不可重建；拦截读取点是唯一既能由 config 控制、
 * 又不需要复制 19 份矿脉 JSON（TFC 一改数据就失配）的位置。
 *
 * <p>不影响已生成的区块：世界生成只对新区块调用这里。
 */
@Mixin(VeinConfig.class)
public abstract class VeinConfigMixin {

    @Inject(method = "indicator", at = @At("RETURN"), cancellable = true)
    private void tfcie_prospecting$suppressSmallOreIndicators(CallbackInfoReturnable<Optional<Indicator>> cir) {
        if (!ProspectingConfig.suppressSmallOreIndicators()) {
            return;
        }
        final Optional<Indicator> indicator = cir.getReturnValue();
        if (indicator == null || indicator.isEmpty()) {
            return;
        }
        for (BlockState state : indicator.get().states().values()) {
            final ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            // 群峦的小矿石块统一叫 tfc:ore/small_<矿种>；其余指示物（例如高岭土的血百合）不在此列。
            if ("tfc".equals(id.getNamespace()) && id.getPath().startsWith("ore/small_")) {
                cir.setReturnValue(Optional.empty());
                return;
            }
        }
    }
}
