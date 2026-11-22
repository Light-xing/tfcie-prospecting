package com.tfcie.prospecting.common.survey;

import com.tfcie.prospecting.api.OreTaxonomy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** 探查配色表：完整性、唯一性、回退（阶段 7 追加需求）。 */
public class OreColorsTest {

    @Test
    public void everyOreHasAColor() {
        // 32 个矿种必须都有配色，否则地图上会出现「查不到颜色」的空洞
        OreTaxonomy.all().keySet().forEach(key ->
                assertTrue("矿种缺少配色: " + key, OreColors.has(key)));
    }

    @Test
    public void everyAnomalyKeyHasAColor() {
        for (String key : new String[]{
                OreColors.RECON_TRACE,
                OreColors.DENSITY_HIGH, OreColors.DENSITY_LOW, OreColors.CONDUCTIVE,
                OreColors.MAGNETIC_STRONG, OreColors.MAGNETIC_WEAK, OreColors.CAVITY,
                OreColors.FLUID_WATER, OreColors.FLUID_LAVA, OreColors.EXPOSED_ORE}) {
            assertTrue("异常类别缺少配色: " + key, OreColors.has(key));
        }
    }

    @Test
    public void tableCoversAllOresPlusAnomalies() {
        // 32 矿种 + 10 个迹象/异常类别
        assertEquals(OreTaxonomy.size() + 10, OreColors.size());
    }

    @Test
    public void allColorsAreFullyOpaque() {
        // 半透明像素在地图上会与背景混色，看起来像噪点
        OreColors.all().forEach((key, argb) ->
                assertEquals("颜色必须不透明: " + key, 0xFF, (argb >>> 24) & 0xFF));
    }

    @Test
    public void colorsAreDistinctEnough() {
        // 不要求两两不同，但至少不能整张表就一个色
        final long distinct = OreColors.all().values().stream().distinct().count();
        assertTrue("配色过于单调: " + distinct, distinct >= 30);
    }

    @Test
    public void instanceColorsMatchIntuition() {
        // 抽查几个「一般人想象中的颜色」：孔雀石偏绿、朱砂偏红、青金石偏蓝
        assertTrue("孔雀石应偏绿", green(OreColors.of("malachite")) > red(OreColors.of("malachite")));
        assertTrue("朱砂应偏红", red(OreColors.of("cinnabar")) > green(OreColors.of("cinnabar")));
        assertTrue("青金石应偏蓝", blue(OreColors.of("lapis_lazuli")) > red(OreColors.of("lapis_lazuli")));
        assertTrue("磁铁矿应偏暗", OreColors.of("magnetite") < 0xFF808080);
    }

    @Test
    public void unknownKeyFallsBackToGrey() {
        assertFalse(OreColors.has("not_a_real_key"));
        assertEquals(0xFF808080, OreColors.of("not_a_real_key"));
    }

    @Test
    public void rgbWeightSeparatesLightFromDark() {
        assertNotEquals(0, Integer.compare(
                rgbWeight(OreColors.of("diamond")), rgbWeight(OreColors.of("bituminous_coal"))));
        assertTrue(rgbWeight(OreColors.of("diamond")) > rgbWeight(OreColors.of("bituminous_coal")));
    }

    private static int rgbWeight(int argb) {
        return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
    }

    private static int red(int argb) { return (argb >> 16) & 0xFF; }

    private static int green(int argb) { return (argb >> 8) & 0xFF; }

    private static int blue(int argb) { return argb & 0xFF; }
}
