package com.tfcie.prospecting.common.prospecting;

/**
 * 逐列地表判定：每个水平列 {@code (dx, dz)} 各自记录「是否已进入地下」。
 *
 * <h2>为什么必须按列</h2>
 * 原先所有列共用一个 {@code boolean[1]}：只要扫描碰到的第一个点不是空气/流体，
 * 整盘扫描就被判为「已入地」；于是站在斜坡、台阶或 TFC 河岸旁时，旁边列的地表
 * 空气 / 河水会被当成地下体素计入 <b>0-8 格浅层桶</b>，制造出压过真矿的假低密度
 * （报告 18 §1 第 5 条、§2.1）。
 *
 * <p>现在每列各自穿过地表以上的空气、水与植物/草丛，碰到该列第一个<b>实心方块</b>后才
 * 开始计分。索引按 {@code (dx + radius) * side + (dz + radius)}，{@code side = 2·radius+1}；
 * 不依赖游戏运行时，可直接单测。
 */
final class GroundColumns {

    private final int radius;
    private final int side;
    private final boolean[] entered;

    GroundColumns(int radius) {
        this.radius = Math.max(0, radius);
        this.side = 2 * this.radius + 1;
        this.entered = new boolean[this.side * this.side];
    }

    int side() {
        return side;
    }

    /** 列在扁平数组中的下标；越界时抛 {@link ArrayIndexOutOfBoundsException}（与直接索引一致）。 */
    int index(int dx, int dz) {
        return (dx + radius) * side + (dz + radius);
    }

    /**
     * 该列是否已进入（或本次刚进入）地下。
     *
     * @param solidRender 该点是否为实心方块（{@code BlockState#isSolidRender}）；
     *                    {@code false} 表示仍在地表以上的空气 / 水 / 植物
     * @return {@code true} 表示该点可以计入地下信号
     */
    boolean entered(int dx, int dz, boolean solidRender) {
        final int i = index(dx, dz);
        if (entered[i]) {
            return true;
        }
        if (!solidRender) {
            return false;
        }
        entered[i] = true;
        return true;
    }
}
