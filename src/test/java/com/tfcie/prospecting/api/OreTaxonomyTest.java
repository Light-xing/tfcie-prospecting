package com.tfcie.prospecting.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tfcie.prospecting.api.enums.DensityClass;
import com.tfcie.prospecting.api.enums.Magnetism;
import com.tfcie.prospecting.api.enums.OreGroup;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 矿种分类表与物性表（标定地基：与 TFC 1.20.x 实际数据包一致）。 */
public class OreTaxonomyTest {

    @Test
    public void coversAllTfcOres() {
        // TFC 1.20.x 的 Ore 枚举共 32 项
        assertEquals(32, OreTaxonomy.size());
    }

    @Test
    public void gemstonesIncludeRubySapphireTopaz() {
        // 设计文档曾误判这三者为 1.21 新增；实测 TFC 1.20.x 数据包中它们确实存在：
        // Sapphire 由温泉、Topaz 由火山裂隙生成（都在 configured_feature/vein/ 之外），
        // Ruby 无世界生成、仅经淘洗 / 溜槽矿床获得。
        assertEquals(OreGroup.GEM, OreTaxonomy.groupOf("ruby"));
        assertEquals(OreGroup.GEM, OreTaxonomy.groupOf("sapphire"));
        assertEquals(OreGroup.GEM, OreTaxonomy.groupOf("topaz"));
        assertEquals(OreGroup.GEM, OreTaxonomy.groupOf("diamond"));
        assertEquals(OreGroup.GEM, OreTaxonomy.groupOf("emerald"));
        assertEquals(OreGroup.GEM, OreTaxonomy.groupOf("lapis_lazuli"));
        assertEquals(OreGroup.GEM, OreTaxonomy.groupOf("amethyst"));
        assertEquals(OreGroup.GEM, OreTaxonomy.groupOf("opal"));
    }

    @Test
    public void pyriteIsMetallicFoolsGold() {
        // 黄铁矿在 1.20 以 fake_native_gold（愚人金）矿脉生成，属金属矿侧的干扰源
        assertEquals(OreGroup.METALLIC, OreTaxonomy.groupOf("pyrite"));
    }

    @Test
    public void metalAndNonMetalSplit() {
        assertEquals(OreGroup.METALLIC, OreTaxonomy.groupOf("native_copper"));
        assertEquals(OreGroup.METALLIC, OreTaxonomy.groupOf("cassiterite"));
        assertEquals(OreGroup.METALLIC, OreTaxonomy.groupOf("garnierite"));
        assertEquals(OreGroup.NON_METALLIC, OreTaxonomy.groupOf("graphite"));
        assertEquals(OreGroup.NON_METALLIC, OreTaxonomy.groupOf("bituminous_coal"));
        assertEquals(OreGroup.NON_METALLIC, OreTaxonomy.groupOf("sulfur"));
        assertEquals(OreGroup.NON_METALLIC, OreTaxonomy.groupOf("halite"));
    }

    @Test
    public void conductivityFollowsCalibratedBlindSpots() {
        // 孔雀石是碳酸铜 → 不导电（v1.3 已修正）
        assertFalse(OreTaxonomy.isConductive("malachite"));
        // 硅镁镍矿是风化硅酸盐 → 不导电
        assertFalse(OreTaxonomy.isConductive("garnierite"));
        // 石墨是导电非金属（判读表「低密度 + 导电 + 无磁 → 石墨」）
        assertTrue(OreTaxonomy.isConductive("graphite"));
        // 煤不导电
        assertFalse(OreTaxonomy.isConductive("bituminous_coal"));
        // 常见金属硫化物 / 自然金属导电
        assertTrue(OreTaxonomy.isConductive("native_copper"));
        assertTrue(OreTaxonomy.isConductive("tetrahedrite"));
        assertTrue(OreTaxonomy.isConductive("pyrite"));
        // 宝石不导电
        assertFalse(OreTaxonomy.isConductive("diamond"));
    }

    @Test
    public void ironThreeFormsHaveDistinctMagnetism() {
        // 复核结论：只有磁铁矿是强磁目标，赤铁矿弱磁、褐铁矿基本无磁
        assertEquals(Magnetism.STRONG, OreTaxonomy.magnetismOf("magnetite"));
        assertEquals(Magnetism.WEAK, OreTaxonomy.magnetismOf("hematite"));
        assertEquals(Magnetism.NONE, OreTaxonomy.magnetismOf("limonite"));
    }

    @Test
    public void nickelIsGeophysicalBlindSpot() {
        // 镍（硅镁镍矿）非磁性、不导电、非高密度 —— 物探盲区，只能靠钻探发现
        assertEquals(Magnetism.NONE, OreTaxonomy.magnetismOf("garnierite"));
        assertFalse(OreTaxonomy.isConductive("garnierite"));
        assertEquals(DensityClass.NORMAL, OreTaxonomy.densityOf("garnierite"));
    }

    @Test
    public void densityClasses() {
        // 金属矿体致密
        assertEquals(DensityClass.HIGH, OreTaxonomy.densityOf("native_copper"));
        assertEquals(DensityClass.HIGH, OreTaxonomy.densityOf("magnetite"));
        // 煤 / 盐 / 石膏 / 硼砂为低密度
        assertEquals(DensityClass.LOW, OreTaxonomy.densityOf("bituminous_coal"));
        assertEquals(DensityClass.LOW, OreTaxonomy.densityOf("halite"));
        assertEquals(DensityClass.LOW, OreTaxonomy.densityOf("gypsum"));
        assertEquals(DensityClass.LOW, OreTaxonomy.densityOf("borax"));
        // 石墨低密度 + 导电
        assertEquals(DensityClass.LOW, OreTaxonomy.densityOf("graphite"));
    }

