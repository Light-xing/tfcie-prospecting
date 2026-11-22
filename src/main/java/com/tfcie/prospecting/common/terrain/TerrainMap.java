package com.tfcie.prospecting.common.terrain;

/**
 * 一次遥感扫描的地形数据：<b>地表高度 + 地表颜色</b>，按方块分辨率。
 *
 * <h2>为什么只需要两项</h2>
 * 三个栏目里，栏一（鸟瞰）渲染的是<b>真实世界本身</b>，不需要任何扫描数据；
 * 栏二（平面地图）要的是地表颜色 + 坡度明暗；栏三（等高线）要的是高度。
 * 因此一次扫描只取这两项即可 —— 比探查地图（要逐格垂向扫描）轻两个数量级。
 *
 * <h2>缺失数据的表示</h2>
 * 未加载的区块不伪造地形：该列高度记为 {@link #NO_DATA}，颜色记为 0，
 * 由各栏目渲染时留空。这是刻意的 —— 假地形比空白更有害。
 *
 * <h2>为什么只保留相对索引访问</h2>
 * 所有访问都发生在「按区块批量写入的扫描器」与「逐像素渲染循环」里，
 * 两者都已知自己在图内的位置，用相对索引可以省掉每格一次边界判断。
 * 早先还提供过一组世界坐标访问器（{@code heightAt/colorAt/contains/hasData}）
 * 与两个统计方法（{@code dataColumns/heightRange}），但生产代码从未调用过，
 * 只有测试在测自己，已删除。
 */
public final class TerrainMap {

    /** 无数据的哨兵高度。 */
    public static final short NO_DATA = Short.MIN_VALUE;

    private final int originX;
    private final int originZ;
    private final int chunks;
    private final int size;

    private final short[] heights;
    private final int[] colors;

    public TerrainMap(int originX, int originZ, int chunks) {
        this.originX = originX;
        this.originZ = originZ;
        this.chunks = chunks;
        this.size = chunks * 16;
        this.heights = new short[size * size];
        this.colors = new int[size * size];
        java.util.Arrays.fill(this.heights, NO_DATA);
    }

    public int originX() { return originX; }

    public int originZ() { return originZ; }

    public int chunks() { return chunks; }

    /** 边长（方块）。15×15 区块 → 240。 */
    public int size() { return size; }

    /** 相对索引写入（供扫描器按区块批量填充，避免反复做边界判断）。越界时静默忽略。 */
    public void setAt(int localX, int localZ, int height, int color) {
        if (localX < 0 || localX >= size || localZ < 0 || localZ >= size) {
            return;
        }
        final int index = localZ * size + localX;
        heights[index] = (short) height;
        colors[index] = color;
    }

    /** 相对索引读取（供渲染循环使用）。 */
    public int heightAtIndex(int index) {
        return heights[index];
    }

    public int colorAtIndex(int index) {
        return colors[index];
    }
}
