package com.tfcie.prospecting.api.telemetry;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tfcie.prospecting.TfcieProspecting;
import com.tfcie.prospecting.api.ProspectingResult;
import com.tfcie.prospecting.common.item.AbstractProspectingItem;
import com.tfcie.prospecting.common.prospecting.ProspectingService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 勘探遥测账本（Telemetry Ledger）—— 每次经 numen 通道的读数追加一行 JSONL。
 *
 * <h2>为什么是「账本」而不是统计</h2>
 * 报告 16 Phase 2 要把 M1/M2/M3、Drills-to-Hit、Sensor-Wear 与 IIR/USR/SpaRR 从<b>执行轨迹</b>
 * 反推出来，而不是让智能体自报。因此这里只追加最原始的一次读数记录，统计留给评测脚本离线做：
 * 追加写没有读-改-写窗口，天然幂等、并发安全（服务端主线程写）。
 *
 * <h2>位置</h2>
 * {@code <游戏目录>/tfcie_prospecting/telemetry/prospect_ledger.jsonl}。
 * 刻意<b>不</b>写进 numen 的存档/配置目录：账本是本模组的评测产物，不参与她的记忆。
 * 路径由 {@link #path()} 给出，{@link #recent(UUID, int)} 是给一位同伴的只读回读（{@code prospect.instrument.history}），
 * {@link #recent(int)} 不按人过滤（离线导出用）。每行带 {@code companion} 与 {@code dimension}，跨局、多人不会串台。
 *
 * <h2>失败不抛出</h2>
 * 账本写失败（磁盘满、权限、路径被占）只记 debug 日志，绝不打断一次勘探调用 ——
 * 它不是被测能力的一部分。
 */
public final class ProspectLedger {

    private static final Gson GSON = new Gson();

    /** 追加写与回读共用的一把锁；服务端主线程之外的误调也不会写坏行。 */
    private static final Object LOCK = new Object();

    /** 模组自己的目录名（游戏目录下一级）。 */
    private static final String DIR = "tfcie_prospecting";

    private static final String FILE = "prospect_ledger.jsonl";

    /** 回读上限：防止评测导出一口气把整份台账读进内存。 */
    private static final int MAX_READ = 5000;

    /**
     * 一行台账。字段名与报告 16 Phase 2 约定一致（JSON 里用 snake_case，见 {@link #append}）。
     *
     * @param tick        记录的服务器游戏刻
     * @param tool        工具 id（{@code recon_pick} / {@code density_plumb} / … / {@code core_sample}）
     * @param x           读数时身体所在的方块 X（传感器位置；不是目标方块）
     * @param y           同上
     * @param z           同上
     * @param success     本次读数是否成功
     * @param errorKind   失败分类（{@code none}/{@code not_held}/{@code out_of_reach}/{@code occluded}/
     *                    {@code cooldown}/{@code scrapped}/{@code no_energy}/{@code no_sample}/
     *                    {@code inventory_full}/{@code failed}）
     * @param drifted     读数是否受漂移影响（密度探锤）
     * @param saturated   本次是否饱和无新增信息（踏勘镐）
     * @param converged   本次是否收敛（踏勘镐）
     * @param strikeReady 本次是否已能给出走向（磁力探针）
     * @param wear        调用后该工具的已消耗耐久（无工具/不耗耐久为 0）
     * @param companion   读数属于哪位同伴（玩家 UUID）；{@code history} 只回本同伴的行
     * @param dimension   读数发生在哪个维度（{@code minecraft:overworld} 等），跨世界不串台
     */
    public record Entry(long tick, String tool, int x, int y, int z, boolean success, String errorKind,
                        boolean drifted, boolean saturated, boolean converged, boolean strikeReady, int wear,
                        String companion, String dimension) {
    }

    private ProspectLedger() {}

    /** 账本文件（可能还不存在；调用方读之前自己判存在）。 */
    public static Path path() {
        return FMLPaths.GAMEDIR.get().resolve(DIR).resolve("telemetry").resolve(FILE);
    }

    /**
     * 记一次读数。只在服务端调用；任何失败都被吞掉（见类注释）。
     *
     * @param player 使用者（numen 场景下即同伴身体）
     * @param toolId 工具 id
     * @param result 服务门面的返回值（读成功与否、结构化 data、失败文案）
     */
    public static void record(Player player, String toolId, ProspectingResult result) {
        if (player == null || toolId == null || result == null || player.level().isClientSide) {
            return;
        }
        try {
            final Map<String, Object> data = result.data();
            final BlockPos at = player.blockPosition();
            final JsonObject row = row(player.level().getGameTime(), toolId, at.getX(), at.getY(), at.getZ(),
                    result.success(), classify(player, toolId, result),
                    flag(data, "drifted"), flag(data, "saturated"), flag(data, "converged"),
                    flag(data, "strike_ready"), wearOf(player, toolId),
                    player.getUUID().toString(), player.level().dimension().location().toString());
            append(GSON.toJson(row));
        } catch (Throwable failure) {
            // 账本不是被测能力：写不进去也不许影响这次勘探
            TfcieProspecting.LOGGER.debug("[tfcie_prospecting] 遥测账本写入失败（已忽略）", failure);
        }
    }

    /**
     * 回读最近的 {@code limit} 行（按写入顺序，旧 → 新），<b>不按人过滤</b>：离线评测导出用。
     *
     * @param limit 最多读多少行；{@code <= 0} 时按 1 行处理，超过 {@value #MAX_READ} 截断
     */
    public static List<Entry> recent(int limit) {
        return recent(null, limit);
    }

    /**
     * 回读<b>这一位同伴</b>最近的 {@code limit} 行（旧 → 新）。{@code prospect.instrument.history} 用它：
     * 账本是全局一份文件，不按人过滤的话，换一局、换一个同伴、换一个实验臂都会读到别人的测站与读数。
     *
     * @param companion 同伴（玩家）UUID；{@code null} 表示不过滤（同 {@link #recent(int)}）
     * @param limit     最多读多少行，规则同 {@link #recent(int)}
     */
    public static List<Entry> recent(UUID companion, int limit) {
        final int cap = Math.max(1, Math.min(limit, MAX_READ));
        final Path path = path();
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            final List<Entry> out = new ArrayList<>();
            final String who = companion == null ? null : companion.toString();
            // 从末尾往前收：只要最新的 cap 行；给了同伴就只收他自己的，别人的行不占名额
            for (int i = lines.size() - 1; i >= 0 && out.size() < cap; i--) {
                final Entry entry = parse(lines.get(i));
                if (entry != null && (who == null || who.equals(entry.companion()))) {
                    out.add(entry);
                }
            }
            Collections.reverse(out);
            return out;
        } catch (IOException | RuntimeException failure) {
            TfcieProspecting.LOGGER.debug("[tfcie_prospecting] 遥测账本回读失败（已忽略）", failure);
            return List.of();
        }
    }

    /**
     * 清掉一位同伴的台账行（评测在一局开始时调用），别人的行原样留着。追加写不能删行，所以整份重写：
     * 读全部、过滤、写临时文件、原子换上去。任何失败都吞掉——账本不是被测能力。
     */
    public static void reset(UUID companion) {
        if (companion == null) {
            return;
        }
        final Path path = path();
        synchronized (LOCK) {
            try {
                if (!Files.isRegularFile(path)) {
                    return;
                }
                final String who = companion.toString();
                final List<String> keep = new ArrayList<>();
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    final Entry entry = parse(line);
                    if (entry == null || !who.equals(entry.companion())) {
                        keep.add(line);
                    }
                }
                final Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
                Files.write(tmp, keep, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException noAtomic) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException failure) {
                TfcieProspecting.LOGGER.debug("[tfcie_prospecting] 遥测账本清空失败（已忽略）", failure);
            }
        }
    }

    /** 清掉整份台账（评测换一局、或跑完归档）。失败吞掉，理由同上。 */
    public static void clearAll() {
        synchronized (LOCK) {
            try {
                Files.deleteIfExists(path());
            } catch (IOException | RuntimeException failure) {
                TfcieProspecting.LOGGER.debug("[tfcie_prospecting] 遥测账本删除失败（已忽略）", failure);
            }
        }
    }

    // ---------------- 内部 ----------------

    private static void append(String line) {
        final Path path = path();
        synchronized (LOCK) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException failure) {
                TfcieProspecting.LOGGER.debug("[tfcie_prospecting] 遥测账本追加失败（已忽略）", failure);
            }
        }
    }

    /** 一行台账的 JSON 形状；字段名就是离线评测脚本读的那一套（与报告 16 Phase 2 约定一致）。 */
    static JsonObject row(long tick, String tool, int x, int y, int z, boolean success, String errorKind,
                          boolean drifted, boolean saturated, boolean converged, boolean strikeReady, int wear,
                          String companion, String dimension) {
        final JsonObject row = new JsonObject();
        row.addProperty("tick", tick);
        row.addProperty("tool", tool);
        row.addProperty("x", x);
        row.addProperty("y", y);
        row.addProperty("z", z);
        row.addProperty("success", success);
        row.addProperty("error_kind", errorKind);
        row.addProperty("drifted", drifted);
        row.addProperty("saturated", saturated);
        row.addProperty("converged", converged);
        row.addProperty("strike_ready", strikeReady);
        row.addProperty("wear", wear);
        row.addProperty("companion", companion);
        row.addProperty("dimension", dimension);
        return row;
    }

    private static boolean flag(@Nullable Map<String, Object> data, String key) {
        return data != null && Boolean.TRUE.equals(data.get(key));
    }

    /** 调用后的已消耗耐久；没持有该工具（或不是采集工具）为 0。 */
    private static int wearOf(Player player, String toolId) {
        final ItemStack stack = ProspectingService.findTool(player, toolId);
        return stack != null && stack.getItem() instanceof AbstractProspectingItem item ? item.wear(stack) : 0;
    }

    /**
     * 失败分类。API 返回值里的 {@code error_kind} 与台账用同一份判据，避免两处漂移。
     *
     * <p>不额外定义错误码：服务门面把「没拿工具 / 冷却 / 超距 / 未裸露」都压成一句失败文案，
     * 这里按调用后的现场状态与文案回推 —— 只服务归因，不参与运行时决策。
     * 两处中文文案来自 {@link ProspectingService#invoke}（该处是硬编码、未走 lang 键）。
     */
    public static String classify(Player player, String toolId, ProspectingResult result) {
        if (result.success()) {
            return "none";
        }
        final String message = result.message() == null ? "" : result.message();
        final String lower = message.toLowerCase(java.util.Locale.ROOT);
        // 满包拒绝必须排在「钻探样本」之前：那条文案本身就含「钻探样本」，否则会被误判成 no_sample
        if (message.contains("背包已满") || lower.contains("inventory full")
                || lower.contains("inventory_full")) {
            return "inventory_full";
        }
        // 钻探样本是数据物品、不在工具位：先按文案认，再去找工具
        if (message.contains("钻探样本") || lower.contains("core sample")) {
            return "no_sample";
        }
        final ItemStack stack = ProspectingService.findTool(player, toolId);
        if (stack == null) {
            return "not_held";
        }
        if (stack.getItem() instanceof AbstractProspectingItem item) {
            final AbstractProspectingItem.Reject reject = item.rejectReason(stack, player);
            switch (reject) {
                case COOLDOWN:
                    return "cooldown";
                case SCRAPPED:
                    return "scrapped";
                case NO_ENERGY:
                    return "no_energy";
                default:
                    break;
            }
        }
        if (message.contains("触及距离")) {
            return "out_of_reach";
        }
        if (message.contains("遮挡") || message.contains("裸露")) {
            return "occluded";
        }
        return "failed";
    }

    @Nullable
    private static Entry parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            final JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            return new Entry(
                    o.has("tick") ? o.get("tick").getAsLong() : 0L,
                    o.has("tool") ? o.get("tool").getAsString() : "",
                    o.has("x") ? o.get("x").getAsInt() : 0,
                    o.has("y") ? o.get("y").getAsInt() : 0,
                    o.has("z") ? o.get("z").getAsInt() : 0,
                    o.has("success") && o.get("success").getAsBoolean(),
                    o.has("error_kind") ? o.get("error_kind").getAsString() : "failed",
                    o.has("drifted") && o.get("drifted").getAsBoolean(),
                    o.has("saturated") && o.get("saturated").getAsBoolean(),
                    o.has("converged") && o.get("converged").getAsBoolean(),
                    o.has("strike_ready") && o.get("strike_ready").getAsBoolean(),
                    o.has("wear") ? o.get("wear").getAsInt() : 0,
                    o.has("companion") ? o.get("companion").getAsString() : "",
                    o.has("dimension") ? o.get("dimension").getAsString() : "");
        } catch (RuntimeException broken) {
            // 半行 / 被外部编辑坏的行：跳过，不让一行坏数据毁掉整份回读
            return null;
        }
    }
}
