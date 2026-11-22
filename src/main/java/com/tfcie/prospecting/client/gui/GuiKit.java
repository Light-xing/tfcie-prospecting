package com.tfcie.prospecting.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 各界面共用的小工具 —— <b>纯函数</b>，不持有状态，可直接单测。
 *
 * <h2>为什么单独一个类</h2>
 * 本模组有多个「地图式」界面（探查地图、遥感扫描仪的平面图/等高线图）。
 * 它们各自需要同一批基础操作：坐标换算、矩形命中、像素序转换，以及在地图上标出玩家位置。
 * 这些逻辑此前在每个界面各写了一遍 —— 同一段代码存在两份，改一处就会漏另一处。
 * 集中到这里后，「玩家标记长什么样」只有一个定义。
 */
public final class GuiKit {

    /** 玩家标记的外晕 / 内晕 / 核心色。 */
    private static final int MARKER_HALO_OUTER = 0x28FFE066;
    private static final int MARKER_HALO_INNER = 0x60FFE066;
    private static final int MARKER_CORE = 0xFFFFE84D;

    private GuiKit() {}

    // ---------------- 基础几何 ----------------

    /** 点是否落在矩形内。右边与下边取开区间，与 MC 的 GUI 命中习惯一致。 */
    public static boolean inRect(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** 把滚动量夹到 {@code [0, max]}。 */
    public static int clampScroll(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    /**
     * MC 的 {@code NativeImage} 内部是 <b>ABGR</b> 序，与常用的 ARGB 相比 R/B 相反。
     *
     * <p>直接写 ARGB 会得到红蓝互换的图，故所有像素写入前都要过一次这里。
     */
    public static int argbToAbgr(int argb) {
        return (argb & 0xFF00FF00)
                | ((argb & 0x00FF0000) >> 16)
                | ((argb & 0x000000FF) << 16);
    }

    // ---------------- 玩家标记 ----------------

    /**
     * 在地图上标出玩家位置：三层同心圆叠出的<b>黄色发光点</b>（外圈最淡、核心最亮实心）。
     *
     * <p>比贯通十字线更贴近「我就站在这儿」的直觉，也不会像十字线那样盖住整张图、压掉沿途的矿点。
     *
     * @param centerX 标记中心的屏幕 X（应取该方块像素的中心）
     * @param unit    一个方块占的屏幕像素数，用于让光晕随放大倍数缩放；1× 时也不至于糊成一团
     * @param clip    可见区域，超出部分会被裁掉（地图视口 / 图幅边界）
     */
    public static void drawPlayerMarker(GuiGraphics graphics, int centerX, int centerY, int unit,
                                        int clipLeft, int clipTop, int clipRight, int clipBottom) {
        final int u = Math.max(1, unit);
        filledCircle(graphics, centerX, centerY, u * 3 + 1, MARKER_HALO_OUTER,
                clipLeft, clipTop, clipRight, clipBottom);
        filledCircle(graphics, centerX, centerY, u * 2 + 1, MARKER_HALO_INNER,
                clipLeft, clipTop, clipRight, clipBottom);
        filledCircle(graphics, centerX, centerY, Math.max(2, u + 1), MARKER_CORE,
                clipLeft, clipTop, clipRight, clipBottom);
    }

    /** 逐行 fill 画实心圆，并按裁剪矩形收边（半径很小，开销可忽略）。 */
    public static void filledCircle(GuiGraphics graphics, int centerX, int centerY, int radius, int color,
                                    int clipLeft, int clipTop, int clipRight, int clipBottom) {
        for (int dy = -radius; dy <= radius; dy++) {
            final int half = (int) Math.sqrt((double) radius * radius - (double) dy * dy);
            final int left = Math.max(centerX - half, clipLeft);
            final int right = Math.min(centerX + half + 1, clipRight);
            final int top = Math.max(centerY + dy, clipTop);
            final int bottom = Math.min(centerY + dy + 1, clipBottom);
            if (left < right && top < bottom) {
                graphics.fill(left, top, right, bottom, color);
            }
        }
    }
}
