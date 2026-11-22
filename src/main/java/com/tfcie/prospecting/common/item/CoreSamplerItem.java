package com.tfcie.prospecting.common.item;

import com.tfcie.prospecting.api.ToolParam;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.TerminatedBy;
import com.tfcie.prospecting.api.enums.UseMode;
import com.tfcie.prospecting.common.prospecting.CoreSampleData;
import com.tfcie.prospecting.common.prospecting.CoreSamplerProspector;
import com.tfcie.prospecting.common.prospecting.ProspectOutcome;
import com.tfcie.prospecting.common.prospecting.Readout;
import com.tfcie.prospecting.config.ProspectingConfig;
import com.tfcie.prospecting.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * T6 钻探取样器：对准实体方块右键，<b>即时</b>取芯并产出钻探样本（唯一真值工具）。
 *
 * <h2>交互</h2>
 * <ul>
 *   <li><b>对实体方块右键</b> —— 立刻从该方块起沿垂向读取岩层并生成样本；</li>
 *   <li><b>潜行右键</b> —— 循环切换深度档（{@code 8/16/32/64}，按配置上限裁剪）。</li>
 * </ul>
 * 取样起点是<b>被点击的方块本身</b>，因此不必站在矿体正上方，对着目标方块点即可；
 * 代价是它仍只能测一个点、无横向信息。
 *
 * <h2>红线</h2>
 * <b>不产出任何矿物掉落物，只产出数据</b>；默认不留钻孔，且即使开启留孔也不允许
 * 钻孔方块掉落（{@code shaft_drops_items} 必须为 false），否则退化为免费挖矿机。
 * 当前实现全程只读取、不修改世界。
 */
public final class CoreSamplerItem extends AbstractProspectingItem {

    public static final String ID = "core_sampler";

    private static final String KEY_PREFIX = "message.tfcie_prospecting.core_sampler.";

    public CoreSamplerItem() {
        super(new Item.Properties().stacksTo(1), ID, UseMode.RIGHT_CLICK, 200, 1);
    }

    @Override
    protected ToolParameters defaultParameters() {
        return new ToolParameters()
                .material("default")
                .maxDurability(256)
                .maxDepth(ProspectingConfig.intOr(ProspectingConfig.CoreSampler.maxDepth, 64));
    }

    @Override
    protected Set<ToolParam> allowedParameters() {
        return EnumSet.of(ToolParam.MATERIAL, ToolParam.MAX_DURABILITY, ToolParam.MAX_DEPTH);
    }

    /** 潜行右键：切换深度档位。 */
    @Override
    protected boolean onSneakUse(Level level, Player player, ItemStack stack) {
        final CoreSamplerProspector.Config cfg = CoreSamplerProspector.Config.from(parameters(stack));
        final int next = CoreSamplerProspector.nextDepthStep(depthOf(stack, cfg), cfg.maxDepth());
        writeDepth(stack, next);
        if (!level.isClientSide) {
            player.displayClientMessage(
                    Component.translatable(KEY_PREFIX + "depth_selected", next), true);
        }
        return true;
    }

