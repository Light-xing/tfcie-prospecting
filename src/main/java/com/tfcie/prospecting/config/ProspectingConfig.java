package com.tfcie.prospecting.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * 勘探工具配置。翻译自 {@code prospecting_tools.json} 的 {@code config_defaults}。
 *
 * <p>按范围决策 D6，旧勘矿镐（legacy.prospectors_pick）本次不涉及，故不在此定义。
 * 所有字段均可通过 config 文件热载。
 */
public final class ProspectingConfig {

    public static final ForgeConfigSpec SPEC;

    /** 世界生成开关的运行时镜像：mixin 在世界生成热路径上读它（见 {@link #suppressSmallOreIndicators()}）。 */
    private static volatile boolean suppressSmallOreIndicators;

    // ---- T1 踏勘镐 ----
    public static final class ReconPick {
        public static ForgeConfigSpec.IntValue radius;
        public static ForgeConfigSpec.IntValue shallowDepth;
        public static ForgeConfigSpec.IntValue convergeSamples;
        public static ForgeConfigSpec.IntValue saturationAfter;
        public static ForgeConfigSpec.BooleanValue exposeOreName;
        public static ForgeConfigSpec.ConfigValue<String> confidenceCap;
        public static ForgeConfigSpec.DoubleValue falseNegativeChance;
        public static ForgeConfigSpec.BooleanValue requiresExposedRock;
        public static ForgeConfigSpec.BooleanValue canMine;
        public static ForgeConfigSpec.IntValue strongThreshold;
        public static ForgeConfigSpec.DoubleValue boulderFalsePositive;
        public static ForgeConfigSpec.DoubleValue oreConfusion;
        public static ForgeConfigSpec.DoubleValue overburdenContamination;
    }

    // ---- T2 密度探锤 ----
    public static final class DensityPlumb {
        public static ForgeConfigSpec.IntValue maxDepth;
        public static ForgeConfigSpec.IntValue depthBandBase;
        public static ForgeConfigSpec.IntValue driftPerUse;
        public static ForgeConfigSpec.IntValue driftResetAfterUses;
        public static ForgeConfigSpec.BooleanValue requireCalibration;
        public static ForgeConfigSpec.IntValue horizontalRadius;
    }

    // ---- T3 感应探棒 ----
    public static final class InductionProbe {
        public static ForgeConfigSpec.IntValue radius;
        public static ForgeConfigSpec.IntValue verticalDepth;
        public static ForgeConfigSpec.IntValue energyCost;
        public static ForgeConfigSpec.BooleanValue humanInterference;
    }

    // ---- T4 磁力探针 ----
    public static final class MagneticNeedle {
        public static ForgeConfigSpec.IntValue radius;
        public static ForgeConfigSpec.IntValue verticalDepth;
        public static ForgeConfigSpec.BooleanValue weatherNoise;
        public static ForgeConfigSpec.BooleanValue strikeRequiresMultiSample;
        public static ForgeConfigSpec.BooleanValue humanInterference;
    }

    // ---- T5 回声探针 ----
    public static final class EchoProbe {
        public static ForgeConfigSpec.IntValue rangeDry;
        public static ForgeConfigSpec.IntValue rangeWater;
        public static ForgeConfigSpec.BooleanValue enableCaveInHint;
    }

    // ---- 探查地图（改进版 / 电子探矿仪） ----
    public static final class Survey {
        public static ForgeConfigSpec.IntValue chunkRadius;
        public static ForgeConfigSpec.BooleanValue fullDepth;
        public static ForgeConfigSpec.IntValue verticalStride;
        public static ForgeConfigSpec.IntValue depthBelow;
        /** 以下三项是「代价接口」：默认 0 表示不设代价，改成非 0 即启用。 */
        public static ForgeConfigSpec.IntValue maxDurability;
        public static ForgeConfigSpec.IntValue wearPerUse;
        public static ForgeConfigSpec.IntValue cooldownTicks;
    }

    // ---- 遥感扫描仪 ----
    public static final class Terrain {
        public static ForgeConfigSpec.IntValue chunkRadius;
        public static ForgeConfigSpec.IntValue cameraRadius;
        public static ForgeConfigSpec.IntValue chunksPerTick;
    }

    // ---- 能量（预留接口，见 common/energy） ----
    public static final class Power {
        public static ForgeConfigSpec.BooleanValue enabled;
        public static ForgeConfigSpec.IntValue defaultCapacity;
    }

