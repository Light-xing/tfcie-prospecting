package com.tfcie.prospecting.common.arena;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tfcie.prospecting.api.OreTaxonomy;

import javax.annotation.Nullable;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 靶场声明式配置（{@code map*.json}）的<b>纯数据模型 + 宽容解析器</b>。
 *
 * <h2>职责边界</h2>
 * 本类只做「JSON 文本 → 内存对象」与「结构合法性体检」，<b>不引用任何 Minecraft / Forge 类型</b>：
 * 它必须能在没有游戏运行时的纯 JUnit 里跑（见 `ArenaConfigTest`）。真正的方块放置由
 * {@code ArenaBuilder} 在服务端完成，本类只提供 {@link #zoneAt} / {@link #rockAt} 这类纯查询，
 * 让「哪一列属于哪个母岩分区」这条判定可以脱离 {@code ServerLevel} 单测。
 *
 * <h2>宽严分界</h2>
 * <ul>
 *   <li><b>宽容</b>：所有可缺省键都有明确默认值（见 {@link #parse(String, String)} 的注释）；
 *       未知岩石名 / 未知矿种 / 未知特征类型等「值不合法」<b>不</b>在解析期抛异常，
 *       而是攒到 {@link #structuralErrors()} 里由调用方决定是否拒绝整图——
 *       这样 {@code /prospect arena build} 能一次列出全部配置错误，而不是改一个报一个。</li>
 *   <li><b>严格</b>：JSON 语法错误、顶层不是对象、数组元素不是对象、缺少契约标为「必填」的键
 *       （zone 的 {@code rock}、outcrop/feature 的 {@code dx,dz}、feature 的 {@code type}）
 *       直接抛 {@link IllegalArgumentException}，消息带定位（如 {@code rock_zones[2]}），
 *       便于玩家在聊天栏一眼看出是哪一行写坏了。</li>
 * </ul>
 *
 * <h2>坐标约定</h2>
 * 一切偏移 {@code dx/dz} 均相对靶场内部有效区域的<b>西北角</b>{@code (minX, minZ)}；
 * 有效区间是<b>闭区间</b>{@code [0, sizeX) × [0, sizeZ)}。母岩分区 {@code rock_zones} 的矩形同样是闭区间，
 * 且允许重叠——重叠处按「首个命中」裁决（{@link #zoneAt} 的文档有说明），这正好支持
 * 「先铺全图底色，再盖局部特例」这种写法。
 *
 * @see ArenaGeometry 椭球矿核 / 起伏地表
 * @see ArenaOres 矿种 → 方块 id 的映射
 */
public final class ArenaConfig {

    // ---------------- 默认值（契约 §1.1） ----------------

    /** 缺省场地宽度（格）。 */
    public static final int DEFAULT_SIZE = 128;
    /** 缺省基准地表高度（模式 B 纯虚空兜底时的地表 y）。 */
    public static final int DEFAULT_BASE_SURFACE_Y = 72;
    /** 缺省泥土方块 id。 */
    public static final String DEFAULT_DIRT_BLOCK = "tfc:dirt/loam";
    /** 缺省草皮方块 id。 */
    public static final String DEFAULT_GRASS_BLOCK = "tfc:grass/loam";
    /** 缺省露头半径。 */
    public static final int DEFAULT_OUTCROP_RADIUS = 4;
    /** 缺省矿脉埋深（距该列真实地表向下）。 */
    public static final int DEFAULT_DEPTH_BELOW_SURFACE = 20;
    /** 缺省椭球 X 半径。 */
    public static final int DEFAULT_RX = 8;
    /** 缺省椭球 Y 半径。 */
    public static final int DEFAULT_RY = 4;
    /** 缺省椭球 Z 半径。 */
    public static final int DEFAULT_RZ = 8;
    /** 缺省走向角（度）。 */
    public static final double DEFAULT_STRIKE_DEG = 0.0;

    /** TFC 1.20.x 的 20 种标准岩石名（{@code tfc:rock/raw/<rock>} 的 {@code <rock>}）。 */
    public static final Set<String> TFC_ROCKS = orderedSet(
            "granite", "diorite", "gabbro", "shale", "claystone", "limestone", "conglomerate",
            "dolomite", "chert", "chalk", "rhyolite", "basalt", "andesite", "dacite",
            "quartzite", "slate", "phyllite", "schist", "gneiss", "marble");

    /** 契约允许的三种注入体类型。 */
    public static final Set<String> FEATURE_TYPES = orderedSet("vein", "water_pocket", "surface_metal");

    /** 注入体类型：矿脉。 */
    public static final String TYPE_VEIN = "vein";
    /** 注入体类型：地下水囊。 */
    public static final String TYPE_WATER_POCKET = "water_pocket";
    /** 注入体类型：地表金属干扰。 */
    public static final String TYPE_SURFACE_METAL = "surface_metal";

    // ---------------- 数据记录 ----------------

    /**
     * 一个母岩分区（闭区间矩形）。
     *
     * @param rock          上层 / 全区母岩名（20 种 TFC 岩石之一）
     * @param bottomRock    分界深度以下的下层母岩；{@code null} 表示整列同岩
     * @param boundaryDepth 距该列真实地表向下的分界深度（格）；{@code <= boundaryDepth} 为上层
     */
    public record Zone(int xMin, int xMax, int zMin, int zMax, String rock,
                       @Nullable String bottomRock, int boundaryDepth) {}

    /**
     * 一处人工裸岩露头。
     *
     * @param rock {@code null} 表示运行时回落到该点 {@link #rockAt} 判定的母岩
     */
    public record Outcrop(int dx, int dz, int radius, @Nullable String rock) {}

    /**
     * 一个注入体（矿脉 / 水囊 / 地表金属）。
     *
     * @param ore            矿种 id；仅 {@code vein} 必填，其余为 {@code null}
     * @param depthBelowSurface 距该列真实地表向下的埋深（格）；水囊取椭球中心埋深
     * @param rx,ry,rz       椭球三轴半径（格）
     * @param strikeDeg      水平走向角（度）；{@code 45} = 长轴沿 dx=dz，{@code 135} = 长轴沿 dx=-dz
     * @param salt           水囊专用：{@code true} 填盐水，{@code false} 填淡水
     * @param hostRock       兜底母岩；当前位置无对应矿方块时用它拼 id
     */
    public record Feature(String type, String id, @Nullable String ore, boolean isTarget,
                          int dx, int dz, int depthBelowSurface, int rx, int ry, int rz,
                          double strikeDeg, boolean salt, @Nullable String hostRock) {}

    /** 推荐起点（相对西北角偏移）。 */
    public record Spawn(int dx, int dz) {}

    // ---------------- 字段 ----------------

    /** 靶场名（用于 {@code arenas_output/<name>_ground_truth.json}）。 */
    public final String name;
    /** 内部有效区域 X 宽度（格）。 */
    public final int sizeX;
    /** 内部有效区域 Z 深度（格）。 */
    public final int sizeZ;
    /** 模式 B 纯虚空兜底时的基准地表高度。 */
    public final int baseSurfaceY;
    /** 是否把地表以下的水 / 空气空腔回填母岩（幂等重刷与纯净场地用）。 */
    public final boolean fillUndergroundCaves;
    /** 地表泥土方块 id。 */
    public final String dirtBlock;
    /** 地表草皮方块 id。 */
    public final String grassBlock;
    /** 推荐起点。 */
    public final Spawn spawn;
    /** 母岩分区（有序，首个命中优先）。 */
    public final List<Zone> zones;
    /** 人工露头。 */
    public final List<Outcrop> outcrops;
    /** 注入体。 */
    public final List<Feature> features;

    private ArenaConfig(String name, int sizeX, int sizeZ, int baseSurfaceY, boolean fillUndergroundCaves,
                        String dirtBlock, String grassBlock, Spawn spawn,
                        List<Zone> zones, List<Outcrop> outcrops, List<Feature> features) {
        this.name = name;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.baseSurfaceY = baseSurfaceY;
        this.fillUndergroundCaves = fillUndergroundCaves;
        this.dirtBlock = dirtBlock;
        this.grassBlock = grassBlock;
        this.spawn = spawn;
        this.zones = zones;
        this.outcrops = outcrops;
        this.features = features;
    }

    // ---------------- 解析 ----------------

    /**
     * 从 JSON 文本解析。
     *
     * <p>缺省值（契约 §1.1）：
     * <ul>
     *   <li>{@code name} → {@code fallbackName}；{@code size_x}/{@code size_z} → 128；
     *       {@code base_surface_y} → 72；{@code fill_underground_caves} → false；
     *       {@code dirt_block}/{@code grass_block} → {@code tfc:dirt/loam} / {@code tfc:grass/loam}；</li>
     *   <li>{@code spawn} → 地图中心 {@code (sizeX/2, sizeZ/2)}；{@code rock_zones}/{@code outcrops}/{@code features} → 空表；</li>
     *   <li>zone：{@code bottom_rock} → null，{@code boundary_depth} → 0；</li>
     *   <li>outcrop：{@code radius} → 4，{@code rock} → null；</li>
     *   <li>feature：{@code id} → {@code type + "-" + (数组下标+1)}，{@code ore} → null，
     *       {@code is_target} → false，{@code depth_below_surface} → 20，
     *       {@code rx/ry/rz} → 8/4/8，{@code strike_deg} → 0，{@code salt} → false，{@code host_rock} → null。</li>
     * </ul>
     *
     * @param fallbackName JSON 未写 {@code name} 时使用的名字（通常取文件名）
     * @throws IllegalArgumentException JSON 语法错误 / 顶层非对象 / 缺少必填字段 / 字段类型严重不符
     */
    public static ArenaConfig parse(String json, @Nullable String fallbackName) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("靶场配置内容为空，无法解析（fallbackName=" + fallbackName + "）");
        }
        final JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("靶场 JSON 语法错误: " + e.getMessage(), e);
        }
        if (root == null || root.isJsonNull() || !root.isJsonObject()) {
            throw new IllegalArgumentException("靶场 JSON 顶层必须是对象 {...}，实际为: "
                    + (root == null ? "null" : root.getClass().getSimpleName()));
        }
        return parseObject(root.getAsJsonObject(), fallbackName);
    }

    /**
     * 从任意 {@link Reader}（文件 / classpath 资源）解析，语义与 {@link #parse(String, String)} 完全一致。
     *
     * <p>本方法<b>不关闭</b>传入的 reader，调用方负责（{@code ArenaStore} 用 try-with-resources）。
     */
    public static ArenaConfig parse(Reader reader, @Nullable String fallbackName) {
        if (reader == null) {
            throw new IllegalArgumentException("靶场配置 Reader 为 null（fallbackName=" + fallbackName + "）");
        }
        final JsonElement root;
        try {
            root = JsonParser.parseReader(reader);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("靶场 JSON 语法错误: " + e.getMessage(), e);
        }
        if (root == null || root.isJsonNull() || !root.isJsonObject()) {
            throw new IllegalArgumentException("靶场 JSON 顶层必须是对象 {...}（fallbackName=" + fallbackName + "）");
        }
        return parseObject(root.getAsJsonObject(), fallbackName);
    }

    private static ArenaConfig parseObject(JsonObject root, @Nullable String fallbackName) {
        final String name = string(root, "name", fallbackName);
        final int sizeX = integer(root, "size_x", DEFAULT_SIZE, "根");
        final int sizeZ = integer(root, "size_z", DEFAULT_SIZE, "根");
        final int baseSurfaceY = integer(root, "base_surface_y", DEFAULT_BASE_SURFACE_Y, "根");
        final boolean fillUndergroundCaves = bool(root, "fill_underground_caves", false, "根");
        final String dirtBlock = string(root, "dirt_block", DEFAULT_DIRT_BLOCK);
        final String grassBlock = string(root, "grass_block", DEFAULT_GRASS_BLOCK);

        // spawn：整块缺省 → 地图中心；块内单键缺省 → 对应中心分量
        final Spawn spawn;
        final JsonElement spawnElement = root.get("spawn");
        if (spawnElement == null || spawnElement.isJsonNull()) {
            spawn = new Spawn(sizeX / 2, sizeZ / 2);
        } else if (!spawnElement.isJsonObject()) {
            throw new IllegalArgumentException("根字段 spawn 必须是对象 {dx, dz}");
        } else {
            final JsonObject spawnObject = spawnElement.getAsJsonObject();
            spawn = new Spawn(
                    integer(spawnObject, "dx", sizeX / 2, "spawn"),
                    integer(spawnObject, "dz", sizeZ / 2, "spawn"));
        }

        // rock_zones
        final List<Zone> zones = new ArrayList<>();
        final JsonArray zoneArray = array(root, "rock_zones", "根");
        for (int i = 0; i < zoneArray.size(); i++) {
            final JsonObject o = objectAt(zoneArray, "rock_zones", i);
            final String ctx = "rock_zones[" + i + "]";
            zones.add(new Zone(
                    integer(o, "x_min", 0, ctx),
                    integer(o, "x_max", 0, ctx),
                    integer(o, "z_min", 0, ctx),
                    integer(o, "z_max", 0, ctx),
                    requiredString(o, "rock", ctx),
                    nullableString(o, "bottom_rock"),
                    integer(o, "boundary_depth", 0, ctx)));
        }

        // outcrops
        final List<Outcrop> outcrops = new ArrayList<>();
        final JsonArray outcropArray = array(root, "outcrops", "根");
        for (int i = 0; i < outcropArray.size(); i++) {
            final JsonObject o = objectAt(outcropArray, "outcrops", i);
            final String ctx = "outcrops[" + i + "]";
            outcrops.add(new Outcrop(
                    requiredInt(o, "dx", ctx),
                    requiredInt(o, "dz", ctx),
                    integer(o, "radius", DEFAULT_OUTCROP_RADIUS, ctx),
                    nullableString(o, "rock")));
        }

        // features
        final List<Feature> features = new ArrayList<>();
        final JsonArray featureArray = array(root, "features", "根");
        for (int i = 0; i < featureArray.size(); i++) {
            final JsonObject o = objectAt(featureArray, "features", i);
            final String ctx = "features[" + i + "]";
            final String type = requiredString(o, "type", ctx);
            final String explicitId = nullableString(o, "id");
            // 缺省 id 用「类型 + 序号」，序号取数组下标 + 1，保证同图内唯一可读
            final String id = (explicitId == null || explicitId.isBlank()) ? type + "-" + (i + 1) : explicitId;
            features.add(new Feature(
                    type,
                    id,
                    nullableString(o, "ore"),
                    bool(o, "is_target", false, ctx),
                    requiredInt(o, "dx", ctx),
                    requiredInt(o, "dz", ctx),
                    integer(o, "depth_below_surface", DEFAULT_DEPTH_BELOW_SURFACE, ctx),
                    integer(o, "rx", DEFAULT_RX, ctx),
                    integer(o, "ry", DEFAULT_RY, ctx),
                    integer(o, "rz", DEFAULT_RZ, ctx),
                    decimal(o, "strike_deg", DEFAULT_STRIKE_DEG, ctx),
                    bool(o, "salt", false, ctx),
                    nullableString(o, "host_rock")));
        }

        return new ArenaConfig(name, sizeX, sizeZ, baseSurfaceY, fillUndergroundCaves,
                dirtBlock, grassBlock, spawn,
                List.copyOf(zones), List.copyOf(outcrops), List.copyOf(features));
    }

    // ---------------- 查询 ----------------

    /**
     * 该列（相对西北角的偏移）命中的母岩分区；<b>首个命中</b>优先，未覆盖返回 {@code null}。
     *
     * <p>「首个命中」让配置可以写成「先铺全图底色，再叠局部特例」；
     * 想让特例生效就把它写在底色之前（或干脆别让底色覆盖它）。
     */
    @Nullable
    public Zone zoneAt(int dx, int dz) {
        for (Zone zone : zones) {
            if (dx >= zone.xMin() && dx <= zone.xMax() && dz >= zone.zMin() && dz <= zone.zMax()) {
                return zone;
            }
        }
        return null;
    }

    /**
     * 该方块位置最终应用的母岩名。
     *
     * <p>先由 {@link #zoneAt} 找到分区（未覆盖返回 {@code null}），再按上下层裁决：
     * 距该列真实地表向下 {@code <= boundaryDepth} 为上层 {@code rock}，更深为 {@code bottomRock}
     * （没有下层则整列同岩）。
     *
     * @param dx,dz    相对靶场西北角的水平偏移
     * @param y        方块 y
     * @param surfaceY 该列真实地表高度（模式 A 保留原地形，模式 B 由 {@link ArenaGeometry#surfaceY} 给出）
     */
    @Nullable
    public String rockAt(int dx, int dz, int y, int surfaceY) {
        final Zone zone = zoneAt(dx, dz);
        if (zone == null) {
            return null;
        }
        final String bottomRock = zone.bottomRock();
        if (bottomRock == null) {
            return zone.rock();
        }
        return (surfaceY - y) <= zone.boundaryDepth() ? zone.rock() : bottomRock;
    }

    /** 注入体数量（{@code features.size()}）。 */
    public int featureCount() {
        return features.size();
    }

    /**
     * {@link #spawn} 的访问器别名。
     *
     * <p>契约把 {@code spawn} 声明为 public final 字段，运行时代码既可能写
     * {@code cfg.spawn.dx()} 也可能写 record 风格的 {@code cfg.spawn().dx()}；
     * 这里补一个同值访问器（纯新增，不改既有签名）让两种写法都能编译。
     */
    public Spawn spawn() {
        return spawn;
    }

    // ---------------- 结构体检 ----------------

    /**
     * 结构 / 越界 / 覆盖 / 命名合法性体检；<b>空表 = 通过</b>。
     *
     * <p>契约要求至少覆盖：{@code sizeX/sizeZ > 0}、zones 非空、
     * zone 的 rock/bottom_rock 在 20 种 TFC 岩石名内、全图每一列至少被一个 zone 覆盖、
     * outcrop/feature 的 dx,dz 在界内、vein 的 ore 非空且 {@link OreTaxonomy#contains} 通过、
     * rx/ry/rz &gt; 0、depth_below_surface &gt;= 0、type 为三种之一、spawn 在界内。
     * 另外顺手体检矩形方向、boundary_depth、露头半径、host_rock 与 name。
     *
     * <p>返回的是可读中文错误项，逐条列出、不短路，便于一次改完所有配置问题。
     */
    public List<String> structuralErrors() {
        final List<String> errors = new ArrayList<>();

        if (name == null || name.isBlank()) {
            errors.add("name 不能为空（可在 JSON 写 name，或由文件名兜底）");
        }
        if (sizeX <= 0) {
            errors.add("size_x 必须 > 0，当前为 " + sizeX);
        }
        if (sizeZ <= 0) {
            errors.add("size_z 必须 > 0，当前为 " + sizeZ);
        }
        if (zones.isEmpty()) {
            errors.add("rock_zones 不能为空：全图每一列都必须有母岩，否则模式 B 无法铺地层");
        }

        // 分区自身合法性
        for (int i = 0; i < zones.size(); i++) {
            final Zone zone = zones.get(i);
            final String ctx = "rock_zones[" + i + "]";
            if (!TFC_ROCKS.contains(zone.rock())) {
                errors.add(ctx + ".rock 不是 TFC 岩石名: " + zone.rock()
                        + "（允许: " + String.join(", ", TFC_ROCKS) + "）");
            }
            if (zone.bottomRock() != null && !TFC_ROCKS.contains(zone.bottomRock())) {
                errors.add(ctx + ".bottom_rock 不是 TFC 岩石名: " + zone.bottomRock());
            }
            if (zone.xMin() > zone.xMax()) {
                errors.add(ctx + " 矩形非法: x_min(" + zone.xMin() + ") > x_max(" + zone.xMax() + ")");
            }
            if (zone.zMin() > zone.zMax()) {
                errors.add(ctx + " 矩形非法: z_min(" + zone.zMin() + ") > z_max(" + zone.zMax() + ")");
            }
            if (zone.boundaryDepth() < 0) {
                errors.add(ctx + ".boundary_depth 必须 >= 0，当前为 " + zone.boundaryDepth());
            }
        }

        // 覆盖检查：全图每一列都必须命中至少一个 zone（O(sizeX*sizeZ*zoneCount)，256×256 也微不足道）
        if (sizeX > 0 && sizeZ > 0 && !zones.isEmpty()) {
            int uncovered = 0;
            String firstUncovered = null;
            for (int dx = 0; dx < sizeX; dx++) {
                for (int dz = 0; dz < sizeZ; dz++) {
                    if (zoneAt(dx, dz) == null) {
                        uncovered++;
                        if (firstUncovered == null) {
                            firstUncovered = "(" + dx + ", " + dz + ")";
                        }
                    }
                }
            }
            if (uncovered > 0) {
                errors.add("有 " + uncovered + " 个 (dx,dz) 未被任何 rock_zone 覆盖，首个未覆盖列: " + firstUncovered);
            }
        }

        // 露头
        for (int i = 0; i < outcrops.size(); i++) {
            final Outcrop outcrop = outcrops.get(i);
            final String ctx = "outcrops[" + i + "]";
            if (!inBounds(outcrop.dx(), outcrop.dz())) {
                errors.add(ctx + " 坐标越界: (" + outcrop.dx() + ", " + outcrop.dz() + ")，"
                        + "要求 dx ∈ [0," + sizeX + ")、dz ∈ [0," + sizeZ + ")");
            }
            if (outcrop.radius() <= 0) {
                errors.add(ctx + ".radius 必须 > 0，当前为 " + outcrop.radius());
            }
            if (outcrop.rock() != null && !TFC_ROCKS.contains(outcrop.rock())) {
                errors.add(ctx + ".rock 不是 TFC 岩石名: " + outcrop.rock());
            }
        }

        // 注入体
        for (int i = 0; i < features.size(); i++) {
            final Feature feature = features.get(i);
            final String ctx = "features[" + i + "]";
            if (!FEATURE_TYPES.contains(feature.type())) {
                errors.add(ctx + ".type 未知: " + feature.type()
                        + "（只允许 vein / water_pocket / surface_metal）");
            }
            if (!inBounds(feature.dx(), feature.dz())) {
                errors.add(ctx + " 坐标越界: (" + feature.dx() + ", " + feature.dz() + ")，"
                        + "要求 dx ∈ [0," + sizeX + ")、dz ∈ [0," + sizeZ + ")");
            }
            if (feature.rx() <= 0 || feature.ry() <= 0 || feature.rz() <= 0) {
                errors.add(ctx + " 椭球半径必须都 > 0，当前 rx/ry/rz = "
                        + feature.rx() + "/" + feature.ry() + "/" + feature.rz());
            }
            if (feature.depthBelowSurface() < 0) {
                errors.add(ctx + ".depth_below_surface 必须 >= 0，当前为 " + feature.depthBelowSurface());
            }
            if (TYPE_VEIN.equals(feature.type())) {
                final String ore = feature.ore();
                if (ore == null || ore.isBlank()) {
                    errors.add(ctx + " 是 vein，必须填写 ore（矿种 id，如 malachite）");
                } else if (!OreTaxonomy.contains(ore)) {
                    errors.add(ctx + ".ore 不是已知矿种: " + ore + "（见 OreTaxonomy 的 32 种 id）");
                }
            }
            if (feature.hostRock() != null && !TFC_ROCKS.contains(feature.hostRock())) {
                errors.add(ctx + ".host_rock 不是 TFC 岩石名: " + feature.hostRock());
            }
        }

        // 起点
        if (!inBounds(spawn.dx(), spawn.dz())) {
            errors.add("spawn 越界: (" + spawn.dx() + ", " + spawn.dz() + ")，"
                    + "要求 dx ∈ [0," + sizeX + ")、dz ∈ [0," + sizeZ + ")");
        }

        return errors;
    }

    /** 该 (dx,dz) 是否落在内部有效区域（闭区间外沿）内。 */
    public boolean inBounds(int dx, int dz) {
        return dx >= 0 && dx < sizeX && dz >= 0 && dz < sizeZ;
    }

    /** 是否为 TFC 20 种标准岩石名之一。 */
    public static boolean isKnownRock(@Nullable String rock) {
        return rock != null && TFC_ROCKS.contains(rock);
    }

    /** 是否为契约允许的三种注入体类型之一。 */
    public static boolean isKnownFeatureType(@Nullable String type) {
        return type != null && FEATURE_TYPES.contains(type);
    }

    // ---------------- JSON 字段读取（宽容 + 定位） ----------------

    private static JsonArray array(JsonObject o, String key, String ctx) {
        final JsonElement element = o.get(key);
        if (element == null || element.isJsonNull()) {
            return new JsonArray();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(ctx + " 字段 " + key + " 必须是数组 []");
        }
        return element.getAsJsonArray();
    }

    private static JsonObject objectAt(JsonArray array, String key, int index) {
        final JsonElement element = array.get(index);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(key + "[" + index + "] 必须是 JSON 对象 {...}");
        }
        return element.getAsJsonObject();
    }

    private static String requiredString(JsonObject o, String key, String ctx) {
        final String value = nullableString(o, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(ctx + " 缺少必填字段 " + key);
        }
        return value;
    }

    private static int requiredInt(JsonObject o, String key, String ctx) {
        final JsonElement element = o.get(key);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException(ctx + " 缺少必填字段 " + key);
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(ctx + " 字段 " + key + " 必须是整数", e);
        }
    }

    @Nullable
    private static String nullableString(JsonObject o, String key) {
        final JsonElement element = o.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        final String value = element.getAsString();
        return value == null ? null : value.trim();
    }

    private static String string(JsonObject o, String key, @Nullable String fallback) {
        final String value = nullableString(o, key);
        return value == null ? fallback : value;
    }

    private static int integer(JsonObject o, String key, int fallback, String ctx) {
        final JsonElement element = o.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(ctx + " 字段 " + key + " 必须是整数", e);
        }
    }

    private static double decimal(JsonObject o, String key, double fallback, String ctx) {
        final JsonElement element = o.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(ctx + " 字段 " + key + " 必须是数字", e);
        }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback, String ctx) {
        final JsonElement element = o.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(ctx + " 字段 " + key + " 必须是布尔值", e);
        }
    }

    /** 构造保持声明顺序的不可变集合（错误信息与文档可读性需要顺序稳定）。 */
    private static Set<String> orderedSet(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
    }
}
