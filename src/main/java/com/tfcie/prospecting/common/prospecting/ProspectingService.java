package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.TfcieProspecting;
import com.tfcie.prospecting.api.ProspectingResult;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.common.item.AbstractProspectingItem;
import com.tfcie.prospecting.common.item.CoreSampleItem;
import com.tfcie.prospecting.common.item.CoreSamplerItem;
import com.tfcie.prospecting.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 勘探工具的对外服务门面 —— <b>智能体调用接口的实质</b>。
 *
 * <h2>与 numen 完全解耦</h2>
 * 本类只依赖 Minecraft 与 {@code AbstractProspectingItem}，<b>不引用 numen 的任何类型</b>。
 * 因此它可被任意框架消费：numen 适配层只是一层薄胶水（见
 * {@code compat.numen.NumenBridge}），命令行入口见 {@code command.ProspectCommand}。
 *
 * <h2>调用代价与玩家手动一致</h2>
 * {@link #invoke} 走的是 {@link AbstractProspectingItem#useExternally}，
 * 与玩家右键**同一套读数逻辑、同一套耐久与冷却结算** —— 否则智能体等于绕开了
 * 整套耐久/冷却设计，可以免费无限探测。
 *
 * <h2>红线仍然成立</h2>
 * 返回的 {@link ProspectingResult} 受全局纪律约束：推断类工具只给档位、方位与区间，
 * **永不输出坐标或精确深度**；精确位置仅钻探取样与回声探针的洞壁直见两类例外。
 */
public final class ProspectingService {

    private static final String SERVICE_ID = "prospecting_service";

    /**
     * 一件工具的状态快照（供智能体在调用前判断是否可用、参数为何）。
     *
     * @param wear               已消耗耐久
     * @param maxDurability      耐久上限（来自材质参数；0 表示该工具不耗耐久）
     * @param scrapped           是否已报废（独立耐久机制：耗尽后保留但不可用）
     */
    public record ToolInfo(String toolId,
                           String useMode,
                           int cooldownTicks,
                           int wearPerUse,
                           int wear,
                           int maxDurability,
                           int remainingDurability,
                           boolean scrapped,
                           ToolParameters parameters) {

        public Map<String, Object> toData() {
            final Map<String, Object> d = new LinkedHashMap<>();
            d.put("tool_id", toolId);
            d.put("use_mode", useMode);
            d.put("cooldown_ticks", cooldownTicks);
            d.put("wear_per_use", wearPerUse);
            d.put("wear", wear);
            d.put("max_durability", maxDurability);
            d.put("remaining_durability", remainingDurability);
            d.put("scrapped", scrapped);
            d.put("material", parameters.material());
            d.put("radius", parameters.radius());
            d.put("shallow_depth", parameters.shallowDepth());
            d.put("max_depth", parameters.maxDepth());
            d.put("false_negative_chance", parameters.falseNegativeChance());
            d.put("confidence_cap", parameters.confidenceCap());
            d.put("energy_cost", parameters.energyCost());
            return d;
        }
    }

    /** 全部已注册的勘探工具 id（按字典序）。智能体据此发现可用能力。 */
    public static List<String> toolIds() {
        return ModItems.ITEMS.getEntries().stream()
                .filter(entry -> entry.get() instanceof AbstractProspectingItem)
                .map(entry -> entry.getId().getPath())
                .sorted()
                .toList();
    }

    /** 在玩家背包（含双手）中查找指定工具；未持有返回 {@code null}。 */
    @Nullable
    public static ItemStack findTool(Player player, String toolId) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()
                    && stack.getItem() instanceof AbstractProspectingItem item
                    && item.toolId().equals(toolId)) {
                return stack;
            }
        }
        return null;
    }

    /** 读取一件工具物品的状态；该物品不是勘探工具时返回 {@code null}。 */
    @Nullable
    public static ToolInfo describe(ItemStack stack) {
        if (!(stack.getItem() instanceof AbstractProspectingItem item)) {
            return null;
        }
        return new ToolInfo(
                item.toolId(),
                item.useMode().wire(),
                item.cooldownTicks(),
                item.wearPerUse(),
                item.wear(stack),
                item.maxDurability(stack),
                item.remainingDurability(stack),
                item.isBroken(stack),
                item.parameters(stack));
    }

    /**
     * 调用一次工具读数。
     *
     * @param player 使用者（智能体场景下即 companion 实体）
     * @param tool   手中的工具物品（状态载体：采样会话、漂移、测点等存于其 NBT）
     * @param target 目标方块位置；对空使用（如磁力探针扫掠）时为 {@code null}
     */
    public static ProspectingResult invoke(Player player, ItemStack tool, @Nullable BlockPos target) {
        if (!(tool.getItem() instanceof AbstractProspectingItem item)) {
            return ProspectingResult.fail(SERVICE_ID,
                    Component.translatable("message.tfcie_prospecting.service.not_a_tool").getString());
        }
        final Level level = player.level();
        if (target != null) {
            if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) > 5.0D * 5.0D
                    && player.blockPosition().distSqr(target) > 25.0D) {
                return ProspectingResult.fail(item.toolId(), "目标方块超出触及距离（需在 4.5 格以内）");
            }
            if (!isExposed(level, target) || !hasLineOfSight(level, player, target)) {
                return ProspectingResult.fail(item.toolId(), "目标方块被遮挡或未裸露，无法直接取样");
            }
        }
        final long started = System.nanoTime();
        final ProspectingResult result = item.useExternally(level, player, tool, target).result();
        final long spentMicros = (System.nanoTime() - started) / 1000L;
        if (TfcieProspecting.LOGGER.isDebugEnabled()) {
            TfcieProspecting.LOGGER.debug("[tfcie_prospecting] {} 读数耗时 {} ms（目标 {}）",
                    item.toolId(), spentMicros / 1000.0D, target);
        }
        return result;
    }

    private static boolean isExposed(Level level, BlockPos target) {
        for (Direction dir : Direction.values()) {
            final BlockPos neighbor = target.relative(dir);
            final BlockState state = level.getBlockState(neighbor);
            if (state.isAir() || !state.getFluidState().isEmpty() || !state.isSolidRender(level, neighbor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLineOfSight(Level level, Player player, BlockPos target) {
        final Vec3 eyes = player.getEyePosition();
        final Vec3 center = Vec3.atCenterOf(target);
        if (canReachPoint(level, player, eyes, center, target)) {
            return true;
        }
        for (Direction dir : Direction.values()) {
            final Vec3 faceCenter = center.add(
                    dir.getStepX() * 0.5D,
                    dir.getStepY() * 0.5D,
                    dir.getStepZ() * 0.5D);
            if (canReachPoint(level, player, eyes, faceCenter, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这一点到眼睛的射线上有没有别的东西挡着。遮挡按<b>物理碰撞体</b>（{@code COLLIDER}）算，与 Numen 的
     * {@code Sight.use}/{@code Sight.open} 同一口径：草、蕨这类没有碰撞体的植物挡不住射线（{@code numen.scan} 里它们
     * 算软遮挡、看得见也够得着）；树叶、玻璃有碰撞体，照旧挡住。
     *
     * <p>用 {@code OUTLINE} 会把草也算成遮挡：TFC 地表裸岩上常长着草，对着这种露头采样会误报"被遮挡或未裸露"。
     * 这条口径差异见报告 17 §7.3。
     */
    private static boolean canReachPoint(Level level, Player player, Vec3 eyes, Vec3 point, BlockPos target) {
        final BlockHitResult hit = level.clip(new ClipContext(
                eyes, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    /** 便捷入口：按工具 id 在背包中查找后调用。 */
    public static ProspectingResult invokeById(Player player, String toolId, @Nullable BlockPos target) {
        return invokeById(player, toolId, target, null);
    }

    /** 便捷入口：按工具 id 在背包中查找后调用，支持为钻探取样器指定本次钻深（裁剪至配置上限）。 */
    public static ProspectingResult invokeById(Player player, String toolId, @Nullable BlockPos target,
                                               @Nullable Integer depth) {
        final ItemStack tool = findTool(player, toolId);
        if (tool == null) {
            return ProspectingResult.fail(toolId,
                    Component.translatable("message.tfcie_prospecting.service.not_held", toolId).getString());
        }
        if (depth != null && tool.getItem() instanceof CoreSamplerItem) {
            CoreSamplerItem.selectDepth(tool, depth);
        }
        return invoke(player, tool, target);
    }

    /**
     * 读取背包中<b>全部钻探样本</b>的真值数据（岩层序列、含矿层、恢复率）。
     *
     * <p>钻探样本是数据物品而非工具，不能走 {@link #invokeById}；但它是整条信息链的
     * <b>真值出口</b>，智能体必须能读到它才能反向校准其它工具的推断。
     */
    public static ProspectingResult readCoreSamples(Player player) {
        final List<Map<String, Object>> samples = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof CoreSampleItem)) {
                continue;
            }
            final Map<String, Object> sample = CoreSampleItem.read(stack).toData();
            // 槽位跟着样本走：prune_samples / numen.inv.destroy 都需要能定位到具体那一份
            sample.put("slot", slot);
            samples.add(sample);
        }

        if (samples.isEmpty()) {
            return ProspectingResult.fail(CoreSampleItem.ID,
                    Component.translatable("message.tfcie_prospecting.service.no_sample").getString());
        }

        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("sample_count", samples.size());
        data.put("samples", samples);
        return ProspectingResult.ok(CoreSampleItem.ID,
                "共读取 " + samples.size() + " 份钻探样本", data);
    }

    /**
     * 清理背包里的<b>无矿空样本</b>：只删 {@code ore_layer_count == 0} 的样本，保留所有含矿样本。
     *
     * <p>样本不可堆叠、每份 NBT 唯一，{@code numen.inv.destroy} 只会删掉「碰到的第一份」，
     * 分不清空柱与含矿（报告 18 §1 第 6b）。这里按数据判定，返回清理数与剩余含矿数。
     */
    public static ProspectingResult pruneSamples(Player player) {
        final var inventory = player.getInventory();
        int removed = 0;
        int oreKept = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof CoreSampleItem)) {
                continue;
            }
            if (CoreSamplerProspector.producesSample(CoreSampleItem.read(stack))) {
                oreKept++;
                continue;
            }
            inventory.setItem(slot, ItemStack.EMPTY);
            removed++;
        }

        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("removed", removed);
        data.put("remaining_ore_samples", oreKept);
        return ProspectingResult.ok(CoreSampleItem.ID,
                "清理了 " + removed + " 份无矿空样本，保留 " + oreKept + " 份含矿样本", data);
    }

    private ProspectingService() {}
}