    // ---- T6 钻探取样器 ----
    public static final class CoreSampler {
        public static ForgeConfigSpec.IntValue maxDepth;
        public static ForgeConfigSpec.IntValue defaultDepth;
        public static ForgeConfigSpec.IntValue ticksPerBlock;
        public static ForgeConfigSpec.IntValue energyPerBlock;
        public static ForgeConfigSpec.BooleanValue leavesShaft;
        public static ForgeConfigSpec.BooleanValue shaftDropsItems;
        public static ForgeConfigSpec.BooleanValue stopOnBedrock;
        public static ForgeConfigSpec.DoubleValue lavaDamageChance;
        public static ForgeConfigSpec.BooleanValue lossyLooseLayers;
        public static ForgeConfigSpec.BooleanValue allowManualVariant;
    }

    // ---- 公共 ----
    public static final class Common {
        public static ForgeConfigSpec.BooleanValue humanInterference;
    }

    // ---- 世界生成（评估用开关，默认关闭） ----
    public static final class Worldgen {
        public static ForgeConfigSpec.BooleanValue disableSmallOreIndicators;
    }

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("recon_pick");
        ReconPick.radius = b.comment("踏勘镐采样半径（格）").defineInRange("radius", 8, 1, 64);
        ReconPick.shallowDepth = b.comment("有效浅带深度（格），自玩家脚下垂直向下计").defineInRange("shallow_depth", 24, 1, 256);
        ReconPick.convergeSamples = b.comment("收敛出疑似矿名所需一致采样次数").defineInRange("converge_samples", 3, 1, 64);
        ReconPick.saturationAfter = b.comment("同区块采样饱和阈值（须大于 converge_samples）").defineInRange("saturation_after", 6, 1, 128);
        ReconPick.exposeOreName = b.comment("是否允许收敛出疑似矿名（false = 只报矿族）").define("expose_ore_name", true);
        ReconPick.confidenceCap = b.comment("置信度上限，永不 high").define("confidence_cap", "medium");
        ReconPick.falseNegativeChance = b.comment("假阴性概率（青铜档默认；铜 0.3 / 铁 0.1）").defineInRange("false_negative_chance", 0.2, 0.0, 1.0);
        ReconPick.requiresExposedRock = b.comment("必须对裸露岩面使用").define("requires_exposed_rock", true);
        ReconPick.canMine = b.comment("是否可作为镐挖掘").define("can_mine", false);
        ReconPick.strongThreshold = b.comment("迹象判为「明显」所需的命中矿石方块数（少于该值为「微弱」）")
                .defineInRange("strong_threshold", 5, 1, 1024);
        ReconPick.boulderFalsePositive = b.comment("异地漂砾误报概率：只敲到地表矿屑时的假阳性概率")
                .defineInRange("boulder_false_positive", 0.5, 0.0, 1.0);
        ReconPick.oreConfusion = b.comment("相似矿物混淆概率：物性表中声明 confused_with 的矿种被误报为其目标矿种的概率（如黄铁矿误判金）")
                .defineInRange("ore_confusion", 0.5, 0.0, 1.0);
        ReconPick.overburdenContamination = b.comment("覆盖层污染概率：矿体之上为松散覆盖层时的读数污染概率")
                .defineInRange("overburden_contamination", 0.15, 0.0, 1.0);
        b.pop();

        b.push("density_plumb");
        DensityPlumb.maxDepth = b.comment("垂向探测深度（格）").defineInRange("max_depth", 96, 1, 256);
        DensityPlumb.depthBandBase = b.comment("浅层深度区间宽度基准（格），随深度放大").defineInRange("depth_band_base", 8, 1, 64);
        DensityPlumb.driftPerUse = b.comment("每次使用累积漂移（格）").defineInRange("drift_per_use", 2, 0, 64);
        DensityPlumb.driftResetAfterUses = b.comment("累计使用多少次后强制提示回基点复位").defineInRange("drift_reset_after_uses", 3, 1, 64);
        DensityPlumb.requireCalibration = b.comment("是否强制基点校准").define("require_calibration", true);
        // 默认 8：靶场单矿核水平半径 7~9 格，4 格半径扫不到矿体边缘、又不足以证明
        // 「多点粗方位 + 梯度追踪」的设计意图；与实机配置覆盖包保持一致（报告 20 §1.1）。
        DensityPlumb.horizontalRadius = b.comment("水平采样半径（格）；0 = 仅正下方单列（单列盲区，轻微偏离矿体即漏报）；"
                        + "默认 8 与靶场矿核半径匹配（报告 20 §1.1）")
                .defineInRange("horizontal_radius", 8, 0, 32);
        b.pop();

