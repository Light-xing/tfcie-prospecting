package com.tfcie.prospecting.common.item;

import com.tfcie.prospecting.common.prospecting.CoreSampleData;
import com.tfcie.prospecting.common.prospecting.CoreSampleGuiOpener;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * D1 钻探样本：不可堆叠的数据物品，记录某坐标的方块层序与厚度。
 *
 * <p>数据本身极为<b>唯一</b>（每份 NBT 各不相同），故不可堆叠；右键打开 GUI
 * 查看柱状图、分层列表与摘要。数据由客户端直接从 ItemStack NBT 读取，
 * 因此展示无需网络同步。
 *
 * <p>但 GUI 只存在于客户端。服务端实体（numen companion 这类没有自己客户端的身体）
 * 右键时没有界面可开，真值就断了出口 —— 所以服务端侧另发一条聊天读数
 * （坐标 / 深度 / 终止原因 / 层序摘要 / 含矿层），聊天事件即智能体可读的通道。
 *
 * <p>设计文档预留的扩展位：可作为后续「综合反演图册」的输入数据源。
 */
public final class CoreSampleItem extends Item {

    public static final String ID = "core_sample";

    /** 服务端聊天读数的 lang 键前缀。 */
    private static final String READOUT_PREFIX = "message.tfcie_prospecting.core_sample.readout.";

    public CoreSampleItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            // 客户端直接开界面：数据就在这份 ItemStack 的 NBT 里，不需要服务端往返
            CoreSampleGuiOpener.open(stack);
        } else {
            // 服务端没有界面可开：把同一份数据打成聊天读数，供没有客户端的身体（智能体）读取
            sendReadout(player, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * 服务端读数：坐标、深度、终止原因、层序摘要与含矿层，逐行发进聊天栏。
     *
     * <p>层序摘要用的是 {@code CoreSamplerProspector.buildSummary} 落进 NBT 的那串
     * 「泥土 5 闪长岩 10」，与 GUI 顶部显示的完全同源；含矿层给矿名 + y + 厚度，
     * 因为它是钻探相对其它物探工具的唯一增量 —— 精确深度。
     */
    private static void sendReadout(Player player, ItemStack stack) {
        final CoreSampleData data = read(stack);
        if (data.summary().isEmpty()) {
            player.displayClientMessage(
                    Component.translatable(READOUT_PREFIX + "empty"), false);
            return;
        }

        player.displayClientMessage(Component.translatable(READOUT_PREFIX + "header",
                data.origin().getX(), data.origin().getY(), data.origin().getZ(),
                data.depth(),
                Component.translatable("enum.tfcie_prospecting.terminated_by."
                        + data.terminatedBy().wire())), false);
        player.displayClientMessage(Component.translatable(READOUT_PREFIX + "summary",
                data.summary()), false);

        if (data.oreHits().isEmpty()) {
            player.displayClientMessage(
                    Component.translatable(READOUT_PREFIX + "ore_none"), false);
            return;
        }
        for (CoreSampleData.OreHit hit : data.oreHits()) {
            player.displayClientMessage(Component.translatable(READOUT_PREFIX + "ore",
                    hit.display(), hit.y(), hit.thickness()), false);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        final CoreSampleData data = read(stack);

        tooltip.add(Component.translatable("tooltip.tfcie_prospecting.core_sample.coords",
                        data.origin().getX(), data.origin().getY(), data.origin().getZ())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.tfcie_prospecting.core_sample.depth", data.depth())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.tfcie_prospecting.core_sample.ore_count", data.oreLayerCount())
                .withStyle(data.oreLayerCount() > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.tfcie_prospecting.core_sample.recovery",
                        Math.round(data.recoveryRate() * 100.0D))
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 从样本物品读取数据；NBT 缺失时返回空样本（安全回落）。 */
    public static CoreSampleData read(ItemStack stack) {
        final CompoundTag tag = stack.getTagElement(CoreSampleData.NBT_ROOT);
        return tag == null ? CoreSampleData.empty() : CoreSampleData.fromTag(tag);
    }
}
