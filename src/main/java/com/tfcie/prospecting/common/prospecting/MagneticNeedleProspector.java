package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.OreTaxonomy;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.Confidence;
import com.tfcie.prospecting.api.enums.Extent;
import com.tfcie.prospecting.api.enums.FieldStrength;
import com.tfcie.prospecting.api.enums.MagneticClass;
import com.tfcie.prospecting.api.enums.Strike;
import com.tfcie.prospecting.config.ProspectingConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T4 磁力探针（磁法）的读数逻辑。
 *
 * <h2>算法：矢量合成，而非逐向扫掠</h2>
 * 对扫描范围内每个磁性方块，取其<b>水平单位方向向量</b>按磁偶极场衰减
 * {@code 1/(1+d²)} 加权求和，再对该合成向量取 {@code atan2} 得到方位角，
 * 量化到 16 向（22.5° 一档）。求和时每个采样点还要乘它代表的体素体积
 * {@code sx·sy·sz}（见 {@link #magneticWeight}），否则水平粗采样区的读数会被
 * 平白除小 —— 这是报告 16 §1.4 ② 的积分漏乘体素体积修复。
 *
 * <p>相比「扫掠 16 个方位各测一次」，矢量合成只需一遍扫描，且输出是<b>连续角度</b>
 * 再量化，而非在 16 个离散方向上比大小 —— 精度更稳、代价更低。物理上这也正是
 * 磁偶极场叠加的直接结果。
 *
 * <h2>严格对齐设计文档的盲区</h2>
 * <ul>
 *   <li>对铜、锡、金、银、煤、石墨、硫、盐、宝石<b>完全无响应</b>（物性表磁性为 NONE）；</li>
 *   <li><b>只判磁 / 非磁，不分辨具体磁性矿种</b>；铁矿按形态分化：
 *       磁铁矿强磁、赤铁矿弱磁、褐铁矿无磁；硅镁镍矿无磁（属物探盲区）；</li>
 *   <li><b>走向需至少 2 个测点</b>，单点仅给方位角与强度；</li>
 *   <li>雷雨天气读数噪声上升；铁轨 / 铁块 / IE 机械造成干扰。</li>
 * </ul>
 */
public final class MagneticNeedleProspector {

    private static final String NBT_ROOT = "tfcie_prospecting:magnetic_station";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";
    private static final String KEY_VX = "vx";
    private static final String KEY_VZ = "vz";

    /** 行为参数。 */
    public record Config(int radius,
                         int verticalDepth,
                         boolean weatherNoise,
                         boolean strikeRequiresMultiSample,
                         boolean humanInterference) {

        public static Config from(ToolParameters params) {
            return new Config(
                    params.radius(),
                    ProspectingConfig.intOr(ProspectingConfig.MagneticNeedle.verticalDepth, 48),
                    ProspectingConfig.boolOr(ProspectingConfig.MagneticNeedle.weatherNoise, true),
                    ProspectingConfig.boolOr(ProspectingConfig.MagneticNeedle.strikeRequiresMultiSample, true),
                    ProspectingConfig.boolOr(ProspectingConfig.MagneticNeedle.humanInterference, true));
        }
    }

    /** 一次读数。 */
    public record Reading(FieldStrength fieldStrength,
                          double fieldReading,
                          int bearingDegrees,
                          @Nullable Strike strike,
                          Extent lateralExtent,
                          Extent verticalExtent,
                          MagneticClass magneticClass,
                          Confidence confidence,
                          boolean interference,
                          boolean strikeReady) {

        public Map<String, Object> toData() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("field_strength", fieldStrength);
            d.put("field_reading", Math.round(fieldReading * 100.0D) / 100.0D);
            d.put("magnetic_class", magneticClass);
            if (magneticClass != MagneticClass.NONE) {
                d.put("bearing_degrees", bearingDegrees);
                d.put("lateral_extent", lateralExtent);
                d.put("vertical_extent", verticalExtent);
            }
            if (strike != null) {
                d.put("strike", strike);
            }
            d.put("strike_ready", strikeReady);
            d.put("confidence", confidence);
            d.put("interference", interference);
            return d;
        }
    }

    /** 判定走向所需的最小测点间距（格）。 */
    private static final int STRIKE_MIN_DISTANCE = 6;

    /**
     * 向上的探测窗口（格）。
     *
     * <p>磁偶极场是全向的，没有理由只向下看；但 {@link ToolScan#scanCylinder} 过去起点写死
     * {@code dy = 0}，于是「脚上方一格」整层落进盲区 —— 报告 13 §13.3 / §14.1 实测：
     * 17 块矿带放在 feet+1 读数为 0.0，脚下一层 1256.84。这里给一个<b>小</b>的向上窗口，
     * 既补上「露头在侧上方」的漏报，又不改变「以向下探测为主」的方法语义（向下仍是
     * {@code verticalDepth} 格）。4 格与近距逐格采样的 {@code Adaptive.nearRadius = 8}
     * 同量级，不会显著增加扫描量。
     */
    private static final int UP_WINDOW = 4;

    /**
     * 两点法的合成场:两个测点的水平向量都指向矿体中心。测点跨在矿脉两侧时两个向量反号,直接相加会互相抵消
     * ({@code vx + prevVx ≈ 0}),走向就变成噪声;按点积把上一个测点翻到同一侧再相加,同侧时原样相加。
     * 返回 {@code {combVx, combVz}}(未归一化)。
     */
    static double[] alignedField(double vx, double vz, double prevVx, double prevVz) {
        final double sign = vx * prevVx + vz * prevVz < 0.0D ? -1.0D : 1.0D;
        return new double[]{vx + sign * prevVx, vz + sign * prevVz};
    }

    public static Reading measure(Level level, ItemStack stack, BlockPos origin, Config cfg) {
        final double[] vx = {0.0D};
        final double[] vz = {0.0D};
        final double[] total = {0.0D};
        final double[] minX = {Double.MAX_VALUE};
        final double[] maxX = {-Double.MAX_VALUE};
        final double[] minY = {Double.MAX_VALUE};
        final double[] maxY = {-Double.MAX_VALUE};
        final boolean[] human = {false};

        ToolScan.scanCylinder(level, origin, cfg.radius(), cfg.verticalDepth(), UP_WINDOW,
                ToolScan.Adaptive.DEFAULT, (dx, dy, dz, distance, sx, sy, sz, state) -> {
                    // 先认矿种、再判干扰：名字里带 iron 的矿石（如 IE 的赤铁矿）本身是目标物，
                    // 不能被 isFerrousStructure 当成人文干扰吞掉；只有非矿方块才走干扰判定
                    final String oreId = ReconScanner.oreIdOf(state);
                    if (oreId == null) {
                        if (cfg.humanInterference() && isFerrousStructure(state)) {
                            human[0] = true;
                        }
                        return;
                    }
                    final double unit = OreTaxonomy.magnetismOf(oreId).weight();
                    if (unit <= 0.0D) {
                        return;
                    }
                    // 磁偶极场衰减 × 该采样点代表的体素体积
                    final double w = magneticWeight(unit, distance, sx, sy, sz);
                    final double horiz = Math.hypot(dx, dz);
                    if (horiz > 1.0E-6D) {
                        vx[0] += (dx / horiz) * w;
                        vz[0] += (dz / horiz) * w;
                    }
                    total[0] += w;

                    minX[0] = Math.min(minX[0], dx);
                    maxX[0] = Math.max(maxX[0], dx);
                    minY[0] = Math.min(minY[0], dy);
                    maxY[0] = Math.max(maxY[0], dy);
                });

        double field = total[0] * 1000.0D;   // 伪 nT 读数，便于展示量级

        // 雷雨天气噪声
        boolean storm = false;
        if (cfg.weatherNoise() && level.isThundering()) {
            storm = true;
            field *= 0.7D + level.getRandom().nextDouble() * 0.6D;
        }

        final MagneticClass magneticClass = total[0] <= 1.0E-6D ? MagneticClass.NONE : MagneticClass.MAGNETIC;
        final FieldStrength strength = FieldStrength.of(Math.min(1.0D, total[0]));

        // 方位角：合成向量 → 角度 → 量化到 16 向（22.5° 一档）
        double angle = Math.toDegrees(Math.atan2(vx[0], -vz[0]));
        if (angle < 0) {
            angle += 360.0D;
        }
        final int sector = (int) (Math.round(angle / 22.5D) % 16);
        final int bearingDegrees = MagneticClass.NONE == magneticClass
                ? 0
                : (int) Math.round(sector * 22.5D);

        final Extent lateral = magneticClass == MagneticClass.NONE ? Extent.SMALL
                : Extent.ofSpan(maxX[0] - minX[0], cfg.radius() * 0.5D, cfg.radius() * 0.9D);
        // 垂向覆盖现在是 [-UP_WINDOW, verticalDepth]：minY 可为负，跨度取两边之差即可；
        // 分档阈值也按「向下 + 向上」的总跨度归一，避免向上窗口把整段矿体挤成同一档。
        final double verticalSpan = maxY[0] - minY[0];
        final double verticalRange = cfg.verticalDepth() + UP_WINDOW;
        final Extent vertical = magneticClass == MagneticClass.NONE ? Extent.SMALL
                : Extent.ofSpan(verticalSpan, verticalRange * 0.5D, verticalRange * 0.9D);

        // 走向：需要 ≥2 个测点且间距足够
        final BlockPos previous = readStation(stack);
        Strike strike = null;
        boolean strikeReady = false;
        if (MagneticClass.MAGNETIC == magneticClass && previous != null) {
            final double ddx = origin.getX() - previous.getX();
            final double ddz = origin.getZ() - previous.getZ();
            if (Math.hypot(ddx, ddz) >= STRIKE_MIN_DISTANCE) {
                final CompoundTag stationTag = stack.getTagElement(NBT_ROOT);
                final double prevVx = stationTag != null ? stationTag.getDouble(KEY_VX) : 0.0D;
                final double prevVz = stationTag != null ? stationTag.getDouble(KEY_VZ) : 0.0D;
                final double[] comb = alignedField(vx[0], vz[0], prevVx, prevVz);
                double combVx = comb[0];
                double combVz = comb[1];
                if (Math.hypot(combVx, combVz) <= 1.0E-6D) {
                    combVx = vx[0];
                    combVz = vz[0];
                }
                strike = Strike.fromVector(combVz, -combVx);
                strikeReady = true;
            }
        }
        if (!cfg.strikeRequiresMultiSample() && MagneticClass.MAGNETIC == magneticClass) {
            // 配置关闭多测点要求时，用单点方位角的垂直方向给出走向
            strike = strike == null ? Strike.fromVector(vz[0], -vx[0]) : strike;
            strikeReady = true;
        }
        writeStation(stack, origin, vx[0], vz[0]);

        Confidence confidence = switch (strength) {
            case STRONG -> Confidence.HIGH;
            case MEDIUM -> Confidence.MEDIUM;
            default -> Confidence.LOW;
        };
        if (storm || human[0]) {
            confidence = downgrade(confidence);
        }

        return new Reading(strength, field, bearingDegrees, strike, lateral, vertical,
                magneticClass, confidence, human[0], strikeReady);
    }

    /**
     * 单点磁权重：{@code unit · sx·sy·sz / (1 + d²)}。
     *
     * <p>{@code sx·sy·sz} 是该采样点代表的体素体积（水平自适应步长的面积 × 垂向步长 1）。
     * 粗采样时一个点代表多格，体积积分必须乘上它（报告 16 §1.4 ②）。包内可见以便单测。
     */
    static double magneticWeight(double unit, double distance, int sx, int sy, int sz) {
        return unit * (double) (sx * sy * sz) / (1.0D + distance * distance);
    }

    private static boolean isFerrousStructure(BlockState state) {
        final var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null) {
            return false;
        }
        final String path = key.getPath().toLowerCase(java.util.Locale.ROOT);
        return path.contains("iron") || path.contains("rail") || path.contains("steel");
    }

    private static Confidence downgrade(Confidence c) {
        return switch (c) {
            case HIGH -> Confidence.MEDIUM;
            case MEDIUM -> Confidence.LOW;
            case LOW -> Confidence.LOW;
        };
    }

    // ---------------- 测点记录（用于走向判定） ----------------

    @Nullable
    private static BlockPos readStation(ItemStack stack) {
        final CompoundTag tag = stack.getTagElement(NBT_ROOT);
        if (tag == null || !tag.contains(KEY_X)) {
            return null;
        }
        return new BlockPos(tag.getInt(KEY_X), tag.getInt(KEY_Y), tag.getInt(KEY_Z));
    }

    private static void writeStation(ItemStack stack, BlockPos pos, double vx, double vz) {
        final CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_X, pos.getX());
        tag.putInt(KEY_Y, pos.getY());
        tag.putInt(KEY_Z, pos.getZ());
        tag.putDouble(KEY_VX, vx);
        tag.putDouble(KEY_VZ, vz);
        stack.getOrCreateTag().put(NBT_ROOT, tag);
    }

    /** 供提示文案使用：量化前的角度工具。 */
    public static int quantize(double angleDegrees) {
        return (int) (Math.round(Mth.positiveModulo(angleDegrees, 360.0D) / 22.5D) % 16);
    }

    private MagneticNeedleProspector() {}
}
