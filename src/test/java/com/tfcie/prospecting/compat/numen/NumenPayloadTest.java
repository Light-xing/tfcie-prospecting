package com.tfcie.prospecting.compat.numen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tfcie.prospecting.api.ProspectingResult;
import com.tfcie.prospecting.api.enums.OreGroup;
import com.tfcie.prospecting.api.enums.Presence;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * numen 适配层的载荷契约（阶段 6）。纯逻辑，不需要游戏运行时。
 */
public class NumenPayloadTest {

    // ---------------- 入参 ----------------

    @Test
    public void readTargetReturnsNullWhenArgsAbsent() {
        assertNull(NumenPayload.readTarget(null));
        assertNull(NumenPayload.readTarget(new JsonObject()));
    }

    @Test
    public void readTargetNeedsAllThreeCoordinates() {
        // 只给两个坐标视为「对空使用」，而不是补 0 —— 补 0 会静默钻错地方
        final JsonObject partial = new JsonObject();
        partial.addProperty(NumenPayload.KEY_X, 10);
        partial.addProperty(NumenPayload.KEY_Y, 64);
        assertNull(NumenPayload.readTarget(partial));

        final JsonObject jsonNull = new JsonObject();
        jsonNull.add(NumenPayload.KEY_X, null);
        jsonNull.addProperty(NumenPayload.KEY_Y, 64);
        jsonNull.addProperty(NumenPayload.KEY_Z, -3);
        assertNull(NumenPayload.readTarget(jsonNull));
    }

    @Test
    public void readTargetParsesCompleteCoordinates() {
        final JsonObject args = new JsonObject();
        args.addProperty(NumenPayload.KEY_X, 120);
        args.addProperty(NumenPayload.KEY_Y, 74);
        args.addProperty(NumenPayload.KEY_Z, -330);
        assertEquals(new BlockPos(120, 74, -330), NumenPayload.readTarget(args));
    }

    // ---------------- 出参 ----------------

    @Test
    public void taskResultUsesNumenShapeAndKeepsEnumWireNames() {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("presence", Presence.STRONG);
        data.put("ore_group", OreGroup.METALLIC);
        data.put("saturated", false);

        final ProspectingResult result =
                ProspectingResult.ok("recon_pick", "附近明显，见金属矿迹象（置信度：中）", data);

        final JsonObject json = JsonParser.parseString(NumenPayload.toTaskResult(result)).getAsJsonObject();

        // numen 的 TaskResult 形状：success / message / data
        assertTrue(json.get("success").getAsBoolean());
        assertEquals("附近明显，见金属矿迹象（置信度：中）", json.get("message").getAsString());
        final JsonObject payload = json.getAsJsonObject("data");
        // 工具名补进 data，不污染顶层
        assertEquals("recon_pick", payload.get("tool").getAsString());
        // 关键：重塑外形后枚举仍是 wire 名，而不是 Java 枚举名
        assertEquals("strong", payload.get("presence").getAsString());
        assertEquals("metallic", payload.get("ore_group").getAsString());
        assertFalse(payload.get("saturated").getAsBoolean());
    }

    @Test
    public void failedResultKeepsSuccessFalse() {
        final ProspectingResult result = ProspectingResult.fail("core_sampler", "需要对准实体方块");
        final JsonObject json = JsonParser.parseString(NumenPayload.toTaskResult(result)).getAsJsonObject();

        assertFalse(json.get("success").getAsBoolean());
        assertEquals("core_sampler", json.getAsJsonObject("data").get("tool").getAsString());
    }

    @Test
    public void resultWithoutDataStillProducesEmptyDataObject() {
        final ProspectingResult result = ProspectingResult.ok("magnetic_needle", "无磁性异常");
        final JsonObject json = JsonParser.parseString(NumenPayload.toTaskResult(result)).getAsJsonObject();

        assertTrue(json.get("success").getAsBoolean());
        assertTrue(json.getAsJsonObject("data").has("tool"));
    }

    @Test
    public void failurePayloadIsWellFormed() {
        final JsonObject json = JsonParser.parseString(NumenPayload.failure("boom")).getAsJsonObject();

        assertFalse(json.get("success").getAsBoolean());
        assertEquals("boom", json.get("message").getAsString());
        assertTrue(json.getAsJsonObject("data").isEmpty());
    }
}
