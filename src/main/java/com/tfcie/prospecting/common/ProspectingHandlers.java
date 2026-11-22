package com.tfcie.prospecting.common;

import com.tfcie.prospecting.TfcieProspecting;
import com.tfcie.prospecting.common.command.ProspectCommand;
import com.tfcie.prospecting.common.prospecting.OrePropertyReloadListener;
import com.tfcie.prospecting.common.survey.SurveyManager;
import com.tfcie.prospecting.common.terrain.TerrainManager;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 服务端事件注册。
 *
 * <ul>
 *   <li><b>矿种物性表</b>挂进数据包重载链 —— 它是数据驱动的
 *       （{@code data/<ns>/prospecting/ore_properties/*.json}），须随数据包刷新；</li>
 *   <li>注册 {@code /prospect} 命令 —— 智能体调用接口的命令行通道；</li>
 *   <li>推进<b>探查与遥感任务</b> —— 分帧执行，避免集中扫描冻结服务端。</li>
 * </ul>
 *
 * <p>注：钻探已改为「对准实体方块右键即时取芯」，不再存在需要中断的长跑过程，
 * 因此原先的 {@code LivingHurtEvent} 中断处理器已移除。
 */
@Mod.EventBusSubscriber(modid = TfcieProspecting.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ProspectingHandlers {

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new OrePropertyReloadListener());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ProspectCommand.register(event.getDispatcher());
    }

    /** 探查 / 遥感任务分时推进。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            SurveyManager.onServerTick(event.getServer());
            TerrainManager.onServerTick(event.getServer());
        }
    }

    private ProspectingHandlers() {}
}
