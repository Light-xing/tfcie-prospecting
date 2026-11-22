package com.tfcie.prospecting.common.item;

import com.tfcie.prospecting.api.ProspectingResult;
import com.tfcie.prospecting.api.ToolParam;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.UseMode;
import com.tfcie.prospecting.common.durability.ToolWear;
import com.tfcie.prospecting.common.energy.Chargeable;
import com.tfcie.prospecting.common.energy.ToolEnergy;
import com.tfcie.prospecting.common.nbt.ToolParameterNbt;
import com.tfcie.prospecting.common.prospecting.ProspectOutcome;
import com.tfcie.prospecting.config.ProspectingConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * 全部勘探工具的公共基类：统一交互入口、可用性守卫、<b>独立耐久</b>与<b>能量缓冲</b>。
 *
 * <h2>唯一执行核心</h2>
 * 三个入口（{@link #useOn}、{@link #use}、{@link #useExternally}）全部汇入同一条链路：
 * <pre>
 *     守卫 rejectReason() → 读数 prospect() → 结算 settleUse() → 反馈
 * </pre>
 * 守卫规则（冷却 / 报废 / 能量）只写一遍。历史上它在三处各抄了一份，规则一改就得改三处，
 * 且已经出现过「对空右键」与「对块右键」提示不一致的问题。
 *
 * <h2>为什么不用原版耐久</h2>
 * 原版耐久的语义是「耗尽即消失」，上限是物品级固定值。本模组需要：同一件物品由不同材质
 * 制作、各自独立上限，且耗尽后<b>报废而非消失</b>。故耐久由 {@link ToolWear} 独立实现 ——
 * 自有 NBT 存储、自有报废语义，不写原版 {@code Damage} 标签、不调用 {@code hurtAndBreak}。
 *
 * <h2>能量</h2>
 * 通过 {@link Chargeable} 实现，底层是自有 NBT 缓冲 {@link ToolEnergy}。本次<b>默认不强制</b>
 * 耗能（配置 {@code power.enabled = false}），工具照常可用；开关打开后耗能工具在能量不足时
 * 拒绝使用。这样接口现在就接好了，又不会在尚无充能手段的现阶段把工具锁死。
 */
public abstract class AbstractProspectingItem extends Item implements Chargeable {

    private final String toolId;
    private final UseMode useMode;
    private final int cooldownTicks;
    private final int wearPerUse;

    /**
     * 使用被拒绝的原因。
     *
     * <p>把「为什么不能用」做成枚举而不是散落的布尔判断，是为了让三个入口对同一情况的
     * 处理保持一致，且新增一种拒绝原因时编译器会强制所有 switch 补全分支。
     */
    public enum Reject {
        /** 可用。 */
        NONE(null),
        /** 冷却中。 */
        COOLDOWN("message.tfcie_prospecting.service.cooldown"),
        /** 已报废。 */
        SCRAPPED("message.tfcie_prospecting.service.scrapped"),
        /** 能量不足（仅在 power.enabled 打开时出现）。 */
        NO_ENERGY("message.tfcie_prospecting.service.no_energy");

        @Nullable
        private final String agentKey;

        Reject(@Nullable String agentKey) {
            this.agentKey = agentKey;
        }

        /** 供智能体 / 命令通道使用的纯文本原因键；{@code NONE} 为 {@code null}。 */
        @Nullable
        public String agentKey() {
            return agentKey;
        }
    }

    protected AbstractProspectingItem(Properties properties, String toolId, UseMode useMode,
                                      int cooldownTicks, int wearPerUse) {
        super(properties);
        this.toolId = toolId;
        this.useMode = useMode;
        this.cooldownTicks = cooldownTicks;
        this.wearPerUse = wearPerUse;
    }

    public String toolId() { return toolId; }

    public UseMode useMode() { return useMode; }

    /**
     * 使用后的冷却（tick）。默认取构造期定值；子类可覆盖以<b>延迟读取配置</b> ——
     * 物品在注册期构造，那时配置尚未加载，构造期读配置只会拿到兜底值。
     */
    public int cooldownTicks() { return cooldownTicks; }

    /** 每次使用的磨损量；{@code <= 0} 表示不耗耐久。同样可被覆盖以延迟读配置。 */
    public int wearPerUse() { return wearPerUse; }

    // ---------------- 子类契约 ----------------

    /** 默认参数（本次为默认材质档）。未来由制作写入的 NBT 覆盖其中的白名单项。 */
    protected abstract ToolParameters defaultParameters();

    /** 可被材质覆盖的参数白名单（白名单外的参数不参与 NBT 覆盖）。 */
    protected abstract Set<ToolParam> allowedParameters();

    /**
     * 读数逻辑。由各工具实现；调用方保证此刻工具可用（守卫已通过）。
     *
     * @param clicked 被右键的方块位置；对空右键时为 {@code null}
     */
    protected abstract ProspectOutcome prospect(Level level, Player player, ItemStack stack,
                                                @Nullable BlockPos clicked);

    /**
     * 潜行右键的钩子（如钻探取样器切换深度档位）。返回 {@code true} 表示已处理该次交互。
     */
    protected boolean onSneakUse(Level level, Player player, ItemStack stack) {
        return false;
    }

    /** 该物品实例的参数：{@code NBT → 默认}。 */
    public ToolParameters parameters(ItemStack stack) {
        return ToolParameterNbt.read(stack, defaultParameters(), allowedParameters());
    }

    // ---------------- 独立耐久（见 ToolWear） ----------------

    /** 耐久上限（来自材质参数）；{@code <= 0} 表示该工具不消耗耐久。 */
    public int maxDurability(ItemStack stack) {
        return parameters(stack).maxDurability();
    }

    public int wear(ItemStack stack) {
        return ToolWear.read(stack).wear();
    }

    public int remainingDurability(ItemStack stack) {
        return ToolWear.remaining(ToolWear.read(stack), maxDurability(stack));
    }

    public boolean isBroken(ItemStack stack) {
        return ToolWear.isBroken(stack);
    }

    /** 施加磨损。磨损达到上限时工具转入<b>报废</b>状态（保留在背包中，不消失）。 */
    public void applyWear(ItemStack stack, int amount, @Nullable Player player) {
        final int max = maxDurability(stack);
        if (max <= 0 || amount <= 0) {
            return;
        }
        final ToolWear.State before = ToolWear.read(stack);
        final ToolWear.State after = ToolWear.apply(before, amount, max);
        if (after.equals(before)) {
            return;
        }
        ToolWear.write(stack, after);
        if (after.broken() && !before.broken() && player != null) {
            player.displayClientMessage(
                    Component.translatable("message.tfcie_prospecting.tool_scrapped", stack.getHoverName()), true);
        }
    }

    /** 修复工具（未来由制作 / 修复阶段接入）。 */
    public void repair(ItemStack stack, int amount) {
        ToolWear.write(stack, ToolWear.repair(ToolWear.read(stack), amount, maxDurability(stack)));
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return maxDurability(stack) > 0 && wear(stack) > 0;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        final int max = maxDurability(stack);
        if (max <= 0) {
            return 0;
        }
        return Math.round(13.0F * ((float) remainingDurability(stack) / (float) max));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        final int max = maxDurability(stack);
        if (max <= 0) {
            return 0xFFFFFF;
        }
        if (isBroken(stack)) {
            return 0xFF5555;
        }
        final float remain = (float) remainingDurability(stack) / (float) max;
        return Mth.hsvToRgb(Math.max(0.0F, remain) / 3.0F, 1.0F, 1.0F);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        final int max = maxDurability(stack);
        if (max > 0) {
            tooltip.add(Component.translatable("tooltip.tfcie_prospecting.tool_durability",
                            remainingDurability(stack), max)
                    .withStyle(isBroken(stack) ? ChatFormatting.RED : ChatFormatting.GRAY));
            if (isBroken(stack)) {
                tooltip.add(Component.translatable("tooltip.tfcie_prospecting.tool_scrapped")
                        .withStyle(ChatFormatting.RED));
            }
        }
        // 容量为 0（默认）时不显示——避免给不耗能的工具挂一行无意义的信息
        final int capacity = energyCapacity(stack);
        if (capacity > 0) {
            tooltip.add(Component.translatable("tooltip.tfcie_prospecting.tool_energy",
                            energyStored(stack), capacity)
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    // ---------------- 能量（见 Chargeable / ToolEnergy） ----------------

    /** 每次使用的能量开销；{@code <= 0} 表示不耗能。 */
    public int energyCost(ItemStack stack) {
        return parameters(stack).energyCost();
    }

    @Override
    public int energyCapacity(ItemStack stack) {
        return parameters(stack).energyCapacity();
    }

    @Override
    public int energyStored(ItemStack stack) {
        return ToolEnergy.read(stack).stored();
    }

    @Override
    public int receiveEnergy(ItemStack stack, int amount, boolean simulate) {
        final int capacity = energyCapacity(stack);
        if (capacity <= 0 || amount <= 0) {
            return 0;
        }
        final ToolEnergy.State current = ToolEnergy.read(stack);
        final ToolEnergy.State next = ToolEnergy.receive(current, amount, capacity);
        final int accepted = next.stored() - current.stored();
        if (!simulate && accepted > 0) {
            ToolEnergy.write(stack, next);
        }
        return accepted;
    }

    @Override
    public int extractEnergy(ItemStack stack, int amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        final ToolEnergy.State current = ToolEnergy.read(stack);
        final ToolEnergy.State next = ToolEnergy.extract(current, amount);
        final int taken = current.stored() - next.stored();
        if (!simulate && taken > 0) {
            ToolEnergy.write(stack, next);
        }
        return taken;
    }

    /** 本次使用是否强制要求能量（由配置总开关决定）。 */
    private static boolean powerEnforced() {
        return ProspectingConfig.boolOr(ProspectingConfig.Power.enabled, false);
    }

    // ---------------- 使用流程 ----------------

    /**
     * <b>唯一的可用性守卫</b>：冷却 / 报废 / 能量。三个入口共用，规则只此一份。
     */
    public final Reject rejectReason(ItemStack stack, Player player) {
        if (player.getCooldowns().isOnCooldown(this)) {
            return Reject.COOLDOWN;
        }
        if (isBroken(stack)) {
            return Reject.SCRAPPED;
        }
        final int cost = energyCost(stack);
        if (powerEnforced() && cost > 0 && !hasEnergy(stack, cost)) {
            return Reject.NO_ENERGY;
        }
        return Reject.NONE;
    }

    /**
     * 读数被拒绝时的标准装配：纯文本与玩家可见文本共用同一个 lang 键，内容必然一致。
     *
     * <p>此前 {@code ReconPickItem} 与 {@code CoreSamplerItem} 各写了一份一模一样的私有副本，
     * 上提到此以免再出现「两处措辞不一致」。
     *
     * @param messageKey <b>完整</b> lang 键（不是相对前缀）
     */
    protected final ProspectOutcome rejected(String messageKey) {
        return ProspectOutcome.of(
                ProspectingResult.fail(toolId, Component.translatable(messageKey).getString()),
                Component.translatable(messageKey));
    }

    /**
     * 外部接口（智能体服务 / 命令）调用一次的完整流程。
     *
     * <p>刻意与玩家手动使用走<b>同一套守卫、同一套读数逻辑、同一套代价</b>：否则智能体
     * 就能免费、无冷却、无视报废地无限调用，等于绕开了整套耐久与冷却设计。
     * 差别只在反馈方式 —— 这里返回结构化结果，不往聊天栏发消息。
     *
     * @param target 被右键的方块位置；对空使用时为 {@code null}
     */
    public final ProspectOutcome useExternally(Level level, Player player, ItemStack stack,
                                              @Nullable BlockPos target) {
        final Reject reject = rejectReason(stack, player);
        if (reject != Reject.NONE) {
            return ProspectOutcome.of(ProspectingResult.fail(toolId,
                    Component.translatable(reject.agentKey()).getString()));
        }
        return readAndSettle(level, player, stack, target);
    }

    /** 读数 + 结算。调用方须已确认守卫通过。 */
    private ProspectOutcome readAndSettle(Level level, Player player, ItemStack stack,
                                          @Nullable BlockPos clicked) {
        final ProspectOutcome outcome = prospect(level, player, stack, clicked);
        // 只在服务端结算：客户端算出来的磨损会被服务端同步覆盖，冷却也会重复计时
        if (!level.isClientSide && outcome.result().success()) {
            settleUse(stack, player);
        }
        return outcome;
    }

    /** 一次生效后的结算：消耗耐久 + 放电 + 进入冷却。 */
    protected void settleUse(ItemStack stack, Player player) {
        applyWear(stack, wearPerUse(), player);
        // 不强制时也照常放电：缓冲里有多少就放多少，不会变成负数
        extractEnergy(stack, energyCost(stack), false);
        final int cooldown = cooldownTicks();
        if (cooldown > 0) {
            player.getCooldowns().addCooldown(this, cooldown);
        }
    }

    /** 对块右键。 */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        final Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        final ItemStack stack = context.getItemInHand();
        if (player.isShiftKeyDown() && onSneakUse(context.getLevel(), player, stack)) {
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
        }
        return performUse(context.getLevel(), player, stack, context.getClickedPos());
    }

    /** 对空右键。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && onSneakUse(level, player, stack)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        // 冷却 / 报废 / 能量的判定与提示统一由 performUse 负责，这里不再重复一遍规则
        return switch (performUse(level, player, stack, null)) {
            case FAIL -> InteractionResultHolder.fail(stack);
            case PASS -> InteractionResultHolder.pass(stack);
            default -> InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        };
    }

    /**
     * 交互的唯一执行核心：守卫 → 读数 → 结算 → 反馈。
     *
     * @return {@code PASS} 表示冷却中（已提示剩余秒数）、{@code FAIL} 表示被拒绝（已提示）、
     *         否则为 {@code sidedSuccess}
     */
    private InteractionResult performUse(Level level, Player player, ItemStack stack,
                                         @Nullable BlockPos clicked) {
        final Reject reject = rejectReason(stack, player);
        if (reject == Reject.COOLDOWN) {
            // 冷却不是「用不了」，只是「这次点击不算数」：仍然返回 PASS，让原版交互
            // （放置方块、开箱等）照常发生；但玩家/智能体需要知道为什么没有读数。
            notifyRejected(level, player, stack, reject);
            return InteractionResult.PASS;
        }
        if (reject != Reject.NONE) {
            notifyRejected(level, player, stack, reject);
            return InteractionResult.FAIL;
        }

        final ProspectOutcome outcome = readAndSettle(level, player, stack, clicked);
        if (!level.isClientSide) {
            Component display = outcome.display();
            final String message = outcome.result().message();
            if (display == null && message != null && !message.isEmpty()) {
                display = Component.literal(message);
            }
            if (display != null) {
                player.displayClientMessage(display, false);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** 被拒绝时的即时反馈（只对「玩家能做什么」的原因提示）。 */
    private void notifyRejected(Level level, Player player, ItemStack stack, Reject reject) {
        if (level.isClientSide) {
            return;
        }
        final Component message = switch (reject) {
            case SCRAPPED -> Component.translatable(
                    "message.tfcie_prospecting.tool_scrapped", stack.getHoverName());
            case NO_ENERGY -> Component.translatable("message.tfcie_prospecting.tool_no_energy");
            case COOLDOWN -> Component.translatable(
                    "message.tfcie_prospecting.tool_cooldown", remainingCooldownSeconds(player));
            case NONE -> null;
        };
        if (message != null) {
            player.displayClientMessage(message, true);
        }
    }

    /**
     * 冷却剩余秒数（向上取整，至少 1 秒）。
     *
     * <p>1.20.1 的 {@code ItemCooldowns} 只暴露剩余<b>比例</b>
     * （{@link net.minecraft.world.item.ItemCooldowns#getCooldownPercent}），故用比例乘本次
     * 冷却总长换回剩余 tick。总长取 {@link #cooldownTicks()}，与实际下发的冷却同源，
     * 不会因配置中途变化而算出明显的假值。
     */
    private int remainingCooldownSeconds(Player player) {
        final float remainingPercent = player.getCooldowns().getCooldownPercent(this, 0.0F);
        final int totalTicks = Math.max(1, cooldownTicks());
        return Math.max(1, Mth.ceil(remainingPercent * totalTicks / 20.0F));
    }
}
