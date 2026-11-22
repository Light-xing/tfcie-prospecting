package com.tfcie.prospecting.client.terrain;

/**
 * 地形图的配色计算 —— <b>纯函数</b>，不依赖游戏运行时，可直接单测。
 *
 * <h2>等高线的档位配色</h2>
 * 要求「以 3 格为 gap，每一档不同颜色，且相邻两档要能明显区分」。
 *
 * <p>最朴素的「按高度线性取色」在相邻档之间只差一点点，恰恰是最难分辨的。
 * 这里改用<b>黄金角配色</b>：第 {@code n} 档的色相取 {@code n · 137.508° mod 360°}。
 * 黄金角是圆周上最不共振的步进，任意相邻两档色相相差 137.5°，
 * 而相隔两档又落在另外的位置上 —— 既保证「相邻必然好区分」，也保证「多档不重复」。
 */
public final class TerrainPalette {

    /** 等高线间距（格）。 */
    public static final int CONTOUR_GAP = 3;

    /** 黄金角：圆周上最不共振的步进。 */
    private static final double GOLDEN_ANGLE = 137.508D;

    // ---- 原版地图的浮雕亮度（见 shadeBySlope） ----

    /** 三档亮度系数，取自 {@code MapColor.Brightness} 的 modifier（180 / 220 / 255）÷ 255。 */
    public static final float BRIGHTNESS_LOW = 180.0F / 255.0F;
    public static final float BRIGHTNESS_NORMAL = 220.0F / 255.0F;
    public static final float BRIGHTNESS_HIGH = 255.0F / 255.0F;

    /** 高度差的权重：原式为 {@code 4.0 / (i + 4)}，本模组地图为 1:1（{@code i = 1}）故为 0.8。 */
    private static final double SLOPE_WEIGHT = 0.8D;

    /** 分档阈值：高于 +0.6 取亮档、低于 −0.6 取暗档。 */
    private static final double SLOPE_THRESHOLD = 0.6D;

    /** 棋盘格抖动幅度：原式为 {@code (parity − 0.5) × 0.4}，即 ±0.2。 */
    private static final double DITHER_AMPLITUDE = 0.4D;

    /** 区块边界向灰色混合的比例。 */
    public static final float CHUNK_BORDER_GRAY = 0.45F;

    private static final int GRAY_RGB = 0x808080;

    private TerrainPalette() {}

    // ---------------- 平面地图 ----------------

    /**
     * 原版地图的<b>浮雕亮度</b>：按<b>与相邻像素的高度差</b>分档，而不是按绝对高度。
     *
     * <h2>为什么用高度差</h2>
     * 绝对高度着色只能表达「这里高、那里低」，整片地图像一张平铺的渐变；而高度差表达的
     * 是<b>坡向与坡度</b> —— 迎光面提亮、背光面压暗、陡坎两侧立刻出现明暗分界，
     * 于是地形有了立体感。这正是原版地图看起来「有地形」的原因。
     *
     * <h2>原式（{@code MapItem.update}）</h2>
     * <pre>
     *   d3 = (d1 - d0) * 4.0 / (i + 4) + ((x + z &amp; 1) - 0.5) * 0.4
     *   d3 &gt;  0.6 → HIGH(255)   d3 &lt; -0.6 → LOW(180)   否则 NORMAL(220)
     * </pre>
     * 其中 {@code d1} 是本像素高度、{@code d0} 是<b>同一 x 上前一个 z</b>的高度
     * （原版 {@code d0} 在每个 x 列开头复位，故是南北向比较）。
     * 末项是棋盘格抖动，用来打散大片等高区域、避免出现色带。
     *
     * @param northY 北侧（{@code z - 1}）同列的高度；无相邻数据时传 {@code y} 自身
     */
    public static int shadeBySlope(int baseColor, int y, int northY, int worldX, int worldZ) {
        if (baseColor == 0) {
            return 0;
        }
        final double d3 = slopeValue(y, northY, worldX, worldZ);

        final float factor;
        if (d3 > SLOPE_THRESHOLD) {
            factor = BRIGHTNESS_HIGH;
        } else if (d3 < -SLOPE_THRESHOLD) {
            factor = BRIGHTNESS_LOW;
        } else {
            factor = BRIGHTNESS_NORMAL;
        }
        return scaleRgb(baseColor, factor);
    }

    /**
     * 浮雕判据的原始数值 {@code d3 = 坡度项 + 棋盘格抖动}。
     *
     * <p>单独抽出来是为了可测：本模组地图是 1:1 且高度为整数，坡度只能是 0.8 的整数倍，
     * ±0.2 的抖动恰好落在 ±0.6 阈值边界上 —— 也就是说<b>在当前参数下抖动不会改变档位</b>。
     * 原版需要它，是因为其高度取自 {@code i×i} 像素块的<b>平均</b>（缩放地图时为小数），
     * 抖动才有打散色带的空间。这里保留该式以对齐原版，也为将来支持缩放留好位置。
     */
    public static double slopeValue(int y, int northY, int worldX, int worldZ) {
        final double slope = (y - northY) * SLOPE_WEIGHT;
        final double dither = (((worldX + worldZ) & 1) - 0.5D) * DITHER_AMPLITUDE;
        return slope + dither;
    }

