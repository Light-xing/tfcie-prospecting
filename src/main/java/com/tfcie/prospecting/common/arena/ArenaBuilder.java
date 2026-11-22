package com.tfcie.prospecting.common.arena;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.tfcie.prospecting.TfcieProspecting;
import com.tfcie.prospecting.common.prospecting.ReconScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.common.Tags;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 声明式靶场构建流水线：<b>一条命令把任意地图变成受控勘矿考场</b>。
 *
 * <h2>五步幂等流水线</h2>
 * <ol>
 *   <li><b>Step 0 区块确保 + 纯虚空兜底</b>：强制加载靶场外扩 1 格的区块；逐列判定
 *       「已有地形（模式 A）」还是「纯空气虚空列（模式 B）」。模式 A 原样保留地表起伏、
 *       草皮、树木与崖壁；模式 B 自基岩层起按 {@code base_surface_y ± 2} 的正弦起伏
 *       现铺一整列地层（母岩 / 泥土 / 草皮）。</li>
 *   <li><b>Step 1 换母岩 + 100% 洗净野生矿</b>：遍历每列地下，把
 *       {@code tfc:ore/*}、{@code tfc:deposit/*}、{@code immersivegeology:*}、
 *       {@code tfcgyres_orehints:*}、{@code #forge:ores} 及「按注册名能推断成矿种」的
 *       方块一律换回该列目标母岩；地表以上 3 格内的矿粒/提示物直接清成空气；
 *       TFC 岩石家族（{@code tfc:rock/<variant>/<old>}）一对一改名为目标母岩的同变体；
 *       可选（{@code fill_underground_caves}）把地下空腔与水体回填成母岩 —— 这样改参数
 *       重刷时上一轮注入的水囊也会被洗干净，真正幂等。最后按配置铺裸岩露头。</li>
 *   <li><b>Step 2 注入矿核与干扰体</b>：椭球（可绕 Y 轴旋转出走向）矿脉按
 *       {@code depth_below_surface} 相对<b>该列真实地表</b>定位；中心 {@code r<=0.4}
 *       填 rich、{@code 0.4..0.75} 填 normal、外缘 {@code 0.75..1.0} 以 0.7 概率填 poor
 *       （非品位矿种不带前缀直接铺满）。水囊按流体+1 格密封岩壳生成；地表金属干扰铺
 *       铁轨/铁栏杆。</li>
 *   <li><b>Step 3 封边</b>：外框整高 {@code minecraft:barrier}，内部底面
 *       {@code minecraft:bedrock}。</li>
 *   <li><b>Step 4 真值导出</b>：写 {@code arenas_output/<name>_ground_truth.json}。</li>
 * </ol>
 *
 * <h2>为什么全部走 {@code setBlock(..., Block.UPDATE_CLIENTS)}</h2>
 * 批量造图绝不能触发邻格流体/方块物理连锁更新，也不能触发玩家放置事件（否则会污染
 * Numen 的权限记录）。{@code UPDATE_CLIENTS}（flag = 2）恰好只发客户端同步，不做这些。
 * 相同方块会被 {@code LevelChunk#setBlockState} 的引用相等快路径直接跳过，因此重复执行
 * 同一命令是廉价的幂等重刷，而不是把整张图重写一遍。
 *
 * <h2>纯算法与运行时分离</h2>
 * 椭球/走向/起伏/矿种命名/分区的全部数学都在
 * {@link ArenaGeometry}、{@link ArenaOres}、{@link ArenaConfig} 里（不依赖
 * {@code ServerLevel}，可纯 JUnit 覆盖）；本类只负责「读世界 → 调纯函数 → 写世界 → 导出」。
 */
public final class ArenaBuilder {

    /** 洗矿时地表以上额外清理的格数（散落矿粒 / OreHints 提示块）。 */
    private static final int SURFACE_CLEAN_HEIGHT = 3;

    /** 一次 {@code /prospect arena build} 的完整结果（命令层据此打印紧凑 JSON 摘要）。 */
    public record Result(String name,
                         int minX, int maxX, int minZ, int maxZ,
                         int spawnX, int spawnY, int spawnZ,
                         long washedOldOreBlocks,
                         long placedTargetOreBlocks,
                         long placedDistractorBlocks,
                         List<FeatureTruth> targets,
                         List<FeatureTruth> distractors,
                         List<String> warnings,
                         Path groundTruthPath,
                         long elapsedMs) {}

    /** 真值文件里每个异常源的一条记录。 */
    public record FeatureTruth(String id, String type, @Nullable String ore, boolean salt,
                               int centerX, int centerY, int centerZ, int depthBelowSurface,
                               int horizontalRadiusX, int horizontalRadiusZ, double strikeDeg,
                               int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                               long placedBlocks) {}

    /** 构建期上下文（世界、边界、缓存、计数器）。 */
    private static final class Ctx {
        final ServerLevel level;
        final ArenaConfig cfg;
        final int minX, maxX, minZ, maxZ;
        final int minY, maxY;
        final int[] surface; // 按 (dx * sizeZ + dz) 缓存每列真实地表 y

        final Map<Block, Boolean> oreLikeCache = new HashMap<>();
        final Map<String, BlockState> rawRockCache = new HashMap<>();
        final Map<String, BlockState> variantCache = new HashMap<>();
        final BlockState dirtState, grassState, barrierState, bedrockState, airState;

        long washed;
        long placedTarget;
        long placedDistractor;
        final List<FeatureTruth> targets = new ArrayList<>();
        final List<FeatureTruth> distractors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        Ctx(ServerLevel level, ArenaConfig cfg, int minX, int minZ) {
            this.level = level;
            this.cfg = cfg;
            this.minX = minX;
            this.maxX = minX + cfg.sizeX - 1;
            this.minZ = minZ;
            this.maxZ = minZ + cfg.sizeZ - 1;
            this.minY = level.getMinBuildHeight();
            this.maxY = level.getMaxBuildHeight();
            this.surface = new int[cfg.sizeX * cfg.sizeZ];
            java.util.Arrays.fill(this.surface, Integer.MIN_VALUE);
            this.dirtState = requireState(cfg.dirtBlock);
            this.grassState = requireState(cfg.grassBlock);
            this.barrierState = Blocks.BARRIER.defaultBlockState();
            this.bedrockState = Blocks.BEDROCK.defaultBlockState();
            this.airState = Blocks.AIR.defaultBlockState();
        }

        int index(int dx, int dz) {
            return dx * cfg.sizeZ + dz;
        }
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 按配置在 {@code (minX, minZ)} 起、{@code sizeX × sizeZ} 的区域内构建靶场。
     *
     * @throws IllegalArgumentException 配置在运行时不可解析（岩石/方块 id 缺失等）
     */
    public static Result build(ServerLevel level, ArenaConfig cfg, int minX, int minZ) {
        final long start = System.currentTimeMillis();
        final Ctx c = new Ctx(level, cfg, minX, minZ);

        final List<String> problems = validateRuntime(c);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("靶场配置 " + cfg.name + " 运行时不可构建：" + problems);
        }

        TfcieProspecting.LOGGER.info("[arena] 开始构建 {}：内部区域 x[{}..{}] z[{}..{}] y[{}..{}]",
                cfg.name, c.minX, c.maxX, c.minZ, c.maxZ, c.minY, c.maxY);

        final List<int[]> forced = forceChunks(c);
        try {
            terrainAndWash(c);   // Step 0 + Step 1（含露头）
            injectFeatures(c);   // Step 2
            buildEnvelope(c);    // Step 3
        } finally {
            for (int[] chunk : forced) {
                level.setChunkForced(chunk[0], chunk[1], false);
            }
        }

        final int spawnX = c.minX + cfg.spawn().dx();
        final int spawnZ = c.minZ + cfg.spawn().dz();
        final int spawnY = clampY(c, surfaceAt(c, spawnX, spawnZ) + 1);

        final Path truthPath = ArenaStore.groundTruthPath(cfg.name);
        final JsonObject truth = buildGroundTruth(c, spawnX, spawnY, spawnZ);
        ArenaStore.writeJson(truthPath, truth);   // Step 4

        final long elapsed = System.currentTimeMillis() - start;
        TfcieProspecting.LOGGER.info("[arena] 构建完成 {}：洗掉旧矿 {} 块，目标矿 {} 块，干扰 {} 块，耗时 {} ms，真值 {}",
                cfg.name, c.washed, c.placedTarget, c.placedDistractor, elapsed, truthPath);

        return new Result(cfg.name, c.minX, c.maxX, c.minZ, c.maxZ, spawnX, spawnY, spawnZ,
                c.washed, c.placedTarget, c.placedDistractor,
                List.copyOf(c.targets), List.copyOf(c.distractors), List.copyOf(c.warnings),
                truthPath, elapsed);
    }

    // ------------------------------------------------------------------
    // Step 0 + Step 1
    // ------------------------------------------------------------------

    /** 强制加载靶场外扩 1 格覆盖的全部区块，返回需在收尾时解除的区块列表。 */
    private static List<int[]> forceChunks(Ctx c) {
        final List<int[]> forced = new ArrayList<>();
        final int chunkMinX = (c.minX - 1) >> 4;
        final int chunkMaxX = (c.maxX + 1) >> 4;
        final int chunkMinZ = (c.minZ - 1) >> 4;
        final int chunkMaxZ = (c.maxZ + 1) >> 4;
        for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
            for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                if (c.level.setChunkForced(cx, cz, true)) {
                    forced.add(new int[]{cx, cz});
                }
                // 强制加载后显式取一次，确保区块真正进入 FULL 状态再写方块
                c.level.getChunk(cx << 4, cz << 4);
            }
        }
        TfcieProspecting.LOGGER.info("[arena] 已强制加载 {} 个区块（{}×{}）",
                forced.size(), chunkMaxX - chunkMinX + 1, chunkMaxZ - chunkMinZ + 1);
        return forced;
    }

    /** Step 0 + Step 1：逐列兜底地层、洗矿、换母岩、回填空腔、铺露头。 */
    private static void terrainAndWash(Ctx c) {
        final int reportEvery = Math.max(1, c.cfg.sizeX / 8);
        for (int x = c.minX; x <= c.maxX; x++) {
            for (int z = c.minZ; z <= c.maxZ; z++) {
                prepareColumn(c, x, z);
            }
            if ((x - c.minX + 1) % reportEvery == 0) {
                TfcieProspecting.LOGGER.info("[arena] {}：已完成 {}/{} 列", c.cfg.name,
                        x - c.minX + 1, c.cfg.sizeX);
            }
        }
        placeOutcrops(c);
    }

    private static void prepareColumn(Ctx c, int x, int z) {
        final int dx = x - c.minX;
        final int dz = z - c.minZ;
        final int foundTop = scanGroundTop(c, x, z);

        if (foundTop <= c.minY) {
            // 模式 B：纯空气虚空列 —— 按基础地表高度 + 正弦微起伏现铺一整列
            final int surfaceY = Math.max(c.minY + 2,
                    Math.min(c.maxY - 2, ArenaGeometry.surfaceY(c.cfg.baseSurfaceY, dx, dz)));
            generateVoidColumn(c, x, z, dx, dz, surfaceY);
            c.surface[c.index(dx, dz)] = surfaceY;
            return;
        }

        // 模式 A：保留既有地形，只洗矿 + 换母岩（+ 可选回填空腔）
        c.surface[c.index(dx, dz)] = foundTop;
        washColumn(c, x, z, dx, dz, foundTop);
        cleanAboveGround(c, x, z, foundTop);
    }

    /** 自世界顶部向下找该列「地面」方块；找不到（纯虚空）返回 {@code -1}。 */
    private static int scanGroundTop(Ctx c, int x, int z) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = c.maxY - 1; y >= c.minY; y--) {
            pos.set(x, y, z);
            if (isGround(c.level.getBlockState(pos))) {
                return y;
            }
        }
        return -1;
    }

    /** 「地面」判定：排除空气、流体、树叶原木与可被替换的植被/雪层。 */
    private static boolean isGround(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() || state.canBeReplaced()) {
            return false;
        }
        return !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS);
    }

    /** 模式 B：自基岩层到地表铺出「母岩 → 泥土 → 草皮」的完整地层。 */
    private static void generateVoidColumn(Ctx c, int x, int z, int dx, int dz, int surfaceY) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(x, c.minY, z);
        put(c, pos, c.bedrockState);

        final int soilTop = surfaceY - 1;
        for (int y = c.minY + 1; y < soilTop; y++) {
            final String rock = c.cfg.rockAt(dx, dz, y, surfaceY);
            if (rock == null) {
                continue;
            }
            pos.set(x, y, z);
            put(c, pos, rawRock(c, rock));
        }
        pos.set(x, soilTop, z);
        put(c, pos, c.dirtState);
        pos.set(x, surfaceY, z);
        put(c, pos, c.grassState);
    }

    /** Step 1：洗矿 + 母岩家族换肤 + 可选空腔回填（逐列地下部分）。 */
    private static void washColumn(Ctx c, int x, int z, int dx, int dz, int surfaceY) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = c.minY + 1; y <= surfaceY; y++) {
            pos.set(x, y, z);
            final BlockState state = c.level.getBlockState(pos);

            if (isOreLike(c, state)) {
                final String rock = c.cfg.rockAt(dx, dz, y, surfaceY);
                if (rock != null) {
                    put(c, pos, rawRock(c, rock));
                    c.washed++;
                }
                continue;
            }

            final boolean rockFamily = isRockFamily(c, state);
            if (rockFamily) {
                reskinRockFamily(c, pos, state, dx, dz, y, surfaceY);
                continue;
            }

            if (c.cfg.fillUndergroundCaves && y < surfaceY
                    && (state.isAir() || !state.getFluidState().isEmpty())) {
                final String rock = c.cfg.rockAt(dx, dz, y, surfaceY);
                if (rock != null) {
                    put(c, pos, rawRock(c, rock));
                }
            }
        }
    }

    /** 地表以上 3 格：散落的 {@code tfc:ore/small_*} 与 OreHints 提示块直接清成空气。 */
    private static void cleanAboveGround(Ctx c, int x, int z, int surfaceY) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int top = Math.min(surfaceY + SURFACE_CLEAN_HEIGHT, c.maxY - 1);
        for (int y = surfaceY + 1; y <= top; y++) {
            pos.set(x, y, z);
            final BlockState state = c.level.getBlockState(pos);
            if (isOreLike(c, state)) {
                put(c, pos, c.airState);
                c.washed++;
            }
        }
    }

    /** 该方块是否为「矿 / 矿脉提示物 / 矿床」——见提示词 Step 1 第 1 条。 */
    private static boolean isOreLike(Ctx c, BlockState state) {
        final Block block = state.getBlock();
        final Boolean cached = c.oreLikeCache.get(block);
        if (cached != null) {
            return cached;
        }
        final boolean result = computeOreLike(state);
        c.oreLikeCache.put(block, result);
        return result;
    }

    private static boolean computeOreLike(BlockState state) {
        final ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null) {
            return false;
        }
        final String namespace = key.getNamespace();
        final String path = key.getPath();

        if ("immersivegeology".equals(namespace) || "tfcgyres_orehints".equals(namespace)) {
            return true;
        }
        // 本模组自己的方块（基点砝码等）绝不能被当成矿洗掉
        if (TfcieProspecting.MOD_ID.equals(namespace)) {
            return false;
        }
        if ("tfc".equals(namespace) && (path.startsWith("ore/") || path.startsWith("deposit/"))) {
            return true;
        }
        if (state.is(Tags.Blocks.ORES)) {
            return true;
        }
        return ReconScanner.oreIdOf(state) != null;
    }

    /** 该方块是否属于 {@code tfc:rock/<variant>/<rock>} 岩石家族。 */
    private static boolean isRockFamily(Ctx c, BlockState state) {
        final ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null || !"tfc".equals(key.getNamespace())) {
            return false;
        }
        final String path = key.getPath();
        if (!path.startsWith("rock/")) {
            return false;
        }
        return path.split("/").length == 3;
    }

    /**
     * 岩石家族一对一换肤：{@code tfc:rock/<variant>/<old>} → {@code tfc:rock/<variant>/<new>}。
     * 目标变体不存在时保持原方块；存在时按属性名拷贝兼容属性（如 {@code LooseRockBlock.COUNT}）。
     */
    private static void reskinRockFamily(Ctx c, BlockPos.MutableBlockPos pos, BlockState state,
                                         int dx, int dz, int y, int surfaceY) {
        final ResourceLocation key = Objects.requireNonNull(ForgeRegistries.BLOCKS.getKey(state.getBlock()));
        final String[] parts = key.getPath().split("/");
        final String variant = parts[1];
        final String oldRock = parts[2];
        final String newRock = c.cfg.rockAt(dx, dz, y, surfaceY);
        if (newRock == null || newRock.equals(oldRock)) {
            return;
        }
        final BlockState target = variantState(c, variant, newRock);
        if (target == null || target.isAir()) {
            return; // 该变体没有全部 20 种岩石（如 rock/magma 只有火成岩）→ 保持原样
        }
        put(c, pos, copyCompatibleProperties(state, target));
    }

    /** 按<b>属性名</b>（而非属性对象身份）拷贝两套 BlockState 间可兼容的属性值。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState copyCompatibleProperties(BlockState from, BlockState to) {
        BlockState out = to;
        for (Property<?> source : from.getProperties()) {
            Property target = null;
            for (Property<?> candidate : out.getProperties()) {
                if (candidate.getName().equals(source.getName())) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                continue;
            }
            final Comparable value = from.getValue((Property) source);
            if (target.getPossibleValues().contains(value)) {
                out = out.setValue(target, value);
            }
        }
        return out;
    }

    /** 按配置铺裸岩露头：圆域内地表最上面 2 格换成目标母岩，保证踏勘镐有合法裸露岩面。 */
    private static void placeOutcrops(Ctx c) {
        for (ArenaConfig.Outcrop outcrop : c.cfg.outcrops) {
            final int centerX = c.minX + outcrop.dx();
            final int centerZ = c.minZ + outcrop.dz();
            final int radius = outcrop.radius();
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int ox = -radius; ox <= radius; ox++) {
                for (int oz = -radius; oz <= radius; oz++) {
                    if (ox * ox + oz * oz > radius * radius) {
                        continue;
                    }
                    final int x = centerX + ox;
                    final int z = centerZ + oz;
                    if (x < c.minX || x > c.maxX || z < c.minZ || z > c.maxZ) {
                        continue;
                    }
                    final int dx = x - c.minX;
                    final int dz = z - c.minZ;
                    final int surfaceY = surfaceAt(c, x, z);
                    final String rock = outcrop.rock() != null
                            ? outcrop.rock()
                            : c.cfg.rockAt(dx, dz, surfaceY, surfaceY);
                    if (rock == null) {
                        continue;
                    }
                    final BlockState raw = rawRock(c, rock);
                    pos.set(x, surfaceY, z);
                    put(c, pos, raw);
                    if (surfaceY - 1 > c.minY) {
                        pos.set(x, surfaceY - 1, z);
                        put(c, pos, raw);
                    }
                    // 露头正上方清空，避免草/花遮挡敲击面
                    for (int y = surfaceY + 1; y <= Math.min(surfaceY + 2, c.maxY - 1); y++) {
                        pos.set(x, y, z);
                        final BlockState above = c.level.getBlockState(pos);
                        if (!above.isAir() && above.canBeReplaced()) {
                            put(c, pos, c.airState);
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Step 2：注入矿核与干扰体
    // ------------------------------------------------------------------

    private static void injectFeatures(Ctx c) {
        int index = 0;
        for (ArenaConfig.Feature feature : c.cfg.features) {
            switch (feature.type()) {
                case "vein" -> injectVein(c, feature, index++);
                case "water_pocket" -> injectWaterPocket(c, feature, index++);
                case "surface_metal" -> injectSurfaceMetal(c, feature, index++);
                default -> c.warnings.add("未知特征类型，已跳过：" + feature.type() + " (" + feature.id() + ")");
            }
        }
    }

    /** 椭球 / 走向条带矿脉。 */
    private static void injectVein(Ctx c, ArenaConfig.Feature f, int salt) {
        final String ore = f.ore();
        if (ore == null) {
            c.warnings.add("vein 缺少 ore，已跳过：" + f.id());
            return;
        }
        final int centerX = c.minX + f.dx();
        final int centerZ = c.minZ + f.dz();
        final int centerSurface = surfaceAt(c, centerX, centerZ);
        final int centerY = centerSurface - f.depthBelowSurface();
        final boolean graded = ArenaOres.isGraded(ore);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        long placed = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (int oy = -f.ry(); oy <= f.ry(); oy++) {
            for (int ox = -f.rx(); ox <= f.rx(); ox++) {
                for (int oz = -f.rz(); oz <= f.rz(); oz++) {
                    final double r = ArenaGeometry.normalizedRadius(
                            ox, oy, oz, f.rx(), f.ry(), f.rz(), f.strikeDeg());
                    if (r > 1.0D) {
                        continue;
                    }
                    final String grade = graded
                            ? ArenaGeometry.gradeFor(r, ArenaGeometry.deterministic01(ox, oy, oz, salt))
                            : null;
                    if (graded && grade == null) {
                        continue; // 外缘 30% 留空，避免矿体外壳过于规则
                    }

                    final int x = centerX + ox;
                    final int y = centerY + oy;
                    final int z = centerZ + oz;
                    if (y <= c.minY + 1 || y >= c.maxY) {
                        continue;
                    }
                    final int dx = x - c.minX;
                    final int dz = z - c.minZ;
                    final String rock = c.cfg.rockAt(dx, dz, y, surfaceAt(c, x, z));
                    if (rock == null) {
                        continue;
                    }
                    BlockState state = stateOrNull(ArenaOres.oreBlockId(ore, rock, grade));
                    if (state == null) {
                        // 兜底：显式 host_rock（TFC 1.20.x 为 20 岩石 × 全矿种都注册了方块，正常不会走到）
                        final String host = f.hostRock();
                        if (host != null) {
                            state = stateOrNull(ArenaOres.oreBlockId(ore, host, grade));
                            c.warnings.add("矿种 " + ore + " 在岩石 " + rock + " 下无对应方块，回退 host_rock=" + host);
                        }
                    }
                    if (state == null) {
                        c.warnings.add("无法解析矿石方块：" + ore + " / " + rock + " / grade=" + grade);
                        continue;
                    }
                    pos.set(x, y, z);
                    put(c, pos, state);
                    placed++;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }

        if (placed == 0) {
            c.warnings.add("矿脉未放置任何方块（越界或深度不合理）：" + f.id());
            minX = maxX = centerX;
            minY = maxY = centerY;
            minZ = maxZ = centerZ;
        }
        final FeatureTruth truth = featureTruth(f, centerX, centerY, centerZ,
                minX, maxX, minY, maxY, minZ, maxZ, placed);
        if (f.isTarget()) {
            c.placedTarget += placed;
            c.targets.add(truth);
        } else {
            c.placedDistractor += placed;
            c.distractors.add(truth);
        }
    }

    /** 封闭地下水囊 / 盐水囊：流体椭球 + 1 格岩石密封壳（防止漏水/塌方）。 */
    private static void injectWaterPocket(Ctx c, ArenaConfig.Feature f, int salt) {
        final int centerX = c.minX + f.dx();
        final int centerZ = c.minZ + f.dz();
        final int centerSurface = surfaceAt(c, centerX, centerZ);
        final int centerY = centerSurface - f.depthBelowSurface();
        final BlockState fluid = f.salt()
                ? requireState("tfc:fluid/salt_water")
                : Blocks.WATER.defaultBlockState();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        final Set<Long> inside = new HashSet<>();
        final List<int[]> insideCells = new ArrayList<>();
        for (int oy = -f.ry(); oy <= f.ry(); oy++) {
            for (int ox = -f.rx(); ox <= f.rx(); ox++) {
                for (int oz = -f.rz(); oz <= f.rz(); oz++) {
                    final double r = ArenaGeometry.normalizedRadius(
                            ox, oy, oz, f.rx(), f.ry(), f.rz(), f.strikeDeg());
                    if (r > 1.0D) {
                        continue;
                    }
                    final int x = centerX + ox;
                    final int y = centerY + oy;
                    final int z = centerZ + oz;
                    if (y <= c.minY + 1 || y >= c.maxY) {
                        continue;
                    }
                    pos.set(x, y, z);
                    put(c, pos, fluid);
                    final long packed = BlockPos.asLong(x, y, z);
                    inside.add(packed);
                    insideCells.add(new int[]{x, y, z});
                }
            }
        }

        // 1 格密封壳：所有与流体体素六邻接、但本身不在流体内的格子填成该列目标母岩
        long placed = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        final int[][] neighbours = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        final Set<Long> shell = new HashSet<>();
        for (int[] cell : insideCells) {
            minX = Math.min(minX, cell[0]);
            maxX = Math.max(maxX, cell[0]);
            minY = Math.min(minY, cell[1]);
            maxY = Math.max(maxY, cell[1]);
            minZ = Math.min(minZ, cell[2]);
            maxZ = Math.max(maxZ, cell[2]);
            for (int[] n : neighbours) {
                final int x = cell[0] + n[0];
                final int y = cell[1] + n[1];
                final int z = cell[2] + n[2];
                if (y <= c.minY || y >= c.maxY) {
                    continue;
                }
                final long packed = BlockPos.asLong(x, y, z);
                if (!inside.contains(packed)) {
                    shell.add(packed);
                }
            }
        }
        for (long packed : shell) {
            final int x = BlockPos.getX(packed);
            final int y = BlockPos.getY(packed);
            final int z = BlockPos.getZ(packed);
            final int dx = x - c.minX;
            final int dz = z - c.minZ;
            final String rock = c.cfg.rockAt(dx, dz, y, surfaceAt(c, x, z));
            if (rock == null) {
                continue;
            }
            pos.set(x, y, z);
            put(c, pos, rawRock(c, rock));
        }

        if (insideCells.isEmpty()) {
            c.warnings.add("水囊未放置任何方块（越界或深度不合理）：" + f.id());
            minX = maxX = centerX;
            minY = maxY = centerY;
            minZ = maxZ = centerZ;
        }
        final FeatureTruth truth = featureTruth(f, centerX, centerY, centerZ,
                minX, maxX, minY, maxY, minZ, maxZ, insideCells.size() + (long) shell.size());
        if (f.isTarget()) {
            c.placedTarget += truth.placedBlocks();
            c.targets.add(truth);
        } else {
            c.placedDistractor += truth.placedBlocks();
            c.distractors.add(truth);
        }
    }

    /** 地表人文电磁/磁干扰：铁轨 + 铁栏杆小阵列。 */
    private static void injectSurfaceMetal(Ctx c, ArenaConfig.Feature f, int salt) {
        final int centerX = c.minX + f.dx();
        final int centerZ = c.minZ + f.dz();
        final BlockState rail = Blocks.RAIL.defaultBlockState();
        final BlockState bars = Blocks.IRON_BARS.defaultBlockState();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        long placed = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int ox = -2; ox <= 2; ox++) {
            final int x = centerX + ox;
            final int z = centerZ;
            if (x < c.minX || x > c.maxX) {
                continue;
            }
            final int y = surfaceAt(c, x, z) + 1;
            if (y >= c.maxY) {
                continue;
            }
            pos.set(x, y, z);
            put(c, pos, Math.abs(ox) == 2 ? bars : rail);
            placed++;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        if (placed == 0) {
            c.warnings.add("地表金属干扰未放置任何方块（越界）：" + f.id());
            minX = maxX = centerX;
            minY = maxY = surfaceAt(c, centerX, centerZ) + 1;
            minZ = maxZ = centerZ;
        }
        final FeatureTruth truth = featureTruth(f, centerX, surfaceAt(c, centerX, centerZ) + 1, centerZ,
                minX, maxX, minY, maxY, minZ, maxZ, placed);
        if (f.isTarget()) {
            c.placedTarget += placed;
            c.targets.add(truth);
        } else {
            c.placedDistractor += placed;
            c.distractors.add(truth);
        }
    }

    private static FeatureTruth featureTruth(ArenaConfig.Feature f, int centerX, int centerY, int centerZ,
                                             int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                                             long placed) {
        return new FeatureTruth(f.id(), f.type(), f.ore(), f.salt(),
                centerX, centerY, centerZ, f.depthBelowSurface(),
                f.rx(), f.rz(), f.strikeDeg(),
                minX, maxX, minY, maxY, minZ, maxZ, placed);
    }

    // ------------------------------------------------------------------
    // Step 3：屏障封边 + 基岩封底
    // ------------------------------------------------------------------

    private static void buildEnvelope(Ctx c) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int ringMinX = c.minX - 1;
        final int ringMaxX = c.maxX + 1;
        final int ringMinZ = c.minZ - 1;
        final int ringMaxZ = c.maxZ + 1;

        // 南北两面（含四角）
        for (int x = ringMinX; x <= ringMaxX; x++) {
            for (int y = c.minY; y < c.maxY; y++) {
                pos.set(x, y, ringMinZ);
                put(c, pos, c.barrierState);
                pos.set(x, y, ringMaxZ);
                put(c, pos, c.barrierState);
            }
        }
        // 东西两面（去掉已覆盖的角）
        for (int z = c.minZ; z <= c.maxZ; z++) {
            for (int y = c.minY; y < c.maxY; y++) {
                pos.set(ringMinX, y, z);
                put(c, pos, c.barrierState);
                pos.set(ringMaxX, y, z);
                put(c, pos, c.barrierState);
            }
        }
        // 内部底面基岩
        for (int x = c.minX; x <= c.maxX; x++) {
            for (int z = c.minZ; z <= c.maxZ; z++) {
                pos.set(x, c.minY, z);
                put(c, pos, c.bedrockState);
            }
        }
    }

    // ------------------------------------------------------------------
    // Step 4：真值导出
    // ------------------------------------------------------------------

    private static JsonObject buildGroundTruth(Ctx c, int spawnX, int spawnY, int spawnZ) {
        final JsonObject root = new JsonObject();
        root.addProperty("arena_name", c.cfg.name);
        root.addProperty("built_at_epoch_ms", System.currentTimeMillis());
        root.add("bounds", bounds(c.minX, c.maxX, c.minZ, c.maxZ));
        root.add("recommended_spawn", pos(spawnX, spawnY, spawnZ));

        final JsonArray zones = new JsonArray();
        for (ArenaConfig.Zone zone : c.cfg.zones) {
            final JsonObject z = new JsonObject();
            z.addProperty("x_min", zone.xMin());
            z.addProperty("x_max", zone.xMax());
            z.addProperty("z_min", zone.zMin());
            z.addProperty("z_max", zone.zMax());
            z.addProperty("rock", zone.rock());
            if (zone.bottomRock() != null) {
                z.addProperty("bottom_rock", zone.bottomRock());
                z.addProperty("boundary_depth", zone.boundaryDepth());
            } else {
                z.add("bottom_rock", JsonNull.INSTANCE);
            }
            zones.add(z);
        }
        root.add("rock_zones", zones);

        final JsonArray targets = new JsonArray();
        for (FeatureTruth t : c.targets) {
            targets.add(truthToJson(t));
        }
        root.add("targets", targets);

        final JsonArray distractors = new JsonArray();
        for (FeatureTruth t : c.distractors) {
            distractors.add(truthToJson(t));
        }
        root.add("distractors", distractors);

        final JsonObject stats = new JsonObject();
        stats.addProperty("washed_old_ore_blocks", c.washed);
        stats.addProperty("placed_target_ore_blocks", c.placedTarget);
        stats.addProperty("placed_distractor_blocks", c.placedDistractor);
        root.add("stats", stats);

        if (!c.warnings.isEmpty()) {
            final JsonArray warnings = new JsonArray();
            c.warnings.forEach(warnings::add);
            root.add("warnings", warnings);
        }
        return root;
    }

    private static JsonObject truthToJson(FeatureTruth t) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", t.id());
        o.addProperty("type", t.type());
        if (t.ore() != null) {
            o.addProperty("ore", t.ore());
        } else {
            o.add("ore", JsonNull.INSTANCE);
        }
        if (t.salt()) {
            o.addProperty("salt", true);
        }
        o.add("center", pos(t.centerX(), t.centerY(), t.centerZ()));
        o.addProperty("depth_below_surface", t.depthBelowSurface());
        o.add("aabb", aabb(t.minX(), t.maxX(), t.minY(), t.maxY(), t.minZ(), t.maxZ()));
        o.addProperty("horizontal_radius_x", t.horizontalRadiusX());
        o.addProperty("horizontal_radius_z", t.horizontalRadiusZ());
        o.addProperty("strike_deg", t.strikeDeg());
        o.addProperty("placed_blocks", t.placedBlocks());
        return o;
    }

    private static JsonObject bounds(int minX, int maxX, int minZ, int maxZ) {
        final JsonObject o = new JsonObject();
        o.addProperty("min_x", minX);
        o.addProperty("max_x", maxX);
        o.addProperty("min_z", minZ);
        o.addProperty("max_z", maxZ);
        return o;
    }

    private static JsonObject aabb(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        final JsonObject o = new JsonObject();
        o.addProperty("min_x", minX);
        o.addProperty("max_x", maxX);
        o.addProperty("min_y", minY);
        o.addProperty("max_y", maxY);
        o.addProperty("min_z", minZ);
        o.addProperty("max_z", maxZ);
        return o;
    }

    private static JsonObject pos(int x, int y, int z) {
        final JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /**
     * 统一写方块入口：只在方块<b>确实不同</b>时调用
     * {@code setBlock(pos, state, Block.UPDATE_CLIENTS)}。
     *
     * <p>相同方块直接跳过 —— {@code BlockState} 在方块状态容器里是共享实例，引用比较既快又准，
     * 这让「改一个参数重刷同一张图」只写差异部分，而不是把几百万格重写一遍。
     */
    private static boolean put(Ctx c, BlockPos.MutableBlockPos pos, BlockState state) {
        if (state == null) {
            return false;
        }
        if (c.level.getBlockState(pos) == state) {
            return false;
        }
        return c.level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    private static int surfaceAt(Ctx c, int x, int z) {
        final int dx = x - c.minX;
        final int dz = z - c.minZ;
        if (dx < 0 || dx >= c.cfg.sizeX || dz < 0 || dz >= c.cfg.sizeZ) {
            final int found = scanGroundTop(c, x, z);
            return found <= c.minY ? c.cfg.baseSurfaceY : found;
        }
        final int index = c.index(dx, dz);
        int cached = c.surface[index];
        if (cached == Integer.MIN_VALUE) {
            final int found = scanGroundTop(c, x, z);
            cached = found <= c.minY ? ArenaGeometry.surfaceY(c.cfg.baseSurfaceY, dx, dz) : found;
            c.surface[index] = cached;
        }
        return cached;
    }

    private static int clampY(Ctx c, int y) {
        return Math.max(c.minY + 1, Math.min(c.maxY - 2, y));
    }

    private static BlockState rawRock(Ctx c, String rock) {
        return c.rawRockCache.computeIfAbsent(rock, r -> requireState("tfc:rock/raw/" + r));
    }

    /** 变体存在性缓存；不存在的变体存 {@code AIR} 作哨兵。 */
    private static BlockState variantState(Ctx c, String variant, String rock) {
        return c.variantCache.computeIfAbsent(variant + "|" + rock, key -> {
            final BlockState state = stateOrNull("tfc:rock/" + variant + "/" + rock);
            return state == null ? Blocks.AIR.defaultBlockState() : state;
        });
    }

    @Nullable
    private static BlockState stateOrNull(String id) {
        final ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return null;
        }
        final Block block = ForgeRegistries.BLOCKS.getValue(key);
        if (block == null || block == Blocks.AIR) {
            return null;
        }
        return block.defaultBlockState();
    }

    private static BlockState requireState(String id) {
        final BlockState state = stateOrNull(id);
        if (state == null) {
            throw new IllegalArgumentException("注册表中不存在方块：" + id);
        }
        return state;
    }

    /** 构建前把配置里用到的方块 id 全部解析一遍，避免造到一半才发现拼错岩石名。 */
    private static List<String> validateRuntime(Ctx c) {
        final List<String> problems = new ArrayList<>();
        for (ArenaConfig.Zone zone : c.cfg.zones) {
            if (stateOrNull("tfc:rock/raw/" + zone.rock()) == null) {
                problems.add("未知岩石：" + zone.rock());
            }
            if (zone.bottomRock() != null && stateOrNull("tfc:rock/raw/" + zone.bottomRock()) == null) {
                problems.add("未知下层岩石：" + zone.bottomRock());
            }
        }
        for (ArenaConfig.Feature feature : c.cfg.features) {
            if ("vein".equals(feature.type()) && feature.ore() != null && !ArenaOres.isGraded(feature.ore())
                    && !com.tfcie.prospecting.api.OreTaxonomy.contains(feature.ore())) {
                problems.add("未知矿种：" + feature.ore());
            }
        }
        if (stateOrNull(c.cfg.dirtBlock) == null) {
            problems.add("未知泥土方块：" + c.cfg.dirtBlock);
        }
        if (stateOrNull(c.cfg.grassBlock) == null) {
            problems.add("未知草皮方块：" + c.cfg.grassBlock);
        }
        return problems;
    }

    private ArenaBuilder() {}
}
