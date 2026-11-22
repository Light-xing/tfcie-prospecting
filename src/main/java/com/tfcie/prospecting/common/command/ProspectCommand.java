package com.tfcie.prospecting.common.command;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.tfcie.prospecting.TfcieProspecting;
import com.tfcie.prospecting.api.ProspectingResult;
import com.tfcie.prospecting.common.arena.ArenaBuilder;
import com.tfcie.prospecting.common.arena.ArenaConfig;
import com.tfcie.prospecting.common.arena.ArenaStarter;
import com.tfcie.prospecting.common.arena.ArenaStore;
import com.tfcie.prospecting.common.item.AbstractProspectingItem;
import com.tfcie.prospecting.common.prospecting.ProspectingService;
import com.tfcie.prospecting.registry.StarterKit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /prospect} 命令 —— 智能体调用接口的<b>命令行通道</b>。
 *
 * <p>存在意义有二：
 * <ol>
 *   <li><b>非 numen 场景的入口</b>：任何能发命令的智能体（脚本、其他模组的桥、RCON）
 *       都能驱动整套勘探工具，不必等 numen 适配层就位；</li>
 *   <li><b>人工验证入口</b>：把同一套 {@link ProspectingService} 的读数打到聊天栏，
 *       便于对照设计文档实测。</li>
 * </ol>
 *
 * <p>输出统一为 <b>JSON</b>，与 {@link ProspectingResult#toJson()} 同一口径，
 * 因此程序解析与智能体消费拿到的是同一份结构化数据。
 *
 * <pre>
 * /prospect list                              列出可用工具
 * /prospect describe &lt;tool&gt;                   读取工具状态与当前参数
 * /prospect use &lt;tool&gt; [&lt;target&gt;]             调用一次（target 为可选的方块坐标）
 * /prospect kit [&lt;player&gt;]                    一次性发放全部勘探工具（默认执行者）
 * /prospect arena list                        列出可用靶场配置（首次自动释放 map1~4.json）
 * /prospect arena build &lt;name&gt; [&lt;x&gt; &lt;z&gt;]       在指定西北角（缺省 = 脚下区块）构建靶场
 * /prospect arena starter [&lt;player&gt;|&lt;name&gt;] [&lt;name&gt;]  清背包发装备并按需传送到靶场起点
 * </pre>
 *
 * <p><b>权限分层</b>：{@code list|describe|use|kit} 无门槛（智能体的日常通道）；
 * {@code arena} 整棵子树要求 OP（{@code hasPermission(2)}）—— 刷图是管理员运维动作，
 * 受测智能体绝不该能调用它（否则可以自己造一张没有矿的图交差）。
 */
public final class ProspectCommand {

    private static final Gson GSON = new Gson();

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TOOLS =
            (context, builder) -> SharedSuggestionProvider.suggest(ProspectingService.toolIds(), builder);

    /** 靶场配置名补全（读配置目录；首次会顺手释放内置四图）。 */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_ARENAS =
            (context, builder) -> SharedSuggestionProvider.suggest(ArenaStore.listNames(), builder);

    /** starter 的参数既可能是玩家名也可能是靶场名，两者都补全。 */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PLAYERS_AND_ARENAS =
            (context, builder) -> {
                final List<String> candidates = new ArrayList<>(ArenaStore.listNames());
                final MinecraftServer server = context.getSource().getServer();
                if (server != null) {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        candidates.add(player.getScoreboardName());
                    }
                }
                return SharedSuggestionProvider.suggest(candidates, builder);
            };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 不设权限门槛：命令只用执行者自己的工具、走同一套耐久与冷却结算，
        // 拿不到额外好处；设了 OP 门槛反而会挡住未提权的智能体玩家实体。
        dispatcher.register(Commands.literal("prospect")
                .then(Commands.literal("list")
                        .executes(ProspectCommand::list))
                .then(Commands.literal("describe")
                        .then(Commands.argument("tool", StringArgumentType.word())
                                .suggests(SUGGEST_TOOLS)
                                .executes(ProspectCommand::describe)))
                .then(Commands.literal("use")
                        .then(Commands.argument("tool", StringArgumentType.word())
                                .suggests(SUGGEST_TOOLS)
                                .executes(context -> use(context, null))
                                .then(Commands.argument("target", BlockPosArgument.blockPos())
                                        .executes(context -> use(context,
                                                BlockPosArgument.getLoadedBlockPos(context, "target"))))))
                .then(Commands.literal("kit")
                        .executes(context -> kit(context, null))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> kit(context,
                                        EntityArgument.getPlayer(context, "player")))))
                .then(arenaSubtree()));
    }

    // ------------------------------------------------------------------
    // /prospect arena —— 管理员专用声明式刷图
    // ------------------------------------------------------------------

    /**
     * {@code arena} 子树。<b>整棵子树</b>都要求 OP 权限等级 2：
     * 与 {@code list|describe|use|kit} 的无门槛设计刻意不同，这里能改写世界方块与导出真值，
     * 一旦受测智能体可调用，评测公平性直接失效。
     */
    private static LiteralCommandNode<CommandSourceStack> arenaSubtree() {
        return Commands.literal("arena")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(ProspectCommand::arenaList))
                .then(Commands.literal("build")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(SUGGEST_ARENAS)
                                .executes(context -> arenaBuild(context, null, null))
                                .then(Commands.argument("originX", IntegerArgumentType.integer())
                                        .then(Commands.argument("originZ", IntegerArgumentType.integer())
                                                .executes(context -> arenaBuild(context,
                                                        IntegerArgumentType.getInteger(context, "originX"),
                                                        IntegerArgumentType.getInteger(context, "originZ")))))))
                .then(Commands.literal("starter")
                        .executes(context -> arenaStarter(context, null, null))
                        .then(Commands.argument("first", StringArgumentType.word())
                                .suggests(SUGGEST_PLAYERS_AND_ARENAS)
                                .executes(context -> arenaStarter(context,
                                        StringArgumentType.getString(context, "first"), null))
                                .then(Commands.argument("second", StringArgumentType.word())
                                        .suggests(SUGGEST_PLAYERS_AND_ARENAS)
                                        .executes(context -> arenaStarter(context,
                                                StringArgumentType.getString(context, "first"),
                                                StringArgumentType.getString(context, "second"))))))
                .build();
    }

    private static int arenaList(CommandContext<CommandSourceStack> context) {
        try {
            final List<String> released = ArenaStore.releaseDefaults();
            final List<String> names = ArenaStore.listNames();
            final JsonArray arenas = new JsonArray();
            for (String name : names) {
                final JsonObject entry = new JsonObject();
                entry.addProperty("name", name);
                entry.addProperty("config", ArenaStore.arenaDir().resolve(name + ".json").toString());
                try {
                    final ArenaConfig cfg = ArenaStore.load(name);
                    entry.addProperty("size_x", cfg.sizeX);
                    entry.addProperty("size_z", cfg.sizeZ);
                    entry.addProperty("features", cfg.featureCount());
                    final List<String> errors = cfg.structuralErrors();
                    if (!errors.isEmpty()) {
                        final JsonArray problems = new JsonArray();
                        errors.forEach(problems::add);
                        entry.add("problems", problems);
                    }
                } catch (RuntimeException e) {
                    entry.addProperty("error", String.valueOf(e.getMessage()));
                }
                arenas.add(entry);
            }
            final JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("arena_dir", ArenaStore.arenaDir().toString());
            root.add("released", GSON.toJsonTree(released));
            root.add("arenas", arenas);
            root.addProperty("count", names.size());
            return reply(context, root);
        } catch (RuntimeException e) {
            TfcieProspecting.LOGGER.error("[arena] list 失败", e);
            return fail(context, "列出靶场配置失败：" + e.getMessage());
        }
    }

    /**
     * 实时读盘 → 五步构建 → 聊天栏紧凑 JSON 摘要。
     *
     * <p>原点缺省取执行者脚下区块的西北角（{@code chunkPosition().getMinBlockX/Z()}）：
     * 靶场内部有效区域就是 {@code [minX, minX+sizeX-1] × [minZ, minZ+sizeZ-1]}。
     */
    private static int arenaBuild(CommandContext<CommandSourceStack> context,
                                  @Nullable Integer originX, @Nullable Integer originZ) {
        final String name = StringArgumentType.getString(context, "name");
        final ServerLevel level = context.getSource().getLevel();
        final ServerPlayer player = context.getSource().getPlayer();

        final int minX;
        final int minZ;
        if (originX != null && originZ != null) {
            minX = originX;
            minZ = originZ;
        } else if (player != null) {
            minX = player.chunkPosition().getMinBlockX();
            minZ = player.chunkPosition().getMinBlockZ();
        } else {
            return fail(context, "需要在游戏内执行，或显式给出 <originX> <originZ>");
        }

        try {
            final ArenaConfig cfg = ArenaStore.load(name);
            final List<String> errors = cfg.structuralErrors();
            if (!errors.isEmpty()) {
                return fail(context, "配置不合法：" + errors);
            }
            final ArenaBuilder.Result result = ArenaBuilder.build(level, cfg, minX, minZ);

            final JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("arena", result.name());
            final JsonObject bounds = new JsonObject();
            bounds.addProperty("min_x", result.minX());
            bounds.addProperty("max_x", result.maxX());
            bounds.addProperty("min_z", result.minZ());
            bounds.addProperty("max_z", result.maxZ());
            root.add("bounds", bounds);
            final JsonObject spawn = new JsonObject();
            spawn.addProperty("x", result.spawnX());
            spawn.addProperty("y", result.spawnY());
            spawn.addProperty("z", result.spawnZ());
            root.add("recommended_spawn", spawn);
            root.addProperty("washed_old_ore_blocks", result.washedOldOreBlocks());
            root.addProperty("placed_target_ore_blocks", result.placedTargetOreBlocks());
            root.addProperty("placed_distractor_blocks", result.placedDistractorBlocks());
            root.addProperty("targets", result.targets().size());
            root.addProperty("distractors", result.distractors().size());
            root.addProperty("build_ms", result.elapsedMs());
            root.addProperty("ground_truth", result.groundTruthPath().toString());
            root.add("warnings", GSON.toJsonTree(result.warnings()));
            return reply(context, root);
        } catch (IllegalArgumentException e) {
            return fail(context, e.getMessage());
        } catch (RuntimeException e) {
            TfcieProspecting.LOGGER.error("[arena] build {} 失败", name, e);
            return fail(context, "构建靶场失败：" + e);
        }
    }

    /**
     * 清背包 + 发标准装 + 按需传送到靶场起点。
     *
     * <p>参数顺序按提示词是 {@code [<player>] [<name>]}，但两个都是可选单词：为了让
     * {@code /prospect arena starter map1} 这种「只想传送到 map1」的写法也能用，
     * 这里按「先当靶场名、再当玩家名」的顺序智能解析，两个都给时两种顺序都接受。
     */
    private static int arenaStarter(CommandContext<CommandSourceStack> context,
                                    @Nullable String first, @Nullable String second) {
        final MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return fail(context, "服务端尚未就绪");
        }
        String playerName = null;
        String arenaName = null;
        for (String token : new String[]{first, second}) {
            if (token == null) {
                continue;
            }
            if (arenaName == null && ArenaStore.exists(token)) {
                arenaName = token;
            } else if (playerName == null && server.getPlayerList().getPlayerByName(token) != null) {
                playerName = token;
            } else {
                return fail(context, "无法识别参数：" + token
                        + "（既不是可用靶场配置，也不是在线玩家；用 /prospect arena list 查看配置名）");
            }
        }

        final ServerPlayer target;
        if (playerName != null) {
            target = server.getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                return fail(context, "玩家不在线：" + playerName);
            }
        } else {
            target = context.getSource().getPlayer();
            if (target == null) {
                return fail(context, "需要在游戏内执行，或指定在线玩家");
            }
        }

        try {
            final ArenaStarter.Result result = ArenaStarter.start(target, arenaName);
            final JsonObject root = new JsonObject();
            root.addProperty("ok", true);
            root.addProperty("player", result.player());
            root.add("given", GSON.toJsonTree(result.given()));
            root.addProperty("count", result.count());
            if (result.arena() != null) {
                root.addProperty("arena", result.arena());
            }
            root.addProperty("teleported", result.teleported());
            if (result.message() != null) {
                root.addProperty("message", result.message());
            }
            return reply(context, root);
        } catch (RuntimeException e) {
            TfcieProspecting.LOGGER.error("[arena] starter 失败", e);
            return fail(context, "配装失败：" + e);
        }
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        final List<String> ids = ProspectingService.toolIds();
        final JsonObject root = new JsonObject();
        final var array = GSON.toJsonTree(ids);
        root.add("tools", array);
        root.addProperty("count", ids.size());
        return reply(context, root);
    }

    private static int describe(CommandContext<CommandSourceStack> context) {
        final Player player = context.getSource().getPlayer();
        if (player == null) {
            return fail(context, "需要由玩家执行");
        }
        final String toolId = StringArgumentType.getString(context, "tool");
        final var tool = ProspectingService.findTool(player, toolId);
        if (tool == null) {
            return fail(context, "背包中没有工具：" + toolId);
        }
        final var info = ProspectingService.describe(tool);
        if (info == null) {
            return fail(context, "该物品不是勘探工具");
        }
        final JsonObject root = GSON.toJsonTree(info.toData()).getAsJsonObject();
        root.addProperty("ok", true);
        return reply(context, root);
    }

    private static int use(CommandContext<CommandSourceStack> context, @Nullable BlockPos target) {
        final Player player = context.getSource().getPlayer();
        if (player == null) {
            return fail(context, "需要由玩家执行");
        }
        final String toolId = StringArgumentType.getString(context, "tool");
        final ProspectingResult result = ProspectingService.invokeById(player, toolId, target);

        // ProspectingResult 已自带 JSON；直接解析后补一个顶层 ok，与其它子命令口径一致
        final JsonObject root = JsonParser.parseString(result.toJson()).getAsJsonObject();
        root.addProperty("ok", result.success());
        return reply(context, root);
    }

    /**
     * 一次性把「基础包」发给目标玩家（默认执行者）。
     *
     * <p>给 owner 用：新建的 companion 背包是空的，而勘探是整场评测的核心动作，
     * 逐件 {@code /give} 既繁琐又容易漏。发放走
     * {@link net.minecraft.world.entity.player.Inventory#placeItemBackInInventory}，
     * 背包满时自动掉在脚下，不会凭空消失。
     *
     * <p>清单与开局补给同源（{@link StarterKit}）：只有六件基础工具 + 一个基点砝码。
     * 改进型与电子探矿仪 / 遥感扫描仪<b>刻意不发</b> —— 它们按设计不暴露给智能体，
     * 手工通道同样不能成为绕过评测的后门（报告 13 §11.4 / §12.2）。
     *
     * <p>权限与其余子命令一致（不设 OP 门槛）：这是建体阶段的运维动作，
     * 只发放本模组自己的物品，用途单一 —— 让新身体能立刻开始勘探。
     */
    private static int kit(CommandContext<CommandSourceStack> context, @Nullable ServerPlayer target) {
        final ServerPlayer player = target != null ? target : context.getSource().getPlayer();
        if (player == null) {
            return fail(context, "需要由玩家执行，或指定目标玩家");
        }

        // 工具报 toolId（与 /prospect use 同口径）；非工具物品（基点砝码）报注册名
        final JsonArray given = new JsonArray();
        for (RegistryObject<? extends Item> entry : StarterKit.ITEMS) {
            final Item item = entry.get();
            player.getInventory().placeItemBackInInventory(new ItemStack(item));
            given.add(item instanceof AbstractProspectingItem tool ? tool.toolId() : itemName(item));
        }

        final JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("player", player.getScoreboardName());
        root.add("given", given);
        root.addProperty("count", given.size());
        return reply(context, root);
    }

    /** 物品的注册名（{@code namespace:path}）；理论上不会缺失，缺失时退回物品的字符串形式。 */
    private static String itemName(Item item) {
        final ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        return key != null ? key.toString() : String.valueOf(item);
    }

    private static int reply(CommandContext<CommandSourceStack> context, JsonObject json) {
        context.getSource().sendSuccess(() -> Component.literal(json.toString()), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> context, String message) {
        final JsonObject root = new JsonObject();
        root.addProperty("ok", false);
        root.addProperty("message", message);
        context.getSource().sendFailure(Component.literal(root.toString()));
        return 0;
    }

    private ProspectCommand() {}
}
