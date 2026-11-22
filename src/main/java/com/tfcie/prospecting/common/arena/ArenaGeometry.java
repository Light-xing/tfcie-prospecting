package com.tfcie.prospecting.common.arena;

import javax.annotation.Nullable;

/**
 * 靶场的<b>纯几何 / 纯数值算子</b>：地表微起伏、走向椭球、确定性随机与品位分档。
 *
 * <h2>为什么要单独成类</h2>
 * 这些函数决定「哪一格放哪种矿」，是整张靶场可解性的核心；把它们做成无状态的静态方法，
 * 就能在纯 JUnit 里验证走向、连续性与概率，不用启动 Minecraft。{@code ArenaBuilder} 在服务端
 * 逐格调用同一批函数，因此单测验证过的性质就是实机性质。
 *
 * <h2>走向约定（与契约 §1.2 逐字一致）</h2>
 * <pre>
 *   u = dx * cos(strike) + dz * sin(strike)
 *   v = -dx * sin(strike) + dz * cos(strike)
 *   rNorm = sqrt((u/rx)^2 + (dy/ry)^2 + (v/rz)^2)
 * </pre>
 * {@code strike=0} 时长轴沿 dx 轴（东西）；{@code strike=45} 时长轴沿 {@code dx=dz}（契约称之为 NE-SW）；
 * {@code strike=135} 时长轴沿 {@code dx=-dz}（契约称之为 NW-SE）。
 *
 * <p><b>注意</b>：{@code inside} 判据是严格椭球 {@code rNorm <= 1.0}，即长轴端点在 {@code rx} 处。
 * 若想判断「沿长轴方向还够不够得着」，应比较 {@link #normalizedRadius} 而不是只看 dx/dz 分量大小——
 * 斜向点 {@code (dx,dz)=(t,t)} 的径向距离是 {@code t*sqrt(2)}，在 {@code t=10, rx=12} 处已经出界。
 *
 * <h2>品位分档（保证中心垂向柱连续）</h2>
 * {@link #gradeFor} 的阈值：{@code rNorm <= 0.4 → rich}，{@code <= 0.75 → normal}，
 * {@code <= 1.0} 且 {@code roll < 0.7 → poor}，否则 {@code null}（不放置）。
 * 由于中心列 {@code dy = -1,0,1} 的 {@code rNorm = 1/ry <= 1/3}（ry >= 3），必为 rich 且必放置，
 * 从而「中心 3 格连续无空隙、单钻必中」。
 */
public final class ArenaGeometry {

    /** 模式 B 地表微起伏振幅（格）。 */
    public static final int WAVE_AMPLITUDE = 2;

    /** {@code rNorm <= 此值} 为富矿（rich）。 */
    public static final double RICH_MAX_RADIUS = 0.4;
    /** {@code rNorm <= 此值} 为普通矿（normal）。 */
    public static final double NORMAL_MAX_RADIUS = 0.75;
    /** 外缘（{@code 0.75..1.0}）放置 poor 矿的概率。 */
    public static final double POOR_PLACE_CHANCE = 0.7;

    /** 品位名：富矿。 */
    public static final String GRADE_RICH = "rich";
    /** 品位名：普通矿。 */
    public static final String GRADE_NORMAL = "normal";
    /** 品位名：贫矿。 */
    public static final String GRADE_POOR = "poor";

    /** 地表起伏的角频率：以约 30~40 格为周期，保证相邻列只差 0/±1 格。 */
    private static final double WAVE_FREQ_X = 0.19;
    private static final double WAVE_FREQ_Z = 0.23;
    /** 两个方向的分量幅度（1.2 + 0.8 = 2.0，正好等于振幅上限，无需担心越界）。 */
    private static final double WAVE_AMP_X = 1.2;
    private static final double WAVE_AMP_Z = 0.8;

    private ArenaGeometry() {}

    // ---------------- 地表起伏 ----------------

    /**
     * 模式 B（纯虚空列）在偏移 {@code (dx,dz)} 处的地表起伏，整数且 {@code ∈ [-2,2]}。
     *
     * <p>由两条周期不同的正弦叠加而成，故相邻列（任一轴）的取值变化只有 {@code -1/0/+1} 格，
     * 既是「平滑可微」的连续地形，又保证施工后不会出现 1 格宽的断崖。
     */
    public static int surfaceWave(int dx, int dz) {
        final double raw = WAVE_AMP_X * Math.sin(dx * WAVE_FREQ_X)
                + WAVE_AMP_Z * Math.sin(dz * WAVE_FREQ_Z);
        int wave = (int) Math.round(raw);
        if (wave > WAVE_AMPLITUDE) {
            wave = WAVE_AMPLITUDE;
        } else if (wave < -WAVE_AMPLITUDE) {
            wave = -WAVE_AMPLITUDE;
        }
        return wave;
    }

