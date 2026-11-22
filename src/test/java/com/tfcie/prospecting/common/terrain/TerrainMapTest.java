package com.tfcie.prospecting.common.terrain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 地形数据层：尺寸推导、哨兵值、越界写入、索引布局。
 *
 * <p>只测生产代码真正用到的接口。此前这里还测过一组世界坐标访问器与统计方法，
 * 但那些方法没有任何生产调用者 —— 等于测试在测自己，已随方法一并移除。
 */
public class TerrainMapTest {

    private static final int CHUNKS = 15;
    private static final int SIZE = CHUNKS * 16;   // 240

    private static TerrainMap map() {
        return new TerrainMap(0, 0, CHUNKS);
    }

    @Test
    public void sizeFollowsChunkCount() {
        assertEquals(SIZE, map().size());
        assertEquals(CHUNKS, map().chunks());
    }

    @Test
    public void originIsPreserved() {
        final TerrainMap map = new TerrainMap(-320, 480, 3);
        assertEquals(-320, map.originX());
        assertEquals(480, map.originZ());
        assertEquals(48, map.size());
    }

    @Test
    public void freshMapIsEntirelyNoData() {
        final TerrainMap map = map();
        for (int i = 0; i < SIZE * SIZE; i++) {
            assertEquals(TerrainMap.NO_DATA, map.heightAtIndex(i));
            assertEquals(0, map.colorAtIndex(i));
        }
    }

    @Test
    public void setAndReadBack() {
        final TerrainMap map = map();
        map.setAt(10, 20, 74, 0xFF55AA33);
        final int index = 20 * SIZE + 10;
        assertEquals(74, map.heightAtIndex(index));
        assertEquals(0xFF55AA33, map.colorAtIndex(index));
    }

    @Test
    public void negativeHeightsSurviveShortRoundTrip() {
        final TerrainMap map = map();
        map.setAt(3, 3, -48, 0xFFFFFFFF);
        assertEquals(-48, map.heightAtIndex(3 * SIZE + 3));
    }

    @Test
    public void outOfBoundsWritesAreIgnored() {
        final TerrainMap map = map();
        map.setAt(-1, 0, 64, 0xFF000000);
        map.setAt(0, -1, 64, 0xFF000000);
        map.setAt(SIZE, 0, 64, 0xFF000000);
        map.setAt(0, SIZE, 64, 0xFF000000);
        for (int i = 0; i < SIZE * SIZE; i++) {
            assertEquals(TerrainMap.NO_DATA, map.heightAtIndex(i));
        }
    }

    /**
     * 索引是<b>行主序</b>：{@code index = localZ * size + localX}。
     *
     * <p>渲染器取「北侧邻格」用的正是 {@code index - size}，
     * 这条布局一旦改动，坡度浮雕与等高线都会静默错位，故在此钉死。
     */
    @Test
    public void indexLayoutIsRowMajor() {
        final TerrainMap map = map();
        map.setAt(5, 7, 70, 0xFF010203);
        assertEquals(70, map.heightAtIndex(7 * SIZE + 5));
        // 北邻（localZ - 1）就是 index - size
        map.setAt(5, 6, 66, 0xFF040506);
        assertEquals(66, map.heightAtIndex(7 * SIZE + 5 - SIZE));
    }
}
