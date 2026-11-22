package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.FluidKind;
import com.tfcie.prospecting.api.enums.Hazard;
import com.tfcie.prospecting.config.ProspectingConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T5 回声探针（超声·声波）的读数逻辑。
 *
 * <h2>算法：受限洪泛填充（BFS），而不是体积扫描</h2>
 * 从探头位置出发，只穿越<b>非固体</b>体素（空气 / 流体）做广度优先扩展，
 * 边界半径由耦合模式决定（接触 8 格 / 浸水 16 格），并记录：
 * <ul>
 *   <li>可达的空腔体素集合（空腔形状、顶板高度）；</li>
 *   <li>相邻的流体（水 / 岩浆）；</li>
 *   <li>与空腔相邻的<b>固体表面</b> —— 其中属于矿石的即为「洞壁暴露矿」。</li>
 * </ul>
 *
 * <p>这个算法同时给出两个好处：
 * <ol>
 *   <li><b>语义正确</b>：声波只能沿空腔传播，因此「<b>不穿透完整岩体</b>、
 *       只能看到与空腔连通的暴露表面」不是靠额外规则硬凑，而是算法的自然结果 ——
 *       封闭在完整岩体内部的矿永远不会被访问到；</li>
 *   <li><b>性能优异</b>：只访问连通区域（通常远小于同等半径的体积），并设访问上限兜底。</li>
 * </ol>
 *
 * <h2>矿种判定不是本工具职责</h2>
 * 声波只测波阻抗差异，能发现「洞壁存在矿面」并给出其 y，但<b>无法分辨是哪种矿</b>
 * （设计文档 v1.3 评审结论：原实现让回声探针报告矿种属越权）。故本实现只输出
 * 暴露矿的<b>位置与数量</b>，矿种交由磁力探针 + 判读表 + 钻探确认。
 */
public final class EchoProbeProspector {

    /** 洪泛填充的体素上限（兜底，防止超大空腔拖慢服务端）。 */
    private static final int MAX_CAVITY_VOXELS = 4096;

    /** 行为参数。 */
    public record Config(int rangeDry, int rangeWater, boolean enableCaveInHint) {

        public static Config from(ToolParameters params) {
            return new Config(
                    ProspectingConfig.intOr(ProspectingConfig.EchoProbe.rangeDry, 8),
                    ProspectingConfig.intOr(ProspectingConfig.EchoProbe.rangeWater, 16),
                    ProspectingConfig.boolOr(ProspectingConfig.EchoProbe.enableCaveInHint, true));
        }
    }

