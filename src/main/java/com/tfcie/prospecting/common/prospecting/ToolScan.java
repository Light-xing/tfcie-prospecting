package com.tfcie.prospecting.common.prospecting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * 体探工具共用的体积扫描器：<b>水平距离自适应步长</b> + 形状裁剪。
 *
 * <h2>为什么水平方向可以粗采样，垂向不行</h2>
 * 若逐格全扫，感应探棒（半径 24 × 深 64）约需 11.6 万次 {@code getBlockState}、
 * 磁力探针（半径 32 × 深 48）约 15.4 万次，单次右键即可造成可见卡顿。本扫描器按
 * 采样点与原点（仪器）的距离切换<b>水平</b>步长：近距逐格、中距隔一取一、远距隔三取一，
 * 使计算量降到约 1/8 ~ 1/27。物探本身的分辨率就随距离下降，设计文档也要求
 * {@code precision_degrades_with_depth}，因此水平粗采样与设计意图一致。
 *
 * <p>但<b>垂向步长恒为 1</b>：TFC 的矿层常常只有 1 格厚，垂向取 2 / 4 会在数学上
 * 直接跳过整层（垂向混叠）。垂向分辨率的损失没有「水平距离」作借口，一律逐格。
 *
 * <h2>权重交给调用方</h2>
 * 扫描器只负责「在哪些点采样、离原点多远、该点代表多大体素、那里是什么方块」，
 * 各工具按自己的物理衰减规律加权（重力法 1/(1+d)、电磁 1/(1+d)、磁法 1/(1+d²) 等）。
 * 粗采样时一个采样点代表 {@code sx × sy × sz} 格，做体积积分的方法（磁法）必须把
 * 单点权重乘上该体积因子，否则读数会随步长被平白除小（报告 16 §1.4 ②）。
 */
public final class ToolScan {

    /**
     * 自适应步长参数。
     *
     * @param nearRadius 该半径内逐格采样（{@code step = 1}）
     * @param midRadius  该半径内隔一取一（{@code midStep}）
     * @param midStep    中距步长
     * @param farStep    远距步长
     */
    public record Adaptive(double nearRadius, double midRadius, int midStep, int farStep) {

        public static final Adaptive DEFAULT = new Adaptive(8.0D, 24.0D, 2, 4);

        public int stepFor(double distance) {
            if (distance <= nearRadius) {
                return 1;
            }
            if (distance <= midRadius) {
                return Math.max(1, midStep);
            }
            return Math.max(1, farStep);
        }
    }

    /** 采样点访问者。 */
    @FunctionalInterface
    public interface VoxelVisitor {
        /**
         * @param dx       相对原点的水平偏移（x）
         * @param dy       相对原点的垂向偏移（向下为正）
         * @param dz       相对原点的水平偏移（z）
         * @param distance 到原点的欧氏距离
         * @param sx       x 向采样步长：该点代表 {@code sx} 格宽
         * @param sy       垂向采样步长（恒为 1）
         * @param sz       z 向采样步长：该点代表 {@code sz} 格宽
         * @param state    该点的方块状态
         */
        void visit(int dx, int dy, int dz, double distance, int sx, int sy, int sz, BlockState state);
    }

