package com.tfcie.prospecting.common.arena;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 矿种 → TFC 方块 id 的映射（与 1.20.x 注册名逐字对齐）。
 *
 * <h2>TFC 的两种矿石注册形态（实测 {@code net.dries007.tfc.common.blocks.rock.Ore}）</h2>
 * <ul>
 *   <li><b>有品位金属矿（12 种，{@link #GRADED}）</b>：
 *       {@code tfc:ore/<grade>_<ore>/<rock>}，{@code grade ∈ {rich, normal, poor}}。
 *       枚举里 {@code isGraded() == true} 的正是这 12 种；
 *       对应数据包 {@code assets/tfc/blockstates/ore/} 下每个 rock 都有三档方块。</li>
 *   <li><b>无品位矿（煤 / 石墨 / 盐 / 石膏 / 宝石等 20 种）</b>：
 *       {@code tfc:ore/<ore>/<rock>}，<b>不带</b> {@code normal_} 前缀。</li>
 * </ul>
 *
 * <p>本类<b>不引用任何 Minecraft 类型</b>（{@code Ore} 是 MC 侧枚举，纯 JUnit 里不可用），
 * 因此 12 种硬编码集合是从 TFC 1.20.x 的 {@code Ore} 枚举逐一核对而来；
 * 纯 JUnit 侧再用 {@link com.tfcie.prospecting.api.OreTaxonomy#contains(String)}
 * 交叉校验这些 id 确实是已收录矿种，防止硬编码漂移。
 */
public final class ArenaOres {

    /** 品位名：富矿。 */
    public static final String GRADE_RICH = "rich";
    /** 品位名：普通矿。 */
    public static final String GRADE_NORMAL = "normal";
    /** 品位名：贫矿。 */
    public static final String GRADE_POOR = "poor";

    /** 三种合法品位。 */
    public static final Set<String> GRADES = orderedSet(GRADE_RICH, GRADE_NORMAL, GRADE_POOR);

    /**
     * TFC 1.20.x 带品位的 12 种金属矿 id。
     *
     * <p>顺序照 {@code Ore} 枚举：铜、金、赤铁、银、锡、辉铋、硅镁镍、孔雀石、磁铁、褐铁、闪锌、黝铜。
     */
    public static final Set<String> GRADED = orderedSet(
            "native_copper", "native_gold", "hematite", "native_silver",
            "cassiterite", "bismuthinite", "garnierite", "malachite",
            "magnetite", "limonite", "sphalerite", "tetrahedrite");

    /** 方块 id 前缀 {@code tfc:ore/}。 */
    private static final String ORE_PREFIX = "tfc:ore/";

    private ArenaOres() {}

    /** 该矿种是否带品位（12 种金属矿之一）。{@code null} / 未知 id 一律 {@code false}。 */
    public static boolean isGraded(@Nullable String oreId) {
        return oreId != null && GRADED.contains(oreId);
    }

    /**
     * 拼出 TFC 矿石方块 id。
     *
     * <ul>
     *   <li>graded：{@code tfc:ore/<grade>_<ore>/<rock>}；{@code grade} 为 {@code null} / 空白时用 {@code normal}；</li>
     *   <li>非 graded：{@code tfc:ore/<ore>/<rock>}（{@code grade} 被忽略，因为根本没有品位方块）。</li>
     * </ul>
     *
     * <p>例：{@code oreBlockId("malachite", "marble", "rich") = "tfc:ore/rich_malachite/marble"}；
     * {@code oreBlockId("lignite", "shale", "normal") = "tfc:ore/lignite/shale"}。
     *
     * @param oreId 矿种 id（如 {@code malachite}），取自 {@link com.tfcie.prospecting.api.OreTaxonomy}
     * @param rock  母岩名（20 种 TFC 岩石之一）
     * @param grade 品位名；非 graded 矿种忽略此参数
     * @throws IllegalArgumentException {@code oreId} 为空、{@code rock} 为空，或 graded 矿种传了未知品位
     */
    public static String oreBlockId(String oreId, String rock, @Nullable String grade) {
        if (oreId == null || oreId.isBlank()) {
            throw new IllegalArgumentException("oreId 不能为空：只有 vein 才有矿种，其他特征不应调用本方法");
        }
        if (rock == null || rock.isBlank()) {
            throw new IllegalArgumentException("rock 不能为空（拼方块 id 需要母岩名，如 granite）");
        }
        if (!isGraded(oreId)) {
            // 非品位矿：tfc:ore/lignite/shale；grade 参数对它没有意义
            return ORE_PREFIX + oreId + "/" + rock;
        }
        final String normalizedGrade = (grade == null || grade.isBlank())
                ? GRADE_NORMAL
                : grade.toLowerCase(Locale.ROOT);
        if (!GRADES.contains(normalizedGrade)) {
            throw new IllegalArgumentException("未知品位: " + grade + "（只允许 rich / normal / poor）");
        }
        return ORE_PREFIX + normalizedGrade + "_" + oreId + "/" + rock;
    }

    /** 构造保持声明顺序的不可变集合（便于测试与文档对照）。 */
    private static Set<String> orderedSet(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
    }
}
