package com.tfcie.prospecting.common.arena;

import com.google.gson.JsonObject;
import com.tfcie.prospecting.api.ToolParam;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.common.item.AbstractProspectingItem;
import com.tfcie.prospecting.common.nbt.ToolParameterNbt;
import com.tfcie.prospecting.registry.StarterKit;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * {@code /prospect arena starter} 的一键开局配装。
 *
 * <h2>为什么还要一条命令</h2>
 * 靶场是给人/给陪伴体进去做受控勘探的，但「清空背包 + 六件仪器 + 挖掘垫脚材料 + 基点砝码」
 * 逐件 {@code /give} 既慢又容易漏；漏了砝码密度探锤就永远无法复位漂移（报告 13 §11.5）。
 * 所以把清单固化在这里，并顺手把玩家送到该靶场真值文件记录的 {@code recommended_spawn}。
 *
 * <h2>map4 的探锤预算</h2>
 * 256×256 的 Map 4 需要更大范围的探锤扫测，默认 192 点耐久的探锤在网格扫测下会先报废：
 * 该图发放的 {@code density_plumb} 通过物品 NBT 写入
 * {@code max_durability = 512}（其余图保持默认 192，维持各图难度差异）。
 */
public final class ArenaStarter {

    /** Map 4 的探锤耐久预算。 */
    public static final int MAP4_PLUMB_DURABILITY = 512;

    /** 玩家挖掘与垫脚工具（蓝钢镐/锹 + 稳固原石 ×128）。 */
    private static final String PICKAXE = "tfc:metal/pickaxe/blue_steel";
    private static final String SHOVEL = "tfc:metal/shovel/blue_steel";
    private static final String PILLAR_BLOCK = "tfc:rock/raw/granite";
    private static final int PILLAR_COUNT = 128;

    /** 一次配装的结果。 */
    public record Result(String player,
                         List<String> given,
                         int count,
                         @Nullable String arena,
                         boolean teleported,
                         @Nullable String message) {}

    /**
     * 清空玩家背包并发放标准测试装备；若给了已构建过的靶场名，传送到其推荐起点。
     *
     * @param arenaName 可为 {@code null}（只发装备不传送）
     */
    public static Result start(ServerPlayer player, @Nullable String arenaName) {
        player.getInventory().clearContent();
        final List<String> given = new ArrayList<>();

        // 六件基础勘探工具 + 1 个基点砝码（StarterKit 与 numen 开局补给同源）
        for (RegistryObject<? extends Item> entry : StarterKit.ITEMS) {
            final Item item = entry.get();
            player.getInventory().placeItemBackInInventory(new ItemStack(item));
            given.add(item instanceof AbstractProspectingItem tool ? tool.toolId() : itemName(item));
        }
        // 提示词要求 base_weight × 2
        final Item baseWeight = StarterKit.ITEMS.get(StarterKit.ITEMS.size() - 1).get();
        player.getInventory().placeItemBackInInventory(new ItemStack(baseWeight));
        given.add(itemName(baseWeight));

        // 挖掘 / 垫脚材料（Map 3 需要挖竖井，Map 1 要剥表土）
        give(given, player, PICKAXE, 1);
        give(given, player, SHOVEL, 1);
        give(given, player, PILLAR_BLOCK, PILLAR_COUNT);

        // Map 4 加大探锤耐久预算（按图幅判定，改名字也不影响）
        String message = null;
        if (isLargeArena(arenaName)) {
            message = boostPlumbDurability(player);
        }

        boolean teleported = false;
        if (arenaName != null) {
            final JsonObject truth = ArenaStore.loadGroundTruth(arenaName);
            if (truth != null && truth.has("recommended_spawn")) {
                final JsonObject spawn = truth.getAsJsonObject("recommended_spawn");
                final double x = spawn.get("x").getAsDouble() + 0.5D;
                final double y = spawn.get("y").getAsDouble();
                final double z = spawn.get("z").getAsDouble() + 0.5D;
                final ServerLevel level = player.serverLevel();
                player.teleportTo(level, x, y, z, player.getYRot(), player.getXRot());
                teleported = true;
            } else if (message == null) {
                message = "靶场 " + arenaName + " 尚未构建，没有真值起点，只发装备";
            }
        }

        return new Result(player.getScoreboardName(), List.copyOf(given), given.size(),
                arenaName, teleported, message);
    }

    /** 找到刚发放的探锤，把它自己的参数复制一份、只把耐久上限提到 {@link #MAP4_PLUMB_DURABILITY} 写回 NBT。 */
    private static String boostPlumbDurability(ServerPlayer player) {
        final Item plumb = com.tfcie.prospecting.registry.ModItems.DENSITY_PLUMB.get();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(plumb) || !(plumb instanceof AbstractProspectingItem tool)) {
                continue;
            }
            final ToolParameters params = tool.parameters(stack).copy()
                    .maxDurability(MAP4_PLUMB_DURABILITY);
            // 白名单与 DensityPlumbItem.allowedParameters() 保持一致（protected，不能直接取）
            ToolParameterNbt.write(stack, params,
                    EnumSet.of(ToolParam.MATERIAL, ToolParam.MAX_DURABILITY, ToolParam.MAX_DEPTH));
            return "map4 探锤耐久提升至 " + MAP4_PLUMB_DURABILITY;
        }
        return "未在背包中找到密度探锤，无法写入 map4 耐久预算";
    }

    /** 「大图」判定：map4 系命名，或任一轴 ≥ 256 格（256×256 需要更大范围的探锤扫测）。 */
    private static boolean isLargeArena(@Nullable String arenaName) {
        if (arenaName == null) {
            return false;
        }
        if (arenaName.startsWith("map4")) {
            return true;
        }
        try {
            final ArenaConfig cfg = ArenaStore.load(arenaName);
            return cfg.sizeX >= 256 || cfg.sizeZ >= 256;
        } catch (RuntimeException e) {
            return false; // 配置缺失/损坏时不影响发装备
        }
    }

    private static void give(List<String> given, ServerPlayer player, String itemId, int count) {
        final ResourceLocation key = ResourceLocation.tryParse(itemId);
        final Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        if (item == null) {
            given.add("MISSING:" + itemId);
            return;
        }
        player.getInventory().placeItemBackInInventory(new ItemStack(item, count));
        given.add(itemId + " x" + count);
    }

    private static String itemName(Item item) {
        final ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        return key != null ? key.toString() : String.valueOf(item);
    }

    private ArenaStarter() {}
}
