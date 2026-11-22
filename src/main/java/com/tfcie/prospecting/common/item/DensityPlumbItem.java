package com.tfcie.prospecting.common.item;

import com.tfcie.prospecting.api.ToolParam;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.DensityAnomaly;
import com.tfcie.prospecting.api.enums.UseMode;
import com.tfcie.prospecting.common.prospecting.DensityPlumbProspector;
import com.tfcie.prospecting.common.prospecting.EnumNames;
import com.tfcie.prospecting.common.prospecting.ProspectOutcome;
import com.tfcie.prospecting.common.prospecting.Readout;
import com.tfcie.prospecting.config.ProspectingConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * T2 密度探锤（重力法·定深）：报脚下垂向的密度异常符号与粗粒度深度带。
 *
 * <p>关键限制（严格对齐设计文档）：<b>无方位</b>；只给密度异常符号，
 * <b>无法区分造成低密度异常的具体物质</b>（煤 / 盐 / 石膏 / 硼砂 / 空洞 / 水囊 / 岩浆腔
 * 一律表现为低密度）；深度结论以钻探取样为准。
 */
public final class DensityPlumbItem extends AbstractProspectingItem {

    public static final String ID = "density_plumb";

    private static final String KEY_PREFIX = "message.tfcie_prospecting.density_plumb.";

    public DensityPlumbItem() {
        super(new Item.Properties().stacksTo(1), ID, UseMode.PLACE_ACTIVATE, 100, 1);
    }

    @Override
    protected ToolParameters defaultParameters() {
        return new ToolParameters()
                .material("default")
                .maxDurability(192)
                .radius(0)
                .maxDepth(ProspectingConfig.intOr(ProspectingConfig.DensityPlumb.maxDepth, 96));
    }

    @Override
    protected Set<ToolParam> allowedParameters() {
        return EnumSet.of(ToolParam.MATERIAL, ToolParam.MAX_DURABILITY, ToolParam.MAX_DEPTH);
    }

    /** 提示校准流程：砝码是方块，不提示放置智能体就不会主动摆基点（报告 16 §1.4 ④）。 */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.tfcie_prospecting.density_plumb.calibration")
                .withStyle(ChatFormatting.GRAY));
    }

    @Override
    protected ProspectOutcome prospect(Level level, Player player, ItemStack stack, @Nullable BlockPos clicked) {
        final DensityPlumbProspector.Config cfg = DensityPlumbProspector.Config.from(parameters(stack));
        final DensityPlumbProspector.Reading r =
                DensityPlumbProspector.measure(level, stack, player.blockPosition(), cfg);

        final Readout readout = r.anomaly() == DensityAnomaly.NONE
                ? Readout.of(KEY_PREFIX, "no_anomaly")
                : Readout.of(KEY_PREFIX, "result",
                        r.depthFrom(), r.depthTo(),
                        EnumNames.of("magnitude", r.magnitude()),
                        EnumNames.of("density_anomaly", r.anomaly()),
                        EnumNames.of("confidence", r.confidence()));

        if (r.atBase()) {
            readout.note("recalibrated");
        } else {
            // 有漂移偏移时必须在消息里说清「读数已偏移、需回基点复测」，
            // 不能只降置信度却让智能体继续把偏移后的深度带当真值（报告 18 §1 7e）
            readout.noteIf(r.drifted(), "drifted", r.driftOffset());
            readout.noteIf(r.needsCalibration(), "needs_calibration");
        }

        return ProspectOutcome.of(readout.result(ID, r.toData()), readout.display());
    }
}
