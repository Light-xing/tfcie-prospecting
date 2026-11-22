package com.tfcie.prospecting.common.arena;

import com.tfcie.prospecting.api.OreTaxonomy;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 内置四张靶场配置（{@code map1.json} ～ {@code map4.json}）的契约测试。
 *
 * <p>本测试<b>不加载 Minecraft 运行时</b>：只从 classpath 读取 jar 内资源，
 * 再用 {@link ArenaConfig#parse(String, String)} 反序列化，随后逐项核对
 * 设计契约 §3 规定的坐标、矿种、深度、半径、走向、露头数量与同图异常源间距，
 * 并顺带检查所有矿脉的 {@code ore} 都能被 {@link OreTaxonomy} 收录。
 *
 * <p>覆盖的契约条目：
 * <ul>
 *   <li>§1.1 —— {@code structuralErrors()} 为空、尺寸 / 水囊回填开关 / 起点的合法性；</li>
 *   <li>§3 —— 每张图的 zone 分区、features 精确参数、outcrops 数量与落区；</li>
 *   <li>§5.2 —— 同图任意两个异常源中心水平间距断言（map1~3 ≥ 70、map4 ≥ 85）。</li>
 * </ul>
 */
public class ArenaBuiltinMapsTest {

    /** jar 内置靶场配置目录。 */
    private static final String RESOURCE_DIR = "/data/tfcie_prospecting/arenas/";

    /** 同图异常源中心水平间距下限：map1~map3。 */
    private static final double MIN_CENTRE_DISTANCE_SMALL = 70.0;

    /** 同图异常源中心水平间距下限：map4。 */
    private static final double MIN_CENTRE_DISTANCE_MAP4 = 85.0;

    /** 露头中心到任一异常源中心的最小水平间距（契约 §3 明文要求）。 */
    private static final double MIN_OUTCROP_CLEARANCE = 12.0;

    // ------------------------------------------------------------------
    // 公共结构断言
    // ------------------------------------------------------------------

    /**
     * 四张图都能解析、没有结构错误，且尺寸 / 水囊回填 / 起点符合契约。
     */
    @Test
    public void allBuiltinMapsParseWithoutStructuralErrors() {
        final int[] expectedSize = {128, 128, 128, 256};
        for (int i = 0; i < 4; i++) {
            final int index = i + 1;
            final String name = "map" + index;
            final ArenaConfig cfg = load(name);

            assertEquals("name 应与文件名一致：" + name, name, cfg.name);
            final List<String> errors = cfg.structuralErrors();
            assertTrue(name + " 的 structuralErrors() 应为空，实际：" + errors, errors.isEmpty());

            assertEquals(name + " 的 size_x", expectedSize[i], cfg.sizeX);
            assertEquals(name + " 的 size_z", expectedSize[i], cfg.sizeZ);
            assertTrue(name + " 的 base_surface_y 应落在合理高度区间", cfg.baseSurfaceY > -64 && cfg.baseSurfaceY < 320);
            assertTrue(name + " 必须开启 fill_underground_caves（保证幂等重刷）", cfg.fillUndergroundCaves);

            assertNotNull(name + " 必须有 spawn", cfg.spawn);
            assertInBounds(name + " 的 spawn", cfg.spawn.dx(), cfg.spawn.dz(), cfg.sizeX, cfg.sizeZ);

            assertNotNull(name + " 必须有 rock_zones", cfg.zones);
            assertFalse(name + " 的 rock_zones 不能为空", cfg.zones.isEmpty());
            for (ArenaConfig.Zone zone : cfg.zones) {
                assertTrue(name + " 的 zone 必须闭区间且有序：x " + zone.xMin() + ".." + zone.xMax(),
                        zone.xMin() <= zone.xMax());
                assertTrue(name + " 的 zone 必须闭区间且有序：z " + zone.zMin() + ".." + zone.zMax(),
                        zone.zMin() <= zone.zMax());
                assertTrue(name + " 的 zone x 越界", zone.xMin() >= 0 && zone.xMax() < cfg.sizeX);
                assertTrue(name + " 的 zone z 越界", zone.zMin() >= 0 && zone.zMax() < cfg.sizeZ);
            }

            // 每个 (dx,dz) 都必须至少被一个 zone 覆盖：抽样 + 四角 + 边界缝合处。
            int[] probe = {0, 1, cfg.sizeX / 2 - 1, cfg.sizeX / 2, cfg.sizeX - 2, cfg.sizeX - 1};
            for (int dx : probe) {
                for (int dz : probe) {
                    assertNotNull(name + " 在 (" + dx + "," + dz + ") 未被任何 rock zone 覆盖",
                            cfg.zoneAt(dx, dz));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // map1 —— 母岩推断与浅层非导电盲矿
    // ------------------------------------------------------------------

    /** map1：西 shale / 东 marble 半区，孔雀石真矿 + 褐煤干扰。 */
    @Test
    public void map1FeaturesMatchSpec() {
        final ArenaConfig cfg = load("map1");

        assertEquals("map1 的起点", 64, cfg.spawn.dx());
        assertEquals("map1 的起点", 108, cfg.spawn.dz());

        assertEquals("map1 应恰好 2 个 zone", 2, cfg.zones.size());
        assertEquals("map1 西半区母岩", "shale", cfg.zoneAt(0, 0).rock());
        assertEquals("map1 西半区母岩", "shale", cfg.zoneAt(63, 127).rock());
        assertEquals("map1 东半区母岩", "marble", cfg.zoneAt(64, 0).rock());
        assertEquals("map1 东半区母岩", "marble", cfg.zoneAt(127, 127).rock());
        assertEquals("map1 西半区 x 上界", 63, cfg.zoneAt(63, 10).xMax());
        assertEquals("map1 东半区 x 下界", 64, cfg.zoneAt(64, 10).xMin());

        assertEquals("map1 的 feature 数", 2, cfg.featureCount());

        final ArenaConfig.Feature target = feature(cfg, "map1-target-malachite");
        assertEquals("vein", target.type());
        assertTrue("map1 真矿必须标记为 is_target", target.isTarget());
        assertEquals("malachite", target.ore());
        assertEquals("dx", 98, target.dx());
        assertEquals("dz", 34, target.dz());
        assertEquals("depth_below_surface（须落在踏勘镐 24 格浅带内）", 18, target.depthBelowSurface());
        assertEquals("rx", 8, target.rx());
        assertEquals("ry", 4, target.ry());
        assertEquals("rz", 8, target.rz());
        assertEquals("strike_deg", 0.0, target.strikeDeg(), 1e-9);
        assertEquals("真矿必须落在东半区 marble 内", "marble", cfg.zoneAt(target.dx(), target.dz()).rock());

        final ArenaConfig.Feature distractor = feature(cfg, "map1-distractor-lignite");
        assertEquals("vein", distractor.type());
        assertFalse("map1 干扰体不得标记为 is_target", distractor.isTarget());
        assertEquals("lignite", distractor.ore());
        assertEquals("dx", 26, distractor.dx());
        assertEquals("dz", 40, distractor.dz());
        assertEquals("depth_below_surface", 16, distractor.depthBelowSurface());
        assertEquals("rx", 9, distractor.rx());
        assertEquals("ry", 3, distractor.ry());
        assertEquals("rz", 9, distractor.rz());
        assertEquals("strike_deg", 0.0, distractor.strikeDeg(), 1e-9);
        assertEquals("干扰体必须落在西半区 shale 内", "shale",
                cfg.zoneAt(distractor.dx(), distractor.dz()).rock());

        // 两侧露头：西 shale、东 marble 各 2~3 处。
        int west = 0;
        int east = 0;
        for (ArenaConfig.Outcrop o : cfg.outcrops) {
            if (o.dx() < 64) {
                west++;
            } else {
                east++;
            }
        }
        assertTrue("map1 西侧（shale）露头应为 2~3 处，实际 " + west, west >= 2 && west <= 3);
        assertTrue("map1 东侧（marble）露头应为 2~3 处，实际 " + east, east >= 2 && east <= 3);
    }

    // ------------------------------------------------------------------
    // map2 —— 多源消歧与磁法走向
    // ------------------------------------------------------------------

    /** map2：全图 gneiss，水囊 + 石墨伪异常 + 黝铜矿 + 狭长磁铁矿（strike 45）。 */
    @Test
    public void map2FeaturesMatchSpec() {
        final ArenaConfig cfg = load("map2");

        assertEquals("map2 的起点", 64, cfg.spawn.dx());
        assertEquals("map2 的起点", 64, cfg.spawn.dz());
        assertEquals("map2 应恰好 1 个 zone", 1, cfg.zones.size());
        assertEquals("map2 全图母岩", "gneiss", cfg.zoneAt(0, 0).rock());
        assertEquals("map2 全图母岩", "gneiss", cfg.zoneAt(127, 127).rock());
        assertEquals("map2 的 feature 数", 4, cfg.featureCount());

        assertEquals("map2 应恰好 1 个 water_pocket", 1, countType(cfg, "water_pocket"));

        final ArenaConfig.Feature water = feature(cfg, "map2-distractor-water-a");
        assertEquals("water_pocket", water.type());
        assertFalse("水囊是伪异常", water.isTarget());
        assertEquals("dx", 24, water.dx());
        assertEquals("dz", 24, water.dz());
        assertEquals("depth_below_surface", 26, water.depthBelowSurface());
        assertEquals("rx", 7, water.rx());
        assertEquals("ry", 4, water.ry());
        assertEquals("rz", 7, water.rz());
        assertFalse("伪异常 A 是淡水囊（salt=false）", water.salt());

        final ArenaConfig.Feature graphite = feature(cfg, "map2-distractor-graphite");
        assertEquals("vein", graphite.type());
        assertFalse("石墨是伪异常（低密度 + 高导电）", graphite.isTarget());
        assertEquals("graphite", graphite.ore());
        assertEquals("dx", 104, graphite.dx());
        assertEquals("dz", 24, graphite.dz());
        assertEquals("depth_below_surface", 30, graphite.depthBelowSurface());
        assertEquals("rx", 8, graphite.rx());
        assertEquals("ry", 4, graphite.ry());
        assertEquals("rz", 8, graphite.rz());

        final ArenaConfig.Feature tetrahedrite = feature(cfg, "map2-target-tetrahedrite");
        assertEquals("vein", tetrahedrite.type());
        assertTrue("黝铜矿是真矿", tetrahedrite.isTarget());
        assertEquals("tetrahedrite", tetrahedrite.ore());
        assertEquals("dx", 26, tetrahedrite.dx());
        assertEquals("dz", 102, tetrahedrite.dz());
        assertEquals("depth_below_surface（超出踏勘镐浅带）", 32, tetrahedrite.depthBelowSurface());
        assertEquals("rx", 8, tetrahedrite.rx());
        assertEquals("ry", 4, tetrahedrite.ry());
        assertEquals("rz", 8, tetrahedrite.rz());

        final ArenaConfig.Feature magnetite = feature(cfg, "map2-target-magnetite");
        assertEquals("vein", magnetite.type());
        assertTrue("磁铁矿是真矿", magnetite.isTarget());
        assertEquals("magnetite", magnetite.ore());
        assertEquals("dx", 100, magnetite.dx());
        assertEquals("dz", 100, magnetite.dz());
        assertEquals("depth_below_surface", 30, magnetite.depthBelowSurface());
        assertEquals("rx（狭长脉长轴）", 12, magnetite.rx());
        assertEquals("ry", 4, magnetite.ry());
        assertEquals("rz（狭长脉短轴）", 5, magnetite.rz());
        assertEquals("strike_deg 必须是 NE-SW 的 45°", 45.0, magnetite.strikeDeg(), 1e-9);

        assertEquals("map2 的露头数量", 3, cfg.outcrops.size());
    }

    // ------------------------------------------------------------------
    // map3 —— 深部盲矿与中深竖井
    // ------------------------------------------------------------------

    /** map3：上层 granite / 下层 gabbro（分界 25），深 72 格辉铋矿 + 水囊干扰。 */
    @Test
    public void map3FeaturesMatchSpec() {
        final ArenaConfig cfg = load("map3");

        assertEquals("map3 的起点", 32, cfg.spawn.dx());
        assertEquals("map3 的起点", 32, cfg.spawn.dz());
        assertEquals("map3 应恰好 1 个 zone", 1, cfg.zones.size());

        final ArenaConfig.Zone zone = cfg.zoneAt(64, 64);
        assertNotNull("map3 全图应被单一 zone 覆盖", zone);
        assertEquals("map3 上层母岩", "granite", zone.rock());
        assertEquals("map3 下层母岩", "gabbro", zone.bottomRock());
        assertEquals("map3 上下层分界深度", 25, zone.boundaryDepth());
        assertEquals("map3 的 feature 数", 2, cfg.featureCount());

        final ArenaConfig.Feature target = feature(cfg, "map3-target-bismuthinite");
        assertEquals("vein", target.type());
        assertTrue("辉铋矿是真矿", target.isTarget());
        assertEquals("bismuthinite", target.ore());
        assertEquals("dx", 88, target.dx());
        assertEquals("dz", 86, target.dz());
        assertEquals("depth_below_surface 必须是 72（地面仪器全部够不着）", 72, target.depthBelowSurface());
        assertEquals("rx", 9, target.rx());
        assertEquals("ry", 5, target.ry());
        assertEquals("rz", 9, target.rz());
        assertEquals("strike_deg", 0.0, target.strikeDeg(), 1e-9);

        final ArenaConfig.Feature water = feature(cfg, "map3-distractor-water-a");
        assertEquals("water_pocket", water.type());
        assertFalse("水囊是干扰体", water.isTarget());
        assertEquals("dx", 16, water.dx());
        assertEquals("dz", 96, water.dz());
        assertEquals("depth_below_surface", 32, water.depthBelowSurface());
        assertEquals("rx", 6, water.rx());
        assertEquals("ry", 4, water.ry());
        assertEquals("rz", 6, water.rz());
        assertFalse("map3 水囊为淡水（salt=false）", water.salt());

        assertEquals("map3 的露头数量", 2, cfg.outcrops.size());
        for (ArenaConfig.Outcrop o : cfg.outcrops) {
            assertEquals("map3 露头必须是 granite（上位 25 格为花岗岩）", "granite",
                    cfg.zoneAt(o.dx(), o.dz()).rock());
            assertEquals("map3 露头半径", 4, o.radius());
        }
    }

    // ------------------------------------------------------------------
    // map4 —— 多岩区综合考场
    // ------------------------------------------------------------------

    /** map4：三岩区（claystone/slate、marble、granite）+ 3 真矿 + 3 伪异常。 */
    @Test
    public void map4FeaturesMatchSpec() {
        final ArenaConfig cfg = load("map4");

        assertEquals("map4 的起点", 128, cfg.spawn.dx());
        assertEquals("map4 的起点", 128, cfg.spawn.dz());
        assertEquals("map4 应恰好 3 个 zone", 3, cfg.zones.size());

        final ArenaConfig.Zone northWest = cfg.zoneAt(0, 0);
        assertNotNull("map4 西北区应有 zone", northWest);
        assertEquals("map4 西北区母岩", "claystone", northWest.rock());
        assertEquals("map4 西北区下伏母岩", "slate", northWest.bottomRock());
        assertEquals("map4 西北区上下分界深度", 32, northWest.boundaryDepth());

        final ArenaConfig.Zone northEast = cfg.zoneAt(128, 0);
        assertNotNull("map4 东北区应有 zone", northEast);
        assertEquals("map4 东北区母岩", "marble", northEast.rock());
        assertNull("map4 东北区没有下伏岩层", northEast.bottomRock());

        final ArenaConfig.Zone south = cfg.zoneAt(0, 128);
        assertNotNull("map4 南区应有 zone", south);
        assertEquals("map4 南区母岩", "granite", south.rock());
        assertEquals("map4 南区向西延伸至 x=0", "granite", cfg.zoneAt(255, 128).rock());
        assertEquals("map4 分界线两侧岩性", "marble", cfg.zoneAt(128, 127).rock());
        assertEquals("map4 分界线两侧岩性", "granite", cfg.zoneAt(128, 128).rock());

        assertEquals("map4 的 feature 数", 6, cfg.featureCount());
        assertEquals("map4 应恰好 1 个 water_pocket", 1, countType(cfg, "water_pocket"));

        final ArenaConfig.Feature magnetite = feature(cfg, "map4-target-magnetite");
        assertEquals("vein", magnetite.type());
        assertTrue("磁铁矿是真矿", magnetite.isTarget());
        assertEquals("magnetite", magnetite.ore());
        assertEquals("dx", 44, magnetite.dx());
        assertEquals("dz", 30, magnetite.dz());
        assertEquals("depth_below_surface", 28, magnetite.depthBelowSurface());
        assertEquals("rx", 10, magnetite.rx());
        assertEquals("ry", 4, magnetite.ry());
        assertEquals("rz", 6, magnetite.rz());
        assertEquals("strike_deg 必须是 NW-SE 的 135°", 135.0, magnetite.strikeDeg(), 1e-9);
        assertEquals("磁铁矿应位于西北 claystone 区", "claystone",
                cfg.zoneAt(magnetite.dx(), magnetite.dz()).rock());

        final ArenaConfig.Feature malachite = feature(cfg, "map4-target-malachite");
        assertEquals("vein", malachite.type());
        assertTrue("孔雀石是真矿", malachite.isTarget());
        assertEquals("malachite", malachite.ore());
        assertEquals("dx", 204, malachite.dx());
        assertEquals("dz", 30, malachite.dz());
        assertEquals("depth_below_surface 必须是浅层 18", 18, malachite.depthBelowSurface());
        assertEquals("rx", 8, malachite.rx());
        assertEquals("ry", 4, malachite.ry());
        assertEquals("rz", 8, malachite.rz());
        assertEquals("孔雀石应位于东北 marble 区", "marble",
                cfg.zoneAt(malachite.dx(), malachite.dz()).rock());

        final ArenaConfig.Feature bismuthinite = feature(cfg, "map4-target-bismuthinite");
        assertEquals("vein", bismuthinite.type());
        assertTrue("辉铋矿是真矿", bismuthinite.isTarget());
        assertEquals("bismuthinite", bismuthinite.ore());
        assertEquals("dx", 186, bismuthinite.dx());
        assertEquals("dz", 206, bismuthinite.dz());
        assertEquals("depth_below_surface 必须是深层 70", 70, bismuthinite.depthBelowSurface());
        assertEquals("rx", 9, bismuthinite.rx());
        assertEquals("ry", 5, bismuthinite.ry());
        assertEquals("rz", 9, bismuthinite.rz());
        assertEquals("辉铋矿应位于南侧 granite 区", "granite",
                cfg.zoneAt(bismuthinite.dx(), bismuthinite.dz()).rock());

        final ArenaConfig.Feature lignite = feature(cfg, "map4-distractor-lignite");
        assertEquals("vein", lignite.type());
        assertFalse("褐煤是伪异常", lignite.isTarget());
        assertEquals("lignite", lignite.ore());
        assertEquals("dx", 48, lignite.dx());
        assertEquals("dz", 116, lignite.dz());
        assertEquals("depth_below_surface", 16, lignite.depthBelowSurface());
        assertEquals("rx", 9, lignite.rx());
        assertEquals("ry", 3, lignite.ry());
        assertEquals("rz", 9, lignite.rz());

        final ArenaConfig.Feature graphite = feature(cfg, "map4-distractor-graphite");
        assertEquals("vein", graphite.type());
        assertFalse("石墨是伪异常", graphite.isTarget());
        assertEquals("graphite", graphite.ore());
        assertEquals("dx", 196, graphite.dx());
        assertEquals("dz", 118, graphite.dz());
        assertEquals("depth_below_surface", 30, graphite.depthBelowSurface());
        assertEquals("rx", 8, graphite.rx());
        assertEquals("ry", 4, graphite.ry());
        assertEquals("rz", 8, graphite.rz());

        final ArenaConfig.Feature water = feature(cfg, "map4-distractor-water-a");
        assertEquals("water_pocket", water.type());
        assertFalse("水囊是伪异常", water.isTarget());
        assertEquals("dx", 52, water.dx());
        assertEquals("dz", 204, water.dz());
        assertEquals("depth_below_surface", 26, water.depthBelowSurface());
        assertEquals("rx", 7, water.rx());
        assertEquals("ry", 4, water.ry());
        assertEquals("rz", 7, water.rz());
        assertFalse("map4 水囊为淡水（salt=false）", water.salt());

        assertEquals("map4 共 9 处露头（每区 3 处）", 9, cfg.outcrops.size());
        for (ArenaConfig.Zone zone : cfg.zones) {
            int inZone = 0;
            for (ArenaConfig.Outcrop o : cfg.outcrops) {
                final ArenaConfig.Zone hit = cfg.zoneAt(o.dx(), o.dz());
                // 不依赖 zoneAt 返回同一对象引用，只按岩性 + 覆盖范围比对。
                if (hit != null && zone.rock().equals(hit.rock())
                        && hit.xMin() >= zone.xMin() && hit.xMax() <= zone.xMax()
                        && hit.zMin() >= zone.zMin() && hit.zMax() <= zone.zMax()) {
                    inZone++;
                }
            }
            assertEquals("map4 每个岩区应有 3 处露头（zone " + zone.rock() + "）", 3, inZone);
        }
    }

    // ------------------------------------------------------------------
    // 同图异常源间距 / 矿种合法性 / 露头落区
    // ------------------------------------------------------------------

    /** §5.2：同图任意两个异常源中心水平距离 ≥ 70（map4 ≥ 85）。 */
    @Test
    public void sameMapFeatureCentresAreFarApart() {
        for (int index = 1; index <= 4; index++) {
            final String name = "map" + index;
            final ArenaConfig cfg = load(name);
            final double required = index == 4 ? MIN_CENTRE_DISTANCE_MAP4 : MIN_CENTRE_DISTANCE_SMALL;
            final List<ArenaConfig.Feature> features = cfg.features;

            assertTrue(name + " 至少要有 2 个异常源才能考察间距", features.size() >= 2);
            for (int i = 0; i < features.size(); i++) {
                for (int j = i + 1; j < features.size(); j++) {
                    final ArenaConfig.Feature a = features.get(i);
                    final ArenaConfig.Feature b = features.get(j);
                    final double distance = horizontalDistance(a, b);
                    assertTrue(String.format(Locale.ROOT,
                                    "%s 的 %s(%.0f,%.0f) 与 %s(%.0f,%.0f) 中心水平距离 %.3f 格 < 要求的 %.1f 格",
                                    name, a.id(), (double) a.dx(), (double) a.dz(),
                                    b.id(), (double) b.dx(), (double) b.dz(), distance, required),
                            distance >= required);
                }
            }
        }
    }

    /** 所有 vein 的 ore 都必须被 OreTaxonomy 收录（否则运行时会拼出不存在的方块 id）。 */
    @Test
    public void allVeinOresExistInOreTaxonomy() {
        for (int index = 1; index <= 4; index++) {
            final String name = "map" + index;
            final ArenaConfig cfg = load(name);
            for (ArenaConfig.Feature f : cfg.features) {
                if (!"vein".equals(f.type())) {
                    continue;
                }
                assertNotNull(name + " 的矿脉 " + f.id() + " 缺少 ore", f.ore());
                assertTrue(name + " 的矿脉 " + f.id() + " 矿种 '" + f.ore() + "' 不在 OreTaxonomy 中",
                        OreTaxonomy.contains(f.ore()));
            }
        }
    }

    /** 露头：数量、半径、必须落在某个 zone 内，且与异常源中心保持 ≥ 12 格净距。 */
    @Test
    public void outcropsMatchSpec() {
        final int[] expected = {6, 3, 2, 9};
        for (int index = 1; index <= 4; index++) {
            final String name = "map" + index;
            final ArenaConfig cfg = load(name);

            assertEquals(name + " 的露头数量", expected[index - 1], cfg.outcrops.size());
            for (ArenaConfig.Outcrop o : cfg.outcrops) {
                assertInBounds(name + " 的露头", o.dx(), o.dz(), cfg.sizeX, cfg.sizeZ);
                assertEquals(name + " 露头 (" + o.dx() + "," + o.dz() + ") 半径", 4, o.radius());
                assertNotNull(name + " 露头 (" + o.dx() + "," + o.dz() + ") 未落在任何岩区，recon_pick 无岩可敲",
                        cfg.zoneAt(o.dx(), o.dz()));
                for (ArenaConfig.Feature f : cfg.features) {
                    final double distance = Math.hypot((double) o.dx() - f.dx(), (double) o.dz() - f.dz());
                    assertTrue(String.format(Locale.ROOT,
                                    "%s 露头 (%d,%d) 距异常源 %s 仅 %.2f 格（要求 ≥ %.0f 格，避免露头直接暴露矿核）",
                                    name, o.dx(), o.dz(), f.id(), distance, MIN_OUTCROP_CLEARANCE),
                            distance >= MIN_OUTCROP_CLEARANCE);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 从 classpath 读取内置配置并解析。 */
    private static ArenaConfig load(String name) {
        final String path = RESOURCE_DIR + name + ".json";
        try (InputStream in = ArenaBuiltinMapsTest.class.getResourceAsStream(path)) {
            assertNotNull("classpath 上缺少内置靶场配置：" + path, in);
            final String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            final ArenaConfig cfg = ArenaConfig.parse(json, name);
            assertNotNull("ArenaConfig.parse 对 " + name + " 返回 null", cfg);
            return cfg;
        } catch (IOException e) {
            throw new AssertionError("读取内置靶场配置失败：" + path, e);
        }
    }

    /** 按 id 取 feature；缺失即判失败。 */
    private static ArenaConfig.Feature feature(ArenaConfig cfg, String id) {
        for (ArenaConfig.Feature f : cfg.features) {
            if (id.equals(f.id())) {
                return f;
            }
        }
        fail("找不到 feature id=" + id + "，实际列表=" + ids(cfg.features));
        return null;   // 不可达
    }

    private static List<String> ids(List<ArenaConfig.Feature> features) {
        final List<String> out = new ArrayList<>(features.size());
        for (ArenaConfig.Feature f : features) {
            out.add(f.id());
        }
        return out;
    }

    private static int countType(ArenaConfig cfg, String type) {
        int count = 0;
        for (ArenaConfig.Feature f : cfg.features) {
            if (type.equals(f.type())) {
                count++;
            }
        }
        return count;
    }

    private static double horizontalDistance(ArenaConfig.Feature a, ArenaConfig.Feature b) {
        return Math.hypot((double) a.dx() - b.dx(), (double) a.dz() - b.dz());
    }

    private static void assertInBounds(String what, int dx, int dz, int sizeX, int sizeZ) {
        assertTrue(what + " 的 dx=" + dx + " 越界（size_x=" + sizeX + "）", dx >= 0 && dx < sizeX);
        assertTrue(what + " 的 dz=" + dz + " 越界（size_z=" + sizeZ + "）", dz >= 0 && dz < sizeZ);
    }
}
