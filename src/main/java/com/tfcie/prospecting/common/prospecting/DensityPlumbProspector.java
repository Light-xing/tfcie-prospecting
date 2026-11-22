package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.OreTaxonomy;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.Confidence;
import com.tfcie.prospecting.api.enums.DensityAnomaly;
import com.tfcie.prospecting.api.enums.DensityClass;
import com.tfcie.prospecting.api.enums.Magnitude;
import com.tfcie.prospecting.common.block.BaseWeightBlock;
import com.tfcie.prospecting.config.ProspectingConfig;
import com.tfcie.prospecting.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T2 密度探锤（重力法）的读数逻辑。
 *
 * <h2>算法</h2>
 * 以玩家脚下为中心作小半径（默认 4 格）的垂向柱状扫描，把结果按<b>深度分桶</b>累加：
 * 每个采样点按物性表取密度偏差（矿体高/低密度、空洞与流体低密度），以 {@code 1/(1+d)}
 * 作重力衰减加权，再乘该点代表的体素体积 {@code sx·sy·sz}，落入
 * {@code depth/band_base} 对应的桶。取响应最强的桶即为异常深度带。
 * 半径为 0 时退化为单列盲测，轻微偏离矿体就会漏报（报告 16 §1.4 ④）。
 *
 * <p>这样设计文档要求的两个特性会自然涌现：
 * <ul>
 *   <li><b>深度带随深度变宽</b> —— 带宽 {@code base × (1 + 2×depth/maxDepth)}，
 *       浅处 8 格、深处 24 格（对应 {@code band_width_shallow / band_width_deep}）；</li>
 *   <li><b>置信度随深度递减</b> —— 位场反演的深度非唯一性，越深越不可信。</li>
 * </ul>
 *
 * <h2>地表判定按列独立</h2>
 * 每一列各自穿过地表以上的空气、水与植物，碰到该列第一个实心方块后才计入地下密度
 * （见 {@link GroundColumns}）。共用单个「已入地」标志时，斜坡/台阶/河岸旁的地表空气
 * 会被算进 0-8 格浅层桶，读数被假低密度占满（报告 18 §2.1）。
 *
 * <h2>只给符号，不给物质</h2>
 * 煤、盐、石膏、硼砂、空洞、水囊与<b>岩浆腔</b>都表现为低密度异常（熔融岩浆密度低于
 * 固结围岩，设计文档 v1.3 已修正为低密度），彼此无法分辨 —— 工具只输出异常符号。
 *
 * <h2>漂移与基点</h2>
 * 每次使用累积漂移并整体偏移读数；在<b>基点砝码</b>附近使用时视为基点复测，漂移复位。
 * 连续使用超过阈值后读数不可信，须回基点复位。
 */
public final class DensityPlumbProspector {

    private static final String NBT_ROOT = "tfcie_prospecting:density_drift";
    private static final String KEY_DRIFT = "drift";
    private static final String KEY_USES = "uses";

    /**
     * 幅度归一化的半饱和常数 K：{@code bestAbs / (bestAbs + K)}。
     *
     * <p>原值 8.0 要求单桶 {@code bestAbs ≥ 4.0} 才算 MEDIUM；而单列 8 格桶的
     * 响应最多约 1.83（每格 deviation = 1、以 1/(1+d) 加权），数学上永远只能报 WEAK
     * （报告 16 §1.4 ④）。1.5 让「8 格桶内密度偏差满格」这类合理读数落在 MEDIUM，
     * STRONG 仍要求更厚的异常体。
     */
    static final double MAGNITUDE_K = 1.5D;

    /** 行为参数。 */
    public record Config(int horizontalRadius,
                         int maxDepth,
                         int depthBandBase,
                         int driftPerUse,
                         int driftResetAfterUses,
                         boolean requireCalibration) {

        public static Config from(ToolParameters params) {
            return new Config(
                    ProspectingConfig.intOr(ProspectingConfig.DensityPlumb.horizontalRadius, 8),
                    params.maxDepth(),
                    ProspectingConfig.intOr(ProspectingConfig.DensityPlumb.depthBandBase, 8),
                    ProspectingConfig.intOr(ProspectingConfig.DensityPlumb.driftPerUse, 2),
                    ProspectingConfig.intOr(ProspectingConfig.DensityPlumb.driftResetAfterUses, 3),
                    ProspectingConfig.boolOr(ProspectingConfig.DensityPlumb.requireCalibration, true));
        }
    }

