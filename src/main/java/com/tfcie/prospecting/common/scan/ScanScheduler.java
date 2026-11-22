package com.tfcie.prospecting.common.scan;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * 服务端扫描任务的调度器：按玩家登记、分 tick 推进、完成后回调解交付。
 *
 * <h2>为什么收拢到这里</h2>
 * 原先 {@code SurveyManager} 与 {@code TerrainManager} 各写了一份<b>逐行相同</b>的
 * 「遍历 → 丢弃断线玩家 → 推进任务 → 完成即移除并下发」逻辑，
 * 两处唯一的差别只是「构造哪种任务」与「完成后发什么包」。
 *
 * <p>后者由调用方保留（构造与完成回调各自传进来），其余调度细节收拢至此 ——
 * 泛型参数只有一个、内部零分支，抽象成本远低于维护两份迭代与清理逻辑。
 *
 * <p>同一玩家同一时刻只允许一个任务，避免连点叠加。
 *
 * @param <T> 具体的扫描任务类型
 */
public final class ScanScheduler<T extends ServerScanTask> {

    private final Map<UUID, T> active = new HashMap<>();
    private final BiConsumer<ServerPlayer, T> onComplete;

    /** @param onComplete 任务完成时执行（下发结果 + 提示玩家），在服务端主线程调用 */
    public ScanScheduler(BiConsumer<ServerPlayer, T> onComplete) {
        this.onComplete = onComplete;
    }

    /**
     * 为玩家登记一个任务。
     *
     * @return {@code true} 表示已开始；{@code false} 表示该玩家已有任务在跑
     */
    public boolean start(ServerPlayer player, T task) {
        final UUID id = player.getUUID();
        if (active.containsKey(id)) {
            return false;
        }
        active.put(id, task);
        return true;
    }

    /** 每 server tick 推进所有任务；玩家已离线则直接丢弃该任务。 */
    public void onServerTick(MinecraftServer server) {
        if (active.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<UUID, T>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, T> entry = iterator.next();
            final ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.hasDisconnected()) {
                iterator.remove();
                continue;
            }

            final T task = entry.getValue();
            if (!task.tick()) {
                continue;
            }

            iterator.remove();
            onComplete.accept(player, task);
        }
    }
}
