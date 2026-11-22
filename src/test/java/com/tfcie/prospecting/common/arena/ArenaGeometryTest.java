package com.tfcie.prospecting.common.arena;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ArenaGeometry} 的纯 JUnit 单测：地表起伏平滑性、走向椭球长轴方向、
 * 中心垂向柱连续性、品位阈值与 {@code 0.7} 概率边界、确定性随机。
 *
 * <p>这些性质直接决定靶场可解性（走向能否被两点法测出来、中心是否必有一钻穿矿柱），
 * 所以断言写得比普通工具方法更细。
 */
public class ArenaGeometryTest {

    // ---------------- 地表微起伏 ----------------

    @Test
    public void surfaceWaveStaysWithinAmplitude() {
        assertEquals(2, ArenaGeometry.WAVE_AMPLITUDE);
        for (int dx = -300; dx <= 300; dx += 7) {
            for (int dz = -300; dz <= 300; dz += 11) {
                final int wave = ArenaGeometry.surfaceWave(dx, dz);
                assertTrue("surfaceWave(" + dx + "," + dz + ") = " + wave + " 超出 [-2,2]",
                        wave >= -ArenaGeometry.WAVE_AMPLITUDE && wave <= ArenaGeometry.WAVE_AMPLITUDE);
            }
        }
    }

    @Test
    public void surfaceWaveChangesGentlyBetweenAdjacentColumns() {
        for (int dx = 0; dx < 256; dx++) {
            for (int dz = 0; dz < 256; dz++) {
                final int here = ArenaGeometry.surfaceWave(dx, dz);
                // 相邻列最多差 1 格：既保证地形平滑，也不会出现 1 格宽断崖
                assertTrue("X 相邻列跳变过大 @" + dx + "," + dz,
                        Math.abs(ArenaGeometry.surfaceWave(dx + 1, dz) - here) <= 1);
                assertTrue("Z 相邻列跳变过大 @" + dx + "," + dz,
                        Math.abs(ArenaGeometry.surfaceWave(dx, dz + 1) - here) <= 1);
            }
        }
    }

    @Test
    public void surfaceWaveActuallyVaries() {
        // 不能是恒 0 的假起伏：全图应同时出现高低点
        final Set<Integer> values = new HashSet<>();
        for (int dx = 0; dx < 256; dx += 4) {
            for (int dz = 0; dz < 256; dz += 4) {
                values.add(ArenaGeometry.surfaceWave(dx, dz));
            }
        }
        assertTrue("地表起伏应有真实变化，实际取值: " + values, values.size() >= 3);
        assertTrue(values.contains(-ArenaGeometry.WAVE_AMPLITUDE) || values.contains(ArenaGeometry.WAVE_AMPLITUDE));
    }

    @Test
    public void surfaceYAddsWaveToBase() {
        assertEquals(72, ArenaGeometry.surfaceY(72, 0, 0));
        for (int dx = 0; dx < 80; dx++) {
            for (int dz = 0; dz < 80; dz += 3) {
                assertEquals(64 + ArenaGeometry.surfaceWave(dx, dz),
                        ArenaGeometry.surfaceY(64, dx, dz));
            }
        }
    }

    // ---------------- 走向椭球 ----------------

    @Test
    public void strike45LongAxisRunsAlongDxEqualsDz() {
        // strike=45：长轴应在 dx=dz（契约称 NE-SW）方向。
        // 同半径点 (10,10) 落在长轴上、(10,-10) 落在短轴上 → 归一化半径前者更小。
        final double alongLongAxis = ArenaGeometry.normalizedRadius(10, 0, 10, 12, 4, 5, 45);
        final double alongShortAxis = ArenaGeometry.normalizedRadius(10, 0, -10, 12, 4, 5, 45);
        assertTrue("长轴方向 " + alongLongAxis + " 应小于短轴方向 " + alongShortAxis,
                alongLongAxis < alongShortAxis);

        // 长轴端点 rx=12：斜向 8 格处径向距离 8*sqrt2 ≈ 11.31 < 12 → 在内；
        // 短轴端点 rz=5：斜向 8 格处在垂直方向 11.31 > 5 → 必然出界。
        assertTrue("(8,8) 沿长轴应可容纳", ArenaGeometry.inside(8, 0, 8, 12, 4, 5, 45));
        assertFalse("(8,-8) 跨短轴应出界", ArenaGeometry.inside(8, 0, -8, 12, 4, 5, 45));

        // 注意：任务书里 "inside(10,0,10,12,4,5,45)=true" 与契约公式不自洽——
        // 斜向 (10,10) 的径向距离 = 10*sqrt2 ≈ 14.14 > rx=12，按 rNorm<=1.0 必然出界。
        // 这里显式钉住真实几何（并回写报告），长轴朝向已由上面的 normalizedRadius 比较证明。
        assertEquals(10.0 * Math.sqrt(2.0) / 12.0,
                ArenaGeometry.normalizedRadius(10, 0, 10, 12, 4, 5, 45), 1.0e-9);
        assertFalse(ArenaGeometry.inside(10, 0, 10, 12, 4, 5, 45));
    }

