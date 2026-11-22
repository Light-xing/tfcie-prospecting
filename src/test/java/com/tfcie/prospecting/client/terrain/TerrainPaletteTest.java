package com.tfcie.prospecting.client.terrain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
/** 地形配色的纯逻辑：等高线档位、相邻档可分辨性、区块边界、高度明暗。 */
public class TerrainPaletteTest {

    // ---------------- 等高线档位 ----------------

    @Test
    public void bandGroupsThreeBlocks() {
        assertEquals(20, TerrainPalette.bandOf(60));
        assertEquals(20, TerrainPalette.bandOf(61));
        assertEquals(20, TerrainPalette.bandOf(62));
        assertEquals(21, TerrainPalette.bandOf(63));
    }

    @Test
    public void bandHandlesNegativeHeights() {
        // 主世界可以低于 y=0，必须用向下取整（floorDiv）而不是截断
        assertEquals(-1, TerrainPalette.bandOf(-1));
        assertEquals(-1, TerrainPalette.bandOf(-3));
        assertEquals(-2, TerrainPalette.bandOf(-4));
    }

    @Test
    public void adjacentBandsAreClearlyDistinct() {
        // 用户明确要求：相邻两档颜色必须有明显差别。这里逐对检查 RGB 欧氏距离。
        for (int band = -40; band <= 120; band++) {
            final int a = TerrainPalette.contourColor(band);
            final int b = TerrainPalette.contourColor(band + 1);
            final double distance = rgbDistance(a, b);
            assertTrue("档位 " + band + " 与 " + (band + 1) + " 颜色太接近，距离=" + distance,
                    distance > 100.0D);
        }
    }

    @Test
    public void contourColorsAreOpaque() {
        for (int band = -20; band <= 80; band++) {
            assertEquals(0xFF, (TerrainPalette.contourColor(band) >>> 24) & 0xFF);
        }
    }

    @Test
    public void contourColorsRepeatEventuallyButNotLocally() {
        // 黄金角色相会周期性回到起点，但周期很长，短距离内不应重复
        assertNotEquals(TerrainPalette.contourColor(0), TerrainPalette.contourColor(3));
        assertNotEquals(TerrainPalette.contourColor(10), TerrainPalette.contourColor(13));
    }

    @Test
    public void contourPixelIgnoresSameBandNeighbours() {
        // 同一档内的平坦地形不画线
        assertFalse(TerrainPalette.isContourPixel(60, 60, 61, 62, 60));
    }

    @Test
    public void contourMarksOnlyTheLowerSideOfACrossing() {
        // 跨档发生在 62|63：较低的 62 落笔，较高的 63 不落笔
        assertTrue("较低的一侧应落笔", TerrainPalette.isContourPixel(62, 63, 61, 62, 62));
        assertFalse("较高的一侧不应落笔", TerrainPalette.isContourPixel(63, 64, 62, 63, 63));
    }

    @Test
    public void contourDoesNotTraceDataGaps() {
        // NO_DATA 的档位远低于任何真实高度，不应被误判为「更高」
        assertFalse(TerrainPalette.isContourPixel(60, Short.MIN_VALUE, 60, 60, 60));
    }

    /**
     * 本次 bug 的回归测试。
     *
     * <p>曾经的对称判据（任一邻格档位不同）会把跨越两侧都点上，线宽变成 2 像素；
     * 在 3 格间距下匀坡上约 2/3 的格子都是线像素，看起来就像 gap 被调成了 1。
     * 单侧落笔后，20 格匀坡应恰好画出 6 条线。
     */
    @Test
    public void smoothSlopeDrawsExactlyOneLineEveryThreeBlocks() {
        int lines = 0;
        for (int y = 60; y < 80; y++) {
            final int higher = y + 1;   // 上坡方向：四邻都比它高一格
            if (TerrainPalette.isContourPixel(y, higher, higher, higher, higher)) {
                lines++;
            }
        }
        assertEquals("60..79 共 20 格应恰好 6 条线", 6, lines);
    }

    // ---------------- 平面地图：原版式浮雕亮度 ----------------

    @Test
    public void slopeShadingMakesUphillBrighterAndDownhillDarker() {
        final int base = 0xFF808080;
        // 同一格 (64,64)：北侧更低 → 上坡（朝北逆坡）应亮；北侧更高 → 下坡应暗
        // 选同奇偶的坐标以排除棋盘格抖动的影响
        final int up = TerrainPalette.shadeBySlope(base, 64, 50, 10, 10);
        final int down = TerrainPalette.shadeBySlope(base, 64, 78, 10, 10);
        assertTrue("上坡应比下坡亮", luminance(up) > luminance(down));
    }

