package com.tfcie.prospecting.common.item;

import com.tfcie.prospecting.api.ToolParam;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.Conductivity;
import com.tfcie.prospecting.api.enums.UseMode;
import com.tfcie.prospecting.common.prospecting.EnumNames;
import com.tfcie.prospecting.common.prospecting.InductionProbeProspector;
import com.tfcie.prospecting.common.prospecting.ProspectOutcome;
import com.tfcie.prospecting.common.prospecting.Readout;
import com.tfcie.prospecting.config.ProspectingConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Set;

/**
 * T3 感应探棒（电法·电磁·定位）：报导电异常的强度、八向粗方位、距离档与轮廓。
 *
 * <p>方向来自「旋转接收线圈扫掠八方位取最强响应」的等效实现（八扇区直方图），
 * 单点仅给粗方位与距离档 —— 精确走向需多测点插测或配合磁力探针。
 */
public final class InductionProbeItem extends AbstractProspectingItem {

    public static final String ID = "induction_probe";

    private static final String KEY_PREFIX = "message.tfcie_prospecting.induction_probe.";

    public InductionProbeItem() {
        super(new Item.Properties().stacksTo(1), ID, UseMode.PLACE_ACTIVATE, 160, 0);
    }

    @Override
    protected ToolParameters defaultParameters() {
        return new ToolParameters()
                .material("default")
                .maxDurability(0)
                .radius(ProspectingConfig.intOr(ProspectingConfig.InductionProbe.radius, 24))
                .energyCost(ProspectingConfig.intOr(ProspectingConfig.InductionProbe.energyCost, 5000));
    }

    @Override
    protected Set<ToolParam> allowedParameters() {
        return EnumSet.of(ToolParam.MAX_DURABILITY, ToolParam.RADIUS, ToolParam.ENERGY_COST);
    }

    @Override
    protected ProspectOutcome prospect(Level level, Player player, ItemStack stack, @Nullable BlockPos clicked) {
        final InductionProbeProspector.Config cfg = InductionProbeProspector.Config.from(parameters(stack));
        final InductionProbeProspector.Reading r =
                InductionProbeProspector.measure(level, stack, player.blockPosition(), cfg);

        final Readout readout = r.conductivity() == Conductivity.NONE
                ? Readout.of(KEY_PREFIX, "no_anomaly")
                : Readout.of(KEY_PREFIX, "result",
                        EnumNames.of("conductivity", r.conductivity()),
                        EnumNames.of("bearing", r.bearing()),
                        EnumNames.of("distance_band", r.distanceBand()),
                        EnumNames.of("depth_hint", r.depthHint()));

        // 干扰来源分开写：水体（地下封闭水/盐水）与人文金属结构是两种不同处置
        readout.noteIf(r.humanInterference(), "interference");
        readout.noteIf(r.waterInterference(), "interference_water");
        return ProspectOutcome.of(readout.result(ID, r.toData()), readout.display());
    }
}
