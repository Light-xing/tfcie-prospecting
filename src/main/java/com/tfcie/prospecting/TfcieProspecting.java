package com.tfcie.prospecting;

import com.mojang.logging.LogUtils;
import com.tfcie.prospecting.config.ProspectingConfig;
import com.tfcie.prospecting.registry.ModBlocks;
import com.tfcie.prospecting.registry.ModCreativeTabs;
import com.tfcie.prospecting.registry.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * 勘探工具附属模组（TFC × 沉浸工程）主类。
 *
 * <p>mod_id = {@code tfcie_prospecting}；范围与阶段划分见《勘探工具开发计划.md》。
 */
@Mod(TfcieProspecting.MOD_ID)
public final class TfcieProspecting {

    public static final String MOD_ID = "tfcie_prospecting";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TfcieProspecting() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();

        ModBlocks.BLOCKS.register(bus);
        ModItems.ITEMS.register(bus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(bus);
        ProspectingConfig.init();

        // 探查地图需要把上万点的结果从服务端送到客户端，本项目首个网络通道
        com.tfcie.prospecting.network.ModNetwork.register();

        // 若 numen 在场，把勘探工具挂成它的 NumenTool（软探测，缺席即跳过）。
        // 放在构造期是安全的：ToolRegistry 是静态注册表，且 numen 明确要求「mod init 期间注册」。
        com.tfcie.prospecting.compat.numen.NumenBridge.registerIfPresent();

        // 同一时机登记「同伴出生自动补工具」插件：/prospect kit 是手动通道，这条是开局自动通道。
        com.tfcie.prospecting.compat.numen.NumenSpawnKit.registerIfPresent();

        // 世界生成开关（禁用 TFC 小矿石指示块）是 mixin 实现的：装上/没装上都由这条自检留痕，
        // 免得开关静默失效。
        // 注意：自检不能挂在公共初始化（FMLCommonSetupEvent）里——那会在客户端启动阶段就
        // Class.forName 加载 TFC 的世界生成类（并顺带让 Mixin 在此时改类），是启动路径上
        // 唯一的额外耦合。改到服务端启动完成：此时世界生成注册表已解析，判断依然准确，
        // 而客户端启动完全不被触碰。
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.server.ServerStartedEvent event) ->
                        com.tfcie.prospecting.worldgen.MixinStatus.verify());
    }

    public static ResourceLocation id(String name) {
        return new ResourceLocation(MOD_ID, name);
    }
}
