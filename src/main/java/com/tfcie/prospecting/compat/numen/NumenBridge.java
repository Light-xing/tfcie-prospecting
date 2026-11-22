package com.tfcie.prospecting.compat.numen;

import com.google.gson.JsonObject;
import com.tfcie.prospecting.TfcieProspecting;
import com.tfcie.prospecting.api.ProspectingResult;
import com.tfcie.prospecting.common.prospecting.ProspectingService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * numen（言出法随）适配层 —— 把勘探工具暴露成 numen 的 {@code NumenTool}。
 *
 * <h2>为什么用反射而不是编译期依赖</h2>
 * 本工作区里的 numen-api <b>从未构建、也从未发布到本地 maven</b>（没有
 * {@code build/local-maven}，也没有任何 jar 产物），因此无法声明 {@code compileOnly} 依赖。
 * 反射的好处是<b>零构建耦合</b>：本模组照常编译、照常单独运行，装了 numen 才挂载工具，
 * 没装就静默跳过 —— 这也正是原设计要求的「运行时软探测 + optional」。
 *
 * <h2>对接的真实契约（已按 numen 源码核对）</h2>
 * <ul>
 *   <li>{@code com.dwinovo.numen.agent.tool.NumenTool}，它 extends
 *       {@code com.dwinovo.numen.agent.provider.IToolSpec}；</li>
 *   <li>{@code IToolSpec}：{@code String name()} / {@code String description()} /
 *       {@code Map<String,Object> parameterSchema()}；</li>
 *   <li>{@code NumenTool}：{@code Residency residency()}（默认 DEFERRED）、
 *       {@code void invoke(ToolCall)}（默认把调用发往服务端身体）、
 *       {@code void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
 *       Consumer&lt;String&gt; reply)}；</li>
 *   <li>{@code com.dwinovo.numen.agent.tool.ToolRegistry#register(NumenTool)}（静态）。</li>
 * </ul>
 * 我们只接管 {@code name / description / parameterSchema / onServerCall}，
 * 其余（含 {@code invoke} 把调用送往服务端、{@code residency} 取默认）一律委托给接口的
 * 默认实现（{@link InvocationHandler#invokeDefault}）。
 *
 * <h2>⚠ 验证状态</h2>
 * numen-api 未构建，故本层没有编译期契约：签名依据 numen 源码，并按运行中的 numen 0.1.5
 * 制品逐条核对。所有反射调用一律对<b>声明类型</b>取方法（见 {@link ApiHandles}），
 * 绝不对 numen 传来的实例取 {@code getClass()} —— 否则跨模组模块访问检查会拒绝调用。
 * 若之后 numen 发布正式 api 制品，建议改用 {@code compileOnly} 的类型化实现替换本文件
 * （届时本文件的工具定义表可直接沿用）。
 */
public final class NumenBridge {

    private static final String CLS_NUMEN_TOOL_API = "com.dwinovo.numen.api.agent.tool.NumenTool";
    private static final String CLS_TOOL_REGISTRY_API = "com.dwinovo.numen.api.agent.tool.ToolRegistry";
    private static final String CLS_TOOL_CALL_API = "com.dwinovo.numen.api.agent.tool.ToolCall";
    private static final String CLS_TOOL_ANCHOR_API = "com.dwinovo.numen.api.agent.tool.ToolAnchor";
    private static final String CLS_NUMEN_TOOL = "com.dwinovo.numen.agent.tool.NumenTool";
    private static final String CLS_TOOL_REGISTRY = "com.dwinovo.numen.agent.tool.ToolRegistry";
    private static final String CLS_RESIDENCY = "com.dwinovo.numen.agent.tool.NumenTool$Residency";

    /** 一次注册是否已尝试过（避免重复注册导致 numen 报重名）。 */
    private static boolean attempted;

    /** 工具定义：numen 侧工具名 + 给 LLM 的描述 + 参数 schema。 */
    private record ToolDef(String toolId, String description, Map<String, Object> schema) {}

    private NumenBridge() {}

    /**
     * 若 numen 在场则注册全部勘探工具；否则静默跳过。
     * 由主类在模组构造期调用（{@code ToolRegistry} 是静态注册表，早期注册是安全的）。
     */
    public static void registerIfPresent() {
        if (attempted) {
            return;
        }
        attempted = true;

        // ① 层 Lua API 与 <prospecting_state>：先探测 numen 插件门（0.1.5+）是否在场。
        // 放在这里而不是主类，是为了让 numen 联动只有这一处入口；numen 缺席时它自己会静默跳过。
        NumenLuaBridge.registerIfPresent();

        Class<?> toolInterface;
        Class<?> registryClass;
        try {
            toolInterface = Class.forName(CLS_NUMEN_TOOL_API);
            registryClass = Class.forName(CLS_TOOL_REGISTRY_API);
        } catch (ClassNotFoundException firstMiss) {
            try {
                toolInterface = Class.forName(CLS_NUMEN_TOOL);
                registryClass = Class.forName(CLS_TOOL_REGISTRY);
            } catch (ClassNotFoundException absent) {
                TfcieProspecting.LOGGER.info(
                        "[tfcie_prospecting] 未检测到 numen，跳过智能体工具注册（此为正常情况）");
                return;
            }
        }

        try {
            final Method register = registryClass.getMethod("register", toolInterface);
            Object residency = null;
            try {
                final Class<?> residencyClass = Class.forName(CLS_RESIDENCY);
                for (Object constant : residencyClass.getEnumConstants()) {
                    if ("DEFERRED".equals(String.valueOf(constant))) {
                        residency = constant;
                        break;
                    }
                }
            } catch (ClassNotFoundException ignored) {
                // 取不到就交给接口默认实现
            }

            // 新版 API 的句柄只解析一次；旧版 numen 没有这些声明类型时返回 null，
            // 代理照旧走 onServerCall 兼容分支
            final ApiHandles api = ApiHandles.resolve();

            int registered = 0;
            for (ToolDef def : TOOLS) {
                final Object tool = Proxy.newProxyInstance(
                        toolInterface.getClassLoader(),
                        new Class<?>[]{toolInterface},
                        new Handler(def, residency, api));
                register.invoke(null, tool);
                registered++;
            }
            TfcieProspecting.LOGGER.info("[tfcie_prospecting] 已向 numen 注册 {} 个勘探工具", registered);
        } catch (Throwable failure) {
            TfcieProspecting.LOGGER.warn(
                    "[tfcie_prospecting] numen 适配层注册失败（已忽略，勘探工具本身不受影响）", failure);
        }
    }

    // ---------------- 代理处理 ----------------

    /**
     * 新版 numen（{@code invoke(ToolCall)}）的<b>声明类型</b>句柄。
     *
     * <p>为什么必须对声明类型取 {@link Method}，而不是对传入实例取 {@code getClass()}：
     * 服务端锚点是 {@code CompanionLoop} 里 {@code ToolAnchor anchor = () -> companion;}
     * 生成的 lambda，运行时类型是 numen_api 模块里的<b>隐藏类</b>（非 public）。在另一个模组里
     * 对它的运行时类 {@code getMethod} 再 {@code invoke}，会撞上 JPMS 的成员访问检查，抛
     * 「cannot access a member of class ...$$Lambda$... with modifiers "public"」。
     * 声明类型 {@code ToolCall} / {@code ToolAnchor} 是公开导出的类与接口，跨模组反射合法。
     *
     * <p>numen 缺席或版本过旧（没有这些声明类型）时 {@link #resolve()} 返回 {@code null}，
     * 代理回落到旧版 {@code onServerCall} 分支。
     */
    private record ApiHandles(Class<?> callType,
                              Method complete,
                              Method args,
                              Method ctx,
                              Method entityUuid) {

        @Nullable
        static ApiHandles resolve() {
            try {
                final Class<?> callType = Class.forName(CLS_TOOL_CALL_API);
                final Class<?> anchorType = Class.forName(CLS_TOOL_ANCHOR_API);
                return new ApiHandles(
                        callType,
                        callType.getMethod("complete", String.class),
                        callType.getMethod("args"),
                        callType.getMethod("ctx"),
                        anchorType.getMethod("entityUuid"));
            } catch (ClassNotFoundException | NoSuchMethodException absent) {
                return null;
            }
        }
    }

    private static final class Handler implements InvocationHandler {

        private final ToolDef def;
        @Nullable
        private final Object residency;
        @Nullable
        private final ApiHandles api;
        private final String displayName;

        Handler(ToolDef def, @Nullable Object residency, @Nullable ApiHandles api) {
            this.def = def;
            this.residency = residency;
            this.api = api;
            this.displayName = "prospect_" + def.toolId();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "name":
                    return displayName;
                case "description":
                    return def.description();
                case "parameterSchema":
                    return def.schema();
                case "residency":
                    if (residency != null) {
                        return residency;
                    }
                    break;   // 落回默认实现
                case "invoke":
                    if (api != null && args != null && args.length == 1
                            && api.callType().isInstance(args[0])) {
                        onApiToolCall(args[0], api);
                        return null;
                    }
                    break;   // 旧版 0.1.3 的 invoke 落回默认实现
                case "onServerCall":
                    onServerCall(args);
                    return null;
                default:
                    break;
            }

            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            // Object 的三件套，避免反射调用时出现意外
            return switch (method.getName()) {
                case "toString" -> displayName;
                case "hashCode" -> displayName.hashCode();
                case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                default -> null;
            };
        }

        /**
         * 新版 numen（0.1.4.1 / 0.1.5）{@code invoke(com.dwinovo.numen.api.agent.tool.ToolCall)}。
         *
         * <p>三个动作（{@code args/ctx/complete}）一律经 {@link ApiHandles} 的声明类型句柄调用：
         * 传入的 {@code callObj} 是 {@code ToolCall} 实例，{@code ctx} 往往是 lambda（隐藏类），
         * 对它们的运行时类做反射会越权。
         */
        private void onApiToolCall(Object callObj, ApiHandles api) throws Throwable {
            final Method complete = api.complete();
            try {
                final Object rawArgs = api.args().invoke(callObj);
                final JsonObject jsonArgs = rawArgs instanceof JsonObject json ? json : null;
                final Object ctx = api.ctx().invoke(callObj);
                final UUID uuid = ctx == null ? null : (UUID) api.entityUuid().invoke(ctx);
                final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null || uuid == null) {
                    complete.invoke(callObj, NumenPayload.apiFailure("无法定位服务端或 companion 实体，无法执行勘探读数"));
                    return;
                }
                final Runnable runnable = () -> {
                    try {
                        Player player = server.getPlayerList().getPlayer(uuid);
                        if (player == null) {
                            for (ServerLevel level : server.getAllLevels()) {
                                final Entity entity = level.getEntity(uuid);
                                if (entity instanceof Player p) {
                                    player = p;
                                    break;
                                }
                                final Player byUuid = level.getPlayerByUUID(uuid);
                                if (byUuid != null) {
                                    player = byUuid;
                                    break;
                                }
                            }
                        }
                        if (player == null) {
                            complete.invoke(callObj, NumenPayload.apiFailure("companion 不是服务端玩家实体，无法执行勘探读数"));
                            return;
                        }
                        final BlockPos target = NumenPayload.readTarget(jsonArgs);
                        final Integer depth = NumenPayload.readDepth(jsonArgs);
                        final ProspectingResult result = "core_sample".equals(def.toolId())
                                ? ProspectingService.readCoreSamples(player)
                                : ProspectingService.invokeById(player, def.toolId(), target, depth);
                        com.tfcie.prospecting.api.telemetry.ProspectLedger.record(player, def.toolId(), result);
                        complete.invoke(callObj, NumenPayload.toApiOutcome(result));
                    } catch (Throwable ex) {
                        try {
                            complete.invoke(callObj, NumenPayload.apiFailure("勘探读数执行失败：" + ex.getMessage()));
                        } catch (Throwable ignored) {
                        }
                    }
                };
                if (server.isSameThread()) {
                    runnable.run();
                } else {
                    server.execute(runnable);
                }
            } catch (Throwable ex) {
                complete.invoke(callObj, NumenPayload.apiFailure("勘探读数执行失败：" + ex.getMessage()));
            }
        }

        /**
         * {@code onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
         * Consumer<String> reply)} —— 在服务端、以 companion 的身体执行一次读数。
         */
        @SuppressWarnings("unchecked")
        private void onServerCall(Object[] args) {
            if (args == null || args.length < 4) {
                return;
            }
            final Object rawArgs = args[1];
            final Object companion = args[2];
            final Object replySink = args[3];

            if (!(replySink instanceof java.util.function.Consumer<?> consumer)) {
                return;
            }
            final java.util.function.Consumer<String> reply =
                    (java.util.function.Consumer<String>) consumer;

            if (!(companion instanceof Player player)) {
                reply.accept(NumenPayload.failure("companion 不是服务端玩家实体，无法执行勘探读数"));
                return;
            }

            try {
                final JsonObject json = rawArgs instanceof JsonObject j ? j : null;
                final BlockPos target = NumenPayload.readTarget(json);
                final Integer depth = NumenPayload.readDepth(json);
                // 钻探样本是数据物品而非工具，走专门的读取路径；其余工具统一经服务调用
                final ProspectingResult result = "core_sample".equals(def.toolId())
                        ? ProspectingService.readCoreSamples(player)
                        : ProspectingService.invokeById(player, def.toolId(), target, depth);
                com.tfcie.prospecting.api.telemetry.ProspectLedger.record(player, def.toolId(), result);
                reply.accept(NumenPayload.toTaskResult(result));
            } catch (RuntimeException ex) {
                reply.accept(NumenPayload.failure("勘探读数执行失败：" + ex.getMessage()));
            }
        }
    }

    // ---------------- 工具定义表 ----------------

    private static Map<String, Object> targetSchema(boolean required) {
        final Map<String, Object> props = new LinkedHashMap<>();
        props.put("target_x", Map.of("type", "integer", "description", "目标方块的 X 坐标"));
        props.put("target_y", Map.of("type", "integer", "description", "目标方块的 Y 坐标"));
        props.put("target_z", Map.of("type", "integer", "description", "目标方块的 Z 坐标"));
        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        if (required) {
            schema.put("required", List.of("target_x", "target_y", "target_z"));
        }
        return schema;
    }

    private static Map<String, Object> coreSamplerSchema() {
        final Map<String, Object> schema = targetSchema(true);
        @SuppressWarnings("unchecked")
        final Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        props.put("depth", Map.of("type", "integer", "description", "可选：钻探深度（格，默认 32，最大 64）"));
        return schema;
    }

    private static Map<String, Object> emptySchema() {
        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        return schema;
    }

    /**
     * 工具定义表。描述按 numen 对 {@code IToolSpec#description} 的要求撰写：
     * 说清做什么、何时用、参数含义与关键限制 —— 这是模型能否正确选用的最大杠杆。
     */
    private static final List<ToolDef> TOOLS = List.of(
            new ToolDef("recon_pick",
                    "【踏勘镐】敲击裸露岩面做浅带采样，报出附近矿石的迹象强弱与矿族，"
                            + "同一区块内连续 3 次一致的有效采样后收敛出『疑似矿名』。"
                            + "只在找不到矿的早期用它圈定靶区：水平半径仅 8 格、自脚下向下 24 格，"
                            + "无方位、无精确深度，置信度上限为『中』。约有 20% 假阴性，"
                            + "并可能被异地漂砾或相似矿物（黄铁矿误认金）误导。"
                            + "必须对准实体岩面使用，参数为被打的方块坐标。每次消耗 2 点耐久。",
                    targetSchema(true)),

            new ToolDef("density_plumb",
                    "【密度探锤】重力法定深：报出脚下垂向的密度异常符号（高/低）与粗粒度深度带。"
                            + "只给异常符号，无法分辨造成低密度异常的到底是煤、盐、石膏、空洞、水囊还是岩浆腔。"
                            + "无方位信息；深度区间随深度变宽（8→24 格），深度结论应以钻探为准。"
                            + "连续使用会累积漂移并整体偏移读数，需返回『基点砝码』附近复测才能复位。"
                            + "校准方法：先用 numen.build.place 把发放的基点砝码放在平坦地面，"
                            + "再站在砝码 4 格内使用本工具一次，漂移即清零（at_base=true）。",
                    emptySchema()),

            new ToolDef("induction_probe",
                    "【感应探棒】电法定位：报导电异常的强度、八向粗方位、距离档与大致深度档。"
                            + "对煤、宝石、干燥岩石无响应；孔雀石（碳酸铜）不导电；"
                            + "矿化水与金属矿响应完全相同，因此地下含水层会误报为矿。"
                            + "附近有铁轨、金属块或沉浸工程机械时读数会虚高。",
                    emptySchema()),

            new ToolDef("magnetic_needle",
                    "【磁力探针】磁法：报磁性强度、16 向方位角、脉体走向与横/垂向延展，"
                            + "并判定『磁性体 / 非磁性体』。只判磁与非磁，不分辨具体矿种。"
                            + "只认磁铁矿级磁性体：磁铁矿强磁、赤铁矿弱磁、褐铁矿不响应；"
                            + "TFC 与 Immersive Geology（IE）的磁铁矿都识别。"
                            + "读数为『无磁性』不等于没有铁矿——赤铁矿只给弱读数、褐铁矿与铜锡金煤宝石完全无响应，"
                            + "判断铁矿存否要结合踏勘镐与感应探棒。"
                            + "走向判定需要两个相距足够的测点：单点时只给方位角与强度，"
                            + "请在不同位置再测一次。雷雨天气与附近铁质物体会干扰读数。",
                    emptySchema()),

            new ToolDef("echo_probe",
                    "【回声探针】超声成像：近距报出空腔形状、顶板高度、相邻水或岩浆、"
                            + "以及洞壁表面已暴露的矿面（只给位置与数量，不判矿种），并给塌方风险。"
                            + "不穿透完整岩体——只能看到与空腔连通的暴露表面，封闭在岩体内部的矿看不到。"
                            + "必须紧贴石质方块或浸入水中才能耦合，干燥空气中几乎无效。"
                            + "它是四件物探工具中唯一能裁决几何异常（空腔/流体/暴露矿面）的确定性工具。",
                    emptySchema()),

            new ToolDef("core_sampler",
                    "【钻探取样器】唯一真值工具：对准实体方块右键，自该方块起沿垂向逐格取芯，"
                            + "产出『钻探样本』物品，内含精确的岩层序列、厚度与含矿层深度。"
                            + "只能测正下方一个点、无横向信息，缓慢且消耗耐久。"
                            + "遇基岩、岩浆或不可钻障碍会终止（遇水不终止，水层记为含水层）。"
                            + "它不产出任何矿物掉落物，只产出数据——用于在开挖前验证其它工具的推断。"
                            + "参数为钻探起点的方块坐标，可选 depth 指定钻探深度（默认 32，最高 64 格）。",
                    coreSamplerSchema()),

            new ToolDef("core_sample",
                    "【钻探样本】读取一份已存在的钻探样本（背包中的样本物品）的层序摘要与含矿层。"
                            + "它不是探测工具，只是把已取到的真值数据读出来供推理使用。",
                    emptySchema()));
}
