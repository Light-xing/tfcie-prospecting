package com.tfcie.prospecting.common.survey;

/**
 * 一次探查的实际参数。
 *
 * <p><b>默认值只有一处来源</b>：{@code ProspectingConfig.Survey}。本 record 刻意不再留一份
 * 「默认值常量」—— 那会与配置里的默认值各说各话，改了一处忘了另一处。
 *
 * <h2>为什么全深度下垂向步长默认取 2 而不是 4</h2>
 * 主世界全高 384 格，步长 4 只采 96 个点；TFC 的盘状矿脉（{@code DiscVeinFeature}）
 * 垂向可以很薄，可能整个漏在采样缝隙里。步长 2 采 192 个点，总量约 400 万次查询，
 * 摊到 81 个区块（每 tick 一个）约 4.9 万次/tick，实测可接受。
 * 追求极限可调 1（约 800 万次、9.8 万次/tick，会明显吃 tick）；嫌慢可调 4。
 *
 * @param chunkRadius    区块半径 r；地图为 {@code (2r−1) × (2r−1)} 区块。<b>r = 5 → 9×9 区块</b>
 * @param verticalStride 垂向采样步长
 * @param fullDepth      是否扫描世界全部高度（{@code true} 时忽略 {@code depthBelow}）
 * @param depthBelow     非全深度时，自探测点向下扫描的格数
 */
public record SurveyConfig(int chunkRadius, int verticalStride, boolean fullDepth, int depthBelow) {

    /** 地图边长（区块）。9×9 → 9。 */
    public int diameterChunks() {
        return chunkRadius * 2 - 1;
    }

    /** 地图边长（方块）。9×9 → 144。 */
    public int diameterBlocks() {
        return diameterChunks() * 16;
    }

    /** 需要扫描的区块总数。9×9 → 81。 */
    public int totalChunks() {
        final int d = diameterChunks();
        return d * d;
    }

    public SurveyConfig {
        chunkRadius = Math.max(1, Math.min(chunkRadius, 8));
        verticalStride = Math.max(1, verticalStride);
        depthBelow = Math.max(1, depthBelow);
    }
}
