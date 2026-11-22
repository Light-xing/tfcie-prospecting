package com.tfcie.prospecting.common.prospecting;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** 钻探取样器的档位与摘要逻辑（阶段 5，纯逻辑）。 */
public class CoreSamplerProspectorTest {

    @Test
    public void summaryCompressesByLayerOrder() {
        final List<CoreSampleData.Layer> runs = List.of(
                new CoreSampleData.Layer("tfc:dirt", "泥土", 5, 74, 70, true),
                new CoreSampleData.Layer("tfc:rock/diorite", "闪长岩", 10, 69, 60, true),
                new CoreSampleData.Layer("tfc:dirt", "泥土", 3, 59, 57, true));
        // 设计文档的摘要示例
        assertEquals("泥土 5 闪长岩 10 泥土 3", CoreSamplerProspector.buildSummary(runs));
    }

    @Test
    public void emptySummaryIsEmpty() {
        assertEquals("", CoreSamplerProspector.buildSummary(List.of()));
    }

    @Test
    public void availableStepsAreClippedToMaxDepth() {
        assertArrayEquals(new int[]{8, 16, 32, 64}, CoreSamplerProspector.availableSteps(64));
        assertArrayEquals(new int[]{8, 16}, CoreSamplerProspector.availableSteps(20));
        // 上限极小时至少留一档，且不小于 1
        assertArrayEquals(new int[]{4}, CoreSamplerProspector.availableSteps(4));
    }

    @Test
    public void depthCyclesThroughStepsAndWraps() {
        assertEquals(64, CoreSamplerProspector.nextDepthStep(32, 64));
        assertEquals(16, CoreSamplerProspector.nextDepthStep(8, 64));
        // 到顶后回到第一档
        assertEquals(8, CoreSamplerProspector.nextDepthStep(64, 64));
        // 配置上限较小时只在可用档位内循环
        assertEquals(16, CoreSamplerProspector.nextDepthStep(8, 20));
        assertEquals(8, CoreSamplerProspector.nextDepthStep(16, 20));
    }

    @Test
    public void depthIsClampedToConfigLimit() {
        assertEquals(32, CoreSamplerProspector.clampDepth(32, 64));
        assertEquals(64, CoreSamplerProspector.clampDepth(200, 64));
        assertEquals(1, CoreSamplerProspector.clampDepth(0, 64));
        assertEquals(1, CoreSamplerProspector.clampDepth(-5, 64));
    }
}
