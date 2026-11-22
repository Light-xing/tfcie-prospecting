package com.tfcie.prospecting.common.terrain;

import com.tfcie.prospecting.common.scan.ScanScheduler;
import com.tfcie.prospecting.network.ModNetwork;
import com.tfcie.prospecting.network.TerrainMapS2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端遥感任务的对外入口。
 *
 * <p>与 {@link com.tfcie.prospecting.common.survey.SurveyManager} 共用同一个
 * {@link ScanScheduler}：两者的扫描内容与产出结构截然不同，但「分 tick 推进、完成后交付」
 * 的调度契约完全一致，没有理由各写一份。这里只保留遥感独有的一件事 —— 完成后发什么包。
 *
 * <p>同一玩家同一时刻只允许一个遥感任务，避免连点叠加。
 */
public final class TerrainManager {

    private static final ScanScheduler<TerrainTask> SCHEDULER = new ScanScheduler<>((player, task) -> {
        final TerrainMap map = task.map();
        ModNetwork.sendTo(player, new TerrainMapS2CPacket(
                map.originX(), map.originZ(), map.chunks(), map));
        player.displayClientMessage(
                Component.translatable("message.tfcie_prospecting.terrain.done"), true);
    });

    private TerrainManager() {}

    /** @return {@code true} 表示已开始；{@code false} 表示该玩家已有任务在跑 */
    public static boolean start(ServerPlayer player, BlockPos origin, int chunkRadius, int chunksPerTick) {
        return SCHEDULER.start(player, new TerrainTask(player.level(), origin, chunkRadius, chunksPerTick));
    }

    /** 每 server tick 推进所有进行中的遥感扫描。 */
    public static void onServerTick(MinecraftServer server) {
        SCHEDULER.onServerTick(server);
    }
}
