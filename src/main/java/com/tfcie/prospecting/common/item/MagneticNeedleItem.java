package com.tfcie.prospecting.common.item;

import com.tfcie.prospecting.api.ToolParam;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.MagneticClass;
import com.tfcie.prospecting.api.enums.UseMode;
import com.tfcie.prospecting.common.prospecting.EnumNames;
import com.tfcie.prospecting.common.prospecting.MagneticNeedleProspector;
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
 * T4 磁力探针（磁法）：报磁性强度、16 向方位角、矿脉走向与磁/非磁判别。
 *
 * <p>方位角由「内置磁梯度传感器」给出 —— 实现上取扫描范围内磁质体的<b>矢量合成方向</b>，
 * 量化到 16 向（22.5° 一档）。<b>走向需至少 2 个测点</b>，单点仅给方位角与强度。
 */
public final class MagneticNeedleItem extends AbstractProspectingItem {

    public static final String ID = "magnetic_needle";

    private static final String KEY_PREFIX = "message.tfcie_prospecting.magnetic_needle.";

    public MagneticNeedleItem() {
        super(new Item.Properties().stacksTo(1), ID, UseMode.RIGHT_CLICK, 120, 0);
    }

    @Override
    protected ToolParameters defaultParameters() {
        return new ToolParameters()
                .material("default")
                .maxDurability(0)
                .radius(ProspectingConfig.intOr(ProspectingConfig.MagneticNeedle.radius, 32));
    }

    @Override
    protected Set<ToolParam> allowedParameters() {
        return EnumSet.of(ToolParam.MAX_DURABILITY, ToolParam.RADIUS);
    }

    @Override
    protected ProspectOutcome prospect(Level level, Player player, ItemStack stack, @Nullable BlockPos clicked) {
        final MagneticNeedleProspector.Config cfg = MagneticNeedleProspector.Config.from(parameters(stack));
        final MagneticNeedleProspector.Reading r =
                MagneticNeedleProspector.measure(level, stack, player.blockPosition(), cfg);

        final Readout readout;
        if (r.magneticClass() == MagneticClass.NONE) {
            readout = Readout.of(KEY_PREFIX, "no_anomaly");
        } else if (r.strike() != null) {
            readout = Readout.of(KEY_PREFIX, "result",
                    EnumNames.of("field_strength", r.fieldStrength()),
                    r.bearingDegrees(),
                    EnumNames.of("strike", r.strike()),
                    EnumNames.of("extent", r.lateralExtent()),
                    EnumNames.of("extent", r.verticalExtent()));
        } else {
            readout = Readout.of(KEY_PREFIX, "result_no_strike",
                    EnumNames.of("field_strength", r.fieldStrength()),
                    r.bearingDegrees(),
                    EnumNames.of("extent", r.lateralExtent()),
                    EnumNames.of("extent", r.verticalExtent()));
        }

        // 走向需至少两个测点，单点时应明确告知「测点不足」而不是让玩家以为没有走向
        readout.noteIf(r.magneticClass() != MagneticClass.NONE && r.strike() == null, "insufficient_samples");
        readout.noteIf(r.interference(), "interference");

        return ProspectOutcome.of(readout.result(ID, r.toData()), readout.display());
    }
}
