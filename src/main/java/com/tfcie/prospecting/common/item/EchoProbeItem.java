package com.tfcie.prospecting.common.item;

import com.tfcie.prospecting.api.ToolParam;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.UseMode;
import com.tfcie.prospecting.common.prospecting.EchoProbeProspector;
import com.tfcie.prospecting.common.prospecting.EnumNames;
import com.tfcie.prospecting.common.prospecting.ProspectOutcome;
import com.tfcie.prospecting.common.prospecting.Readout;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Set;

/**
 * T5 回声探针（超声·声波）：近距局部成像空腔 / 流体 / 洞壁暴露矿。
 *
 * <p>两种耦合模式：<b>接触模式</b>（紧贴石质方块，近距 8 格）与<b>浸水模式</b>
 * （站在水中，16 格）；干燥空气中几乎无效。
 *
 * <p>矿种判定非本工具职责 —— 声波只测波阻抗差异，能发现「洞壁有矿面」并给出其 y，
 * 但<b>无法分辨是哪种矿</b>（设计文档 v1.3 评审结论：报告矿种属越权）。
 */
public final class EchoProbeItem extends AbstractProspectingItem {

    public static final String ID = "echo_probe";

    private static final String KEY_PREFIX = "message.tfcie_prospecting.echo_probe.";

    public EchoProbeItem() {
        super(new Item.Properties().stacksTo(1), ID, UseMode.CONTACT, 60, 1);
    }

    @Override
    protected ToolParameters defaultParameters() {
        return new ToolParameters()
                .material("default")
                .maxDurability(128);
    }

    @Override
    protected Set<ToolParam> allowedParameters() {
        return EnumSet.of(ToolParam.MATERIAL, ToolParam.MAX_DURABILITY);
    }

    @Override
    protected ProspectOutcome prospect(Level level, Player player, ItemStack stack, @Nullable BlockPos clicked) {
        final BlockPos origin = player.blockPosition();
        final EchoProbeProspector.Config cfg = EchoProbeProspector.Config.from(parameters(stack));

        if (!EchoProbeProspector.canCouple(level, player, origin)) {
            return rejected(EchoProbeProspector.REJECT_NEED_COUPLING);
        }

        final EchoProbeProspector.Reading r = EchoProbeProspector.image(level, player, origin, cfg);

        final Component orePart = r.exposedOreCount() > 0
                ? Component.translatable(KEY_PREFIX + "ore_hits", r.exposedOreCount())
                : Component.translatable(KEY_PREFIX + "ore_none");

        final Readout readout = Readout.ofComponent(KEY_PREFIX, r.cavityFound()
                ? Component.translatable(KEY_PREFIX + "result",
                        r.cavityDistance(), r.ceilingY(), orePart,
                        EnumNames.of("fluid", r.fluid()),
                        EnumNames.of("hazard", r.hazard()))
                : Component.translatable(KEY_PREFIX + "no_cavity", orePart));

        return ProspectOutcome.of(readout.result(ID, r.toData()), readout.display());
    }
}
