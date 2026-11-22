package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.TerminatedBy;
import com.tfcie.prospecting.config.ProspectingConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * T6 钻探取样器的取芯逻辑。
 *
 * <h2>算法</h2>
 * 自取样起点（<b>右键点击的那块实体方块本身</b>）沿垂向逐格读取方块，
 * 把<b>相邻同类</b>方块合并为一个岩层段（不相邻的同名层各自成段），同时按终止条件裁剪柱长。
 * 全程只读取、不修改世界 —— 红线要求：<b>不产出任何矿物掉落物，只产出数据</b>。
 *
 * <h2>终止条件</h2>
 * 达到设定深度 {@code max_depth} / 基岩 {@code bedrock} / 岩浆 {@code lava}
 * / 不可钻障碍 {@code obstruction}。<b>遇水不终止</b>：水层作为含水层记录进岩层序列，
 * 钻探继续（只有岩浆触发终止并可能损坏取样器）。
 *
 * <p><b>产物策略</b>：无矿空柱、以及背包已满时不生成实物样本（见 {@link #decideSample}）——
 * 空样本 {@code stacksTo(1)} 且 NBT 唯一、不可堆叠，只会白占背包（报告 18 §1 第 6 条）。
 *
 * <h2>取芯恢复率</h2>
 * 松散层（砂 / 砾石 / 表土 / 黏土）按概率未取芯，在样本中以「（未取芯）」占位缺失段；
 * 坚硬岩层为 100%。整体恢复率按厚度加权，使数据保留解释空间，而非完美透视。
 */
public final class CoreSamplerProspector {

    /** 松散层的取芯成功率（坚硬岩层恒为 1.0）。 */
    public static final double LOOSE_RECOVERY = 0.6D;

    /** 深度档位（潜行右键切换）。实际可选值会被裁剪到配置上限以内。 */
    public static final int[] DEPTH_STEPS = {8, 16, 32, 64};

    /** 深度档位存于工具 NBT。 */
    public static final String NBT_DEPTH = "tfcie_prospecting:core_depth";

    /** 行为参数。 */
    public record Config(int maxDepth,
                         int defaultDepth,
                         int ticksPerBlock,
                         int energyPerBlock,
                         boolean leavesShaft,
                         boolean shaftDropsItems,
                         boolean stopOnBedrock,
                         double lavaDamageChance,
                         boolean lossyLooseLayers) {

        public static Config from(ToolParameters params) {
            return new Config(
                    params.maxDepth(),
                    ProspectingConfig.intOr(ProspectingConfig.CoreSampler.defaultDepth, 32),
                    ProspectingConfig.intOr(ProspectingConfig.CoreSampler.ticksPerBlock, 4),
                    ProspectingConfig.intOr(ProspectingConfig.CoreSampler.energyPerBlock, 200),
                    ProspectingConfig.boolOr(ProspectingConfig.CoreSampler.leavesShaft, false),
                    ProspectingConfig.boolOr(ProspectingConfig.CoreSampler.shaftDropsItems, false),
                    ProspectingConfig.boolOr(ProspectingConfig.CoreSampler.stopOnBedrock, true),
                    ProspectingConfig.doubleOr(ProspectingConfig.CoreSampler.lavaDamageChance, 0.5D),
                    ProspectingConfig.boolOr(ProspectingConfig.CoreSampler.lossyLooseLayers, true));
        }
    }

    /**
     * 执行一次取芯。仅在服务端调用。
     *
     * @param origin 取样起点 —— <b>右键点击的那块实体方块本身</b>，即第一个被读取的方块
     * @param depth  目标深度（已裁剪到配置上限）
     */
    public static CoreSampleData drill(Level level, BlockPos origin, int depth, Config cfg, RandomSource random) {
        final List<CoreSampleData.Layer> runs = new ArrayList<>();

        String currentBlock = null;
        String currentDisplay = null;
        int layerTopY = origin.getY();
        int layerThickness = 0;
        boolean currentCored = true;

        TerminatedBy terminated = TerminatedBy.MAX_DEPTH;
        int lastY = origin.getY();
        int blocksRead = 0;
        int coredThickness = 0;
        int totalThickness = 0;

        for (int step = 0; step < depth; step++) {
            final BlockPos pos = origin.below(step);
            if (!level.isLoaded(pos)) {
                terminated = TerminatedBy.OBSTRUCTION;
                break;
            }
            final BlockState state = level.getBlockState(pos);

            // ---- 终止判定 ----
            if (state.is(Blocks.BEDROCK)) {
                terminated = TerminatedBy.BEDROCK;
                break;
            }
            if (state.getDestroySpeed(level, pos) < 0.0F) {
                terminated = TerminatedBy.OBSTRUCTION;
                break;
            }
            final boolean lava = isLava(state);

            // ---- 记录该层 ----
            final ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            final String blockId = key == null ? "minecraft:air" : key.toString();
            final String display = state.getBlock().getName().getString();

            if (!blockId.equals(currentBlock)) {
                // 相邻同类才合并：换种即结算上一段
                if (currentBlock != null) {
                    runs.add(new CoreSampleData.Layer(currentBlock, currentDisplay,
                            layerThickness, layerTopY, layerTopY - layerThickness + 1, currentCored));
                    totalThickness += layerThickness;
                    if (currentCored) {
                        coredThickness += layerThickness;
                    }
                }
                currentBlock = blockId;
                currentDisplay = display;
                layerTopY = pos.getY();
                layerThickness = 1;
                currentCored = decideCored(level, pos, cfg, random);
            } else {
                layerThickness++;
            }

            lastY = pos.getY();
            blocksRead++;

            if (lava) {
                terminated = TerminatedBy.LAVA;
                break;
            }
        }

        if (currentBlock != null) {
            runs.add(new CoreSampleData.Layer(currentBlock, currentDisplay,
                    layerThickness, layerTopY, layerTopY - layerThickness + 1, currentCored));
            totalThickness += layerThickness;
            if (currentCored) {
                coredThickness += layerThickness;
            }
        }

        // ---- 含矿层（真值：精确深度与厚度） ----
        final List<CoreSampleData.OreHit> oreHits = new ArrayList<>();
        for (CoreSampleData.Layer layer : runs) {
            final ResourceLocation key = ResourceLocation.tryParse(layer.block());
            if (key == null) {
                continue;
            }
            final var block = ForgeRegistries.BLOCKS.getValue(key);
            if (block != null && ReconScanner.oreIdOf(block.defaultBlockState()) != null) {
                oreHits.add(new CoreSampleData.OreHit(layer.block(), layer.display(), layer.yFrom(), layer.thickness()));
            }
        }

        final double recovery = totalThickness <= 0 ? 0.0D : (double) coredThickness / (double) totalThickness;

        return new CoreSampleData(
                origin,
                level.dimension().location(),
                origin.getY(),
                lastY,
                blocksRead,
                terminated,
                recovery,
                runs,
                oreHits,
                buildSummary(runs),
                System.currentTimeMillis() / 1000L);
    }

    /** 松散层按概率未取芯；坚硬岩层恒为取芯。 */
    private static boolean decideCored(Level level, BlockPos pos, Config cfg, RandomSource random) {
        if (!cfg.lossyLooseLayers() || !ReconScanner.isLooseOverburden(level, pos)) {
            return true;
        }
        return random.nextDouble() < LOOSE_RECOVERY;
    }

    /** 按层序压缩的摘要串，如 {@code "泥土 5 闪长岩 10 泥土 3"}。 */
    public static String buildSummary(List<CoreSampleData.Layer> runs) {
        final StringBuilder sb = new StringBuilder();
        for (CoreSampleData.Layer layer : runs) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(layer.display()).append(' ').append(layer.thickness());
        }
        return sb.toString();
    }

    private static boolean isLava(BlockState state) {
        // Tag 判定：流体识别统一走 ProspectingFluids，不用 Fluids.* 实例比较（报告 18 §1 4b）
        return ProspectingFluids.isLava(state);
    }

    // ---------------- 产物策略 ----------------

    /**
     * 该柱是否有含矿层。空柱不生成实物样本：样本 {@code stacksTo(1)} 且 NBT 唯一、
     * 不可堆叠，空样本只会白占背包（报告 18 §1 第 6 条）。读数与台账照常返回。
     */
    public static boolean producesSample(CoreSampleData data) {
        return data.oreLayerCount() > 0;
    }

    /** 取芯产物的处置决策。 */
    public enum SampleDecision {
        /** 空柱：不产物品，读数照常返回。 */
        EMPTY_CORE,
        /** 背包已满：拒绝本次取芯（不扣耐久、不丢地上）。 */
        INVENTORY_FULL,
        /** 生成并放入样本。 */
        STORE
    }

    /**
     * 由「是否含矿」与「背包是否还有空位」决定产物处置。
     *
     * <p>纯函数，供单测；调用方按返回值分支，{@code INVENTORY_FULL} 必须在扣耐久前返回。
     */
    public static SampleDecision decideSample(CoreSampleData data, boolean inventoryHasRoom) {
        if (!producesSample(data)) {
            return SampleDecision.EMPTY_CORE;
        }
        return inventoryHasRoom ? SampleDecision.STORE : SampleDecision.INVENTORY_FULL;
    }

    /** 把目标深度裁剪到可选档位与配置上限以内。 */
    public static int clampDepth(int requested, int maxDepth) {
        return Math.max(1, Math.min(requested, Math.max(1, maxDepth)));
    }

    /** 潜行右键切换到下一档深度；返回新档位。 */
    public static int nextDepthStep(int current, int maxDepth) {
        final int[] steps = availableSteps(maxDepth);
        for (int step : steps) {
            if (step > current) {
                return step;
            }
        }
        return steps[0];
    }

    public static int[] availableSteps(int maxDepth) {
        final List<Integer> steps = new ArrayList<>();
        for (int step : DEPTH_STEPS) {
            if (step <= maxDepth) {
                steps.add(step);
            }
        }
        if (steps.isEmpty()) {
            steps.add(Math.max(1, maxDepth));
        }
        final int[] out = new int[steps.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = steps.get(i);
        }
        return out;
    }

    private CoreSamplerProspector() {}
}
