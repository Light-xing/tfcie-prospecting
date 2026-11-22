package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.OreTaxonomy;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.Bearing;
import com.tfcie.prospecting.api.enums.Confidence;
import com.tfcie.prospecting.api.enums.Conductivity;
import com.tfcie.prospecting.api.enums.DepthHint;
import com.tfcie.prospecting.api.enums.DistanceBand;
import com.tfcie.prospecting.config.ProspectingConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * T3 感应探棒（电法·电磁）的读数逻辑。
 *
 * <h2>算法</h2>
 * 一次圆柱扫描，把每个导电体按<b>水平方位量化到八个扇区</b>并按距离加权累加，
 * 取响应最强的扇区作为粗方位 —— 这与设计文档「内置旋转接收线圈扫掠八个方位取最强响应」
 * 等价，但只需一遍扫描即可得出，无需真的扫掠八次。
 *
 * <p>衰减取 {@code 1/(1+d)}：电磁响应随距离比重力衰减更快。
 *
 * <h2>盲区与干扰（严格对齐设计文档）</h2>
 * <ul>
 *   <li><b>孔雀石不导电</b>（碳酸铜）、<b>镍矿全盲</b>（风化硅酸盐）—— 由物性表决定；</li>
 *   <li>对煤、宝石、干燥岩石无响应；</li>
 *   <li>附近 IE 机械 / 铁轨 / 金属块造成<b>人文干扰</b>，读数虚高。</li>
 * </ul>
 *
 * <h2>水体：地表穿透，地下只算干扰</h2>
 * 逐列穿过地表以上的空气、水与植物后再入地（见 {@link GroundColumns}）：湖泊 / 河流 /
 * 海洋表层不再被当成地下导体。地下封闭淡水<b>不进</b> {@code accum / sectors / nearest /
 * depthWeighted / horizontalSpan}（否则会把方位拉向最近水面、把距离锁死在 NEAR，
 * 报告 18 §1 4c），只置 {@code interference} 并封顶置信度；{@code tfc:salt_water}
 * 盐水给低档<b>正</b>响应并标干扰（物理上导电，不能静默按 0 处理）。
 * 流体一律走 Tag，见 {@link ProspectingFluids}。
 */
public final class InductionProbeProspector {

    /** 行为参数。 */
    public record Config(int radius,
                         int verticalDepth,
                         int energyCost,
                         boolean humanInterference) {

        public static Config from(ToolParameters params) {
            return new Config(
                    params.radius(),
                    ProspectingConfig.intOr(ProspectingConfig.InductionProbe.verticalDepth, 64),
                    params.energyCost(),
                    ProspectingConfig.boolOr(ProspectingConfig.InductionProbe.humanInterference, true));
        }
    }