    @Test
    public void strike135LongAxisRunsAlongDxEqualsMinusDz() {
        final double alongLongAxis = ArenaGeometry.normalizedRadius(10, 0, -10, 12, 4, 5, 135);
        final double alongShortAxis = ArenaGeometry.normalizedRadius(10, 0, 10, 12, 4, 5, 135);
        assertTrue("strike=135 长轴应沿 dx=-dz，实际 " + alongLongAxis + " vs " + alongShortAxis,
                alongLongAxis < alongShortAxis);

        assertTrue(ArenaGeometry.inside(8, 0, -8, 12, 4, 5, 135));
        assertFalse(ArenaGeometry.inside(8, 0, 8, 12, 4, 5, 135));
    }

    @Test
    public void strike0LongAxisRunsAlongDxAxis() {
        assertTrue(ArenaGeometry.inside(11, 0, 0, 12, 4, 5, 0));
        assertFalse("rz=5，z 向 6 格应出界", ArenaGeometry.inside(0, 0, 6, 12, 4, 5, 0));
        assertEquals(1.0, ArenaGeometry.normalizedRadius(12, 0, 0, 12, 4, 5, 0), 1.0e-9);
    }

    @Test
    public void insideRespectsVerticalRadius() {
        assertTrue(ArenaGeometry.inside(0, 4, 0, 12, 4, 5, 0));
        assertTrue(ArenaGeometry.inside(0, -4, 0, 12, 4, 5, 0));
        assertFalse(ArenaGeometry.inside(0, 5, 0, 12, 4, 5, 0));
        assertEquals(0.0, ArenaGeometry.normalizedRadius(0, 0, 0, 12, 4, 5, 45), 0.0);
    }

    // ---------------- 中心垂向柱连续性 ----------------

    @Test
    public void centerVerticalColumnIsRichForAtLeastThreeBlocks() {
        // ry = 3/4/5 都试一遍（内置图里 ry 最小 3、最大 5）
        for (int ry : new int[]{3, 4, 5}) {
            String previous = null;
            int richRun = 0;
            for (int dy = -1; dy <= 1; dy++) {
                final double rNorm = ArenaGeometry.normalizedRadius(0, dy, 0, 8, ry, 8, 45);
                // roll 取极端值，证明「中心必是 rich」与掷骰无关
                final String grade = ArenaGeometry.gradeFor(rNorm, 0.999_999);
                assertEquals("ry=" + ry + " dy=" + dy + " 中心柱必须是 rich", "rich", grade);
                if ("rich".equals(grade)) {
                    richRun++;
                }
                previous = grade;
            }
            assertNotNull(previous);
            assertEquals("中心垂向柱应有 3 格连续 rich", 3, richRun);
            assertTrue("中心柱 rNorm 必须 <= 0.4（ry>=3 时 dy=±1 也只到 1/3）",
                    1.0 / ry <= ArenaGeometry.RICH_MAX_RADIUS);
        }
    }

    @Test
    public void centerVerticalColumnHasNoGapAroundCore() {
        // 契约要求中心柱至少 3 格连续；这里再证 ry>=4 时中心 ±2 格也必放置（rNorm<=0.75 → normal）
        for (int ry : new int[]{4, 5}) {
            for (int dy = -2; dy <= 2; dy++) {
                final double rNorm = ArenaGeometry.normalizedRadius(0, dy, 0, 8, ry, 8, 0);
                assertNotNull("ry=" + ry + " dy=" + dy + " 处出现空洞", ArenaGeometry.gradeFor(rNorm, 0.999_999));
                assertTrue(ArenaGeometry.gradeFor(rNorm, 0.999_999).equals("rich")
                        || ArenaGeometry.gradeFor(rNorm, 0.999_999).equals("normal"));
            }
        }
    }

    // ---------------- 品位阈值与概率 ----------------

    @Test
    public void gradeForUsesDocumentedThresholds() {
        assertEquals("rich", ArenaGeometry.gradeFor(0.0, 0.99));
        assertEquals("rich", ArenaGeometry.gradeFor(0.4, 0.99));
        assertEquals("normal", ArenaGeometry.gradeFor(0.4 + 1.0e-9, 0.99));
        assertEquals("normal", ArenaGeometry.gradeFor(0.75, 0.99));
        assertEquals("normal", ArenaGeometry.gradeFor(0.5, 0.0));
        assertEquals("poor", ArenaGeometry.gradeFor(0.75 + 1.0e-9, 0.0));
        assertEquals("poor", ArenaGeometry.gradeFor(1.0, 0.0));
        assertNull("超出椭球不放置", ArenaGeometry.gradeFor(1.0 + 1.0e-9, 0.0));
        assertNull("超出椭球即使掷骰很低也不放置", ArenaGeometry.gradeFor(2.0, -1.0));
    }

