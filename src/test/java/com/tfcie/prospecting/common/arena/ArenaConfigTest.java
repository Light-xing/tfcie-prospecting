package com.tfcie.prospecting.common.arena;

import org.junit.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link ArenaConfig} 的纯 JUnit 单测：宽容解析、缺省值、分区/上下层查询、结构体检。
 *
 * <p>这些用例<b>不启动 Forge / Minecraft</b>，全部以内联 JSON 文本驱动，因此能在
 * {@code gradlew test} 的纯 JVM 环境里跑，是「改 JSON 调参」这条工作流的第一道护栏。
 */
public class ArenaConfigTest {

    /** 契约 §1.1 的完整字段样例：两分区（含上下层母岩）、两露头、三种注入体。 */
    private static final String FULL_JSON = """
            {
              "name": "map-test",
              "size_x": 32, "size_z": 32, "base_surface_y": 80,
              "fill_underground_caves": true,
              "dirt_block": "tfc:dirt/silt", "grass_block": "tfc:grass/silt",
              "spawn": { "dx": 5, "dz": 7 },
              "rock_zones": [
                { "x_min": 0, "x_max": 15, "z_min": 0, "z_max": 31, "rock": "shale" },
                { "x_min": 16, "x_max": 31, "z_min": 0, "z_max": 31, "rock": "granite",
                  "bottom_rock": "gabbro", "boundary_depth": 25 }
              ],
              "outcrops": [
                { "dx": 3, "dz": 4, "radius": 2, "rock": "shale" },
                { "dx": 20, "dz": 9 }
              ],
              "features": [
                { "type": "vein", "id": "target-malachite", "ore": "malachite", "is_target": true,
                  "dx": 20, "dz": 12, "depth_below_surface": 18,
                  "rx": 6, "ry": 4, "rz": 7, "strike_deg": 45 },
                { "type": "water_pocket", "dx": 6, "dz": 6, "depth_below_surface": 26, "salt": true },
                { "type": "surface_metal", "dx": 2, "dz": 30 }
              ]
            }
            """;

    // ---------------- 解析 ----------------

    @Test
    public void parsesEveryDeclaredField() {
        final ArenaConfig config = ArenaConfig.parse(FULL_JSON, "fallback");

        assertEquals("map-test", config.name);
        assertEquals(32, config.sizeX);
        assertEquals(32, config.sizeZ);
        assertEquals(80, config.baseSurfaceY);
        assertTrue(config.fillUndergroundCaves);
        assertEquals("tfc:dirt/silt", config.dirtBlock);
        assertEquals("tfc:grass/silt", config.grassBlock);
        assertEquals(new ArenaConfig.Spawn(5, 7), config.spawn);
    }

    @Test
    public void parsesZonesWithOptionalBottomRock() {
        final ArenaConfig config = ArenaConfig.parse(FULL_JSON, "fallback");

        assertEquals(2, config.zones.size());

        final ArenaConfig.Zone west = config.zones.get(0);
        assertEquals(0, west.xMin());
        assertEquals(15, west.xMax());
        assertEquals(31, west.zMax());
        assertEquals("shale", west.rock());
        assertNull("没写 bottom_rock 就是整列同岩", west.bottomRock());
        assertEquals("没写 boundary_depth 默认 0", 0, west.boundaryDepth());

        final ArenaConfig.Zone east = config.zones.get(1);
        assertEquals("granite", east.rock());
        assertEquals("gabbro", east.bottomRock());
        assertEquals(25, east.boundaryDepth());
    }

    @Test
    public void parsesOutcropsWithDefaults() {
        final ArenaConfig config = ArenaConfig.parse(FULL_JSON, "fallback");

        assertEquals(2, config.outcrops.size());
        assertEquals(new ArenaConfig.Outcrop(3, 4, 2, "shale"), config.outcrops.get(0));
        // 缺省 radius = 4、rock = null（运行时回落到 rockAt 判定的母岩）
        assertEquals(new ArenaConfig.Outcrop(20, 9, 4, null), config.outcrops.get(1));
        assertNull(config.outcrops.get(1).rock());
    }

