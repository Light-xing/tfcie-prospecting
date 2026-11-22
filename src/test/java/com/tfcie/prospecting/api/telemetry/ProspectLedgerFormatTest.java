package com.tfcie.prospecting.api.telemetry;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** 台账 JSONL 的字段名契约（报告 16 Phase 2 + 报告 17 §7.2）：离线评测脚本按这 14 个键读。 */
public class ProspectLedgerFormatTest {

    @Test
    public void rowHasExactlyTheAgreedKeys() {
        final JsonObject row = ProspectLedger.row(1200L, "recon_pick", 10, 64, -3, true, "none",
                false, true, false, false, 2, "uuid-1", "minecraft:overworld");
        final List<String> keys = new ArrayList<>(row.keySet());
        assertEquals(List.of("tick", "tool", "x", "y", "z", "success", "error_kind", "drifted", "saturated",
                "converged", "strike_ready", "wear", "companion", "dimension"), keys);
        assertEquals("uuid-1", row.get("companion").getAsString());
        assertEquals("minecraft:overworld", row.get("dimension").getAsString());
        assertEquals(1200L, row.get("tick").getAsLong());
        assertEquals("recon_pick", row.get("tool").getAsString());
        assertEquals(true, row.get("saturated").getAsBoolean());
        assertEquals(2, row.get("wear").getAsInt());
    }
}