    /** 对实体方块右键：即时取芯。 */
    @Override
    protected ProspectOutcome prospect(Level level, Player player, ItemStack stack, @Nullable BlockPos clicked) {
        if (clicked == null) {
            return rejected(KEY_PREFIX + "need_block");
        }
        final BlockState target = level.getBlockState(clicked);
        if (!target.isSolidRender(level, clicked)) {
            return rejected(KEY_PREFIX + "need_solid_block");
        }

        final CoreSamplerProspector.Config cfg = CoreSamplerProspector.Config.from(parameters(stack));
        final int depth = depthOf(stack, cfg);
        final CoreSampleData data = CoreSamplerProspector.drill(level, clicked, depth, cfg, level.getRandom());

        // 满包前置检查：样本 stacksTo(1) 且 NBT 唯一（不可堆叠），只能放进空槽；
        // 没有空槽就直接拒绝本次取芯 —— 不扣耐久、也不把样本丢地上（报告 18 §1 第 6 条）
        final boolean hasRoom = player.getInventory().getFreeSlot() >= 0;
        final CoreSamplerProspector.SampleDecision decision =
                CoreSamplerProspector.decideSample(data, hasRoom);
        if (decision == CoreSamplerProspector.SampleDecision.INVENTORY_FULL) {
            return rejected(KEY_PREFIX + "inventory_full");
        }

        if (decision == CoreSamplerProspector.SampleDecision.STORE) {
            // 产出样本（不产出任何矿物掉落物）
            final ItemStack sample = new ItemStack(ModItems.CORE_SAMPLE.get());
            // 给样本一个带出处的显示名：背包里同时有多份样本时，numen 的 StackLabel 会把 display
            // 里的名字一起写进 runtime_state（carrying=…core_sample[钻探样本 (3958, 63, 3724)]），
            // 台账才能按坐标对账；GUI/tooltip 也顺带有了出处。样本数据本身（NBT 根键）不动，
            // 旧样本没有这个名字时照旧显示。
            sample.setHoverName(Component.translatable("item.tfcie_prospecting.core_sample.named",
                    data.origin().getX(), data.origin().getY(), data.origin().getZ()));
            sample.getOrCreateTag().put(CoreSampleData.NBT_ROOT, data.toTag());
            if (!player.addItem(sample)) {
                // 前置检查通过却仍放不进（边界情况）：同样拒绝，绝不丢地上
                return rejected(KEY_PREFIX + "inventory_full");
            }
        }

        // 遇岩浆有概率额外损坏取样器
        if (data.terminatedBy() == TerminatedBy.LAVA
                && level.getRandom().nextDouble() < cfg.lavaDamageChance()) {
            applyWear(stack, Math.max(1, maxDurability(stack) / 4), player);
        }

        final Readout readout = Readout.ofComponent(KEY_PREFIX, switch (data.terminatedBy()) {
            case LAVA -> Component.translatable(KEY_PREFIX + "terminated_lava");
            case BEDROCK -> Component.translatable(KEY_PREFIX + "terminated_bedrock", data.endY());
            case OBSTRUCTION -> Component.translatable(KEY_PREFIX + "terminated_obstruction");
            case MAX_DEPTH -> Component.translatable(KEY_PREFIX + "done", data.depth());
        });
        // 空柱不产实物样本，但读数照常返回；在回执里说明，免得智能体以为样本丢了
        readout.noteIf(decision == CoreSamplerProspector.SampleDecision.EMPTY_CORE, "empty_core");

        return ProspectOutcome.of(readout.result(ID, data.toData()), readout.display());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable(KEY_PREFIX + "depth_tooltip", currentDepth(stack))
                .withStyle(ChatFormatting.GRAY));
    }

    // ---------------- 深度档位 ----------------

    private static int depthOf(ItemStack stack, CoreSamplerProspector.Config cfg) {
        final CompoundTag tag = stack.getTag();
        final int configured = tag != null && tag.contains(CoreSamplerProspector.NBT_DEPTH)
                ? tag.getInt(CoreSamplerProspector.NBT_DEPTH)
                : cfg.defaultDepth();
        return CoreSamplerProspector.clampDepth(configured, cfg.maxDepth());
    }

    private static void writeDepth(ItemStack stack, int depth) {
        stack.getOrCreateTag().putInt(CoreSamplerProspector.NBT_DEPTH, depth);
    }

    /** 当前深度档（供 tooltip 与智能体读取）。 */
    public static int currentDepth(ItemStack stack) {
        if (!(stack.getItem() instanceof CoreSamplerItem item)) {
            return 0;
        }
        return depthOf(stack, CoreSamplerProspector.Config.from(item.parameters(stack)));
    }

    /** 设定目标钻探深度（裁剪到 [1, maxDepth] 上限内，供智能体 API 直接指定钻深）。 */
    public static void selectDepth(ItemStack stack, int requestedDepth) {
        if (!(stack.getItem() instanceof CoreSamplerItem item)) {
            return;
        }
        final CoreSamplerProspector.Config cfg = CoreSamplerProspector.Config.from(item.parameters(stack));
        writeDepth(stack, CoreSamplerProspector.clampDepth(requestedDepth, cfg.maxDepth()));
    }
}