    @Test
    public void parsesThreeFeatureKinds() {
        final ArenaConfig config = ArenaConfig.parse(FULL_JSON, "fallback");

        assertEquals(3, config.features.size());
        assertEquals(3, config.featureCount());

        final ArenaConfig.Feature vein = config.features.get(0);
        assertEquals("vein", vein.type());
        assertEquals("target-malachite", vein.id());
        assertEquals("malachite", vein.ore());
        assertTrue(vein.isTarget());
        assertEquals(20, vein.dx());
        assertEquals(18, vein.depthBelowSurface());
        assertEquals(6, vein.rx());
        assertEquals(4, vein.ry());
        assertEquals(7, vein.rz());
        assertEquals(45.0, vein.strikeDeg(), 0.0);
        assertFalse(vein.salt());
        assertNull(vein.hostRock());

        final ArenaConfig.Feature water = config.features.get(1);
        assertEquals("water_pocket", water.type());
        assertEquals("缺省 id = 类型-序号", "water_pocket-2", water.id());
        assertNull(water.ore());
        assertFalse(water.isTarget());
        assertTrue(water.salt());
        assertEquals(26, water.depthBelowSurface());
        assertEquals("椭球半径缺省 8/4/8", 8, water.rx());
        assertEquals(4, water.ry());
        assertEquals(8, water.rz());
        assertEquals("缺省走向 0", 0.0, water.strikeDeg(), 0.0);

        final ArenaConfig.Feature metal = config.features.get(2);
        assertEquals("surface_metal", metal.type());
        assertEquals("surface_metal-3", metal.id());
        assertEquals("缺省埋深 20", 20, metal.depthBelowSurface());
    }

    @Test
    public void appliesAllDocumentedDefaultsForMinimalJson() {
        final ArenaConfig config = ArenaConfig.parse("""
                {
                  "rock_zones": [ { "x_min": 0, "x_max": 127, "z_min": 0, "z_max": 127, "rock": "gneiss" } ],
                  "features": [ { "type": "surface_metal", "dx": 1, "dz": 2 } ]
                }
                """, "map-default");

        assertEquals("缺 name 用 fallbackName", "map-default", config.name);
        assertEquals(128, config.sizeX);
        assertEquals(128, config.sizeZ);
        assertEquals(72, config.baseSurfaceY);
        assertFalse(config.fillUndergroundCaves);
        assertEquals("默认泥土", "tfc:dirt/loam", config.dirtBlock);
        assertEquals("默认草皮", "tfc:grass/loam", config.grassBlock);
        assertEquals("缺省起点 = 地图中心", new ArenaConfig.Spawn(64, 64), config.spawn);
        assertTrue(config.outcrops.isEmpty());
        assertEquals(1, config.features.size());
        assertEquals("surface_metal-1", config.features.get(0).id());

        assertTrue("这份最小配置应当通过结构体检: " + config.structuralErrors(),
                config.structuralErrors().isEmpty());
    }

    @Test
    public void parseReaderMatchesParseString() {
        final ArenaConfig fromString = ArenaConfig.parse(FULL_JSON, "fallback");
        final ArenaConfig fromReader = ArenaConfig.parse(new StringReader(FULL_JSON), "fallback");

        assertEquals(fromString.name, fromReader.name);
        assertEquals(fromString.sizeX, fromReader.sizeX);
        assertEquals(fromString.zones, fromReader.zones);
        assertEquals(fromString.outcrops, fromReader.outcrops);
        assertEquals(fromString.features, fromReader.features);
        assertEquals(fromString.spawn, fromReader.spawn);
    }

    @Test
    public void rejectsMalformedJsonWithReadableReason() {
        final IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> ArenaConfig.parse("{not json", "x"));
        assertTrue("消息要能指认是 JSON 语法问题: " + e.getMessage(),
                e.getMessage().contains("JSON"));