        b.push("induction_probe");
        InductionProbe.radius = b.comment("水平半径（格）").defineInRange("radius", 24, 1, 128);
        InductionProbe.verticalDepth = b.comment("垂向探测深度（格）").defineInRange("vertical_depth", 64, 1, 256);
        InductionProbe.energyCost = b.comment("每次耗能（FE）").defineInRange("energy_cost", 5000, 0, Integer.MAX_VALUE);
        InductionProbe.humanInterference = b.comment("IE 机械/铁质方块干扰开关").define("human_interference", true);
        b.pop();

        b.push("magnetic_needle");
        MagneticNeedle.radius = b.comment("水平半径（格）").defineInRange("radius", 32, 1, 128);
        MagneticNeedle.verticalDepth = b.comment("垂向探测深度（格）").defineInRange("vertical_depth", 48, 1, 256);
        MagneticNeedle.weatherNoise = b.comment("雷雨天气读数噪声").define("weather_noise", true);
        MagneticNeedle.strikeRequiresMultiSample = b.comment("走向判定需至少 2 个测点").define("strike_requires_multi_sample", true);
        MagneticNeedle.humanInterference = b.comment("铁轨 / 铁块 / IE 机械干扰开关").define("human_interference", true);
        b.pop();

        b.push("echo_probe");
        EchoProbe.rangeDry = b.comment("接触模式成像距离（格）").defineInRange("range_dry", 8, 1, 64);
        EchoProbe.rangeWater = b.comment("浸水模式成像距离（格）").defineInRange("range_water", 16, 1, 64);
        EchoProbe.enableCaveInHint = b.comment("塌方风险提示").define("enable_cave_in_hint", true);
        b.pop();

        b.push("core_sampler");
        CoreSampler.maxDepth = b.comment("钻探最大深度（格）").defineInRange("max_depth", 64, 1, 256);
        CoreSampler.defaultDepth = b.comment("默认钻探深度（格）").defineInRange("default_depth", 32, 1, 256);
        CoreSampler.ticksPerBlock = b.comment("每格钻取耗时（tick）。当前未使用：钻探已改为「对准实体方块右键即时取芯」，保留此键以便日后需要时恢复耗时版本")
                .defineInRange("ticks_per_block", 4, 1, 400);
        CoreSampler.energyPerBlock = b.comment("每格耗能（FE）").defineInRange("energy_per_block", 200, 0, Integer.MAX_VALUE);
        CoreSampler.leavesShaft = b.comment("是否留下钻孔").define("leaves_shaft", false);
        CoreSampler.shaftDropsItems = b.comment("钻孔方块是否掉落（必须为 false）").define("shaft_drops_items", false);
        CoreSampler.stopOnBedrock = b.comment("遇基岩是否终止").define("stop_on_bedrock", true);
        CoreSampler.lavaDamageChance = b.comment("遇岩浆损坏取样器概率").defineInRange("lava_damage_chance", 0.5, 0.0, 1.0);
        CoreSampler.lossyLooseLayers = b.comment("松散层取芯恢复率损耗").define("lossy_loose_layers", true);
        CoreSampler.allowManualVariant = b.comment("是否提供 TFC 手动摇柄版").define("allow_manual_variant", true);
        b.pop();

        b.push("survey");
        Survey.chunkRadius = b.comment("探查区块半径 r；地图为 (2r-1)×(2r-1) 区块，默认 5 → 9×9 区块")
                .defineInRange("chunk_radius", 5, 1, 8);
        Survey.fullDepth = b.comment("是否扫描世界全部高度（默认 true）。false 时改用 depth_below，扫描快但看不到深处的矿")
                .define("full_depth", true);
        Survey.verticalStride = b.comment("垂向采样步长。全高度(384格)下：2 → 约400万次查询；1 → 约800万次（明显吃 tick）；4 → 约200万次但薄矿脉可能漏采")
                .defineInRange("vertical_stride", 2, 1, 16);
        Survey.depthBelow = b.comment("full_depth=false 时，自探测点向下扫描的格数")
                .defineInRange("depth_below", 64, 1, 384);
        Survey.maxDurability = b.comment("探查工具的耐久上限。默认 0 = 不耗耐久（这批仪器不进常规生存获取）；"
                        + "给任意正值即启用耐久，耗尽后同样进入报废状态")
                .defineInRange("max_durability", 0, 0, Integer.MAX_VALUE);
        Survey.wearPerUse = b.comment("探查工具每次使用的磨损量。仅在 max_durability > 0 时生效")
                .defineInRange("wear_per_use", 0, 0, Integer.MAX_VALUE);
        Survey.cooldownTicks = b.comment("探查工具使用后的冷却（tick）。默认 0；防连点另由「同时只允许一个探查任务」保证")
                .defineInRange("cooldown_ticks", 0, 0, Integer.MAX_VALUE);
        b.pop();