    @Test
    public void slopeShadingUsesVanillaBrightnessTiers() {
        final int base = 0xFF808080;
        final int high = TerrainPalette.shadeBySlope(base, 64, 50, 10, 10);
        final int low = TerrainPalette.shadeBySlope(base, 64, 78, 10, 10);
        // 系数取自 MapColor.Brightness：HIGH = 255/255、LOW = 180/255
        assertEquals(Math.round(0x80 * TerrainPalette.BRIGHTNESS_HIGH), (high >> 16) & 0xFF);
        assertEquals(Math.round(0x80 * TerrainPalette.BRIGHTNESS_LOW), (low >> 16) & 0xFF);
    }

    @Test
    public void flatTerrainStaysNormal() {
        final int base = 0xFF808080;
        // 高差为 0 时坡度项为 0，抖动 ±0.2 不足以跨过 ±0.6 阈值 → 恒为 NORMAL
        final int flat = TerrainPalette.shadeBySlope(base, 64, 64, 10, 10);
        assertEquals(Math.round(0x80 * TerrainPalette.BRIGHTNESS_NORMAL), (flat >> 16) & 0xFF);
    }

    @Test
    public void slopeValueAppliesCheckerboardDitherOfPlusMinusPointTwo() {
        // 高度相同时坡度项为 0，只剩抖动；两格奇偶不同应差 0.4
        final double even = TerrainPalette.slopeValue(64, 64, 10, 10);
        final double odd = TerrainPalette.slopeValue(64, 64, 11, 10);
        assertEquals(-0.2D, even, 1.0E-9D);
        assertEquals(0.2D, odd, 1.0E-9D);
        assertEquals(0.4D, odd - even, 1.0E-9D);
    }

    @Test
    public void slopeValueScalesHeightDifferenceByPointEight() {
        // 1:1 地图下权重为 4/(1+4) = 0.8
        final double up = TerrainPalette.slopeValue(65, 64, 10, 11);   // parity 1 → +0.2
        assertEquals(0.8D + 0.2D, up, 1.0E-9D);
    }

    @Test
    public void noDataStaysNoData() {
        assertEquals(0, TerrainPalette.shadeBySlope(0, 64, 50, 10, 10));
        assertEquals(0, TerrainPalette.grayOut(0, 0.5F));
        assertEquals(0, TerrainPalette.contourBackdrop(0));
    }

    @Test
    public void grayOutMovesColorTowardGray() {
        final int pure = 0xFF00FF00;   // 纯绿
        final int grayed = TerrainPalette.grayOut(pure, 0.5F);
        // 与灰色 0x808080 的距离应显著缩小
        assertTrue(rgbDistance(grayed, 0xFF808080) < rgbDistance(pure, 0xFF808080));
        assertEquals(0xFF, (grayed >>> 24) & 0xFF);
    }

    @Test
    public void chunkBorderUsesWorldCoordinates() {
        assertTrue(TerrainPalette.isChunkBorder(0, 5));
        assertTrue(TerrainPalette.isChunkBorder(5, 0));
        assertTrue(TerrainPalette.isChunkBorder(16, 16));
        assertFalse(TerrainPalette.isChunkBorder(15, 15));
        // 负坐标同样成立（Math.floorMod 而非 %）
        assertTrue(TerrainPalette.isChunkBorder(-16, 3));
        assertTrue(TerrainPalette.isChunkBorder(-1, 3) == false);
        assertTrue(TerrainPalette.isChunkBorder(-17, 3) == false);
    }

    @Test
    public void hsvToRgbHitsPrimaries() {
        assertEquals(0xFFFF0000, TerrainPalette.hsvToRgb(0.0F, 1.0F, 1.0F));
        assertEquals(0xFF00FF00, TerrainPalette.hsvToRgb(120.0F, 1.0F, 1.0F));
        assertEquals(0xFF0000FF, TerrainPalette.hsvToRgb(240.0F, 1.0F, 1.0F));
    }

    private static double rgbDistance(int a, int b) {
        final int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        final int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        final int db = (a & 0xFF) - (b & 0xFF);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static int luminance(int argb) {
        return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
    }
}