    /** 一次成像结果。 */
    public record Reading(boolean cavityFound,
                          int cavityDistance,
                          int ceilingY,
                          int cavityVolume,
                          FluidKind fluid,
                          List<Integer> exposedOreLevels,
                          int exposedOreCount,
                          Hazard hazard,
                          boolean submerged) {

        public Map<String, Object> toData() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("cavity_found", cavityFound);
            d.put("cavity_distance", cavityDistance);
            d.put("ceiling_y", ceilingY);
            d.put("cavity_volume", cavityVolume);
            d.put("fluid_adjacent", fluid);
            d.put("exposed_ore_levels", exposedOreLevels);
            d.put("exposed_ore_count", exposedOreCount);
            d.put("hazard_flag", hazard);
            d.put("submerged", submerged);
            return d;
        }
    }

    /** 耦合模式未满足时的拒绝理由键。 */
    public static final String REJECT_NEED_COUPLING = "message.tfcie_prospecting.echo_probe.need_coupling";

    /**
     * 执行一次成像。仅在服务端调用。
     *
     * @param origin 探头位置 —— 玩家脚下或紧贴的岩面前
     */
    public static Reading image(Level level, Player player, BlockPos origin, Config cfg) {
        final boolean submerged = player.isInWater();
        final int range = submerged ? cfg.rangeWater() : cfg.rangeDry();

        final Set<BlockPos> visited = new HashSet<>();
        final Set<BlockPos> walls = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        final BlockPos start = origin.immutable();
        if (!isPassable(level, start)) {
            // 探头处于固体内部：从紧邻的可通行体素起步
            boolean seeded = false;
            for (Direction dir : Direction.values()) {
                final BlockPos neighbour = start.relative(dir);
                if (isPassable(level, neighbour)) {
                    queue.add(neighbour);
                    visited.add(neighbour);
                    seeded = true;
                }
            }
            if (!seeded) {
                return empty(submerged);
            }
        } else {
            queue.add(start);
            visited.add(start);
        }

        boolean lava = false;
        boolean water = false;
        int ceilingY = Integer.MIN_VALUE;
        int nearest = Integer.MAX_VALUE;

        while (!queue.isEmpty() && visited.size() < MAX_CAVITY_VOXELS) {
            final BlockPos pos = queue.poll();
            final double dist = Math.sqrt(pos.distSqr(start));
            if (dist > range) {
                continue;
            }
            nearest = Math.min(nearest, (int) Math.round(dist));
            ceilingY = Math.max(ceilingY, pos.getY());

            // Tag 判定：TFC 的 river_water / salt_water / spring_water 也认（报告 18 §1 4b）
            if (ProspectingFluids.isLava(level.getFluidState(pos))) {
                lava = true;
            } else if (ProspectingFluids.isWater(level.getFluidState(pos))) {
                water = true;
            }

            for (Direction dir : Direction.values()) {
                final BlockPos next = pos.relative(dir);
                if (visited.contains(next) || walls.contains(next)) {
                    continue;
                }
                if (Math.sqrt(next.distSqr(start)) > range) {
                    continue;
                }
                if (!level.isLoaded(next)) {
                    continue;
                }
                if (isPassable(level, next)) {
                    visited.add(next);
                    queue.add(next);
                } else {
                    // 空腔壁面：记录为可能的暴露矿面
                    walls.add(next);
                }
            }
        }

        // 洞壁暴露矿：仅记录位置（不判矿种）
        final List<Integer> oreLevels = new ArrayList<>();
        for (BlockPos wall : walls) {
            if (ReconScanner.oreIdOf(level.getBlockState(wall)) != null) {
                oreLevels.add(wall.getY());
            }
        }
        oreLevels.sort(Integer::compareTo);

        final FluidKind fluidKind = lava ? FluidKind.LAVA : (water ? FluidKind.WATER : FluidKind.NONE);
        final int volume = visited.size();
        final Hazard hazard = cfg.enableCaveInHint() ? hazardOf(level, walls, volume) : Hazard.NONE;

        return new Reading(
                volume > 1,
                nearest == Integer.MAX_VALUE ? 0 : nearest,
                ceilingY == Integer.MIN_VALUE ? origin.getY() : ceilingY,
                volume,
                fluidKind,
                oreLevels,
                oreLevels.size(),
                hazard,
                submerged);
    }

    /** 塌方风险：空腔越大、顶板越松散，风险越高。 */
    private static Hazard hazardOf(Level level, Set<BlockPos> walls, int volume) {
        Hazard hazard = volume < 32 ? Hazard.LOW : (volume < 128 ? Hazard.MEDIUM : Hazard.HIGH);
        // 顶板若为松散层（砂 / 砾石 / 土），风险上调一档
        for (BlockPos wall : walls) {
            if (ReconScanner.isLooseOverburden(level, wall)) {
                hazard = switch (hazard) {
                    case NONE, LOW -> Hazard.MEDIUM;
                    case MEDIUM, HIGH -> Hazard.HIGH;
                };
                break;
            }
        }
        return hazard;
    }

    private static boolean isPassable(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        final BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        return !state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }

    private static Reading empty(boolean submerged) {
        return new Reading(false, 0, 0, 0, FluidKind.NONE, List.of(), 0, Hazard.NONE, submerged);
    }

    /** 前置条件：须浸水或紧贴石质方块，否则干燥空气中几乎无效。 */
    public static boolean canCouple(Level level, Player player, BlockPos origin) {
        if (player.isInWater()) {
            return true;
        }
        for (Direction dir : Direction.values()) {
            final BlockPos neighbour = origin.relative(dir);
            if (ReconScanner.isExposedRock(level, neighbour)) {
                return true;
            }
        }
        return ReconScanner.isExposedRock(level, origin.below());
    }

    private EchoProbeProspector() {}
}