        b.push("terrain");
        Terrain.chunkRadius = b.comment("遥感扫描的区块半径 r；栏二/栏三的绘制范围为 (2r-1)×(2r-1) 区块，默认 8 → 15×15 区块")
                .defineInRange("chunk_radius", 8, 1, 12);
        Terrain.cameraRadius = b.comment("栏一摄像机可活动的区块半径 r，范围为 (2r-1)×(2r-1) 区块，默认 6 → 11×11 区块。"
                        + "必须小于 chunk_radius —— 数据范围要比可活动范围大，边缘切换栏目时才有图可画")
                .defineInRange("camera_radius", 6, 1, 12);
        Terrain.chunksPerTick = b.comment("每 tick 扫描的区块数。地表只需「高度图 + 一次方块查询」，可批量处理")
                .defineInRange("chunks_per_tick", 8, 1, 64);
        b.pop();

        b.push("power");
        Power.enabled = b.comment("是否强制工具耗能。默认 false：接口已就位但暂不生效，"
                        + "因为本次尚未接入沉浸工程 FE，没有充能手段；接好 FE 后打开即可")
                .define("enabled", false);
        Power.defaultCapacity = b.comment("能量缓冲的默认上限（FE）。仅当工具未在材质参数里指定容量时使用；0 表示不储能")
                .defineInRange("default_capacity", 0, 0, Integer.MAX_VALUE);
        b.pop();

        b.push("common");
        Common.humanInterference = b.comment("IE 机械/铁质方块干扰总开关").define("human_interference", true);
        b.pop();

        b.push("worldgen");
        Worldgen.disableSmallOreIndicators = b.comment(
                        "关闭 TFC 矿脉的地表/地下小矿石指示块（tfc:ore/small_*）。默认 false = 保持群峦原本行为。",
                        "true 时矿脉本体照常生成，只是不再在矿体之上/周围放小矿石——等价于把矿脉 JSON 里的 indicator 去掉；",
                        "非矿石的指示物（如高岭土矿脉用血百合做指示）不受影响。",
                        "重启后生效（开关在启动时读入）。只影响此后新生成的区块：已生成的区块不会回填，需要开新世界或重新生成区块。",
                        "为评估/勘矿玩法准备：小矿石会提前泄露矿体位置，想要「只能靠仪器找矿」时打开。")
                .define("disable_small_ore_indicators", false);
        b.pop();

        SPEC = b.build();
    }

    public static void init() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        // 世界生成阶段（维度区块生成）没有 event 可挂，只能由 mixin 在 TFC 的矿脉配置上读这个缓存值，
        // 所以这里在 config 载入/热载时把开关镜像到一个 volatile 字段里，避免 mixin 里碰「config 还没载入」。
        bus.addListener((ModConfigEvent.Loading event) -> refreshWorldgenFlags());
        bus.addListener((ModConfigEvent.Reloading event) -> refreshWorldgenFlags());
        refreshWorldgenFlags();
    }

    private static void refreshWorldgenFlags() {
        suppressSmallOreIndicators = boolOr(Worldgen.disableSmallOreIndicators, false);
    }

    // ---------------- 世界生成开关的运行时镜像 ----------------

    /**
     * 是否关闭 TFC 矿脉的小矿石指示块。默认 false（保持群峦原样）。
     *
     * <p>由 {@code mixin.VeinConfigMixin} 在矿脉配置取 {@code indicator} 时读取；只影响新生成的区块。
     */
    public static boolean suppressSmallOreIndicators() {
        return suppressSmallOreIndicators;
    }

    // ---------------- 安全读取 ----------------
    // 配置在 ModConfig 加载前不可取值；而工具的参数可能在过早的时机被读到
    // （例如主菜单里的 tooltip）。此时回落到代码内的默认值，而不是抛异常。

    public static int intOr(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static double doubleOr(ForgeConfigSpec.DoubleValue value, double fallback) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static boolean boolOr(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static String stringOr(ForgeConfigSpec.ConfigValue<String> value, String fallback) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private ProspectingConfig() {}
}
