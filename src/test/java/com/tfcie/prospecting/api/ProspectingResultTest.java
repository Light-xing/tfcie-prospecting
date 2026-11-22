package com.tfcie.prospecting.api;

import com.tfcie.prospecting.api.enums.Confidence;
import com.tfcie.prospecting.api.enums.DensityAnomaly;
import com.tfcie.prospecting.api.enums.MagneticClass;
import com.tfcie.prospecting.api.enums.OreGroup;
import com.tfcie.prospecting.api.enums.Presence;
import com.tfcie.prospecting.api.enums.TerminatedBy;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证 {@link ProspectingResult} 的 JSON 序列化与术语枚举的 wire 值。 */
public class ProspectingResultTest {

    @Test
    public void toJson_serializesWireEnumsAsWireNames() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("presence", Presence.STRONG);
        data.put("ore_group", OreGroup.METALLIC);
        data.put("confidence", Confidence.MEDIUM);
        data.put("radius", 8);
        data.put("saturated", false);

        ProspectingResult r = ProspectingResult.ok("recon_pick", "附近明显，见金属矿迹象（置信度：中）", data);

        String json = r.toJson();
        assertTrue(json.contains("\"tool\":\"recon_pick\""));
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"presence\":\"strong\""));
        assertTrue(json.contains("\"ore_group\":\"metallic\""));
        assertTrue(json.contains("\"confidence\":\"medium\""));
        assertTrue(json.contains("\"radius\":8"));
        assertTrue(json.contains("\"saturated\":false"));
    }

    @Test
    public void toJson_failure_marksSuccessFalse() {
        ProspectingResult r = ProspectingResult.fail("induction_probe", "能量不足，无法发射脉冲");
        String json = r.toJson();
        assertTrue(json.contains("\"success\":false"));
        assertFalse(json.contains("\"data\""));
    }

    @Test
    public void enums_exposeCorrectWireValues() {
        assertEquals("metallic", OreGroup.METALLIC.wire());
        assertEquals("gem", OreGroup.GEM.wire());
        assertEquals("low_density", DensityAnomaly.LOW_DENSITY.wire());
        assertEquals("magnetic", MagneticClass.MAGNETIC.wire());
        assertEquals("max_depth", TerminatedBy.MAX_DEPTH.wire());
    }
}
