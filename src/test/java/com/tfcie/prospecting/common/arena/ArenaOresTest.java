package com.tfcie.prospecting.common.arena;

import com.tfcie.prospecting.api.OreTaxonomy;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link ArenaOres} 的纯 JUnit 单测：12 种带品位矿的硬编码集合、两种方块 id 拼法，
 * 以及与真实 TFC 1.20.x 注册名一致的边界行为。
 *
 * <h2>为什么这组断言值得写死</h2>
 * TFC 1.20.x 的 {@code Ore} 枚举里只有 12 种矿 {@code isGraded() == true}；
 * 其余（煤、石墨、盐、宝石等）注册名<b>没有</b> {@code normal_} 前缀。拼错前缀会得到
 * 一个不存在的方块 id，实机表现为「矿核整块静默不生成」，而单测里看不出来——
 * 所以这里既锁 id 形态，也用 {@link OreTaxonomy} 交叉校验矿种确实存在。
 */
public class ArenaOresTest {

    /** TFC 1.20.x 中 <b>不带</b> 品位前缀的 20 种矿（与 12 种 graded 合计 32 = Ore 枚举总数）。 */
    private static final List<String> NON_GRADED = List.of(
            "bituminous_coal", "lignite", "gypsum", "graphite", "sulfur", "cinnabar",
            "cryolite", "saltpeter", "sylvite", "borax", "halite",
            "amethyst", "diamond", "emerald", "lapis_lazuli",
            "opal", "pyrite", "ruby", "sapphire", "topaz");

    // ---------------- 12 种 graded 判定 ----------------

    @Test
    public void gradedSetIsExactlyTheTwelveTfcGradedOres() {
        assertEquals(12, ArenaOres.GRADED.size());
        assertEquals(Set.of("native_copper", "native_gold", "hematite", "native_silver",
                        "cassiterite", "bismuthinite", "garnierite", "malachite",
                        "magnetite", "limonite", "sphalerite", "tetrahedrite"),
                ArenaOres.GRADED);
    }

    @Test
    public void isGradedRecognizesAllTwelve() {
        for (String ore : ArenaOres.GRADED) {
            assertTrue(ore + " 应判定为 graded", ArenaOres.isGraded(ore));
        }
    }

    @Test
    public void isGradedRejectsEverythingElse() {
        for (String ore : NON_GRADED) {
            assertFalse(ore + " 不是 graded（注册名无品位前缀）", ArenaOres.isGraded(ore));
        }
        assertFalse(ArenaOres.isGraded(null));
        assertFalse(ArenaOres.isGraded(""));
        assertFalse("判定区分大小写（TFC id 全小写）", ArenaOres.isGraded("MALACHITE"));
        assertFalse(ArenaOres.isGraded("unobtainium"));
    }

    @Test
    public void gradedAndNonGradedTogetherCoverAllTfcOres() {
        final Set<String> union = new LinkedHashSet<>(ArenaOres.GRADED);
        union.addAll(NON_GRADED);
        assertEquals("12 graded + 20 非 graded = Ore 枚举 32 项", 32, union.size());
        for (String ore : union) {
            assertTrue("TFC 1.20.x 应收录矿种 " + ore, OreTaxonomy.contains(ore));
        }
    }

    // ---------------- 方块 id 拼法 ----------------

    @Test
    public void gradedBlockIdUsesGradePrefix() {
        assertEquals("tfc:ore/rich_malachite/marble",
                ArenaOres.oreBlockId("malachite", "marble", "rich"));
        assertEquals("tfc:ore/normal_malachite/marble",
                ArenaOres.oreBlockId("malachite", "marble", "normal"));
        assertEquals("tfc:ore/poor_malachite/marble",
                ArenaOres.oreBlockId("malachite", "marble", "poor"));

        // 逐一对 12 种 graded 验证三档都能拼出形如 tfc:ore/<grade>_<ore>/<rock> 的 id
        for (String ore : ArenaOres.GRADED) {
            for (String grade : ArenaOres.GRADES) {
                assertEquals("tfc:ore/" + grade + "_" + ore + "/granite",
                        ArenaOres.oreBlockId(ore, "granite", grade));
            }
        }
    }

    @Test
    public void nullOrBlankGradeFallsBackToNormal() {
        assertEquals("tfc:ore/normal_malachite/marble",
                ArenaOres.oreBlockId("malachite", "marble", null));
        assertEquals("tfc:ore/normal_magnetite/gneiss",
                ArenaOres.oreBlockId("magnetite", "gneiss", ""));
        assertEquals("tfc:ore/normal_tetrahedrite/gneiss",
                ArenaOres.oreBlockId("tetrahedrite", "gneiss", "   "));
    }

    @Test
    public void nonGradedBlockIdHasNoGradePrefix() {
        assertEquals("tfc:ore/lignite/shale", ArenaOres.oreBlockId("lignite", "shale", "normal"));
        // graded 之外矿种即使传 grade 也不加前缀（它们根本没有品位方块）
        assertEquals("tfc:ore/lignite/shale", ArenaOres.oreBlockId("lignite", "shale", "rich"));
        assertEquals("tfc:ore/graphite/marble", ArenaOres.oreBlockId("graphite", "marble", null));
        assertEquals("tfc:ore/diamond/granite", ArenaOres.oreBlockId("diamond", "granite", "poor"));

        for (String ore : NON_GRADED) {
            assertEquals("tfc:ore/" + ore + "/shale", ArenaOres.oreBlockId(ore, "shale", "rich"));
        }
    }

    @Test
    public void unknownOreIsTreatedAsUngradedFormula() {
        // 契约只按 GRADED 集合分流；未知 id 走无前缀公式，由 ArenaConfig 的结构体检拦截未知矿种
        assertEquals("tfc:ore/unobtainium/granite",
                ArenaOres.oreBlockId("unobtainium", "granite", "rich"));
    }

    @Test
    public void gradesSetIsExactlyTheThreeTfcGrades() {
        assertEquals(Set.of("rich", "normal", "poor"), ArenaOres.GRADES);
        assertEquals("rich", ArenaOres.GRADE_RICH);
        assertEquals("normal", ArenaOres.GRADE_NORMAL);
        assertEquals("poor", ArenaOres.GRADE_POOR);
    }

    @Test
    public void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ArenaOres.oreBlockId(null, "marble", "rich"));
        assertThrows(IllegalArgumentException.class, () -> ArenaOres.oreBlockId("", "marble", "rich"));
        assertThrows(IllegalArgumentException.class, () -> ArenaOres.oreBlockId("malachite", null, "rich"));
        assertThrows(IllegalArgumentException.class, () -> ArenaOres.oreBlockId("malachite", " ", "rich"));
        assertThrows("graded 矿种传未知品位应拒绝", IllegalArgumentException.class,
                () -> ArenaOres.oreBlockId("malachite", "marble", "excellent"));
    }

    @Test
    public void gradeFromGeometryRoundTripsToBlockId() {
        // 跨类契约：ArenaGeometry.gradeFor 的输出可以直接喂给 oreBlockId
        for (double rNorm : new double[]{0.1, 0.5, 0.9}) {
            final String grade = ArenaGeometry.gradeFor(rNorm, 0.0);
            assertEquals("tfc:ore/" + grade + "_malachite/marble",
                    ArenaOres.oreBlockId("malachite", "marble", grade));
        }
    }
}
