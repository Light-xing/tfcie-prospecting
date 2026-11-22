package com.tfcie.prospecting.common.survey;

/**
 * 探查地图上的一个数据点。
 *
 * @param x     世界方块 X
 * @param z     世界方块 Z
 * @param key   数据键：矿种 id（如 {@code native_copper}）或异常类别 id（如 {@code density_high}）
 * @param color 显示颜色（ARGB）；由 {@link OreColors} 按 key 固定给出
 */
public record SurveyPoint(int x, int z, String key, int color) {

    /** 颜色的可比较度量：RGB 分量之和。用于同坐标多数据时的取舍。 */
    public int rgbWeight() {
        return ((color >> 16) & 0xFF) + ((color >> 8) & 0xFF) + (color & 0xFF);
    }
}
