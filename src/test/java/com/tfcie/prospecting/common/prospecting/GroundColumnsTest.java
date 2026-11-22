package com.tfcie.prospecting.common.prospecting;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 逐列地表判定（报告 18 §2.1）：斜坡 / 台阶 / 河岸旁的地表空气不得污染 0-8 格浅层桶。
 *
 * <p>纯逻辑单测：用布尔「该点是否实心」模拟地形，复刻 {@code DensityPlumbProspector}
 * 里「只有已入地的采样点才计分」的累加方式，不启动游戏、不触世界。
 */
public class GroundColumnsTest {

    @Test
    public void layoutUsesFullSquareArray() {
        final GroundColumns ground = new GroundColumns(1);
        assertEquals(3, ground.side());
        assertEquals(0, ground.index(-1, -1));
        assertEquals(8, ground.index(1, 1));
        // 列与列互不共享标志
        assertTrue(ground.entered(-1, -1, true));
        assertFalse("另一列不应因为相邻列入地而被当成地下", ground.entered(1, 1, false));
    }

    @Test
    public void undergroundAirStillCountsAfterColumnHasEntered() {
        final GroundColumns ground = new GroundColumns(0);
        assertFalse("地表以上空气不计分", ground.entered(0, 0, false));
        assertTrue(ground.entered(0, 0, true));
        assertTrue("入地后的地下空洞（空气）照常作为低密度计分", ground.entered(0, 0, false));
    }

    /**
     * 3×3 列、半径为 1：中心列 dy=1 就碰到地面，周围 8 列要 dy=3 才落地（1~2 级台阶）。
     * 旧实现共用一个 enteredGround：中心列入地后，周围列 dy=1/2 的地表空气会被记成 -1，
     * 把 0-8 格桶压成假低密度；逐列判定后这些空气被跳过，桶只含真实地下信号。
     */
    @Test
    public void slopeDoesNotPolluteShallowBucket() {
        final int radius = 1;
        final int side = 2 * radius + 1;
        final int[][] firstSolidDy = new int[side][side];
        for (int[] row : firstSolidDy) {
            Arrays.fill(row, 3);
        }
        firstSolidDy[radius][radius] = 1;

        final GroundColumns ground = new GroundColumns(radius);
        double band0 = 0.0D;
        for (int dy = 0; dy <= 8; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    final boolean solid = dy >= firstSolidDy[dx + radius][dz + radius];
                    if (!ground.entered(dx, dz, solid)) {
                        continue;   // 地表以上：空气 / 水 / 植物都不计分
                    }
                    band0 += solid ? 1.0D : -1.0D;
                }
            }
        }

        // 中心列 dy=1..8 共 8 格 + 其余 8 列 dy=3..8 共 6 格 ×8 = 56
        assertEquals(56.0D, band0, 1.0E-9D);
        assertTrue("浅层桶应只剩真实地下信号，不再是假低密度", band0 > 0.0D);
    }
}