    /** 向灰色混合，用于标记区块边界。 */
    public static int grayOut(int color, float amount) {
        if (color == 0) {
            return 0;
        }
        final float a = clamp01(amount);
        final int r = channel(color, 16);
        final int g = channel(color, 8);
        final int b = channel(color, 0);
        final int gr = channel(GRAY_RGB, 16);
        final int gg = channel(GRAY_RGB, 8);
        final int gb = channel(GRAY_RGB, 0);
        return 0xFF000000
                | (mix(r, gr, a) << 16)
                | (mix(g, gg, a) << 8)
                | mix(b, gb, a);
    }

    /**
     * 该方块是否落在区块边界线上（用于把边界像素调灰）。
     *
     * <p>按<b>世界坐标</b>判断而不是图内相对坐标 —— 否则地图原点一变，
     * 灰线就会跟着漂移到非区块边界的位置。
     */
    public static boolean isChunkBorder(int worldX, int worldZ) {
        return Math.floorMod(worldX, 16) == 0 || Math.floorMod(worldZ, 16) == 0;
    }

    // ---------------- 等高线 ----------------

    /** 高度所属的等高线档位（以 {@link #CONTOUR_GAP} 为一档，向下取整）。 */
    public static int bandOf(int y) {
        return Math.floorDiv(y, CONTOUR_GAP);
    }

    /** 档位颜色：黄金角步进的色相，高饱和以保证在地形底图上醒目。 */
    public static int contourColor(int band) {
        final double hue = ((band * GOLDEN_ANGLE) % 360.0D + 360.0D) % 360.0D;
        return hsvToRgb((float) hue, 0.85F, 1.0F);
    }

    /** 等高线底图：把地形色压暗，突出线本身。 */
    public static int contourBackdrop(int baseColor) {
        if (baseColor == 0) {
            return 0;
        }
        return scaleRgb(baseColor, 0.38F);
    }

    /**
     * 该列是否为等高线像素。
     *
     * <h2>只在线条「较低的一侧」落笔</h2>
     * 判据是：四邻中<b>存在档位更高</b>的那一格 —— 说明等高线从这两格之间穿过，
     * 而它自己位于线的下沿。
     *
     * <p><b>为什么不能两侧都标</b>：若用「任一邻格档位不同」这类对称判据，
     * 一次跨越会把两侧的格子都点上，<b>线宽变成 2 像素</b>。在 3 格间距下，
     * 匀坡上的 8 格里有 4 格是线像素（线、线、空、线、线、空…），
     * 看上去就像把 gap 调成了 1。单侧落笔后每 3 格才有一条 1 像素的线。
     *
     * <p>无数据（{@link TerrainPalette} 外部传入的 {@code NO_DATA}）的档位远低于任何真实高度，
     * 因此不会被判为「更高」，数据缺口处不会被勾出假轮廓。
     */
    public static boolean isContourPixel(int y, int yEast, int yWest, int yNorth, int ySouth) {
        final int band = bandOf(y);
        return bandOf(yEast) > band || bandOf(yWest) > band
                || bandOf(yNorth) > band || bandOf(ySouth) > band;
    }

    // ---------------- 内部工具 ----------------

    /** 纯 Java 的 HSV→RGB（不借 MC 的工具类，便于单测锁定具体数值）。 */
    public static int hsvToRgb(float hue, float saturation, float value) {
        final float h = ((hue % 360.0F) + 360.0F) % 360.0F / 60.0F;
        final float c = value * saturation;
        final float x = c * (1.0F - Math.abs(h % 2.0F - 1.0F));
        final float r;
        final float g;
        final float b;
        switch ((int) h) {
            case 0 -> { r = c; g = x; b = 0.0F; }
            case 1 -> { r = x; g = c; b = 0.0F; }
            case 2 -> { r = 0.0F; g = c; b = x; }
            case 3 -> { r = 0.0F; g = x; b = c; }
            case 4 -> { r = x; g = 0.0F; b = c; }
            default -> { r = c; g = 0.0F; b = x; }
        }
        final float m = value - c;
        return 0xFF000000
                | (toByte(r + m) << 16)
                | (toByte(g + m) << 8)
                | toByte(b + m);
    }

    private static int toByte(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255.0F)));
    }

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    private static int channel(int argb, int shift) {
        return (argb >> shift) & 0xFF;
    }

    private static int mix(int from, int to, float amount) {
        return Math.round(from + (to - from) * amount);
    }

    private static int scaleRgb(int argb, float factor) {
        return 0xFF000000
                | (Math.min(255, Math.round(channel(argb, 16) * factor)) << 16)
                | (Math.min(255, Math.round(channel(argb, 8) * factor)) << 8)
                | Math.min(255, Math.round(channel(argb, 0) * factor));
    }
}