        assertThrows(IllegalArgumentException.class, () -> ArenaConfig.parse("", "x"));
        assertThrows(IllegalArgumentException.class, () -> ArenaConfig.parse("[]", "x"));
    }

    @Test
    public void rejectsMissingRequiredFields() {
        // zone 缺 rock（契约标为必填）
        assertThrows(IllegalArgumentException.class, () -> ArenaConfig.parse("""
                { "rock_zones": [ { "x_min": 0, "x_max": 9, "z_min": 0, "z_max": 9 } ] }
                """, "x"));

        // outcrop 缺 dz
        assertThrows(IllegalArgumentException.class, () -> ArenaConfig.parse("""
                { "rock_zones": [ { "x_min": 0, "x_max": 9, "z_min": 0, "z_max": 9, "rock": "shale" } ],
                  "outcrops": [ { "dx": 1 } ] }
                """, "x"));

        // feature 缺 type
        assertThrows(IllegalArgumentException.class, () -> ArenaConfig.parse("""
                { "rock_zones": [ { "x_min": 0, "x_max": 9, "z_min": 0, "z_max": 9, "rock": "shale" } ],
                  "features": [ { "dx": 1, "dz": 1 } ] }
                """, "x"));
    }

    // ---------------- 分区与母岩查询 ----------------

    @Test
    public void zoneAtReturnsFirstMatchAndNullWhenUncovered() {
        final ArenaConfig config = ArenaConfig.parse("""
                {
                  "size_x": 10, "size_z": 10, "spawn": { "dx": 0, "dz": 0 },
                  "rock_zones": [
                    { "x_min": 0, "x_max": 9, "z_min": 0, "z_max": 9, "rock": "shale" },
                    { "x_min": 0, "x_max": 4, "z_min": 0, "z_max": 4, "rock": "marble" }
                  ]
                }
                """, "x");

        // 重叠区按「首个命中」裁决：shale 写在前面，所以 (2,2) 仍是 shale
        assertEquals("shale", config.zoneAt(2, 2).rock());
        assertEquals("shale", config.zoneAt(5, 5).rock());
        assertNull(config.zoneAt(-1, 0));
        assertNull(config.zoneAt(10, 10));

        final ArenaConfig uncovered = ArenaConfig.parse("""
                {
                  "size_x": 10, "size_z": 10, "spawn": { "dx": 0, "dz": 0 },
                  "rock_zones": [ { "x_min": 0, "x_max": 4, "z_min": 0, "z_max": 4, "rock": "shale" } ]
                }
                """, "x");
        assertNull(uncovered.zoneAt(5, 5));
    }

    @Test
    public void rockAtSwitchesAtBoundaryDepth() {
        final ArenaConfig config = ArenaConfig.parse("""
                {
                  "size_x": 16, "size_z": 16, "spawn": { "dx": 0, "dz": 0 },
                  "rock_zones": [
                    { "x_min": 0, "x_max": 15, "z_min": 0, "z_max": 15, "rock": "granite",
                      "bottom_rock": "gabbro", "boundary_depth": 25 }
                  ]
                }
                """, "x");

        final int surfaceY = 72;
        // 距地表 0 格、恰好 25 格 → 上层 granite（<= boundary_depth）
        assertEquals("granite", config.rockAt(0, 0, surfaceY, surfaceY));
        assertEquals("granite", config.rockAt(0, 0, surfaceY - 25, surfaceY));
        assertEquals("granite", config.rockAt(0, 0, surfaceY + 3, surfaceY));
        // 26 格及更深 → 下层 gabbro
        assertEquals("gabbro", config.rockAt(0, 0, surfaceY - 26, surfaceY));
        assertEquals("gabbro", config.rockAt(0, 0, -60, surfaceY));
        // 未覆盖的列 → null（调用方决定是报错还是跳过）
        assertNull(config.rockAt(100, 0, surfaceY, surfaceY));
    }

    @Test
    public void rockAtWithoutBottomRockIsUniform() {
        final ArenaConfig config = ArenaConfig.parse("""
                {
                  "size_x": 8, "size_z": 8, "spawn": { "dx": 0, "dz": 0 },
                  "rock_zones": [ { "x_min": 0, "x_max": 7, "z_min": 0, "z_max": 7, "rock": "marble" } ]
                }
                """, "x");

        for (int y : new int[]{100, 72, 40, -60}) {
            assertEquals("marble", config.rockAt(3, 4, y, 72));
        }
    }

    @Test
    public void rockAtWithZeroBoundaryDepthOnlyKeepsSurfaceLayer() {
        final ArenaConfig config = ArenaConfig.parse("""
                {
                  "size_x": 8, "size_z": 8, "spawn": { "dx": 0, "dz": 0 },
                  "rock_zones": [
                    { "x_min": 0, "x_max": 7, "z_min": 0, "z_max": 7,
                      "rock": "claystone", "bottom_rock": "slate", "boundary_depth": 0 }
                  ]
                }
                """, "x");

        assertEquals("claystone", config.rockAt(0, 0, 72, 72));
        assertEquals("slate", config.rockAt(0, 0, 71, 72));
    }

    // ---------------- 结构体检 ----------------

    @Test
    public void structuralErrorsIsEmptyForValidConfig() {
        final ArenaConfig config = ArenaConfig.parse(FULL_JSON, "fallback");
        assertEquals("合法配置不应有任何结构错误: " + config.structuralErrors(),
                List.of(), config.structuralErrors());
    }

    @Test
    public void structuralErrorsCatchesUncoveredColumns() {
        // 只覆盖西半区，东半区 64 列 × 128 行没有母岩
        final ArenaConfig config = ArenaConfig.parse("""
                {
                  "size_x": 128, "size_z": 128,
                  "rock_zones": [ { "x_min": 0, "x_max": 63, "z_min": 0, "z_max": 127, "rock": "shale" } ]
                }
                """, "x");

        final List<String> errors = config.structuralErrors();
        assertTrue(errors.toString(), hasError(errors, "未被任何 rock_zone 覆盖"));
        assertTrue(errors.toString(), hasError(errors, "(64, 0)"));
    }

    @Test
    public void structuralErrorsCatchesOutOfBoundsCoordinates() {
        final ArenaConfig config = ArenaConfig.parse("""
                {
                  "size_x": 32, "size_z": 32, "spawn": { "dx": 500, "dz": 0 },
                  "rock_zones": [ { "x_min": 0, "x_max": 31, "z_min": 0, "z_max": 31, "rock": "shale" } ],
                  "outcrops": [ { "dx": 200, "dz": 3 } ],
                  "features": [ { "type": "surface_metal", "dx": -1, "dz": 3 } ]
                }
                """, "x");

        final List<String> errors = config.structuralErrors();
        assertTrue(errors.toString(), hasError(errors, "outcrops[0] 坐标越界"));
        assertTrue(errors.toString(), hasError(errors, "features[0] 坐标越界"));
        assertTrue(errors.toString(), hasError(errors, "spawn 越界"));
    }

    @Test
    public void structuralErrorsCatchesUnknownRockNames() {
        final ArenaConfig config = ArenaConfig.parse("""
                {
                  "size_x": 8, "size_z": 8, "spawn": { "dx": 0, "dz": 0 },
                  "rock_zones": [
                    { "x_min": 0, "x_max": 7, "z_min": 0, "z_max": 7,
                      "rock": "obsidian", "bottom_rock": "unobtainium" }
                  ]
                }
                """, "x");

        final List<String> errors = config.structuralErrors();
        assertTrue(errors.toString(), hasError(errors, "rock_zones[0].rock 不是 TFC 岩石名"));
        assertTrue(errors.toString(), hasError(errors, "rock_zones[0].bottom_rock 不是 TFC 岩石名"));
    }

    @Test
    public void structuralErrorsCatchesUnknownOreId() {
        final ArenaConfig config = ArenaConfig.parse("""
                {
                  "size_x": 8, "size_z": 8, "spawn": { "dx": 0, "dz": 0 },
                  "rock_zones": [ { "x_min": 0, "x_max": 7, "z_min": 0, "z_max": 7, "rock": "shale" } ],
                  "features": [ { "type": "vein", "ore": "unobtainium", "dx": 1, "dz": 1 } ]
                }
                """, "x");

        assertTrue(config.structuralErrors().toString(),
                hasError(config.structuralErrors(), "features[0].ore 不是已知矿种"));
    }

    @Test
    public void structuralErrorsCatchesVeinWithoutOre() {
        final ArenaConfig config = ArenaConfig.parse("""
                {
                  "size_x": 8, "size_z": 8, "spawn": { "dx": 0, "dz": 0 },
                  "rock_zones": [ { "x_min": 0, "x_max": 7, "z_min": 0, "z_max": 7, "rock": "shale" } ],
                  "features": [ { "type": "vein", "dx": 1, "dz": 1 } ]
                }
                """, "x");

        assertTrue(config.structuralErrors().toString(),
                hasError(config.structuralErrors(), "必须填写 ore"));
    }

    @Test
    public void structuralErrorsCatchesUnknownFeatureType() {
        final ArenaConfig config = ArenaConfig.parse("""
                {
                  "size_x": 8, "size_z": 8, "spawn": { "dx": 0, "dz": 0 },
                  "rock_zones": [ { "x_min": 0, "x_max": 7, "z_min": 0, "z_max": 7, "rock": "shale" } ],
                  "features": [ { "type": "portal", "dx": 1, "dz": 1 } ]
                }
                """, "x");

        assertTrue(config.structuralErrors().toString(),
                hasError(config.structuralErrors(), "type 未知"));
    }

    @Test
    public void structuralErrorsCatchesBadRadiiAndDepth() {
        final ArenaConfig config = ArenaConfig.parse("""
                {
                  "size_x": 8, "size_z": 8, "spawn": { "dx": 0, "dz": 0 },
                  "rock_zones": [ { "x_min": 0, "x_max": 7, "z_min": 0, "z_max": 7, "rock": "shale" } ],
                  "features": [
                    { "type": "water_pocket", "dx": 1, "dz": 1, "rx": 0, "ry": 4, "rz": 7,
                      "depth_below_surface": -3 }
                  ]
                }
                """, "x");

        final List<String> errors = config.structuralErrors();
        assertTrue(errors.toString(), hasError(errors, "椭球半径必须都 > 0"));
        assertTrue(errors.toString(), hasError(errors, "depth_below_surface 必须 >= 0"));
    }

    @Test
    public void structuralErrorsCatchesEmptyZonesAndBadSize() {
        final ArenaConfig config = ArenaConfig.parse("""
                { "size_x": 0, "size_z": -5 }
                """, "x");

        final List<String> errors = config.structuralErrors();
        assertTrue(errors.toString(), hasError(errors, "size_x 必须 > 0"));
        assertTrue(errors.toString(), hasError(errors, "size_z 必须 > 0"));
        assertTrue(errors.toString(), hasError(errors, "rock_zones 不能为空"));
    }

    @Test
    public void structuralErrorsCatchesSpawnOutsideArena() {
        final ArenaConfig config = ArenaConfig.parse("""
                {
                  "size_x": 16, "size_z": 16, "spawn": { "dx": 16, "dz": 16 },
                  "rock_zones": [ { "x_min": 0, "x_max": 15, "z_min": 0, "z_max": 15, "rock": "marble" } ]
                }
                """, "x");

        assertTrue(config.structuralErrors().toString(),
                hasError(config.structuralErrors(), "spawn 越界"));
    }

    @Test
    public void helperPredicatesExposeContractSets() {
        assertEquals(20, ArenaConfig.TFC_ROCKS.size());
        assertTrue(ArenaConfig.isKnownRock("marble"));
        assertTrue(ArenaConfig.isKnownRock("gneiss"));
        assertFalse(ArenaConfig.isKnownRock("obsidian"));
        assertFalse(ArenaConfig.isKnownRock(null));

        assertEquals(3, ArenaConfig.FEATURE_TYPES.size());
        assertTrue(ArenaConfig.isKnownFeatureType("vein"));
        assertTrue(ArenaConfig.isKnownFeatureType("water_pocket"));
        assertTrue(ArenaConfig.isKnownFeatureType("surface_metal"));
        assertFalse(ArenaConfig.isKnownFeatureType("portal"));
        assertFalse(ArenaConfig.isKnownFeatureType(null));

        assertNotNull(ArenaConfig.parse(FULL_JSON, "x"));
    }

    /** 错误表里是否有一条包含给定片段（避免测试绑死完整中文文案）。 */
    private static boolean hasError(List<String> errors, String fragment) {
        return errors.stream().anyMatch(error -> error.contains(fragment));
    }
}
