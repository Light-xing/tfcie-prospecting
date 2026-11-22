package com.tfcie.prospecting.common.item;

import com.tfcie.prospecting.api.OreTaxonomy;
import com.tfcie.prospecting.api.ToolParam;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.UseMode;
import com.tfcie.prospecting.common.prospecting.EnumNames;
import com.tfcie.prospecting.common.prospecting.ProspectOutcome;
import com.tfcie.prospecting.common.prospecting.ReconProspector;
import com.tfcie.prospecting.common.prospecting.ReconReading;
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
 * T1 踏勘镐：削弱版人工采样。
 *
 * <ul>
 *   <li>对<b>裸露岩面</b>右键使用（探测原点为玩家脚下，凿岩仅为触发条件）；</li>
 *   <li>水平半径默认 8 格、自脚下向下 24 格浅带；</li>
 *   <li>只报<b>迹象三档 + 矿族</b>，累计 3 次连续一致的有效采样后收敛出<b>疑似矿名</b>；</li>
 *   <li>置信度封顶 medium，永不 high；假阴性约 20%（青铜档）且不计数、不中断；</li>
 *   <li>耐久 2 点/次，冷却 3 秒，不可用于挖掘。</li>
 * </ul>
 *
 * <p>材质档位（铜 / 青铜 / 铁）只改变参数数值（假阴性、耐久上限等），
 * 由写进 NBT 的 {@link ToolParameters} 承载。
 */
public final class ReconPickItem extends AbstractProspectingItem {

    public static final String ID = "recon_pick";

    private static final String KEY_PREFIX = "message.tfcie_prospecting.recon_pick.";

    public ReconPickItem() {
        super(new Item.Properties().stacksTo(1), ID, UseMode.RIGHT_CLICK, 60, 2);
    }

    /** 默认青铜档。铜 / 铁档由材质参数覆盖白名单中的项。 */
    @Override
    protected ToolParameters defaultParameters() {
        return new ToolParameters()
                .material("bronze")
                .maxDurability(256)
                .radius(ProspectingConfig.intOr(ProspectingConfig.ReconPick.radius, 8))
                .shallowDepth(ProspectingConfig.intOr(ProspectingConfig.ReconPick.shallowDepth, 24))
                .falseNegativeChance(ProspectingConfig.doubleOr(ProspectingConfig.ReconPick.falseNegativeChance, 0.2))
                .confidenceCap(ProspectingConfig.stringOr(ProspectingConfig.ReconPick.confidenceCap, "medium"));
    }

    @Override
    protected Set<ToolParam> allowedParameters() {
        return EnumSet.of(
                ToolParam.MATERIAL,
                ToolParam.MAX_DURABILITY,
                ToolParam.RADIUS,
                ToolParam.SHALLOW_DEPTH,
                ToolParam.FALSE_NEGATIVE_CHANCE,
                ToolParam.CONFIDENCE_CAP);
    }

    @Override
    protected ProspectOutcome prospect(Level level, Player player, ItemStack stack, @Nullable BlockPos clicked) {
        if (clicked == null) {
            return rejected(ReconProspector.REJECT_NEED_ROCK);
        }

        final ReconProspector.Config cfg = ReconProspector.Config.from(parameters(stack));
        final ReconProspector.Outcome outcome = ReconProspector.sample(
                level, stack, player.blockPosition(), clicked, cfg, level.getRandom());

        if (!outcome.accepted()) {
            return rejected(outcome.rejectionKey());
        }

        final ReconReading r = outcome.reading();
        final Readout readout;
        if (r.repeated()) {
            readout = Readout.of(KEY_PREFIX, "saturated");
        } else if (r.converged() && r.suspectedOreId() != null) {
            readout = Readout.of(KEY_PREFIX, "converged",
                    OreTaxonomy.displayName(r.suspectedOreId()),
                    EnumNames.of("confidence", r.confidence()));
        } else {
            readout = Readout.of(KEY_PREFIX, "result",
                    EnumNames.of("presence", r.presence()),
                    EnumNames.of("ore_group", r.group()),
                    EnumNames.of("confidence", r.confidence()));
        }
        readout.noteIf(r.interference().isFalsePositive(), "interference." + r.interference().wire());

        return ProspectOutcome.of(readout.result(ID, r.toData()), readout.display());
    }
}