    /**
     * 圆柱体积扫描：水平半径 {@code radiusH}，垂向自原点向下 {@code depthDown} 格、
     * 向上 {@code depthUp} 格。
     *
     * <p>垂向覆盖 {@code dy ∈ [-depthUp, depthDown]}，{@code dy} 的约定是<b>向下为正</b>
     * （{@code dy = -1} 即原点上方一格）。<b>垂向逐格步进</b>（{@code sy = 1}），
     * 因此 {@code dy = 0} 一定被采样到，不会因步长跨过同层；也保证 1 格厚的
     * TFC 矿层不会被整层跳过。
     *
     * <p>{@code radiusH = 0} 时退化为单列垂向扫描。
     */
    public static void scanCylinder(Level level, BlockPos origin, int radiusH, int depthDown, int depthUp,
                                    Adaptive adaptive, VoxelVisitor visitor) {
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final int ox = origin.getX();
        final int oy = origin.getY();
        final int oz = origin.getZ();
        final int r2 = radiusH * radiusH;
        // 同一列(dx、dz 相同)在每一层都会再扫一次:每列只问一次区块,之后直接读那块区块。
        // 一次读数里省掉数万次区块查找(原来逐格 isLoaded + level.getBlockState 各查一次)。
        final Columns columns = new Columns(level, radiusH, ox, oz);

        // 从最上一格（dy = -depthUp）开始向下扫；垂向恒为 1 格，避免跳过 1 格厚的薄矿层
        for (int dy = -depthUp; dy <= depthDown; dy++) {
            final int sy = 1;
            for (int dx = -radiusH; dx <= radiusH; ) {
                final int sx = Math.max(1, adaptive.stepFor(Math.hypot(dx, dy)));
                for (int dz = -radiusH; dz <= radiusH; ) {
                    final double distance = Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
                    final int sz = Math.max(1, adaptive.stepFor(distance));

                    if (dx * dx + dz * dz <= r2) {
                        final ChunkAccess chunk = columns.chunkAt(dx, dz);
                        if (chunk != null) {
                            final int wy = oy - dy;
                            cursor.set(ox + dx, wy, oz + dz);
                            visitor.visit(dx, dy, dz, distance, sx, sy, sz, stateAt(level, chunk, cursor, wy));
                        }
                    }
                    dz += sz;
                }
                dx += sx;
            }
        }
    }

    /**
     * 球形体积扫描（以原点为中心，含向上与向下）。用于需要全向感知的工具。
     */
    public static void scanSphere(Level level, BlockPos origin, int radius,
                                  Adaptive adaptive, VoxelVisitor visitor) {
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final int ox = origin.getX();
        final int oy = origin.getY();
        final int oz = origin.getZ();
        final int r2 = radius * radius;
        final Columns columns = new Columns(level, radius, ox, oz);

        for (int dy = -radius; dy <= radius; dy++) {
            final int sy = 1;
            for (int dx = -radius; dx <= radius; ) {
                final int sx = Math.max(1, adaptive.stepFor(Math.hypot(dx, dy)));
                for (int dz = -radius; dz <= radius; ) {
                    final double distance = Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
                    final int sz = Math.max(1, adaptive.stepFor(distance));

                    if (dx * dx + dy * dy + dz * dz <= r2) {
                        final ChunkAccess chunk = columns.chunkAt(dx, dz);
                        if (chunk != null) {
                            final int wy = oy + dy;
                            cursor.set(ox + dx, wy, oz + dz);
                            visitor.visit(dx, dy, dz, distance, sx, sy, sz, stateAt(level, chunk, cursor, wy));
                        }
                    }
                    dz += sz;
                }
                dx += sx;
            }
        }
    }

    /** 世界高度之外是空气:原版 {@code Level.getBlockState} 也是这样答的,不因换了区块读法而改变。 */
    private static BlockState stateAt(Level level, ChunkAccess chunk, BlockPos pos, int y) {
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        return chunk.getBlockState(pos);
    }

    /**
     * 一次扫描期间的「列 → 区块」表:每列只问一次区块(未加载的列记空,不再逐格 {@code isLoaded}),
     * 同一列在各层复用。不改变采样点、采样顺序与 {@link VoxelVisitor} 收到的参数。
     */
    private static final class Columns {
        private final Level level;
        private final int radius;
        private final int side;
        private final int ox;
        private final int oz;
        /** 0 = 还没问过;-1 = 未加载;正数 = {@link #chunks} 的下标 + 1。 */
        private final int[] which;
        private final List<ChunkAccess> chunks = new ArrayList<>();

        Columns(Level level, int radius, int ox, int oz) {
            this.level = level;
            this.radius = radius;
            this.side = 2 * radius + 1;
            this.ox = ox;
            this.oz = oz;
            this.which = new int[side * side];
        }

        ChunkAccess chunkAt(int dx, int dz) {
            final int slot = (dx + radius) * side + (dz + radius);
            final int known = which[slot];
            if (known == -1) {
                return null;
            }
            if (known > 0) {
                return chunks.get(known - 1);
            }
            final ChunkAccess chunk = level.getChunkSource()
                    .getChunk((ox + dx) >> 4, (oz + dz) >> 4, ChunkStatus.FULL, false);
            if (chunk == null) {
                which[slot] = -1;
                return null;
            }
            chunks.add(chunk);
            which[slot] = chunks.size();
            return chunk;
        }
    }

    private ToolScan() {}
}
