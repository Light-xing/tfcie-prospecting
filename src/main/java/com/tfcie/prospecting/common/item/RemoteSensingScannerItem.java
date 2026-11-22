package com.tfcie.prospecting.common.item;

import com.tfcie.prospecting.api.ProspectingResult;
import com.tfcie.prospecting.api.ToolParam;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.UseMode;
import com.tfcie.prospecting.common.prospecting.ProspectOutcome;
import com.tfcie.prospecting.common.terrain.TerrainManager;
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
 * 遥感扫描仪：右键探测周围地形，扫描完成后弹出三栏目面板。
 *
 * <h2>三个栏目</h2>
 * <ol>
 *   <li><b>鸟瞰视角</b> —— 接管渲染摄像机，从空中俯视真实世界（与飞行观感一致）；</li>
 *   <li><b>平面地图</b> —— 类原版地图，含区块边界的灰色标记与横纵向滑动条；</li>
 *   <li><b>等高线</b> —— 以 3 格为一档的分层设色。</li>
 * </ol>
 * 其中第 1 栏渲染的是真实世界，<b>不需要扫描数据</b>；扫描只为第 2、3 栏服务。
 *
 * <h2>与其它工具同构</h2>
 * 继承 {@link AbstractProspectingItem}，因此直接获得独立耐久、冷却、能量与统一守卫。
 * 默认代价为 0（配置可为 0 之外的值以启用），行为与其它仪器一致。
 */
public final class RemoteSensingScannerItem extends AbstractProspectingItem {

    public static final String ID = "remote_sensing_scanner";

    private static final String KEY_PREFIX = "message.tfcie_prospecting.terrain.";

    public RemoteSensingScannerItem() {
        super(new Item.Properties().stacksTo(1), ID, UseMode.RIGHT_CLICK, 0, 0);
    }

    // ---------------- 可被配置启用的代价（与探查仪器同一套口径） ----------------

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

    /**
     * 启动一次异步地形扫描。
     *
     * <p>与探查仪器一样，这里不返回读数：真正的呈现是扫描完成后弹出的面板。
     */
    @Override
    protected ProspectOutcome prospect(Level level, Player player, ItemStack stack, @Nullable BlockPos clicked) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return ProspectOutcome.of(ProspectingResult.ok(toolId(), ""), null);
        }

        // 已有任务在跑时 start 会返回 false，无需另行预检
        final BlockPos origin = clicked != null ? clicked : player.blockPosition();
        if (!TerrainManager.start(serverPlayer, origin,
                ProspectingConfig.intOr(ProspectingConfig.Terrain.chunkRadius, 8),
                ProspectingConfig.intOr(ProspectingConfig.Terrain.chunksPerTick, 8))) {
            return rejected(KEY_PREFIX + "busy");
        }

        return ProspectOutcome.of(
                ProspectingResult.ok(toolId(), ""),
                Component.translatable(KEY_PREFIX + "start"));
    }
}
