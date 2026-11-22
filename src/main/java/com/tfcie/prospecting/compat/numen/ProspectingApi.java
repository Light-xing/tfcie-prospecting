package com.tfcie.prospecting.compat.numen;

import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Flatten;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Positional;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.tfcie.prospecting.api.ProspectingResult;
import com.tfcie.prospecting.api.enums.Bearing;
import com.tfcie.prospecting.api.enums.Conductivity;
import com.tfcie.prospecting.api.enums.Confidence;
import com.tfcie.prospecting.api.enums.DensityAnomaly;
import com.tfcie.prospecting.api.enums.DepthHint;
import com.tfcie.prospecting.api.enums.DistanceBand;
import com.tfcie.prospecting.api.enums.Extent;
import com.tfcie.prospecting.api.enums.FieldStrength;
import com.tfcie.prospecting.api.enums.FluidKind;
import com.tfcie.prospecting.api.enums.Hazard;
import com.tfcie.prospecting.api.enums.MagneticClass;
import com.tfcie.prospecting.api.enums.Magnitude;
import com.tfcie.prospecting.api.enums.OreGroup;
import com.tfcie.prospecting.api.enums.Presence;
import com.tfcie.prospecting.api.enums.Strike;
import com.tfcie.prospecting.api.enums.TerminatedBy;
import com.tfcie.prospecting.api.telemetry.ProspectLedger;
import com.tfcie.prospecting.common.item.AbstractProspectingItem;
import com.tfcie.prospecting.common.item.CoreSampleItem;
import com.tfcie.prospecting.common.prospecting.CoreSampleData;
import com.tfcie.prospecting.common.prospecting.ProspectingService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 勘探仪器的 numen ① 层 Lua API（{@code prospect.instrument.*}）。
 *
 * <h2>薄胶水，不是第二套实现</h2>
 * 每个函数只做三件事：经 {@link ProspectingService} 取一次读数、把结果记进遥测账本、
 * 把结构化 data 摊成 Lua 表。触及距离（4.5 格）、裸露面、射线可达、冷却、耐久、能量
 * 全部由 service 与 {@code AbstractProspectingItem#useExternally} 裁决 —— 这里一行都不重复实现，
 * 因此智能体经 Lua 调工具与玩家右键的代价、限制完全相同。
 *
 * <h2>调用名是三段</h2>
 * numen 的 Lua 函数名由 SDK 固定为 {@code <namespace>.<group>.<fn>}（见 {@code ScriptCatalog} /
 * {@code LuaEngine.pathName}），所以报告里的 {@code prospect.recon_pick} 在实现上是
 * {@code prospect.instrument.recon_pick}。组名 {@code instrument} 是领域名词。
 *
 * <h2>返回值</h2>
 * 每个函数返回一张 record 表，公共头 {@link Outcome}（tool/success/message/error_kind）摊在最外层，
 * 其余字段沿用各 {@code Reading#toData()} 的键名（record 组件自动转 snake_case）。
 * 失败<b>不抛</b> API 错误：读数失败（没拿工具、超距、未裸露、冷却…）也是勘探结果的一部分，
 * 脚本按 {@code success}/{@code error_kind} 分支即可，不会中断整段程序。
 */
public final class ProspectingApi {

    private ProspectingApi() {}

    // ======================= 7 个勘探工具 =======================

    @Fn("用踏勘镐敲击指定位置的裸露岩面，做一次浅带采样：报出附近矿石迹象强弱与矿族；"
            + "同一区块内连续 3 次一致的有效采样后收敛出疑似矿名。无方位、无精确深度，置信度上限为中，"
            + "约有 20% 假阴性并可能被异地漂砾或相似矿物误导。每次消耗 2 点耐久。")
    @Example("local r = prospect.instrument.recon_pick({x = 12, y = 63, z = -4})\n"
            + "print(r.presence, r.converged, r.ore_group)")
    @Note("必须对准实体、裸露且视线可达的岩面；坐标超出 4.5 格、被遮挡或未裸露时 success=false。")
    @SeeAlso({"prospect.instrument.tools", "prospect.instrument.magnetic_needle"})
    public static ReconAnswer recon_pick(ServerCall call, At at) {
        return recon(call, read(call, "recon_pick", at.pos()));
    }

    @Fn("用密度探锤做一次重力法读数：报出脚下垂向密度异常的符号（高/低）与粗粒度深度带。"
            + "只给符号，无法分辨煤、盐、石膏、空洞、水囊还是岩浆腔；连续使用会累积漂移并整体偏移读数，"
            + "须回到基点砝码附近复测才能复位。")
    @Example("local r = prospect.instrument.density_plumb()\n"
            + "if r.needs_calibration then print('先回基点砝码校准') end")
    @Note("基点砝码是方块：用 numen.build.place 放下后，站在其 4 格内使用本工具即完成一次基点复测（漂移清零）。"
            + "靶场若预置了基点，可用 numen.scan.blocks(\"tfcie_prospecting:base_weight\") 找到它。")
    @SeeAlso({"prospect.instrument.tools", "prospect.instrument.core_sampler"})
    public static DensityAnswer density_plumb(ServerCall call) {
        return density(call, read(call, "density_plumb", null));
    }

    @Fn("用感应探棒做一次电法读数：报导电异常强度、八向粗方位、距离档与大致深度档。"
            + "对煤、宝石与干燥岩石无响应；矿化水与金属矿响应相同，地下含水层会误报为矿。")
    @Example("local r = prospect.instrument.induction_probe()\nprint(r.conductivity, r.bearing)")
    @SeeAlso({"prospect.instrument.magnetic_needle", "prospect.instrument.core_sampler"})
    public static InductionAnswer induction_probe(ServerCall call) {
        return induction(call, read(call, "induction_probe", null));
    }

    @Fn("用磁力探针做一次磁法读数：报磁性强度、16 向方位角、脉体横/垂向延展，并判定磁性体/非磁性体。"
            + "只判磁与非磁，不分辨矿种；走向判定需要两个相距足够（≥6 格）的测点，单点只给方位与强度。")
    @Example("local a = prospect.instrument.magnetic_needle()\n"
            + "-- 走到 8 格外再测一次，第二点会给出 strike（走向）\n"
            + "local b = prospect.instrument.magnetic_needle()\nprint(b.strike_ready, b.strike)")
    @SeeAlso({"prospect.instrument.induction_probe", "prospect.instrument.tools"})
    public static MagneticAnswer magnetic_needle(ServerCall call) {
        return magnetic(call, read(call, "magnetic_needle", null));
    }

    @Fn("用回声探针做一次超声成像：报空腔形状、顶板高度、相邻水/岩浆、洞壁已暴露的矿面位置与数量，"
            + "并给塌方风险。不穿透完整岩体，只能看到与空腔连通的暴露表面；须紧贴石质方块或浸入水中才能耦合。")
    @Example("local r = prospect.instrument.echo_probe()\nprint(r.cavity_found, r.cavity_distance)")
    @SeeAlso({"prospect.instrument.core_sampler"})
    public static EchoAnswer echo_probe(ServerCall call) {
        return echo(call, read(call, "echo_probe", null));
    }

    @Fn("用钻探取样器从指定方块起沿垂向逐格取芯，产出一份钻探样本（真值：精确岩层序列、厚度、含矿层深度）。"
            + "只能测正下方一个点、无横向信息、消耗耐久；遇基岩/岩浆/不可钻障碍会终止。不产出任何矿物掉落物。")
    @Example("local r = prospect.instrument.core_sampler({x = 12, y = 63, z = -4})\n"
            + "print(r.terminated_by, r.ore_layer_count)")
    @Example("local r = prospect.instrument.core_sampler({x = 12, y = 63, z = -4}, 64)")
    @Note("起点必须是实体方块且满足裸露/视线/4.5 格触及规则；可选第 2 参数 depth（1~64 格，默认 32）指定深层钻深；"
            + "无矿空柱只返回读数、不生成实物样本；背包已满时直接拒绝（error_kind=inventory_full，不扣耐久）——"
            + "先调 prospect.instrument.prune_samples() 清理空样本；钻出的样本用 prospect.instrument.core_samples 读取。")
    @SeeAlso({"prospect.instrument.core_samples", "prospect.instrument.recon_pick"})
    public static CoreAnswer core_sampler(ServerCall call, CoreAt at) {
        return core(call, read(call, "core_sampler", at.pos(), at.depth().orElse(null)));
    }

    @Fn("读取背包中全部钻探样本的真值数据（岩层摘要、含矿层、恢复率）。它不是探测工具，"
            + "只是把已取到的真值读出来，用来反向校准其它工具的推断。")
    @Example("local r = prospect.instrument.core_samples()\n"
            + "for i, s in ipairs(r.samples) do print(s.origin_x, s.depth, s.ore_layer_count) end")
    @SeeAlso({"prospect.instrument.core_sampler"})
    public static SamplesAnswer core_samples(ServerCall call) {
        final ProspectingResult result = ProspectingService.readCoreSamples(call.her());
        ProspectLedger.record(call.her(), CoreSampleItem.ID, result);
        final Map<String, Object> data = result.data();
        final List<Map<String, Object>> raw = maps(data, "samples");
        final List<SampleInfo> samples = new ArrayList<>(raw.size());
        for (Map<String, Object> sample : raw) {
            samples.add(sample(sample));
        }
        return new SamplesAnswer(outcome(call, result), raw.size(), samples);
    }

    @Fn("清理背包中的无矿空样本：只删 ore_layer_count = 0 的样本，保留所有含矿样本，"
            + "返回清理数量与剩余含矿样本数。背包被空样本占满时先调它再继续取芯。")
    @Example("local r = prospect.instrument.prune_samples()\nprint(r.removed, r.remaining_ore_samples)")
    @SeeAlso({"prospect.instrument.core_samples", "prospect.instrument.core_sampler"})
    public static PruneAnswer prune_samples(ServerCall call) {
        final ProspectingResult result = ProspectingService.pruneSamples(call.her());
        final Map<String, Object> data = result.data();
        return new PruneAnswer(outcome(call, result),
                integer(data, "removed").orElse(0),
                integer(data, "remaining_ore_samples").orElse(0));
    }

    // ======================= 发现与账本 =======================

    @Fn("列出身上携带的勘探仪器：id、数量、使用模式、已消耗/上限/剩余耐久、每次消耗与还能用几次。"
            + "同种工具有多件时数量累加，耐久取当前最可用那一件的真实值；"
            + "免耐久工具（max_durability<=0）的 remaining_durability 与 uses_left 为 -1（无限）。")
    @Example("for i, t in ipairs(prospect.instrument.tools().tools) do print(t.id, t.count, t.wear) end")
    @SeeAlso({"prospect.instrument.history"})
    public static ToolsAnswer tools(ServerCall call) {
        final Map<String, List<ItemStack>> byId = new TreeMap<>();
        final var inventory = call.her().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof AbstractProspectingItem item)) {
                continue;
            }
            byId.computeIfAbsent(item.toolId(), id -> new ArrayList<>()).add(stack);
        }
        final List<ToolState> tools = new ArrayList<>(byId.size());
        for (Map.Entry<String, List<ItemStack>> entry : byId.entrySet()) {
            tools.add(toolState(entry.getKey(), entry.getValue()));
        }
        return new ToolsAnswer(new Outcome("tools", true,
                tools.isEmpty() ? "no prospecting instrument carried" : tools.size() + " instrument kind(s) carried",
                "none"), tools);
    }

    /**
     * 同 id 多件合并成一行：count 累加，耐久取「当前最可用那一件」的真实值
     * （免耐久工具按无限优先当选）。既不会把某一件的 wear 当成全部，
     * 也不会因为某一件报废而把整行标成报废。
     */
    private static ToolState toolState(String id, List<ItemStack> stacks) {
        ItemStack best = stacks.get(0);
        for (ItemStack candidate : stacks) {
            if (durabilityRank(candidate) > durabilityRank(best)) {
                best = candidate;
            }
        }
        final AbstractProspectingItem item = (AbstractProspectingItem) best.getItem();
        final int maxDurability = item.maxDurability(best);
        final int wear = item.wear(best);
        final int wearPerUse = item.wearPerUse();
        final int remaining = maxDurability <= 0 ? -1 : item.remainingDurability(best);
        final int usesLeft = maxDurability <= 0 || wearPerUse <= 0 ? -1 : remaining / wearPerUse;
        return new ToolState(id, stacks.size(), item.useMode().wire(), wear, maxDurability,
                remaining, item.isBroken(best), wearPerUse, usesLeft);
    }

    /** 代表件排序键：免耐久 = 无限大，其余按剩余耐久。 */
    private static int durabilityRank(ItemStack stack) {
        final AbstractProspectingItem item = (AbstractProspectingItem) stack.getItem();
        return item.maxDurability(stack) <= 0 ? Integer.MAX_VALUE : item.remainingDurability(stack);
    }

    @Fn("只读回读本模组的勘探遥测账本（JSONL）：最近若干条读数记录，含 tick、工具、身体坐标、"
            + "成功/失败、失败分类、漂移/饱和/收敛/走向就绪与调用后耐久。评测导出也读同一份文件。")
    @Example("local h = prospect.instrument.history(20)\nprint(h.path, h.count)")
    @Note("账本是只追加的本地文件，不影响读数；只回本同伴自己的记录（跨局、多人、换实验臂不串台）；"
            + "默认最近 100 条，上限 5000 条。")
    @SeeAlso({"prospect.instrument.tools"})
    public static HistoryAnswer history(ServerCall call, Limit args) {
        final int limit = args == null ? 100 : args.limit().orElse(100);
        final List<ProspectLedger.Entry> entries = ProspectLedger.recent(call.her().getUUID(), limit);
        return new HistoryAnswer(
                new Outcome("history", true, entries.size() + " ledger row(s)", "none"),
                ProspectLedger.path().toString(), entries.size(), entries);
    }

    // ======================= 结果 → 表 =======================

    private static ReconAnswer recon(ServerCall call, ProspectingResult result) {
        final Map<String, Object> data = result.data();
        return new ReconAnswer(outcome(call, result),
                constant(data, "presence", Presence.class),
                constant(data, "ore_group", OreGroup.class),
                text(data, "ore_name_suspected"),
                constant(data, "confidence", Confidence.class),
                flag(data, "converged"),
                flag(data, "saturated"),
                flag(data, "repeated"),
                text(data, "interference"));
    }

    private static DensityAnswer density(ServerCall call, ProspectingResult result) {
        final Map<String, Object> data = result.data();
        return new DensityAnswer(outcome(call, result),
                constant(data, "anomaly_type", DensityAnomaly.class),
                integer(data, "depth_from").orElse(0),
                integer(data, "depth_to").orElse(0),
                constant(data, "magnitude", Magnitude.class),
                constant(data, "confidence", Confidence.class),
                flag(data, "drifted"),
                flag(data, "needs_calibration"),
                flag(data, "at_base"));
    }

    private static InductionAnswer induction(ServerCall call, ProspectingResult result) {
        final Map<String, Object> data = result.data();
        return new InductionAnswer(outcome(call, result),
                constant(data, "conductivity", Conductivity.class),
                constant(data, "bearing", Bearing.class),
                constant(data, "distance_band", DistanceBand.class),
                integer(data, "outline_radius"),
                constant(data, "depth_hint", DepthHint.class),
                constant(data, "confidence", Confidence.class),
                bool(data, "interference"));
    }

    private static MagneticAnswer magnetic(ServerCall call, ProspectingResult result) {
        final Map<String, Object> data = result.data();
        return new MagneticAnswer(outcome(call, result),
                constant(data, "field_strength", FieldStrength.class),
                decimal(data, "field_reading"),
                integer(data, "bearing_degrees"),
                constant(data, "lateral_extent", Extent.class),
                constant(data, "vertical_extent", Extent.class),
                constant(data, "magnetic_class", MagneticClass.class),
                constant(data, "strike", Strike.class),
                flag(data, "strike_ready"),
                constant(data, "confidence", Confidence.class),
                bool(data, "interference"));
    }

    private static EchoAnswer echo(ServerCall call, ProspectingResult result) {
        final Map<String, Object> data = result.data();
        return new EchoAnswer(outcome(call, result),
                flag(data, "cavity_found"),
                integer(data, "cavity_distance").orElse(0),
                integer(data, "ceiling_y").orElse(0),
                integer(data, "cavity_volume").orElse(0),
                constant(data, "fluid_adjacent", FluidKind.class),
                integers(data, "exposed_ore_levels"),
                integer(data, "exposed_ore_count").orElse(0),
                constant(data, "hazard_flag", Hazard.class),
                flag(data, "submerged"));
    }

    private static CoreAnswer core(ServerCall call, ProspectingResult result) {
        final Map<String, Object> data = result.data();
        return new CoreAnswer(outcome(call, result),
                integer(data, "origin_x"),
                integer(data, "origin_y"),
                integer(data, "origin_z"),
                text(data, "dimension"),
                integer(data, "start_y"),
                integer(data, "end_y"),
                integer(data, "depth"),
                constant(data, "terminated_by", TerminatedBy.class),
                decimal(data, "recovery_rate"),
                integer(data, "ore_layer_count"),
                constants(data, "ore_hits", CoreSampleData.OreHit.class),
                text(data, "summary"),
                longValue(data, "recorded_at"));
    }

    /** 一份钻探样本的摘要（与 {@code CoreSampleData#toData} 同口径）。 */
    private static SampleInfo sample(Map<String, Object> data) {
        return new SampleInfo(
                integer(data, "origin_x").orElse(0),
                integer(data, "origin_y").orElse(0),
                integer(data, "origin_z").orElse(0),
                text(data, "dimension").orElse(""),
                integer(data, "start_y").orElse(0),
                integer(data, "end_y").orElse(0),
                integer(data, "depth").orElse(0),
                constant(data, "terminated_by", TerminatedBy.class).orElse(TerminatedBy.MAX_DEPTH),
                decimal(data, "recovery_rate").orElse(0.0D),
                integer(data, "ore_layer_count").orElse(0),
                constants(data, "ore_hits", CoreSampleData.OreHit.class),
                text(data, "summary").orElse(""),
                longValue(data, "recorded_at").orElse(0L),
                integer(data, "slot").orElse(-1));
    }

    /** 公共头：三个工具函数都用同一个形状，脚本不需要按工具分别处理成功/失败。 */
    private static Outcome outcome(ServerCall call, ProspectingResult result) {
        final String message = result.message() == null ? "" : result.message();
        return new Outcome(result.tool(), result.success(), message,
                result.success() ? "none" : ProspectLedger.classify(call.her(), result.tool(), result));
    }

    // ======================= 只读取值 =======================

    private static ProspectingResult read(ServerCall call, String toolId, @Nullable BlockPos target) {
        return read(call, toolId, target, null);
    }

    private static ProspectingResult read(ServerCall call, String toolId, @Nullable BlockPos target,
                                          @Nullable Integer depth) {
        final ProspectingResult result = ProspectingService.invokeById(call.her(), toolId, target, depth);
        // 报告 16 Phase 2：每次经 numen 通道的读数都落一行台账（失败不打断调用）
        ProspectLedger.record(call.her(), toolId, result);
        return result;
    }

    private static boolean flag(Map<String, Object> data, String key) {
        return Boolean.TRUE.equals(data.get(key));
    }

    private static Optional<String> text(Map<String, Object> data, String key) {
        final Object value = data.get(key);
        return value instanceof String s ? Optional.of(s) : Optional.empty();
    }

    private static Optional<Integer> integer(Map<String, Object> data, String key) {
        final Object value = data.get(key);
        return value instanceof Number n ? Optional.of(n.intValue()) : Optional.empty();
    }

    private static Optional<Long> longValue(Map<String, Object> data, String key) {
        final Object value = data.get(key);
        return value instanceof Number n ? Optional.of(n.longValue()) : Optional.empty();
    }

    private static Optional<Double> decimal(Map<String, Object> data, String key) {
        final Object value = data.get(key);
        return value instanceof Number n ? Optional.of(n.doubleValue()) : Optional.empty();
    }

    private static Optional<Boolean> bool(Map<String, Object> data, String key) {
        final Object value = data.get(key);
        return value instanceof Boolean b ? Optional.of(b) : Optional.empty();
    }

    private static <E extends Enum<E>> Optional<E> constant(Map<String, Object> data, String key, Class<E> type) {
        final Object value = data.get(key);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    private static List<Integer> integers(Map<String, Object> data, String key) {
        final Object value = data.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        final List<Integer> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Number n) {
                out.add(n.intValue());
            }
        }
        return out;
    }

    private static <T> List<T> constants(Map<String, Object> data, String key, Class<T> type) {
        final Object value = data.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        final List<T> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (type.isInstance(item)) {
                out.add(type.cast(item));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Map<String, Object> data, String key) {
        final Object value = data.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        final List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    // ======================= 参数 =======================

    /** 一个必须给的目标方块位置（Lua 表 {@code {x = …, y = …, z = …}}）。 */
    public record At(@Doc("目标方块的坐标，写成 {x = 10, y = 63, z = -4}；须在 4.5 格内、裸露且视线可达。")
                     BlockPos pos) {
    }

    /** 钻探取样器的起点坐标与可选钻深（格）。 */
    public record CoreAt(@Doc("目标方块的坐标，写成 {x = 10, y = 63, z = -4}；须在 4.5 格内、裸露且视线可达。")
                         BlockPos pos,
                         @Positional
                         @Omitted("use the sampler's current depth setting (default 32, max 64)")
                         @Doc("目标钻探深度（格，1~64，自动裁剪至配置上限）。")
                         Optional<Integer> depth) {
    }

    /** 账本回读的行数（可以不写）。 */
    public record Limit(@Positional
                        @Omitted("read the most recent 100 rows")
                        @Doc("最多回读多少条台账记录。") Optional<Integer> limit) {
    }

    // ======================= 返回值 =======================

    /** 每个答案的公共头；摊平到最外层。 */
    @Doc("每次勘探读数的公共头。")
    public record Outcome(
            @Doc("工具 id。") String tool,
            @Doc("这次读数是否成功。") boolean success,
            @Doc("一句话结果（失败时是原因）。") String message,
            @Doc("失败分类：none/not_held/out_of_reach/occluded/cooldown/scrapped/no_energy/no_sample/inventory_full/failed。")
            String errorKind) {
    }

    public record ReconAnswer(
            @Flatten Outcome outcome,
            @Doc("迹象强弱：none/weak/strong（只反映矿量，不反映距离与方位）。") Optional<Presence> presence,
            @Doc("矿族：gem/metallic/non_metallic/unknown。") Optional<OreGroup> oreGroup,
            @Doc("收敛后的疑似矿名 id；未收敛时没有这个字段。") Optional<String> suspectedOre,
            @Doc("置信度（上限 medium）。") Optional<Confidence> confidence,
            @Doc("本次是否达成收敛（同区块连续 3 次一致）。") boolean converged,
            @Doc("本会话是否已饱和、再敲没有新信息。") boolean saturated,
            @Doc("本次是否为饱和后的重复结论。") boolean repeated,
            @Doc("干扰来源：none/boulder/ore_confusion/overburden。") Optional<String> interference) {
    }

    public record DensityAnswer(
            @Flatten Outcome outcome,
            @Doc("密度异常：high_density/low_density/none。") Optional<DensityAnomaly> anomaly,
            @Doc("深度带起点（脚下起算，格）。") int depthFrom,
            @Doc("深度带终点（格）。") int depthTo,
            @Doc("异常强度：weak/medium/strong。") Optional<Magnitude> magnitude,
            @Doc("置信度。") Optional<Confidence> confidence,
            @Doc("本次读数是否受累积漂移影响。") boolean drifted,
            @Doc("是否已到必须回基点复测的程度。") boolean needsCalibration,
            @Doc("本次读数是否在基点砝码旁完成（漂移已清零）。") boolean atBase) {
    }

    public record InductionAnswer(
            @Flatten Outcome outcome,
            @Doc("导电性：none/low/medium/high。") Optional<Conductivity> conductivity,
            @Doc("八向粗方位：n/ne/e/se/s/sw/w/nw（Lua 里是枚举名的小写形式）。") Optional<Bearing> bearing,
            @Doc("距离档。") Optional<DistanceBand> distanceBand,
            @Doc("轮廓半径估计（格）。") Optional<Integer> outlineRadius,
            @Doc("深度档。") Optional<DepthHint> depthHint,
            @Doc("置信度。") Optional<Confidence> confidence,
            @Doc("是否检测到人文干扰（铁轨/金属块/IE 机械）。") Optional<Boolean> interference) {
    }

    public record MagneticAnswer(
            @Flatten Outcome outcome,
            @Doc("场强档：none/weak/medium/strong。") Optional<FieldStrength> fieldStrength,
            @Doc("伪 nT 场强读数。") Optional<Double> fieldReading,
            @Doc("16 向方位角（度）。") Optional<Integer> bearingDegrees,
            @Doc("横向延展。") Optional<Extent> lateralExtent,
            @Doc("垂向延展。") Optional<Extent> verticalExtent,
            @Doc("磁性体/非磁性体/无异常。") Optional<MagneticClass> magneticClass,
            @Doc("走向：n_s/ne_sw/e_w/nw_se（两点法，strike_ready 时才有）。") Optional<Strike> strike,
            @Doc("两个测点是否已足够给出走向。") boolean strikeReady,
            @Doc("置信度。") Optional<Confidence> confidence,
            @Doc("是否检测到人文干扰或雷雨噪声。") Optional<Boolean> interference) {
    }

    public record EchoAnswer(
            @Flatten Outcome outcome,
            @Doc("是否探测到空腔。") boolean cavityFound,
            @Doc("到空腔的距离（格）。") int cavityDistance,
            @Doc("顶板 y。") int ceilingY,
            @Doc("空腔体素体积。") int cavityVolume,
            @Doc("相邻流体：none/water/lava。") Optional<FluidKind> fluid,
            @Doc("洞壁暴露矿所在 y。") List<Integer> exposedOreLevels,
            @Doc("洞壁暴露矿面数量。") int exposedOreCount,
            @Doc("塌方风险标记。") Optional<Hazard> hazard,
            @Doc("探头是否浸在水中。") boolean submerged) {
    }

    public record CoreAnswer(
            @Flatten Outcome outcome,
            @Doc("取样起点 X。") Optional<Integer> originX,
            @Doc("取样起点 Y。") Optional<Integer> originY,
            @Doc("取样起点 Z。") Optional<Integer> originZ,
            @Doc("维度 id。") Optional<String> dimension,
            @Doc("柱顶 y。") Optional<Integer> startY,
            @Doc("柱底 y。") Optional<Integer> endY,
            @Doc("实际钻深（格）。") Optional<Integer> depth,
            @Doc("终止原因：max_depth/bedrock/lava/obstruction。") Optional<TerminatedBy> terminatedBy,
            @Doc("取芯恢复率（0~1）。") Optional<Double> recoveryRate,
            @Doc("含矿层数。") Optional<Integer> oreLayerCount,
            @Doc("含矿层列表（真值：精确 y 与厚度）。") List<CoreSampleData.OreHit> oreHits,
            @Doc("岩层摘要。") Optional<String> summary,
            @Doc("样本记录的游戏时间。") Optional<Long> recordedAt) {
    }

    public record SamplesAnswer(
            @Flatten Outcome outcome,
            @Doc("背包中的样本数量。") int sampleCount,
            @Doc("每份样本的摘要。") List<SampleInfo> samples) {
    }

    public record PruneAnswer(
            @Flatten Outcome outcome,
            @Doc("本次删除的无矿空样本数。") int removed,
            @Doc("清理后仍在背包中的含矿样本数。") int remainingOreSamples) {
    }

    /** 一份钻探样本的摘要（与 {@code CoreSampleData#toData} 同口径）。 */
    @Doc("一份钻探样本的摘要。")
    public record SampleInfo(
            @Doc("取样起点 X。") int originX,
            @Doc("取样起点 Y。") int originY,
            @Doc("取样起点 Z。") int originZ,
            @Doc("维度 id。") String dimension,
            @Doc("柱顶 y。") int startY,
            @Doc("柱底 y。") int endY,
            @Doc("实际钻深。") int depth,
            @Doc("终止原因。") TerminatedBy terminatedBy,
            @Doc("取芯恢复率。") double recoveryRate,
            @Doc("含矿层数。") int oreLayerCount,
            @Doc("含矿层列表。") List<CoreSampleData.OreHit> oreHits,
            @Doc("岩层摘要。") String summary,
            @Doc("样本记录的游戏时间。") long recordedAt,
            @Doc("样本所在的背包槽位（用于 prune_samples / numen.inv.destroy 定位）。") int slot) {
    }

    public record ToolsAnswer(
            @Flatten Outcome outcome,
            @Doc("携带的仪器（按 id 排序）。") List<ToolState> tools) {
    }

    /** 身上一件仪器（同 id 多件时合并数量，耐久取当前最可用那一件）。 */
    @Doc("身上携带的一件勘探仪器。")
    public record ToolState(
            @Doc("工具 id。") String id,
            @Doc("携带数量。") int count,
            @Doc("使用模式。") String useMode,
            @Doc("已消耗耐久（0 表示不耗耐久）。") int wear,
            @Doc("耐久上限；<=0 表示该工具不耗耐久。") int maxDurability,
            @Doc("剩余耐久；max_durability<=0 时为 -1（无限）。") int remainingDurability,
            @Doc("是否已无可用的一件（同 id 全部报废）。") boolean scrapped,
            @Doc("每次使用消耗的耐久。") int wearPerUse,
            @Doc("还能使用几次；max_durability<=0 或 wear_per_use<=0 时为 -1（不受耐久限制）。") int usesLeft) {
    }

    public record HistoryAnswer(
            @Flatten Outcome outcome,
            @Doc("账本文件的绝对路径（评测导出读同一份）。") String path,
            @Doc("本次回读的条数。") int count,
            @Doc("台账记录（旧 → 新）。") List<ProspectLedger.Entry> entries) {
    }
}
