package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.OreTaxonomy;
import net.dries007.tfc.common.blocks.TFCBlocks;
import net.dries007.tfc.common.blocks.rock.Ore;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 矿石方块的运行时索引与浅带体积扫描。
 *
 * <h2>为什么反查 TFC 注册表而不解析方块名</h2>
 * TFC 的矿石方块命名随矿种是否 graded 而不同：
 * <ul>
 *   <li>非 graded：{@code tfc:ore/<ore>/<rock>}（如 {@code ore/diamond/gabbro}）；</li>
 *   <li>graded：{@code tfc:ore/<grade>_<ore>/<rock>}（如 {@code ore/rich_native_copper/granite}）；</li>
 *   <li>地表矿屑：{@code tfc:ore/small_<ore>}。</li>
 * </ul>
 * 直接读取 TFC 自己的方块映射（{@code TFCBlocks.ORES / GRADED_ORES / SMALL_ORES}）
 * 构建一次 {@code Block → 矿种 id} 索引：既覆盖 graded 的贫/中/富三态（归并为同一矿种），
 * 又不会因命名细节变化而失准。
 *
 * <h2>为什么还要一层「按注册名推断」（回落的第二级）</h2>
 * TFC 索引只认识 TFC 自己注册的方块。整合包里其它模组的矿石方块（如 Immersive Geology 的
 * {@code immersivegeology:minecraft_rich_ore_block_magnetite_stone}）不在 TFC 注册表中，
 * 于是 {@link #oreIdOf} 返回 {@code null}，<b>所有</b>物探工具（磁力 / 感应 / 密度 / 回声 /
 * 钻探）都看不见它们 —— 报告 13 §13.2 的「IE 磁铁矿完全不被识别」就是这个缺口。
 * 因此 TFC 索引未命中时，再按方块注册名里的 token 匹配已知矿种 id（见
 * {@link #inferOreIdFromName(String)}）；结果按方块缓存，TFC 索引命中时优先、不会被覆盖。
 */
public final class ReconScanner {

    /**
     * 一次浅带扫描的结果。
     *
     * @param oreCounts   原位矿体：矿种 id → 命中方块数
     * @param looseCounts 地表矿屑：矿种 id → 命中方块数（异地漂砾的载体）
     */
    public record ScanResult(Map<String, Integer> oreCounts, Map<String, Integer> looseCounts) {

        public boolean hasOre() {
            return !oreCounts.isEmpty();
        }

        public int totalOreBlocks() {
            return oreCounts.values().stream().mapToInt(Integer::intValue).sum();
        }

        public int totalLooseBlocks() {
            return looseCounts.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** 命中数最多的原位矿种 id。 */
        public String dominantOreId() {
            return dominant(oreCounts);
        }

        /** 命中数最多的地表矿屑矿种 id。 */
        public String dominantLooseOreId() {
            return dominant(looseCounts);
        }

        /** 只命中了地表矿屑、没有原位矿体 —— 异地漂砾的信号。 */
        public boolean looseOnly() {
            return oreCounts.isEmpty() && !looseCounts.isEmpty();
        }

        private static String dominant(Map<String, Integer> counts) {
            String best = null;
            int bestCount = 0;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() > bestCount || (e.getValue() == bestCount && best != null
                        && e.getKey().compareTo(best) < 0)) {
                    best = e.getKey();
                    bestCount = e.getValue();
                }
            }
            return best;
        }
    }

    /** Block → 矿种 id（原位矿体）。 */
    private static volatile Map<Block, String> oreIndex;
    /** Block → 矿种 id（地表矿屑）。 */
    private static volatile Map<Block, String> looseIndex;

    /**
     * 非 TFC 矿方块的名字推断缓存：Block → 矿种 id。
     *
     * <p>用空串 {@link #NOT_ORE} 表示「已判定不是矿种」——{@link ConcurrentHashMap} 不能存
     * {@code null}，而一次扫描里绝大多数方块都是普通岩石；把「无」也缓存下来，
     * 才能让每个方块类型只做一次名字匹配。
     */
    private static final Map<Block, String> inferredIndex = new ConcurrentHashMap<>();

    /** 名字推断缓存中表示「不是矿种」的哨兵值。 */
    private static final String NOT_ORE = "";

    private ReconScanner() {}

    private static void ensureIndex() {
        if (oreIndex != null && looseIndex != null) {
            return;
        }
        synchronized (ReconScanner.class) {
            if (oreIndex != null && looseIndex != null) {
                return;
            }
            Map<Block, String> ores = new HashMap<>();
            Map<Block, String> loose = new HashMap<>();

            TFCBlocks.ORES.forEach((rock, byOre) ->
                    byOre.forEach((ore, ro) -> ores.put(ro.get(), oreId(ore))));
            TFCBlocks.GRADED_ORES.forEach((rock, byOre) ->
                    byOre.forEach((ore, byGrade) ->
                            byGrade.forEach((grade, ro) -> ores.put(ro.get(), oreId(ore)))));
            TFCBlocks.SMALL_ORES.forEach((ore, ro) -> loose.put(ro.get(), oreId(ore)));

            oreIndex = Collections.unmodifiableMap(ores);
            looseIndex = Collections.unmodifiableMap(loose);
        }
    }

    private static String oreId(Ore ore) {
        return ore.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 该方块对应的矿种 id；非矿石方块返回 {@code null}。供各物探工具共用。
     *
     * <p>两级判定，顺序不可交换：
     * <ol>
     *   <li><b>TFC 注册表索引优先</b>：{@code TFCBlocks.ORES / GRADED_ORES} 的映射最权威，
     *       graded 三态也归并成同一矿种；</li>
     *   <li><b>按注册名推断回落</b>：TFC 索引未命中的方块（整合包里其它模组的矿石）按名字
     *       里的矿种 token 认定，结果按方块缓存，见 {@link #inferOreIdFromName(String)}。</li>
     * </ol>
     */
    @Nullable
    public static String oreIdOf(BlockState state) {
        ensureIndex();
        final Block block = state.getBlock();
        final String tfc = oreIndex.get(block);
        if (tfc != null) {
            return tfc;
        }
        final String inferred = inferredIndex.computeIfAbsent(block, ReconScanner::inferOreIdByRegistryName);
        return NOT_ORE.equals(inferred) ? null : inferred;
    }

    /** 注册名 → 矿种 id；无注册名或推断不出时返回 {@link #NOT_ORE}（哨兵，供缓存）。 */
    private static String inferOreIdByRegistryName(Block block) {
        final ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        if (key == null) {
            return NOT_ORE;
        }
        final String id = inferOreIdFromName(key.toString());
        return id == null ? NOT_ORE : id;
    }

    /**
     * 按方块注册名推断矿种 id —— <b>纯逻辑</b>：不读游戏状态、不碰注册表，可直接单测。
     *
     * <p>把 {@code namespace:path} 按非字母数字字符切成 token，再在 token 序列里查找
     * 已知矿种 id（{@link OreTaxonomy#contains(String)}）。规则：
     * <ul>
     *   <li><b>最长匹配优先</b>：先尝试跨多个 token 的 id（{@code native_copper}、
     *       {@code bituminous_coal}、{@code lapis_lazuli}），全部长度试完才逐一比对
     *       单 token；</li>
     *   <li>命名空间一并切分，因此
     *       {@code immersivegeology:minecraft_rich_ore_block_magnetite_stone} 的 token 为
     *       [immersivegeology, minecraft, rich, ore, block, magnetite, stone]，
     *       命中 {@code magnetite}；</li>
     *   <li>路径里没有任何已知矿种 token 时返回 {@code null} —— 普通岩石（花岗岩、深板岩、
     *       石灰岩…）不误判。</li>
     * </ul>
     *
     * <p>可能的误判只有「非矿方块名字里恰好含矿种 token」（如原版 {@code diamond_block}）：
     * 这类方块在 TFC 整合包里不参与自然矿脉生成，出现频度也远低于真正的矿石方块，
     * 作为回落层的代价可以接受。
     *
     * @param blockName 注册名全串（{@code namespace:path}）
     * @return 命中的矿种 id；不命中返回 {@code null}
     */
    @Nullable
    static String inferOreIdFromName(@Nullable String blockName) {
        if (blockName == null || blockName.isEmpty()) {
            return null;
        }
        final String[] tokens = blockName.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");

        // 最长匹配：先看能否跨多个 token 命中（native_copper 这类带下划线的 id）
        final int maxSpan = maxOreIdTokenCount();
        for (int span = maxSpan; span >= 2; span--) {
            for (int start = 0; start + span <= tokens.length; start++) {
                final String candidate = joinTokens(tokens, start, span);
                if (OreTaxonomy.contains(candidate)) {
                    return candidate;
                }
            }
        }
        // 再退到单 token
        for (String token : tokens) {
            if (OreTaxonomy.contains(token)) {
                return token;
            }
        }
        return null;
    }

    /** 已知矿种 id 的最大 token 数（如 {@code native_copper} 为 2）。 */
    private static int maxOreIdTokenCount() {
        int max = 1;
        for (String id : OreTaxonomy.ids()) {
            int span = 1;
            for (int i = 0; i < id.length(); i++) {
                if (id.charAt(i) == '_') {
                    span++;
                }
            }
            max = Math.max(max, span);
        }
        return max;
    }

    /** 把 {@code tokens[start..start+span)} 用下划线拼回一个候选 id。 */
    private static String joinTokens(String[] tokens, int start, int span) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < span; i++) {
            if (i > 0) {
                out.append('_');
            }
            out.append(tokens[start + i]);
        }
        return out.toString();
    }

    /**
     * 扫描自原点起、水平<b>半径</b> {@code radius}、垂向<b>向下</b> {@code depth} 的圆柱体积。
     *
     * <p>水平取圆形（真半径），垂向为单向向下的浅带 —— 设计文档 v1.3：踏勘镐的探测原点为
     * 玩家脚下，「对裸露岩面使用」仅为触发条件；方向由旧镐的上下对称改为单向向下。
     */
    public static ScanResult scan(Level level, BlockPos origin, int radius, int depth) {
        ensureIndex();

        final Map<String, Integer> ores = new HashMap<>();
        final Map<String, Integer> loose = new HashMap<>();
        final int r2 = radius * radius;
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final int ox = origin.getX();
        final int oy = origin.getY();
        final int oz = origin.getZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > r2) {
                    continue;
                }
                cursor.set(ox + dx, oy, oz + dz);
                if (!level.isLoaded(cursor)) {
                    continue;
                }
                for (int dy = 0; dy < depth; dy++) {
                    cursor.setY(oy - dy);
                    final Block block = level.getBlockState(cursor).getBlock();
                    final String ore = oreIndex.get(block);
                    if (ore != null) {
                        ores.merge(ore, 1, Integer::sum);
                        continue;
                    }
                    final String looseOre = looseIndex.get(block);
                    if (looseOre != null) {
                        loose.merge(looseOre, 1, Integer::sum);
                    }
                }
            }
        }
        return new ScanResult(ores, loose);
    }

    /**
     * 该位置的方块是否为「可凿击取样的裸露岩面」——踏勘镐的使用前置条件。
     * 判据：可被镐开采，且不是松散覆盖层（土/砂/砾石）。
     */
    public static boolean isExposedRock(Level level, BlockPos pos) {
        final BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.MINEABLE_WITH_PICKAXE) && !isLooseOverburden(level, pos);
    }

    /**
     * 该位置是否为松散覆盖层（土 / 砂 / 砾石类），用于「覆盖层污染」判据。
     * 采用标签 + 注册名路径前缀判定，不依赖具体模组的方块集合。
     */
    public static boolean isLooseOverburden(Level level, BlockPos pos) {
        final BlockState state = level.getBlockState(pos);
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)) {
            return true;
        }
        final ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null) {
            return false;
        }
        final String path = key.getPath();
        return path.startsWith("dirt/") || path.startsWith("sand/") || path.startsWith("gravel/")
                || path.endsWith("_gravel") || path.equals("clay");
    }

    /**
     * 清空「按名字推断」的缓存 —— 物性表重载后已知矿种集合可能变化，
     * 已缓存的推断结果必须作废。由 {@link OrePropertyReloadListener} 在重载后调用。
     */
    static void invalidateOreNameCache() {
        inferredIndex.clear();
    }

    /** 仅用于测试/调试：清除缓存的索引（含名字推断缓存）。 */
    static void invalidateIndex() {
        oreIndex = null;
        looseIndex = null;
        invalidateOreNameCache();
    }
}