    @Test
    public void poorShellUsesSevenTenthsPlacementChance() {
        final double outer = 0.9;   // 落在 (0.75, 1.0] 外缘带
        assertEquals("roll 略小于 0.7 → poor", "poor", ArenaGeometry.gradeFor(outer, 0.699_999));
        assertNull("roll 恰为 0.7 → 不放置（边界不含）", ArenaGeometry.gradeFor(outer, 0.7));
        assertNull("roll 大于 0.7 → 不放置", ArenaGeometry.gradeFor(outer, 0.700_001));
        assertNull(ArenaGeometry.gradeFor(outer, 0.99));
    }

    @Test
    public void poorShellPlacementFractionIsAboutSeventyPercent() {
        // 用确定性随机在外缘带上大量采样，验证实际放置率接近 0.7（同时反向校验 deterministic01 的均匀性）
        int placed = 0;
        int total = 0;
        for (int x = 0; x < 50; x++) {
            for (int z = 0; z < 50; z++) {
                final double roll = ArenaGeometry.deterministic01(x, 7, z, 20261122);
                total++;
                if (ArenaGeometry.gradeFor(0.9, roll) != null) {
                    placed++;
                }
            }
        }
        final double fraction = (double) placed / total;
        assertTrue("poor 放置率应接近 0.7，实际 " + fraction,
                fraction > 0.65 && fraction < 0.75);
    }

    // ---------------- 确定性随机 ----------------

    @Test
    public void deterministic01IsStableForSameArguments() {
        for (int i = 0; i < 100; i++) {
            assertEquals(ArenaGeometry.deterministic01(i, i * 2, -i, 3),
                    ArenaGeometry.deterministic01(i, i * 2, -i, 3), 0.0);
        }
        // 相邻调用之间没有隐藏状态
        final double first = ArenaGeometry.deterministic01(12, 34, 56, 78);
        for (int i = 0; i < 10; i++) {
            ArenaGeometry.deterministic01(1, 2, 3, 4);
        }
        assertEquals(first, ArenaGeometry.deterministic01(12, 34, 56, 78), 0.0);
    }

    @Test
    public void deterministic01StaysInUnitInterval() {
        for (int x = -20; x <= 20; x++) {
            for (int y = -20; y <= 20; y += 3) {
                for (int z = -5; z <= 5; z++) {
                    final double value = ArenaGeometry.deterministic01(x, y, z, x * 31 + y);
                    assertTrue("deterministic01 必须 >= 0，实际 " + value, value >= 0.0);
                    assertTrue("deterministic01 必须 < 1，实际 " + value, value < 1.0);
                }
            }
        }
    }

    @Test
    public void deterministic01DiffersBySaltAndByCoordinate() {
        // 同一坐标不同盐 → 不同值（否则不同特征的矿核会呈现相同随机图案）
        for (int i = 0; i < 32; i++) {
            assertNotEquals("salt 应改变随机流 @" + i,
                    ArenaGeometry.deterministic01(i, 0, 0, 1),
                    ArenaGeometry.deterministic01(i, 0, 0, 2), 0.0);
        }
        // 坐标任一轴变化 → 值变化
        assertNotEquals(ArenaGeometry.deterministic01(0, 0, 0, 0),
                ArenaGeometry.deterministic01(1, 0, 0, 0), 0.0);
        assertNotEquals(ArenaGeometry.deterministic01(0, 0, 0, 0),
                ArenaGeometry.deterministic01(0, 1, 0, 0), 0.0);
        assertNotEquals(ArenaGeometry.deterministic01(0, 0, 0, 0),
                ArenaGeometry.deterministic01(0, 0, 1, 0), 0.0);
    }

    @Test
    public void gradeForAcceptsOnlyDeterministicRollForReproducibleMaps() {
        // 组合用法（运行时就是这样逐格判定的）：同一格重刷两次得到同样品位
        for (int y = -4; y <= 4; y++) {
            final double rNorm = ArenaGeometry.normalizedRadius(3, y, 3, 9, 5, 9, 135);
            final double roll = ArenaGeometry.deterministic01(3, y, 3, 77);
            assertEquals(ArenaGeometry.gradeFor(rNorm, roll),
                    ArenaGeometry.gradeFor(rNorm, ArenaGeometry.deterministic01(3, y, 3, 77)));
        }
    }
}