    /** 该列地表高度 = {@code baseSurfaceY + surfaceWave(dx,dz)}。 */
    public static int surfaceY(int baseSurfaceY, int dx, int dz) {
        return baseSurfaceY + surfaceWave(dx, dz);
    }

    // ---------------- 椭球 ----------------

    /**
     * 归一化椭球半径（{@code 0} = 中心，{@code 1} = 表面，{@code >1} = 外部）。
     *
     * <p>先绕 Y 轴按 {@code strikeDeg} 旋转水平偏移，再按三轴半径归一。非正半径会被夹到极小值，
     * 使该方向「永不命中」而不是抛异常或除零——配置层已由
     * {@link ArenaConfig#structuralErrors()} 拦截 {@code rx/ry/rz <= 0}。
     */
    public static double normalizedRadius(int dx, int dy, int dz, int rx, int ry, int rz, double strikeDeg) {
        final double radians = Math.toRadians(strikeDeg);
        final double cos = Math.cos(radians);
        final double sin = Math.sin(radians);
        final double u = dx * cos + dz * sin;
        final double v = -dx * sin + dz * cos;
        final double ax = u / positiveOrEpsilon(rx);
        final double ay = dy / positiveOrEpsilon(ry);
        final double az = v / positiveOrEpsilon(rz);
        return Math.sqrt(ax * ax + ay * ay + az * az);
    }

    /** 是否落在椭球内（含表面，判据 {@code rNorm <= 1.0}）。 */
    public static boolean inside(int dx, int dy, int dz, int rx, int ry, int rz, double strikeDeg) {
        return normalizedRadius(dx, dy, dz, rx, ry, rz, strikeDeg) <= 1.0;
    }

    // ---------------- 确定性随机 ----------------

    /**
     * 稳定散列到 {@code [0,1)} 的伪随机数：同参数永远同值，无 {@code Random} 状态，
     * 也不依赖任何容器迭代顺序，因此<b>重刷同一张图会得到逐格一致的矿体</b>（幂等的地基）。
     *
     * <p>算法：把四个 int 混一个 long（黄金比例 / MurmurHash3 finalizer 常数族），
     * 再取高 53 位归一——正好是 double 的尾数位宽，故结果严格落在 {@code [0,1)}。
     *
     * @param salt 同一位置需要多路独立随机时的区分盐（如按特征 id 哈希后传入）
     */
    public static double deterministic01(int x, int y, int z, int salt) {
        long h = (long) x * 73856093L
                ^ (long) y * 19349663L
                ^ (long) z * 83492791L
                ^ (long) salt * 2654435761L;
        // MurmurHash3 的 64 位 finalizer：把低位差异充分扩散到高位，避免相邻坐标相关
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        // 取高 53 位（double 尾数），结果严格 ∈ [0, 1)
        return (h >>> 11) * 0x1.0p-53;
    }

    // ---------------- 品位 ----------------

    /**
     * 按归一化半径与掷骰结果决定该格放什么品位的矿。
     *
     * @param rNorm 归一化椭球半径（{@link #normalizedRadius} 的返回值）
     * @param roll  {@code [0,1)} 的掷骰值（一般用 {@link #deterministic01} 生成）
     * @return {@code "rich"} / {@code "normal"} / {@code "poor"}；{@code null} 表示该格不放置
     */
    @Nullable
    public static String gradeFor(double rNorm, double roll) {
        if (rNorm <= RICH_MAX_RADIUS) {
            return GRADE_RICH;
        }
        if (rNorm <= NORMAL_MAX_RADIUS) {
            return GRADE_NORMAL;
        }
        if (rNorm <= 1.0 && roll < POOR_PLACE_CHANCE) {
            return GRADE_POOR;
        }
        return null;
    }

    /** 半径防御：非正 / NaN 一律当极小值（该轴归一化后趋于无穷，即永不命中）。 */
    private static double positiveOrEpsilon(double radius) {
        return radius > 0 ? radius : 1.0e-9;
    }
}
