package com.tfcie.prospecting.common.survey;

import com.tfcie.prospecting.common.scan.ServerScanTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一次探查任务：<b>分时执行</b>——每个 server tick 只扫一个区块。
 *
 * <h2>为什么要分时</h2>
 * 默认 9×9 区块 = 144×144 = 20,736 列，且<b>扫描世界全高</b>（主世界 384 格）。
 * 按垂向步长 2 计，每区块要 16×16×192 ≈ 4.9 万次 {@code getBlockState}；
 * 若单 tick 内把 81 个区块全做完，就是约 400 万次查询集中在一帧，服务端会僵住。
 * 按每 tick 一区块摊开后，单 tick 仅约 4.9 万次，**基本无感**，
 * 81 tick（约 4 秒）即可完成。
 *
 * <p>这与 GT 的 {@code ProspectorMapHandler#scanOres}（{@code chunkIndex++}，
 * 每 tick 一区块）是同一套做法。
 *
 * <h2>两个边界处理</h2>
 * <ul>
 *   <li><b>未加载区块</b>：用 {@code hasChunk} 判定，不强制加载；未加载的区块在地图上留空；</li>
 *   <li><b>地表以上的空气</b>：逐列跳过「入地之前」的空气，否则地表之上的空气会被
 *       当成空腔，把整张图涂满。</li>
 * </ul>
 */
public final class SurveyTask implements ServerScanTask {

    private final ServerPlayer player;
    private final Level level;
    private final ChunkPos centerChunk;
    private final int originX;
    private final int originZ;
    private final int originY;
    private final SurveyFilter filter;
    private final SurveyConfig config;
    private final SurveyMap map;
    private final int diameter;
    private final int minY;

    private int chunkIndex;

    public SurveyTask(ServerPlayer player, BlockPos origin, SurveyFilter filter, SurveyConfig config) {
        this.player = player;
        this.level = player.level();
        this.centerChunk = new ChunkPos(origin);
        this.originX = origin.getX();
        this.originZ = origin.getZ();
        this.originY = origin.getY();
        this.filter = filter;
        this.config = config;
        this.diameter = config.diameterChunks();
        this.minY = level.getMinBuildHeight();

        final int radius = config.chunkRadius() - 1;
        this.map = new SurveyMap(
                (centerChunk.x - radius) * 16,
                (centerChunk.z - radius) * 16,
                diameter,
                originX, originZ);
    }

    /** 探测点 X（玩家右键的位置）。 */
    public int originX() {
        return originX;
    }

    /** 探测点 Z（玩家右键的位置）。 */
    public int originZ() {
        return originZ;
    }

    public ServerPlayer player() {
        return player;
    }

    public SurveyFilter filter() {
        return filter;
    }

    public SurveyMap map() {
        return map;
    }

    /**
     * 推进一个区块。
     *
     * @return {@code true} 表示整张地图扫描完成
     */
    @Override
    public boolean tick() {
        if (chunkIndex >= diameter * diameter) {
            return true;
        }
        final int row = chunkIndex / diameter;
        final int column = chunkIndex % diameter;
        scanChunk(
                centerChunk.x + column - (config.chunkRadius() - 1),
                centerChunk.z + row - (config.chunkRadius() - 1));
        chunkIndex++;
        return chunkIndex >= diameter * diameter;
    }

    private void scanChunk(int chunkX, int chunkZ) {
        if (!level.hasChunk(chunkX, chunkZ)) {
            return;   // 未加载：不强制加载，地图该区块留空
        }

        final int baseX = chunkX << 4;
        final int baseZ = chunkZ << 4;
        final int stride = config.verticalStride();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // 全深度：自世界顶部扫到世界底部；否则自探测点向下扫描 depthBelow 格
        final int topY = config.fullDepth() ? level.getMaxBuildHeight() - 1 : originY;
        final int bottomY = config.fullDepth()
                ? minY
                : Math.max(minY, originY - config.depthBelow());

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                final int x = baseX + dx;
                final int z = baseZ + dz;
                boolean entered = false;

                for (int y = topY; y >= bottomY; y -= stride) {
                    cursor.set(x, y, z);
                    final BlockState state = level.getBlockState(cursor);

                    if (!entered) {
                        if (state.isAir()) {
                            continue;   // 跳过入地之前的空气
                        }
                        entered = true;
                    }

                    final String key = filter.keyOf(state);
                    if (key != null) {
                        map.add(new SurveyPoint(x, z, key, OreColors.of(key)));
                    }
                }
            }
        }
    }
}
