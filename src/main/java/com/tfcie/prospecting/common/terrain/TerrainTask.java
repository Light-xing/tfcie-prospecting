package com.tfcie.prospecting.common.terrain;

import com.tfcie.prospecting.common.scan.ServerScanTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * 一次遥感扫描任务：<b>分帧执行</b>，每 tick 扫若干个区块的地表。
 *
 * <h2>为什么比探查地图轻得多</h2>
 * 探查地图要逐格垂向扫描（约 400 万次方块查询），而地形只需要每列两项：
 * <ul>
 *   <li><b>地表高度</b> —— 走区块自带的高度图（{@link Heightmap}），几乎零成本；</li>
 *   <li><b>地表颜色</b> —— 在高度处取一次 {@code getBlockState}，再问方块的 {@link MapColor}。</li>
 * </ul>
 * 即 2 次查询/列 × 57,600 列（15×15 区块）≈ 11.5 万次，因此可以每 tick 扫 8 个区块。
 */
public final class TerrainTask implements ServerScanTask {

    private final Level level;
    private final ChunkPos centerChunk;
    private final int chunkRadius;
    private final int chunksPerTick;
    private final int diameter;
    private final TerrainMap map;
    private final int minY;

    private int chunkIndex;

    public TerrainTask(Level level, BlockPos origin, int chunkRadius, int chunksPerTick) {
        this.level = level;
        this.centerChunk = new ChunkPos(origin);
        this.chunkRadius = chunkRadius;
        this.chunksPerTick = Math.max(1, chunksPerTick);
        this.diameter = chunkRadius * 2 - 1;
        this.minY = level.getMinBuildHeight();

        final int radius = chunkRadius - 1;
        this.map = new TerrainMap(
                (centerChunk.x - radius) * 16,
                (centerChunk.z - radius) * 16,
                diameter);
    }

    public TerrainMap map() {
        return map;
    }

    /**
     * 推进若干区块。
     *
     * @return {@code true} 表示整张地形图扫描完成
     */
    @Override
    public boolean tick() {
        final int total = diameter * diameter;
        for (int i = 0; i < chunksPerTick && chunkIndex < total; i++) {
            final int row = chunkIndex / diameter;
            final int column = chunkIndex % diameter;
            scanChunk(centerChunk.x + column - (chunkRadius - 1),
                      centerChunk.z + row - (chunkRadius - 1));
            chunkIndex++;
        }
        return chunkIndex >= total;
    }

    private void scanChunk(int chunkX, int chunkZ) {
        if (!level.hasChunk(chunkX, chunkZ)) {
            return;   // 未加载：不伪造地形，该区块在图上留空
        }

        final int baseX = chunkX << 4;
        final int baseZ = chunkZ << 4;
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                final int x = baseX + dx;
                final int z = baseZ + dz;

                // 高度图给出的是「最上方非空气方块之上的一格」，故减 1 才是地表方块
                final int surfaceY = Math.max(minY,
                        level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);

                cursor.set(x, surfaceY, z);
                final BlockState state = mapVisibleState(cursor, surfaceY);
                // 高度取「提供颜色的那个方块」，与原版一致（原版的 d1 累加的正是 k3）。
                // 这同时让栏三的等高线不会被花花草草顶出一圈假轮廓。
                map.setAt(x - map.originX(), z - map.originZ(), cursor.getY(), surfaceColor(state, cursor));
            }
        }
    }

    /**
     * 取该列<b>在地图上可见</b>的方块 —— 与 vanilla {@code MapItem.update} 同一套做法。
     *
     * <h2>为什么不能直接用最上方的方块</h2>
     * 地图色来自 {@link BlockState#getMapColor}。并非所有方块都有地图色：
     * 花、草、树苗、火把、铁轨等返回 {@link MapColor#NONE}，而
     * {@code MapColor.NONE = new MapColor(0, 0)} —— <b>它的颜色值就是 0，即纯黑</b>。
     * 直接取最上方方块的话，一朵蒲公英就会在地图上留下一个黑点。
     *
     * <h2>原版的做法</h2>
     * 自顶向下逐格下降，<b>跳过地图色为 {@code NONE} 的方块</b>，直到找到第一个有颜色的方块：
     * <pre>
     * do {
     *     --y;
     *     state = chunk.getBlockState(pos);
     * } while (state.getMapColor(level, pos) == MapColor.NONE &amp;&amp; y &gt; level.getMinBuildHeight());
     * </pre>
     * 于是蒲公英那一格取到的是它<b>下方的草方块</b>，地图上呈现正常的绿色。
     * 整列都没有可见方块时，原版按基岩处理，这里照做。
     */
    private BlockState mapVisibleState(BlockPos.MutableBlockPos cursor, int topY) {
        cursor.setY(topY);
        BlockState state = level.getBlockState(cursor);
        while (cursor.getY() > minY && state.getMapColor(level, cursor) == MapColor.NONE) {
            cursor.setY(cursor.getY() - 1);
            state = level.getBlockState(cursor);
        }
        if (state.getMapColor(level, cursor) == MapColor.NONE) {
            return Blocks.BEDROCK.defaultBlockState();
        }
        return state;
    }

    /**
     * 方块的原版地图色。
     *
     * <p>调用前 {@link #mapVisibleState} 已保证该方块有非 {@code NONE} 的颜色，
     * 因此这里不会再产出纯黑。
     */
    private int surfaceColor(BlockState state, BlockPos pos) {
        final MapColor mapColor = state.getMapColor(level, pos);
        return 0xFF000000 | mapColor.col;
    }
}
