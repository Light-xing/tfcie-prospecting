package com.tfcie.prospecting.compat.numen;

import com.dwinovo.numen.api.sdk.ApiFunction;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import com.tfcie.prospecting.api.enums.Bearing;
import com.tfcie.prospecting.api.enums.Conductivity;
import com.tfcie.prospecting.api.enums.Confidence;
import com.tfcie.prospecting.api.enums.DensityAnomaly;
import com.tfcie.prospecting.api.enums.DepthHint;
import com.tfcie.prospecting.api.enums.DistanceBand;
import com.tfcie.prospecting.api.enums.Extent;
import com.tfcie.prospecting.api.enums.FieldStrength;
import com.tfcie.prospecting.api.enums.FluidKind;
import com.tfcie.prospecting.api.enums.Hazard;
import com.tfcie.prospecting.api.enums.MagneticClass;
import com.tfcie.prospecting.api.enums.Magnitude;
import com.tfcie.prospecting.api.enums.Strike;
import com.tfcie.prospecting.api.enums.TerminatedBy;
import com.tfcie.prospecting.api.telemetry.ProspectLedger;
import com.tfcie.prospecting.common.prospecting.CoreSampleData;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 每个工具答案表的离线编码契约：组里 9 个函数的返回值都必须能编码成 Lua 表。
 * 任何一个 record 组件用了没有值转换的类型（Object、ToolParameters、裸 Map…）都会在这里失败。
 */
public class ProspectingApiAnswerCodecTest {

    private static ApiFunction function(String namespace, String name) {
        final ApiFunction fn = ApiRegistry.function(namespace + ".instrument." + name);
        assertNotNull(name + " 应已登记", fn);
        return fn;
    }

    private static Map<?, ?> encode(ApiFunction fn, Object answer) {
        final Object table = fn.returns().encode(answer);
        assertTrue(fn.name() + " 的返回值应是一张表", table instanceof Map<?, ?>);
        return (Map<?, ?>) table;
    }

    @Test
    public void everyAnswerRecordEncodes() {
        ApiRegistry.register("prospect_codec", "instrument", "codec test", ProspectingApi.class);
        final ProspectingApi.Outcome ok = new ProspectingApi.Outcome("x", true, "ok", "none");

        final Map<?, ?> density = encode(function("prospect_codec", "density_plumb"),
                new ProspectingApi.DensityAnswer(ok, Optional.of(DensityAnomaly.HIGH_DENSITY), 8, 24,
                        Optional.of(Magnitude.STRONG), Optional.of(Confidence.MEDIUM), true, false, false));
        assertEquals("high_density", density.get("anomaly"));
        assertEquals(true, density.get("drifted"));

        final Map<?, ?> induction = encode(function("prospect_codec", "induction_probe"),
                new ProspectingApi.InductionAnswer(ok, Optional.of(Conductivity.HIGH), Optional.of(Bearing.N),
                        Optional.of(DistanceBand.NEAR), Optional.of(6), Optional.of(DepthHint.SHALLOW),
                        Optional.of(Confidence.MEDIUM), Optional.of(true)));
        assertEquals("n", induction.get("bearing"));

        final Map<?, ?> magnetic = encode(function("prospect_codec", "magnetic_needle"),
                new ProspectingApi.MagneticAnswer(ok, Optional.of(FieldStrength.STRONG), Optional.of(125.5D),
                        Optional.of(90), Optional.of(Extent.MEDIUM), Optional.of(Extent.SMALL),
                        Optional.of(MagneticClass.MAGNETIC), Optional.of(Strike.N_S), true,
                        Optional.of(Confidence.HIGH), Optional.of(false)));
        assertEquals(true, magnetic.get("strike_ready"));
        assertEquals("n_s", magnetic.get("strike"));

        final Map<?, ?> echo = encode(function("prospect_codec", "echo_probe"),
                new ProspectingApi.EchoAnswer(ok, true, 3, 32, 120, Optional.of(FluidKind.WATER),
                        List.of(28, 30), 2, Optional.of(Hazard.LOW), false));
        assertEquals(List.of(28L, 30L), echo.get("exposed_ore_levels"));

        final CoreSampleData.OreHit hit = new CoreSampleData.OreHit("tfc:magnetite", "磁铁矿", 22, 3);
        final Map<?, ?> core = encode(function("prospect_codec", "core_sampler"),
                new ProspectingApi.CoreAnswer(ok, Optional.of(1), Optional.of(64), Optional.of(-2),
                        Optional.of("minecraft:overworld"), Optional.of(64), Optional.of(20), Optional.of(44),
                        Optional.of(TerminatedBy.MAX_DEPTH), Optional.of(0.9D), Optional.of(1), List.of(hit),
                        Optional.of("summary"), Optional.of(1234L)));
        assertEquals("tfc:magnetite", ((Map<?, ?>) ((List<?>) core.get("ore_hits")).get(0)).get("block"));

        final ProspectingApi.SampleInfo sample = new ProspectingApi.SampleInfo(1, 64, -2, "minecraft:overworld",
                64, 20, 44, TerminatedBy.BEDROCK, 0.8D, 0, List.of(), "s", 1L, 7);
        final Map<?, ?> samples = encode(function("prospect_codec", "core_samples"),
                new ProspectingApi.SamplesAnswer(ok, 1, List.of(sample)));
        assertEquals(1L, samples.get("sample_count"));
        assertEquals(7L, ((Map<?, ?>) ((List<?>) samples.get("samples")).get(0)).get("slot"));

        final ProspectingApi.PruneAnswer prune = new ProspectingApi.PruneAnswer(ok, 3, 2);
        final Map<?, ?> pruned = encode(function("prospect_codec", "prune_samples"), prune);
        assertEquals(3L, pruned.get("removed"));
        assertEquals(2L, pruned.get("remaining_ore_samples"));

        final ProspectingApi.ToolState tool = new ProspectingApi.ToolState("recon_pick", 2, "right_click",
                4, 120, 116, false, 2, 58);
        final Map<?, ?> tools = encode(function("prospect_codec", "tools"),
                new ProspectingApi.ToolsAnswer(ok, List.of(tool)));
        assertEquals("recon_pick", ((Map<?, ?>) ((List<?>) tools.get("tools")).get(0)).get("id"));
        assertEquals(2L, ((Map<?, ?>) ((List<?>) tools.get("tools")).get(0)).get("count"));
        assertEquals(58L, ((Map<?, ?>) ((List<?>) tools.get("tools")).get(0)).get("uses_left"));

        final ProspectLedger.Entry entry = new ProspectLedger.Entry(100L, "recon_pick", 1, 64, -2, true,
                "none", false, false, true, false, 4, "uuid-1", "minecraft:overworld");
        final Map<?, ?> history = encode(function("prospect_codec", "history"),
                new ProspectingApi.HistoryAnswer(ok, "/tmp/ledger.jsonl", 1, List.of(entry)));
        final Map<?, ?> row = (Map<?, ?>) ((List<?>) history.get("entries")).get(0);
        assertEquals("recon_pick", row.get("tool"));
        assertEquals("none", row.get("error_kind"));
        assertEquals(true, row.get("converged"));
        assertEquals(false, row.get("strike_ready"));
    }
}