    /** 一次读数。 */
    public record Reading(DensityAnomaly anomaly,
                          int depthFrom,
                          int depthTo,
                          Magnitude magnitude,
                          Confidence confidence,
                          int driftOffset,
                          boolean drifted,
                          boolean needsCalibration,
                          boolean atBase) {

        public Map<String, Object> toData() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("anomaly_type", anomaly);
            d.put("depth_band", java.util.List.of(depthFrom, depthTo));
            d.put("depth_from", depthFrom);
            d.put("depth_to", depthTo);
            d.put("magnitude", magnitude);
            d.put("confidence", confidence);
            // 漂移偏移量：>0 时深度带已被整体平移，须回基点复测后才可信（报告 18 §P1 第 2 条）
            d.put("drift_offset", driftOffset);
            d.put("drifted", drifted);
            d.put("needs_calibration", needsCalibration);
            d.put("at_base", atBase);
            return d;
        }
    }

    /** 漂移状态（存于探锤 NBT）。 */
    private record Drift(int blocks, int usesSinceCalibration) {

        static final Drift CLEAN = new Drift(0, 0);
    }

    /**
     * 执行一次测量。仅在服务端调用。
     *
     * @param origin 探测原点 —— 玩家脚下
     */
    public static Reading measure(Level level, ItemStack stack, BlockPos origin, Config cfg) {
        final boolean atBase = isAtBaseWeight(level, origin);
        Drift drift = atBase ? Drift.CLEAN : readDrift(stack);

        // 读数偏移量取「本次使用之前」已累积的漂移
        final int offset = cfg.requireCalibration() ? drift.blocks() : 0;
        final boolean needsCalibration = cfg.requireCalibration()
                && drift.usesSinceCalibration() >= cfg.driftResetAfterUses();

        // ---- 垂向分桶扫描 ----
        final int bandBase = Math.max(1, cfg.depthBandBase());
        final int bandCount = cfg.maxDepth() / bandBase + 1;
        final double[] buckets = new double[bandCount];
        // 逐列地表判定：每列各自穿过空气 / 水 / 植物后才开始计分，避免斜坡旁的
        // 地表空气被算进 0-8 格浅层桶（报告 18 §1 第 5 条、§2.1）
        final int radiusH = Math.max(0, cfg.horizontalRadius());
        final GroundColumns ground = new GroundColumns(radiusH);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        ToolScan.scanCylinder(level, origin, radiusH, cfg.maxDepth(), 0,
                ToolScan.Adaptive.DEFAULT, (dx, dy, dz, distance, sx, sy, sz, state) -> {
                    cursor.set(origin.getX() + dx, origin.getY() - dy, origin.getZ() + dz);
                    if (!ground.entered(dx, dz, state.isSolidRender(level, cursor))) {
                        // 该列仍在地表以上（空气 / 水 / 植被）：不参与地下密度计分
                        return;
                    }
                    final double deviation = densityDeviation(state);
                    if (deviation == 0.0D) {
                        return;
                    }
                    final int band = Math.min(bandCount - 1, dy / bandBase);
                    // 乘该采样点代表的体素体积（水平自适应步长 × 垂向 1），
                    // 否则深部水平粗采样会把桶和随步长除小（报告 16 §1.4 ② 的同类漏乘）
                    buckets[band] += deviation * (sx * sy * sz) / (1.0D + distance);
                });

        // ---- 取最强桶 ----
        int best = -1;
        double bestAbs = 0.0D;
        for (int i = 0; i < bandCount; i++) {
            final double abs = Math.abs(buckets[i]);
            if (abs > bestAbs) {
                bestAbs = abs;
                best = i;
            }
        }

        final DensityAnomaly anomaly;
        final int depthFrom;
        final int depthTo;
        if (best < 0 || bestAbs <= 1.0E-6D) {
            anomaly = DensityAnomaly.NONE;
            depthFrom = 0;
            depthTo = 0;
        } else {
            anomaly = buckets[best] > 0 ? DensityAnomaly.HIGH_DENSITY : DensityAnomaly.LOW_DENSITY;
            final int center = best * bandBase + bandBase / 2;
            final double depthRatio = (double) center / Math.max(1, cfg.maxDepth());
            // 浅处 = base，深处 = 3 × base（对应 band_width_shallow 8 / band_width_deep 24）
            final int width = (int) Math.round(bandBase * (1.0D + 2.0D * depthRatio));
            depthFrom = Math.max(0, center - width / 2 + offset);
            depthTo = Math.max(0, center + width / 2 + offset);
        }

        final Magnitude magnitude = magnitudeOf(bestAbs);

        final double depthRatio = depthTo <= 0 ? 0.0D : (double) depthFrom / Math.max(1, cfg.maxDepth());
        Confidence confidence = depthRatio <= 0.25D ? Confidence.HIGH
                : (depthRatio <= 0.6D ? Confidence.MEDIUM : Confidence.LOW);
        if (needsCalibration) {
            confidence = downgrade(confidence);
        }

        // ---- 写回漂移 ----
        final Drift next;
        if (atBase) {
            next = Drift.CLEAN;
        } else {
            next = new Drift(drift.blocks() + cfg.driftPerUse(), drift.usesSinceCalibration() + 1);
        }
        writeDrift(stack, next);

        return new Reading(anomaly, depthFrom, depthTo, magnitude, confidence,
                offset, offset > 0, needsCalibration, atBase);
    }

    // ---------------- 物性 ----------------

    private static double densityDeviation(BlockState state) {
        if (isVoid(state)) {
            return DensityClass.LOW.deviation();     // 空洞
        }
        if (isFluid(state)) {
            return DensityClass.LOW.deviation();     // 水囊 / 岩浆腔（熔融岩浆密度低于固结围岩）
        }
        final String oreId = ReconScanner.oreIdOf(state);
        return oreId == null ? 0.0D : OreTaxonomy.densityOf(oreId).deviation();
    }

    private static boolean isVoid(BlockState state) {
        return state.isAir();
    }

    private static boolean isFluid(BlockState state) {
        // Tag 判定：TFC 的 river_water / salt_water / spring_water 也认（报告 18 §1 4b）
        return ProspectingFluids.isWaterOrLava(state);
    }

    /** 由最强桶的绝对响应给出幅度档（纯函数，供单测）。 */
    static Magnitude magnitudeOf(double bestAbs) {
        final double normalized = bestAbs / (bestAbs + MAGNITUDE_K);
        return normalized < 0.33D ? Magnitude.WEAK
                : (normalized < 0.66D ? Magnitude.MEDIUM : Magnitude.STRONG);
    }

    private static Confidence downgrade(Confidence c) {
        return switch (c) {
            case HIGH -> Confidence.MEDIUM;
            case MEDIUM -> Confidence.LOW;
            case LOW -> Confidence.LOW;
        };
    }

    // ---------------- 基点与漂移 ----------------

    /** 原点附近是否存在基点砝码（即「处于基点」）。 */
    public static boolean isAtBaseWeight(Level level, BlockPos origin) {
        final var base = ModBlocks.BASE_WEIGHT.get();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final int r = BaseWeightBlock.BASE_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (level.isLoaded(cursor) && level.getBlockState(cursor).is(base)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 供 UI/提示使用：当前累积漂移与是否已需复位。 */
    public static int driftOf(ItemStack stack) {
        return readDrift(stack).blocks();
    }

    private static Drift readDrift(ItemStack stack) {
        final CompoundTag tag = stack.getTagElement(NBT_ROOT);
        if (tag == null) {
            return Drift.CLEAN;
        }
        return new Drift(Math.max(0, tag.getInt(KEY_DRIFT)), Math.max(0, tag.getInt(KEY_USES)));
    }

    private static void writeDrift(ItemStack stack, Drift drift) {
        if (drift.blocks() == 0 && drift.usesSinceCalibration() == 0) {
            final CompoundTag existing = stack.getTag();
            if (existing != null) {
                existing.remove(NBT_ROOT);
            }
            return;
        }
        final CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_DRIFT, drift.blocks());
        tag.putInt(KEY_USES, drift.usesSinceCalibration());
        stack.getOrCreateTag().put(NBT_ROOT, tag);
    }

    private DensityPlumbProspector() {}
}