    @Test
    public void magneticOresSilentOnBaseMetals() {
        // 磁法对铜、锡、金、银、煤、石墨、硫、盐、宝石完全无响应
        for (String id : new String[]{"native_copper", "cassiterite", "native_gold", "native_silver",
                "bituminous_coal", "graphite", "sulfur", "halite", "diamond", "ruby"}) {
            assertEquals("磁法不应响应 " + id, Magnetism.NONE, OreTaxonomy.magnetismOf(id));
        }
    }

    @Test
    public void tfcEnumNameLookupIsCaseInsensitive() {
        assertNotNull(OreTaxonomy.byTfcEnumName("NATIVE_COPPER"));
        assertEquals("native_copper", OreTaxonomy.byTfcEnumName("NATIVE_COPPER").id());
        assertEquals("ruby", OreTaxonomy.byTfcEnumName("RUBY").id());
    }

    @Test
    public void unknownOreFallsBackToSafeDefaults() {
        assertEquals(OreGroup.UNKNOWN, OreTaxonomy.groupOf("not_a_real_ore"));
        assertNull(OreTaxonomy.byId("not_a_real_ore"));
        assertEquals("not_a_real_ore", OreTaxonomy.displayName("not_a_real_ore"));
        assertFalse(OreTaxonomy.isConductive("not_a_real_ore"));
        assertEquals(Magnetism.NONE, OreTaxonomy.magnetismOf("not_a_real_ore"));
        assertEquals(DensityClass.NORMAL, OreTaxonomy.densityOf("not_a_real_ore"));
    }

    @Test
    public void idsAndContainsExposeKnownOres() {
        // 按方块名推断矿种（ReconScanner）用这两个访问器查已知 id
        assertEquals(OreTaxonomy.size(), OreTaxonomy.ids().size());
        assertTrue(OreTaxonomy.ids().contains("magnetite"));
        assertTrue(OreTaxonomy.contains("hematite"));
        assertFalse(OreTaxonomy.contains("not_a_real_ore"));
        assertFalse(OreTaxonomy.contains(null));
    }

    // ---------------- 数据驱动：文件装载与整合包覆盖 ----------------

    @Test
    public void baselineIsLoadedFromDataFileNotHardcoded() {
        // 表来自 data/tfcie_prospecting/prospecting/ore_properties/default.json
        assertFalse("内置基线应立即可用（classpath 引导装载）", OreTaxonomy.baseline().isEmpty());
        assertEquals(32, OreTaxonomy.baseline().size());
        // 基线中每项都必须带齐物性字段
        OreTaxonomy.baseline().forEach((id, entry) -> {
            assertEquals(id, entry.id());
            assertNotNull("缺少矿族: " + id, entry.group());
            assertNotNull("缺少磁性: " + id, entry.magnetism());
            assertNotNull("缺少密度: " + id, entry.density());
            assertFalse("缺少中文名: " + id, entry.zhName().isBlank());
        });
    }

    @Test
    public void pyriteDeclaresConfusionTargetInData() {
        // 「相似矿物混淆」不再硬编码某对矿种，而由数据文件的 confused_with 声明
        assertTrue(OreTaxonomy.byId("pyrite").confusing());
        assertEquals("native_gold", OreTaxonomy.confusedWith("pyrite"));
        assertNull(OreTaxonomy.confusedWith("native_copper"));
        assertFalse(OreTaxonomy.byId("native_copper").confusing());
    }

    @Test
    public void datapackCanOverrideSingleOreWithoutTouchingCode() {
        final JsonObject root = JsonParser.parseString("""
                {"ores": {"native_copper": {"group": "gem", "zh_cn": "测试铜",
                 "conductive": false, "magnetism": "strong", "density": "low"}}}
                """).getAsJsonObject();

        final Map<String, OreTaxonomy.Entry> merged =
                OreTaxonomy.merge(OreTaxonomy.baseline(), root, false);

        // 被覆盖的条目按数据生效
        assertEquals(OreGroup.GEM, merged.get("native_copper").group());
        assertEquals(Magnetism.STRONG, merged.get("native_copper").magnetism());
        assertFalse(merged.get("native_copper").conductive());
        // 未被覆盖的条目原样保留
        assertEquals(32, merged.size());
        assertEquals(OreGroup.GEM, merged.get("diamond").group());
    }

    @Test
    public void replaceTrueRebuildsWholeTable() {
        final JsonObject root = JsonParser.parseString("""
                {"replace": true, "ores": {"diamond": {"group": "gem", "zh_cn": "钻石"}}}
                """).getAsJsonObject();

        final Map<String, OreTaxonomy.Entry> replaced =
                OreTaxonomy.merge(OreTaxonomy.baseline(), root, true);

        assertEquals(1, replaced.size());
        // 未声明的字段回落到安全默认值
        assertEquals(Magnetism.NONE, replaced.get("diamond").magnetism());
        assertEquals(DensityClass.NORMAL, replaced.get("diamond").density());
        // conductive 缺省为 true（只有显式写 false 才不导电）
        assertTrue(replaced.get("diamond").conductive());
    }

    @Test
    public void commentKeysAreIgnored() {
        final JsonObject root = JsonParser.parseString("""
                {"_comment": "注释", "ores": {"_note": {"group": "gem"}}}
                """).getAsJsonObject();
        final Map<String, OreTaxonomy.Entry> merged = OreTaxonomy.merge(Map.of(), root, true);
        assertTrue(merged.isEmpty());
    }
}
