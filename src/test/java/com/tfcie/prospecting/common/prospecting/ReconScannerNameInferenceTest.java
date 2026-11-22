package com.tfcie.prospecting.common.prospecting;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 按方块注册名推断矿种 id（{@link ReconScanner#inferOreIdFromName(String)}）—— 纯逻辑单测。
 *
 * <p>这一层是报告 13 §13.2 的修复：TFC 注册表索引只认识 TFC 自己的矿石方块，
 * 整合包里 Immersive Geology 的磁铁矿 / 赤铁矿不在其中，于是所有物探工具都漏报。
 * 这里只测「名字 → 矿种 id」的匹配规则，不依赖任何游戏运行时。
 */
public class ReconScannerNameInferenceTest {

    @Test
    public void recognisesImmersiveGeologyIronOres() {
        // 报告 13 §13.2 的两个实测方块名：磁铁矿此前读 0.0，赤铁矿同样漏报
        assertEquals("magnetite",
                ReconScanner.inferOreIdFromName(
                        "immersivegeology:minecraft_rich_ore_block_magnetite_stone"));
        assertEquals("hematite",
                ReconScanner.inferOreIdFromName(
                        "immersivegeology:minecraft_normal_ore_block_hematite_deepslate"));
    }

    @Test
    public void recognisesTfcOreNamesAsWell() {
        // TFC 自己的命名走注册表索引优先；这里只验证名字规则本身也能解析
        assertEquals("magnetite", ReconScanner.inferOreIdFromName("tfc:ore/rich_magnetite/dolomite"));
        assertEquals("hematite", ReconScanner.inferOreIdFromName("tfc:ore/poor_hematite/granite"));
        assertEquals("limonite", ReconScanner.inferOreIdFromName("tfc:ore/small_limonite"));
        assertEquals("graphite", ReconScanner.inferOreIdFromName("tfc:ore/graphite/marble"));
    }

    @Test
    public void multiTokenIdsMatchAcrossUnderscores() {
        // native_copper / bituminous_coal / lapis_lazuli 这类 id 跨两个 token
        assertEquals("native_copper",
                ReconScanner.inferOreIdFromName("foo:deepslate_native_copper_ore"));
        assertEquals("bituminous_coal",
                ReconScanner.inferOreIdFromName("foo:bituminous_coal_seam"));
        assertEquals("lapis_lazuli",
                ReconScanner.inferOreIdFromName("foo:lapis_lazuli_ore"));
    }

    @Test
    public void longestMatchWinsOverEarlierSingleToken() {
        // 「取最长匹配」：同一串里既有单 token 命中又有跨 token 命中时，选更长的那个
        assertEquals("native_copper",
                ReconScanner.inferOreIdFromName("foo:magnetite_native_copper_ore"));
    }

    @Test
    public void plainRockNeverMatches() {
        // 普通岩石的路径里没有已知矿种 token → 必须返回 null，不能误判
        assertNull(ReconScanner.inferOreIdFromName("minecraft:stone"));
        assertNull(ReconScanner.inferOreIdFromName("tfc:rock/raw/granite"));
        assertNull(ReconScanner.inferOreIdFromName("tfc:rock/raw/dolomite"));
        assertNull(ReconScanner.inferOreIdFromName(
                "immersivegeology:minecraft_normal_ore_block_unobtainium_stone"));
        assertNull(ReconScanner.inferOreIdFromName("tfc:dirt/silt"));
        assertNull(ReconScanner.inferOreIdFromName(""));
        assertNull(ReconScanner.inferOreIdFromName(null));
    }

    @Test
    public void matchingIsCaseInsensitive() {
        // 注册名一般小写，但推断层不假设大小写
        assertEquals("hematite", ReconScanner.inferOreIdFromName("SomeMod:Hematite_Ore"));
        assertEquals("magnetite", ReconScanner.inferOreIdFromName("SOMEMOD:MAGNETITE"));
    }

    @Test
    public void namespaceTokensAreSearchedToo() {
        // namespace 与 path 一并切分：矿种 token 出现在 namespace 里也应命中
        assertEquals("cassiterite", ReconScanner.inferOreIdFromName("cassiterite_rich:something"));
    }
}
