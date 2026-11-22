package com.tfcie.prospecting.common.survey;

import com.tfcie.prospecting.common.scan.ScanScheduler;
import com.tfcie.prospecting.network.ModNetwork;
import com.tfcie.prospecting.network.SurveyMapS2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端探查任务的对外入口。
 *
 * <p>「按玩家登记、分 tick 推进、丢弃断线玩家、完成后交付」这套调度统一由
 * {@link ScanScheduler} 负责；这里只保留探查独有的一件事 —— <b>完成后发什么包、提示什么话</b>。
 *
 * <p>每个玩家同一时刻只允许一个进行中的探查，避免连点造成多份二十万次扫描叠加。
 */
public final class SurveyManager {

    private static final ScanScheduler<SurveyTask> SCHEDULER = new ScanScheduler<>((player, task) -> {
        final SurveyMap map = task.map();
        ModNetwork.sendTo(player, new SurveyMapS2CPacket(
                map.originX(), map.originZ(), map.chunks(),
                task.originX(), task.originZ(),
                task.filter().toolId(), map.points()));
        player.displayClientMessage(
                Component.translatable("message.tfcie_prospecting.survey.done"), true);
    });

    private SurveyManager() {}

    /** @return {@code true} 表示已开始；{@code false} 表示该玩家已有任务在进行中 */
    public static boolean start(ServerPlayer player, BlockPos origin, SurveyFilter filter, SurveyConfig config) {
        return SCHEDULER.start(player, new SurveyTask(player, origin, filter, config));
    }

    /** 每 server tick 推进所有进行中的探查。 */
    public static void onServerTick(MinecraftServer server) {
        SCHEDULER.onServerTick(server);
    }
}
