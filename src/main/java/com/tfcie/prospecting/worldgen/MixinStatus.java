package com.tfcie.prospecting.worldgen;

import com.tfcie.prospecting.TfcieProspecting;
import com.tfcie.prospecting.config.ProspectingConfig;

import java.lang.reflect.Method;

/**
 * 自检：确认 {@code VeinConfigMixin} 真的被 Mixin 装到了 TFC 的 {@code VeinConfig} 上。
 *
 * <p>放在 mixin 包之外是<b>硬性要求</b>：Mixin 把配置里声明的包视为「mixin 包」，
 * 任何类从外面直接引用该包内的类都会被拒（{@code IllegalClassLoadError: ... is in a defined
 * mixin package ... and cannot be referenced directly}）。所以自检类必须住在普通包里。
 *
 * <p>为什么需要这一步：mixin 没装上是<b>静默</b>失效——游戏照常启动、配置照常能改，
 * 只是区块里的小矿石照样生成。评估时这种沉默失败会被当成「开关没效果」，很难定位。
 * 所以这里用反射看一眼目标类上有没有本模组注入的处理器方法：
 * 有就一切正常，没有就留一条 ERROR（连同 jar 清单/开发期启动参数的排查提示）。
 */
public final class MixinStatus {

    private static final String TARGET_CLASS = "net.dries007.tfc.world.feature.vein.VeinConfig";
    private static final String HANDLER_METHOD = "tfcie_prospecting$suppressSmallOreIndicators";

    private static boolean checked;

    public static void verify() {
        if (checked) {
            return;
        }
        checked = true;
        try {
            // 只加载、不初始化：避免在世界生成注册表还没就绪时碰 TFC 的静态初始化。
            final Class<?> target = Class.forName(TARGET_CLASS, false, MixinStatus.class.getClassLoader());
            boolean applied = false;
            for (Method method : target.getDeclaredMethods()) {
                if (HANDLER_METHOD.equals(method.getName())) {
                    applied = true;
                    break;
                }
            }
            final boolean requested = ProspectingConfig.suppressSmallOreIndicators();
            if (applied) {
                if (requested) {
                    TfcieProspecting.LOGGER.info("[tfcie_prospecting] worldgen.disable_small_ore_indicators=true："
                            + "新生成的区块不再出现 tfc:ore/small_* 小矿石指示块（矿脉本体保留）");
                } else {
                    TfcieProspecting.LOGGER.debug("[tfcie_prospecting] VeinConfig mixin 已生效"
                            + "（worldgen.disable_small_ore_indicators 可用，当前关闭）");
                }
            } else if (requested) {
                // 开关开着却没装上——这一定得喊出来，否则评估时会被当成「开关没效果」。
                TfcieProspecting.LOGGER.error("[tfcie_prospecting] 已启用 worldgen.disable_small_ore_indicators，"
                        + "但 VeinConfig mixin 未生效，开关不会有任何作用。排查：发行 jar 的 MANIFEST 是否带"
                        + " MixinConfigs: tfcie_prospecting.mixins.json；开发运行是否带了"
                        + " --mixin.config tfcie_prospecting.mixins.json");
            } else {
                TfcieProspecting.LOGGER.debug("[tfcie_prospecting] VeinConfig mixin 未生效"
                        + "（开关当前关闭，不影响游戏）");
            }
        } catch (Throwable throwable) {
            TfcieProspecting.LOGGER.warn("[tfcie_prospecting] 无法确认 VeinConfig mixin 状态：{}", throwable.toString());
        }
    }

    private MixinStatus() {
    }
}
