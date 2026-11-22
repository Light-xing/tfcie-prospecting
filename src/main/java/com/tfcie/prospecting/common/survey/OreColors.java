package com.tfcie.prospecting.common.survey;

import com.tfcie.prospecting.api.OreTaxonomy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 探查地图的固定配色表：数据键 → 颜色。
 *
 * <h2>设计原则（按用户确认）</h2>
 * 「先固定分配颜色，不必按价值排序，能符合一般人对某种矿物颜色的想象即可。」
 * 因此本表**不编码重要性**，只求直觉正确：
 * 孔雀石绿、朱砂红、赤铁矿暗红褐、青金石深蓝、紫水晶紫、磁铁矿近黑……
 *
 * <h2>与「RGB 较大优先」的关系</h2>
 * 冲突规则（{@link SurveyPoint#rgbWeight()}）只用颜色做**确定性仲裁**，
 * 不声称"亮的矿更重要"——它保证同一格反复扫描结果稳定可复现。
 *
 * <h2>键的两类</h2>
 * <ul>
 *   <li><b>矿种键</b>：与 {@link OreTaxonomy} 的 32 个 id 一一对应；</li>
 *   <li><b>异常类别键</b>：改进版工具只报"本方法的异常"时使用
 *       （如磁法的 {@code magnetic_strong}、回声的 {@code cavity}）。</li>
 * </ul>
 */
public final class OreColors {

    // ---- 异常类别键 ----
    public static final String RECON_TRACE = "recon_trace";
    public static final String DENSITY_HIGH = "density_high";
    public static final String DENSITY_LOW = "density_low";
    public static final String CONDUCTIVE = "conductive";
    public static final String MAGNETIC_STRONG = "magnetic_strong";
    public static final String MAGNETIC_WEAK = "magnetic_weak";
    public static final String CAVITY = "cavity";
    public static final String FLUID_WATER = "fluid_water";
    public static final String FLUID_LAVA = "fluid_lava";
    public static final String EXPOSED_ORE = "exposed_ore";

    private static final Map<String, Integer> COLORS;

    static {
        final Map<String, Integer> m = new LinkedHashMap<>();

        // ---- 金属矿（直觉配色）----
        put(m, "native_copper", 0xC87F4A);   // 铜橙
        put(m, "native_gold", 0xE8C547);     // 金
        put(m, "native_silver", 0xD6DCE0);   // 银白
        put(m, "cassiterite", 0x8D8577);     // 锡灰褐
        put(m, "bismuthinite", 0xD97FA8);    // 铋粉
        put(m, "garnierite", 0x7FB069);      // 镍绿
        put(m, "tetrahedrite", 0x7A7062);    // 黝铜青铜灰
        put(m, "sphalerite", 0xA85C1E);      // 闪锌棕橙
        put(m, "pyrite", 0xD4B33C);          // 愚人金黄
        put(m, "magnetite", 0x4A4A52);       // 磁铁矿近黑
        put(m, "hematite", 0x8B3A2E);        // 赤铁矿暗红褐
        put(m, "limonite", 0x9C7A3C);        // 褐铁矿土黄
        put(m, "malachite", 0x3FA34D);       // 孔雀石绿

        // ---- 非金属矿 ----
        put(m, "graphite", 0x3A3A3A);        // 石墨深灰
        put(m, "bituminous_coal", 0x1E1E1E); // 烟煤黑
        put(m, "lignite", 0x4A342A);         // 褐煤深褐
        put(m, "gypsum", 0xE8E0D0);          // 石膏白粉
        put(m, "sulfur", 0xE3D227);          // 硫磺黄
        put(m, "cinnabar", 0xC0392B);        // 朱砂红
        put(m, "cryolite", 0xEAF2F5);        // 冰晶石冰白
        put(m, "saltpeter", 0xCFCFC4);       // 硝石灰白
        put(m, "sylvite", 0xE6B8C0);         // 钾盐淡粉
        put(m, "borax", 0xF0F0F0);           // 硼砂白
        put(m, "halite", 0xF2D8D8);          // 岩盐淡粉白

        // ---- 宝石 ----
        put(m, "diamond", 0x9FE8F5);         // 钻石青白
        put(m, "emerald", 0x2ECC71);         // 祖母绿
        put(m, "lapis_lazuli", 0x2A4FA8);    // 青金石深蓝
        put(m, "amethyst", 0x9B59B6);        // 紫水晶
        put(m, "opal", 0xE8D5F0);            // 蛋白石幻彩白紫
        put(m, "ruby", 0xD62E4B);            // 红宝石
        put(m, "sapphire", 0x2E6FD6);        // 蓝宝石
        put(m, "topaz", 0xE8A030);           // 黄玉橙黄

        // ---- 异常类别 ----
        put(m, RECON_TRACE, 0xC9A227);       // 踏勘迹象：暗金（"这里有东西"，不说是啥）
        put(m, DENSITY_HIGH, 0xB05C4A);      // 高密度：偏红褐
        put(m, DENSITY_LOW, 0x4A7FB0);       // 低密度：偏蓝
        put(m, CONDUCTIVE, 0x36D1DC);        // 导电：青
        put(m, MAGNETIC_STRONG, 0xFF4D4D);   // 强磁：亮红
        put(m, MAGNETIC_WEAK, 0xB36B6B);     // 弱磁：暗红
        put(m, CAVITY, 0xA8B0B8);            // 空腔：浅冷灰（必须与地图底色 0x2A2A2A 明显区分）
        put(m, FLUID_WATER, 0x3A6FD8);       // 水：蓝
        put(m, FLUID_LAVA, 0xFF7A18);        // 岩浆：橙
        put(m, EXPOSED_ORE, 0xE0C070);       // 暴露矿面：暖金

        COLORS = Collections.unmodifiableMap(m);
    }

    private static void put(Map<String, Integer> m, String key, int rgb) {
        m.put(key, 0xFF000000 | rgb);
    }

    /** 表内条数（供完整性测试）。 */
    public static int size() {
        return COLORS.size();
    }

    /** 该键是否已配色。 */
    public static boolean has(String key) {
        return COLORS.containsKey(key);
    }

    /** 颜色（ARGB）。未配色的键回落到中性灰，避免出现透明像素。 */
    public static int of(String key) {
        final Integer color = COLORS.get(key);
        return color == null ? 0xFF808080 : color;
    }

    /** 全部配色（不可变，调试/测试用）。 */
    public static Map<String, Integer> all() {
        return COLORS;
    }

    private OreColors() {}
}
