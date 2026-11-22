package com.tfcie.prospecting.common.scan;

/**
 * 可被服务端按 tick <b>分帧推进</b>的扫描任务。
 *
 * <p>探查（{@code SurveyTask}）与遥感（{@code TerrainTask}）扫描的内容、耗时与产出结构
 * 完全不同，但它们与调度器之间的契约是同一条：
 * <b>「每 tick 推进一点，推进完就产出结果」</b>。
 * 把这一条抽出来，调度逻辑就只需写一份。
 */
public interface ServerScanTask {

    /** @return {@code true} 表示已全部完成，调度器可交付结果并注销本任务 */
    boolean tick();
}