    /** 一次读数。 */
    public record Reading(Conductivity conductivity,
                          Bearing bearing,
                          DistanceBand distanceBand,
                          int outlineRadius,
                          DepthHint depthHint,
                          Confidence confidence,
                          boolean humanInterference,
                          boolean waterInterference) {

        /** 是否存在干扰（人文或水体）。 */
        public boolean interference() {
            return humanInterference || waterInterference;
        }

        public Map<String, Object> toData() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("conductivity", conductivity);
            if (conductivity != Conductivity.NONE) {
                d.put("bearing", bearing);
                d.put("distance_band", distanceBand);
                d.put("outline_radius", outlineRadius);
                d.put("depth_hint", depthHint);
            }
            d.put("confidence", confidence);
            d.put("interference", interference());
            // 水体干扰单独标出，回执里才能写清是「水」还是「金属结构」（报告 18 §P1 第 3 条）
            d.put("water_interference", waterInterference);
            return d;
        }
    }

    /** 盐水（海水 / 咸水）的低档正响应：物理导电，但不能被当成矿。 */
    static final double SALT_WATER_RESPONSE = 0.05D;
    /** 岩浆权重 —— 导电的熔融体（判读表「岩浆腔」行要求导电）。 */
    private static final double LAVA_RESPONSE = 0.8D;
    /** 人文干扰（金属结构）的虚高权重。 */
    private static final double HUMAN_RESPONSE = 0.9D;

    /** 单个采样点对电磁读数的贡献类别（纯函数，供单测）。 */
    enum Contribution {
        /** 计入信号积分：导电矿 / 岩浆 / 盐水。 */
        SIGNAL,
        /** 地下封闭淡水：不计入积分（否则把方位与距离锁向水面），只置干扰。 */
        WATER,
        /** 无贡献。 */
        NONE
    }

    /**
     * 一次采样的贡献：类别、权重与是否构成水体干扰。
     *
     * <p>纯函数，不触世界；{@link #integrates()} 就是 {@code measure} 里「是否进入
     * accum / sectors / nearest / depthWeighted / horizontalSpan」的判据。
     */
    record SampleContribution(Contribution kind, double unit, boolean waterInterference) {

        static SampleContribution of(boolean water, boolean saltWater, boolean lava, double oreUnit) {
            if (water) {
                return saltWater
                        ? new SampleContribution(Contribution.SIGNAL, SALT_WATER_RESPONSE, true)
                        : new SampleContribution(Contribution.WATER, 0.0D, true);
            }
            if (lava) {
                return new SampleContribution(Contribution.SIGNAL, LAVA_RESPONSE, false);
            }
            return oreUnit > 0.0D
                    ? new SampleContribution(Contribution.SIGNAL, oreUnit, false)
                    : new SampleContribution(Contribution.NONE, 0.0D, false);
        }

        boolean integrates() {
            return kind == Contribution.SIGNAL;
        }
    }

    /**
     * 执行一次探测。仅在服务端调用。
     *
     * @param origin 探测原点 —— 探头插地处（玩家脚下）
     */
    /**
     * 一次采样的响应权重:导电单位 × 该采样点代表的体素体积 ÷ 距离衰减。
     *
     * <p>远距采样时 {@link ToolScan.Adaptive} 的步长会变成 2 或 4,一个采样点代表 4 或 16 格;
     * 不乘体积就等于把远处的信号平白缩小 4~16 倍(与磁针、探锤同一个漏乘,Bug 见报告 17 §7.2)。
     */
    static double inductionWeight(double unit, double distance, int sx, int sy, int sz) {
        return unit * (double) (sx * sy * sz) / (1.0D + distance);
    }

    public static Reading measure(Level level, ItemStack stack, BlockPos origin, Config cfg) {
        final double[] sectors = new double[Bearing.values().length];
        final double[] accum = {0.0D};          // 总响应
        final double[] nearest = {Double.MAX_VALUE};
        final double[] depthWeighted = {0.0D};  // 深度加权和
        final double[] weightSum = {0.0D};
        final double[] horizontalSpan = {0.0D};
        final boolean[] human = {false};
        final boolean[] water = {false};

        // 逐列地表判定：湖泊 / 河流 / 海洋表层与植被先跳过，碰到该列第一个实心方块才入地
        final GroundColumns ground = new GroundColumns(cfg.radius());
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        ToolScan.scanCylinder(level, origin, cfg.radius(), cfg.verticalDepth(), 0,
                ToolScan.Adaptive.DEFAULT, (dx, dy, dz, distance, sx, sy, sz, state) -> {
                    if (cfg.humanInterference() && isHumanStructure(state)) {
                        // 人文干扰只回答"有没有结构",不参与体素积分,故不乘体积
                        final double w = HUMAN_RESPONSE / (1.0D + distance);
                        accum[0] += w;
                        sectors[Bearing.fromVector(dx, dz).ordinal()] += w;
                        human[0] = true;
                        return;
                    }

                    cursor.set(origin.getX() + dx, origin.getY() - dy, origin.getZ() + dz);
                    if (!ground.entered(dx, dz, state.isSolidRender(level, cursor))) {
                        // 该列仍在地表以上：敞开水体 / 空气 / 植被不计入地下导电信号
                        return;
                    }

                    final SampleContribution contribution = SampleContribution.of(
                            ProspectingFluids.isWater(state),
                            ProspectingFluids.isSaltWater(state),
                            ProspectingFluids.isLava(state),
                            oreUnit(state));
                    if (!contribution.integrates()) {
                        // 地下封闭淡水只置干扰，不进扇区 / 距离 / 深度统计（报告 18 §1 4c）
                        if (contribution.kind() == Contribution.WATER) {
                            water[0] = true;
                        }
                        return;
                    }
                    if (contribution.waterInterference()) {
                        water[0] = true;   // 盐水：低档正响应 + 干扰
                    }
                    final double w = inductionWeight(contribution.unit(), distance, sx, sy, sz);
                    accum[0] += w;
                    sectors[Bearing.fromVector(dx, dz).ordinal()] += w;
                    depthWeighted[0] += w * dy;
                    weightSum[0] += w;
                    final double horiz = Math.hypot(dx, dz);
                    nearest[0] = Math.min(nearest[0], distance);
                    horizontalSpan[0] = Math.max(horizontalSpan[0], horiz);
                });

        final double total = accum[0];
        final Conductivity conductivity;
        if (total < 0.05D) {
            conductivity = Conductivity.NONE;
        } else if (total < 0.6D) {
            conductivity = Conductivity.LOW;
        } else if (total < 2.5D) {
            conductivity = Conductivity.MEDIUM;
        } else {
            conductivity = Conductivity.HIGH;
        }

        if (conductivity == Conductivity.NONE) {
            return new Reading(Conductivity.NONE, Bearing.N, DistanceBand.NEAR, 0,
                    DepthHint.SHALLOW, Confidence.LOW, human[0], water[0]);
        }

        // 最强扇区 = 粗方位（等价于旋转线圈扫掠八方位取最强）
        int bestSector = 0;
        for (int i = 1; i < sectors.length; i++) {
            if (sectors[i] > sectors[bestSector]) {
                bestSector = i;
            }
        }
        final Bearing bearing = Bearing.values()[bestSector];

        final DistanceBand band = nearest[0] == Double.MAX_VALUE
                ? DistanceBand.FAR
                : DistanceBand.of(nearest[0], cfg.radius());

        final double meanDepth = weightSum[0] <= 0.0D ? 0.0D : depthWeighted[0] / weightSum[0];
        final double depthRatio = meanDepth / Math.max(1.0D, cfg.verticalDepth());
        final DepthHint depthHint = depthRatio < 0.34D ? DepthHint.SHALLOW
                : (depthRatio < 0.67D ? DepthHint.MEDIUM : DepthHint.DEEP);

        final int outlineRadius = (int) Math.round(horizontalSpan[0]);

        Confidence confidence = Conductivity.HIGH == conductivity ? Confidence.MEDIUM : Confidence.LOW;
        if (human[0]) {
            confidence = Confidence.LOW;   // 人文干扰下不可信（保持原有更严格的处置）
        }
        if (water[0]) {
            // 水体干扰：置信度封顶 MEDIUM，并在回执里写明来源
            confidence = capAtMedium(confidence);
        }

        return new Reading(conductivity, bearing, band, outlineRadius, depthHint, confidence,
                human[0], water[0]);
    }

    /** 干扰存在时的置信度上限：最多 MEDIUM（报告 18 §P1 第 3 条）。 */
    private static Confidence capAtMedium(Confidence confidence) {
        return confidence == Confidence.HIGH ? Confidence.MEDIUM : confidence;
    }

    /** 单个方块的矿体电性权重；0 表示无响应（流体由 {@link SampleContribution} 另行判定）。 */
    private static double oreUnit(BlockState state) {
        final String oreId = ReconScanner.oreIdOf(state);
        return oreId != null && OreTaxonomy.isConductive(oreId) ? 1.0D : 0.0D;
    }

    /** 是否为可造成人文干扰的金属结构（铁轨 / 金属块 / 含铁构件）。 */
    private static boolean isHumanStructure(BlockState state) {
        if (state.is(BlockTags.RAILS) || state.is(BlockTags.BEACON_BASE_BLOCKS)) {
            return true;
        }
        final ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null) {
            return false;
        }
        final String path = key.getPath().toLowerCase(Locale.ROOT);
        return path.contains("iron") || path.contains("rail") || path.contains("steel")
                || path.contains("copper_wire") || path.contains("aluminum");
    }

    private InductionProbeProspector() {}
}
