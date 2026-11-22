package com.tfcie.prospecting.common.survey;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** 探查地图数据层：冲突规则、过滤、边界（阶段 7 追加需求）。 */
public class SurveyMapTest {

    private static final int DARK = 0xFF102030;    // rgb 权重 0x10+0x20+0x30 = 96
    private static final int BRIGHT = 0xFFF0F0F0;  // rgb 权重 720

    private static SurveyMap map() {
        return new SurveyMap(0, 0, 7);   // 7×7 区块 = 112×112
    }

    @Test
    public void sizeFollowsChunkCount() {
        final SurveyMap map = map();
        assertEquals(112, map.size());
        assertEquals(7, map.chunks());
    }

    @Test
    public void sameCellWithMultipleDataPicksHighestRgbWeight() {
        final SurveyMap map = map();
        // 先加暗的、后加亮的：结果必须与写入顺序无关
        map.add(new SurveyPoint(10, 20, "native_copper", DARK));
        map.add(new SurveyPoint(10, 20, "native_gold", BRIGHT));

        final SurveyPoint shown = map.displayAt(10, 20, null);
        assertEquals(BRIGHT, shown.color());
        assertEquals("native_gold", shown.key());

        // 反向写入同样结果（确定性）
        final SurveyMap reversed = map();
        reversed.add(new SurveyPoint(10, 20, "native_gold", BRIGHT));
        reversed.add(new SurveyPoint(10, 20, "native_copper", DARK));
        assertEquals(BRIGHT, reversed.displayAt(10, 20, null).color());
    }

    @Test
    public void filterRestrictsToRequestedKey() {
        final SurveyMap map = map();
        map.add(new SurveyPoint(5, 5, "native_copper", DARK));
        map.add(new SurveyPoint(5, 5, "native_gold", BRIGHT));

        // 过滤到暗的那个：即使它权重更低也应显示它
        assertEquals(DARK, map.displayAt(5, 5, "native_copper").color());
        assertEquals(BRIGHT, map.displayAt(5, 5, "native_gold").color());
        // 过滤到一个不存在的键 → 无
        assertNull(map.displayAt(5, 5, "diamond"));
    }

    @Test
    public void duplicateKeyInSameCellIsIgnored() {
        final SurveyMap map = map();
        map.add(new SurveyPoint(1, 1, "native_copper", DARK));
        map.add(new SurveyPoint(1, 1, "native_copper", DARK));
        assertEquals(1, map.pointCount());
    }

    @Test
    public void pointsOutsideMapAreRejected() {
        final SurveyMap map = map();
        map.add(new SurveyPoint(-1, 0, "native_copper", DARK));
        map.add(new SurveyPoint(0, -1, "native_copper", DARK));
        map.add(new SurveyPoint(112, 0, "native_copper", DARK));
        map.add(new SurveyPoint(0, 112, "native_copper", DARK));
        assertEquals(0, map.pointCount());
        assertEquals(0, map.markedCells());

        // 边界内则接受
        map.add(new SurveyPoint(0, 0, "native_copper", DARK));
        map.add(new SurveyPoint(111, 111, "native_copper", DARK));
        assertEquals(2, map.pointCount());
    }

    @Test
    public void containsMatchesMapBounds() {
        final SurveyMap map = map();
        assertTrue(map.contains(0, 0));
        assertTrue(map.contains(111, 111));
        assertFalse(map.contains(112, 111));
        assertFalse(map.contains(-1, 0));
    }

    @Test
    public void keysAndCountsReflectAddedPoints() {
        final SurveyMap map = map();
        map.add(new SurveyPoint(1, 1, "native_copper", DARK));
        map.add(new SurveyPoint(2, 2, "native_copper", DARK));
        map.add(new SurveyPoint(3, 3, "native_gold", BRIGHT));

        assertEquals(2, map.keys().size());
        final Map<String, Integer> counts = map.counts();
        assertEquals(2, counts.get("native_copper").intValue());
        assertEquals(1, counts.get("native_gold").intValue());
        assertEquals(3, map.pointCount());
        assertEquals(3, map.markedCells());
    }

    @Test
    public void emptyCellReturnsNothing() {
        assertNull(map().displayAt(50, 50, null));
        assertTrue(map().at(50, 50).isEmpty());
        assertEquals(List.of(), map().at(50, 50));
    }

    @Test
    public void pointsAreReturnedForTransport() {
        final SurveyMap map = map();
        final SurveyPoint point = new SurveyPoint(4, 6, "graphite", DARK);
        map.add(point);
        assertEquals(1, map.points().size());
        assertSame(point, map.points().get(0));
    }

    // ---------------- 探测点（GUI 副标题） ----------------

    @Test
    public void probePointIsPreservedWhenGiven() {
        final SurveyMap map = new SurveyMap(-770, 560, 9, -698, 631);
        assertEquals(-698, map.probeX());
        assertEquals(631, map.probeZ());
        // 探测点与地图左上角是两个不同的概念，不能混为一谈
        assertEquals(-770, map.originX());
        assertEquals(560, map.originZ());
    }

    @Test
    public void probePointFallsBackToMapCentre() {
        // 三参构造只给网格信息，副标题退化为地图中心
        final SurveyMap map = new SurveyMap(0, 0, 9);
        assertEquals(0 + 9 * 8, map.probeX());
        assertEquals(0 + 9 * 8, map.probeZ());
    }
}
