package com.tfcie.prospecting.common.survey;

import com.tfcie.prospecting.api.OreTaxonomy;
import com.tfcie.prospecting.api.enums.DensityClass;
import com.tfcie.prospecting.api.enums.Magnetism;
import com.tfcie.prospecting.common.prospecting.ReconScanner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import javax.annotation.Nullable;

/**
 * 探查过滤器：决定「哪些方块算命中、命中后归到哪个数据键」。
 *
 * <p>这是各改进版工具与电子探矿仪之间<b>唯一的差异点</b>——扫描框架、地图、GUI 全部共用，
 * 只有这里换了判定集合。分工按用户确认：<b>改进版只染本方法的异常</b>，
 * 电子探矿仪才给全部矿种。
 *
 * <p>本类可直接给出真实矿种，不受「推断类工具永不输出精确坐标」的红线约束 ——
 * 因为这条红线针对的是玩家循常规途径获得的推断工具，而这批仪器不走常规获取。
 */
public enum SurveyFilter {

    /** 电子探矿仪：全部矿物，直接给出矿种。 */
    ALL_ORES("electronic_prospector"),

    /** 改进版踏勘镐：浅带内的全部矿物。 */
    RECON("advanced_recon_pick"),

    /** 改进版感应探棒：仅导电矿物（孔雀石不导电、镍矿全盲由物性表保证）。 */
    CONDUCTIVE("advanced_induction_probe"),

    /** 改进版磁力探针：仅磁性矿物（磁铁矿强磁、赤铁矿弱磁、褐铁矿无磁）。 */
    MAGNETIC("advanced_magnetic_needle"),

    /** 改进版密度探锤：高/低密度矿物，外加空腔与流体（密度的三类来源）。 */
    DENSITY("advanced_density_plumb"),

    /** 改进版回声探针：空腔、流体，以及洞壁暴露的矿面（不辨矿种）。 */
    ECHO("advanced_echo_probe");

    private final String toolId;

    SurveyFilter(String toolId) {
        this.toolId = toolId;
    }

    /** 该过滤器归属的工具 id（用于命令与 tooltip）。 */
    public String toolId() {
        return toolId;
    }

    @Nullable
    public static SurveyFilter byToolId(String toolId) {
        for (SurveyFilter filter : values()) {
            if (filter.toolId.equals(toolId)) {
                return filter;
            }
        }
        return null;
    }

    /**
     * 判定一个方块是否命中，并给出数据键。
     *
     * <h2>改进版只给「迹象类别」，不给矿种</h2>
     * 除电子探矿仪外，各改进版一律返回<b>方法层面的迹象类别</b>
     * （如 {@code magnetic_strong}、{@code conductive}、{@code density_low}），
     * 而不是命中的具体矿种。原因有二：
     * <ol>
     *   <li><b>与各方法的物理能力对齐</b>：磁法只能判磁与非磁，电法只能判导电与否，
     *       重力法只能给密度符号 —— 直接报「磁铁矿 / 赤铁矿」等于凭空多给了它们
     *       并不具备的辨识力；</li>
     *   <li><b>把「矿种」留给电子探矿仪</b>：否则改进版磁力探针就等价于
     *       「只筛磁性矿的电子探矿仪」，两者分工崩塌。</li>
     * </ol>
     * 于是玩家在改进版地图上看到的是「这里有强磁迹象」，需要自己结合多种方法判读；
     * 要直接看到矿种名，得用电子探矿仪。
     *
     * @return 数据键；{@code null} 表示该方块在此方法下无响应
     */
    @Nullable
    public String keyOf(BlockState state) {
        final String oreId = ReconScanner.oreIdOf(state);
        return switch (this) {
            // 唯一直接给出矿种的过滤器
            case ALL_ORES -> oreId;

            // 踏勘镐：只报「浅带存在矿物迹象」，不说是哪种
            case RECON -> oreId != null ? OreColors.RECON_TRACE : null;

            // 电法：只报导电异常（孔雀石不导电、镍矿全盲由物性表保证）
            case CONDUCTIVE ->
                    oreId != null && OreTaxonomy.isConductive(oreId) ? OreColors.CONDUCTIVE : null;

            // 磁法：按物性表分强/弱两档，不报矿种
            case MAGNETIC -> switch (oreId == null ? Magnetism.NONE : OreTaxonomy.magnetismOf(oreId)) {
                case STRONG -> OreColors.MAGNETIC_STRONG;
                case WEAK -> OreColors.MAGNETIC_WEAK;
                case NONE -> null;
            };

            // 重力法：只有符号可判
            case DENSITY -> {
                if (oreId != null) {
                    final DensityClass density = OreTaxonomy.densityOf(oreId);
                    if (density == DensityClass.HIGH) {
                        yield OreColors.DENSITY_HIGH;
                    }
                    if (density == DensityClass.LOW) {
                        yield OreColors.DENSITY_LOW;
                    }
                }
                if (isLava(state)) {
                    yield OreColors.FLUID_LAVA;
                }
                if (isWater(state)) {
                    yield OreColors.FLUID_WATER;
                }
                yield state.isAir() ? OreColors.CAVITY : null;
            }

            // 声呐：几何与流体可判，矿面只报「有暴露矿」不辨矿种（v1.3 评审结论）
            case ECHO -> {
                if (state.isAir()) {
                    yield OreColors.CAVITY;
                }
                if (isLava(state)) {
                    yield OreColors.FLUID_LAVA;
                }
                if (isWater(state)) {
                    yield OreColors.FLUID_WATER;
                }
                yield oreId != null ? OreColors.EXPOSED_ORE : null;
            }
        };
    }

    private static boolean isWater(BlockState state) {
        final var fluid = state.getFluidState().getType();
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
    }

    private static boolean isLava(BlockState state) {
        final var fluid = state.getFluidState().getType();
        return fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;
    }
}
