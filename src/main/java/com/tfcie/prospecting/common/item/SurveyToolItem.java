package com.tfcie.prospecting.common.item;

import com.tfcie.prospecting.api.ProspectingResult;
import com.tfcie.prospecting.api.ToolParam;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.UseMode;
import com.tfcie.prospecting.common.prospecting.ProspectOutcome;
import com.tfcie.prospecting.common.survey.SurveyConfig;
import com.tfcie.prospecting.common.survey.SurveyFilter;
import com.tfcie.prospecting.common.survey.SurveyManager;
import com.tfcie.prospecting.config.ProspectingConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Set;

/**
 * 探查工具：右键发起一次平面探查，扫描完成后弹出地图界面。
 *
 * <h2>6 件共用一个类</h2>
 * 改进版踏勘镐 / 密度探锤 / 感应探棒 / 磁力探针 / 回声探针，以及电子探矿仪 ——
 * 它们之间<b>唯一的差别就是 {@link SurveyFilter}</b>：扫描框架、地图数据、GUI 全部共用。
 * 因此不为每件工具写一个类，而是注册同一个类的 6 个实例。这也保证了「改进版只染本方法异常、
 * 电子探矿仪给全部矿种」这条分工只体现在一个枚举里，不会在六处实现间漂移。
 *
 * <h2>与其它工具同构</h2>
 * 本类继承 {@link AbstractProspectingItem}，因此<b>免费获得</b>与六件生存工具完全相同的能力：
 * 独立耐久、冷却、能量缓冲、统一守卫与统一结算。与它们的差别只在 {@link #prospect} 的实现 ——
 * 它不返回一次读数，而是<b>启动一次异步扫描</b>，真正的结果稍后经网络包回传并开界面。
 *
 * <p>也就是说「给所有工具留出耐久 / 电力接口」不是额外挂上去的，而是同一条链路。
 * 当前默认值（耐久上限 0、磨损 0、冷却 0、耗能 0）使行为与接入前完全一致；
 * 要启用只需改配置或材质参数，不必动代码。
 *
 * <h2>为何默认不消耗耐久</h2>
 * 这批仪器不进常规生存获取，因此默认不设代价；防连点另由「同一玩家同时只允许一个探查任务」
 * 保证（见 {@link SurveyManager#start}）。
 */
public final class SurveyToolItem extends AbstractProspectingItem {

    private static final String KEY_PREFIX = "message.tfcie_prospecting.survey.";

    private final SurveyFilter filter;

    public SurveyToolItem(SurveyFilter filter) {
        // 冷却与磨损在构造期传 0，改由下方覆盖的 getter 延迟读配置（注册期配置尚未加载）
        super(new Item.Properties().stacksTo(1), filter.toolId(), UseMode.RIGHT_CLICK, 0, 0);
        this.filter = filter;
    }

    public SurveyFilter filter() {
        return filter;
    }

    // ---------------- 可被配置启用的代价 ----------------

    @Override
    public int cooldownTicks() {
        return ProspectingConfig.intOr(ProspectingConfig.Survey.cooldownTicks, 0);
    }

    @Override
    public int wearPerUse() {
        return ProspectingConfig.intOr(ProspectingConfig.Survey.wearPerUse, 0);
    }

    // ---------------- 子类契约 ----------------

    @Override
    protected ToolParameters defaultParameters() {
        return new ToolParameters()
                .material("default")
                .maxDurability(ProspectingConfig.intOr(ProspectingConfig.Survey.maxDurability, 0))
                .energyCapacity(ProspectingConfig.intOr(ProspectingConfig.Power.defaultCapacity, 0));
    }

    @Override
    protected Set<ToolParam> allowedParameters() {
        return EnumSet.of(ToolParam.MATERIAL, ToolParam.MAX_DURABILITY, ToolParam.ENERGY_CAPACITY);
    }

    /** 本次探查的扫描参数。 */
    private static SurveyConfig config() {
        return new SurveyConfig(
                ProspectingConfig.intOr(ProspectingConfig.Survey.chunkRadius, 5),
                ProspectingConfig.intOr(ProspectingConfig.Survey.verticalStride, 2),
                ProspectingConfig.boolOr(ProspectingConfig.Survey.fullDepth, true),
                ProspectingConfig.intOr(ProspectingConfig.Survey.depthBelow, 64));
    }

    /**
     * 启动一次异步扫描。
     *
     * <p>与其它工具不同，这里<b>不产生读数</b>：扫描在服务端分帧进行，完成后才把地图经
     * {@code SurveyMapS2CPacket} 发回客户端并弹出界面。因此返回的成功结果只表示「已受理」，
     * 其 message 为空 —— 玩家看到的是稍后弹出的地图窗口，而不是一行聊天文本。
     */
    @Override
    protected ProspectOutcome prospect(Level level, Player player, ItemStack stack, @Nullable BlockPos clicked) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            // 客户端不发起探查：真正的结算会被基类的 !isClientSide 判断挡掉
            return ProspectOutcome.of(ProspectingResult.ok(toolId(), ""), null);
        }

        // 对块右键以该方块为探测点；对空右键以玩家脚下为探测点
        // 已有任务在跑时 start 会返回 false，无需另行预检
        final BlockPos origin = clicked != null ? clicked : player.blockPosition();
        if (!SurveyManager.start(serverPlayer, origin, filter, config())) {
            return rejected(KEY_PREFIX + "busy");
        }

        return ProspectOutcome.of(
                ProspectingResult.ok(toolId(), ""),
                Component.translatable(KEY_PREFIX + "start"));
    }
}
