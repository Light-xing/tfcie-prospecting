package com.tfcie.prospecting.common.survey;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次探查的结果地图（平面 xOz）。
 *
 * <h2>每格可挂多条数据</h2>
 * 与 GT 的 {@code data[x][z] : T[]} 同构：同一坐标可能同时存在多种矿（或多种异常类别）。
 * 保留全部条目而不在写入时淘汰，是为了让「点击图例切换显示」仍能看到被盖住的那一类。
 *
 * <h2>冲突规则</h2>
 * 绘制时每格只显示一个点：按 {@link SurveyPoint#rgbWeight()}（RGB 分量之和）取<b>最大</b>者。
 * 这是用户指定的规则，好处是**确定性**——同一格无论扫描顺序如何，结果都一致。
 */
public final class SurveyMap {

    /** 地图左下角（最小 X/Z）对应的世界方块坐标。 */
    private final int originX;
    private final int originZ;
    /** 边长（方块）——默认 9 区块 × 16 = 144。 */
    private final int size;
    /** 边长（区块）。 */
    private final int chunks;
    /** 发起本次探查的探测点（玩家右键的那个方块）的 X/Z，用于界面副标题。 */
    private final int probeX;
    private final int probeZ;

    private final Map<Long, List<SurveyPoint>> cells = new HashMap<>();

    /**
     * 不带探测点信息时，以地图中心代替 —— 仅供不关心探测点的测试与工具代码使用；
     * 真实探查一律走五参构造，把玩家右键的原始坐标带进来。
     */
    public SurveyMap(int originX, int originZ, int chunks) {
        this(originX, originZ, chunks, originX + chunks * 8, originZ + chunks * 8);
    }

    public SurveyMap(int originX, int originZ, int chunks, int probeX, int probeZ) {
        this.originX = originX;
        this.originZ = originZ;
        this.chunks = chunks;
        this.size = chunks * 16;
        this.probeX = probeX;
        this.probeZ = probeZ;
    }

    /** 探测点 X（玩家右键的位置）。 */
    public int probeX() { return probeX; }

    /** 探测点 Z（玩家右键的位置）。 */
    public int probeZ() { return probeZ; }

    public int originX() { return originX; }

    public int originZ() { return originZ; }

    public int size() { return size; }

    public int chunks() { return chunks; }

    /** 是否落在图内。 */
    public boolean contains(int x, int z) {
        return x >= originX && x < originX + size && z >= originZ && z < originZ + size;
    }

    /** 加入一个数据点；重复的 (坐标, 键) 会被忽略。 */
    public void add(SurveyPoint point) {
        if (!contains(point.x(), point.z())) {
            return;
        }
        final List<SurveyPoint> list = cells.computeIfAbsent(pack(point.x(), point.z()), k -> new ArrayList<>(2));
        for (SurveyPoint existing : list) {
            if (existing.key().equals(point.key())) {
                return;
            }
        }
        list.add(point);
    }

    /** 该格的全部数据（可能为空）。 */
    public List<SurveyPoint> at(int x, int z) {
        final List<SurveyPoint> list = cells.get(pack(x, z));
        return list == null ? List.of() : list;
    }

    /**
     * 该格最终要显示的点：先按 {@code filter}（键）过滤，再取 RGB 权重最大者。
     *
     * @param filter 只显示该键；{@code null} 表示不过滤（显示全部中权重最大者）
     */
    @Nullable
    public SurveyPoint displayAt(int x, int z, @Nullable String filter) {
        final List<SurveyPoint> list = cells.get(pack(x, z));
        if (list == null || list.isEmpty()) {
            return null;
        }
        SurveyPoint best = null;
        for (SurveyPoint point : list) {
            if (filter != null && !filter.equals(point.key())) {
                continue;
            }
            if (best == null || point.rgbWeight() > best.rgbWeight()) {
                best = point;
            }
        }
        return best;
    }

    /** 出现过的全部键（按首次出现顺序），用于生成右侧图例。 */
    public Set<String> keys() {
        final Set<String> keys = new LinkedHashSet<>();
        for (List<SurveyPoint> list : cells.values()) {
            for (SurveyPoint point : list) {
                keys.add(point.key());
            }
        }
        return keys;
    }

    /** 全部数据点（供网络发送）。 */
    public List<SurveyPoint> points() {
        final List<SurveyPoint> out = new ArrayList<>();
        for (List<SurveyPoint> list : cells.values()) {
            out.addAll(list);
        }
        return out;
    }

    /** 数据点总数。 */
    public int pointCount() {
        int total = 0;
        for (List<SurveyPoint> list : cells.values()) {
            total += list.size();
        }
        return total;
    }

    /** 非空格子数。 */
    public int markedCells() {
        return cells.size();
    }

    /** 按图例顺序统计每个键的点数。 */
    public Map<String, Integer> counts() {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (List<SurveyPoint> list : cells.values()) {
            for (SurveyPoint point : list) {
                counts.merge(point.key(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 打包 (x, z) 为单个 long，避免 BlockPos 装箱开销。 */
    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
