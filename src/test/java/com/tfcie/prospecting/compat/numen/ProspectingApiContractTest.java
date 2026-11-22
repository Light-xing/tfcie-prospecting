package com.tfcie.prospecting.compat.numen;

import com.dwinovo.numen.api.sdk.ApiFunction;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import com.dwinovo.numen.api.sdk.FnArgs;
import com.tfcie.prospecting.api.enums.Confidence;
import com.tfcie.prospecting.api.enums.Presence;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Lua ① 层 API 的离线契约测试（报告 16 Phase 2）。
 *
 * <p>不启动游戏，直接用 numen 的 {@code ApiRegistry}/{@code Binder}/{@code LuaCodecs}
 * 做真实登记与返回值编码 —— 编译期看不出来的注解/参数/返回值错误会在这里炸出来：
 * 组名写不出来、{@code @Fn} 方法签名不对、参数 record 不是 record、返回值类型没有值转换等。
 */
public class ProspectingApiContractTest {

    @Test
    public void registersTenFunctionsInSortedOrder() {
        ApiRegistry.register("prospect_contract", "instrument", "contract test", ProspectingApi.class);
        final ApiRegistry.Group group = ApiRegistry.group("prospect_contract.instrument");
        assertNotNull("组应已登记", group);

        final List<String> names = new ArrayList<>();
        group.functions().forEach(fn -> names.add(fn.name()));
        assertEquals(List.of("core_sampler", "core_samples", "density_plumb", "echo_probe", "history",
                "induction_probe", "magnetic_needle", "prune_samples", "recon_pick", "tools"), names);
        assertEquals(10, names.size());

        // 名字空间是 prospect 时脚本里的调用名固定三段：prospect.instrument.<fn>
        assertTrue(group.fullName().endsWith(".instrument"));
    }

    @Test
    public void answerRecordsEncodeToLuaTables() {
        ApiRegistry.register("prospect_encode", "instrument", "encode test", ProspectingApi.class);
        final ApiFunction fn = ApiRegistry.function("prospect_encode.instrument.recon_pick");
        assertNotNull(fn);

        final ProspectingApi.Outcome outcome = new ProspectingApi.Outcome("recon_pick", true, "ok", "none");
        final ProspectingApi.ReconAnswer answer = new ProspectingApi.ReconAnswer(outcome,
                Optional.of(Presence.WEAK), Optional.empty(), Optional.empty(), Optional.of(Confidence.MEDIUM),
                true, false, false, Optional.of("boulder"));

        final Object table = fn.returns().encode(answer);
        assertTrue("返回值应编码成一张表", table instanceof Map<?, ?>);
        final Map<?, ?> map = (Map<?, ?>) table;
        assertEquals("recon_pick", map.get("tool"));
        assertEquals("ok", map.get("message"));
        assertEquals("boulder", map.get("interference"));
        assertEquals("weak", map.get("presence"));
        assertTrue("摊平的公共头字段应出现", map.containsKey("error_kind"));
        assertFalse("Optional.empty 的字段不应出现", map.containsKey("ore_group"));
    }

    @Test
    public void historyTakesOneOptionalPositional() {
        ApiRegistry.register("prospect_history", "instrument", "history test", ProspectingApi.class);
        final ApiFunction fn = ApiRegistry.function("prospect_history.instrument.history");
        assertNotNull(fn);
        assertEquals(1, fn.params().size());
        assertEquals(ApiFunction.Role.OPTIONAL, fn.params().get(0).role());

        final FnArgs args = fn.decode(new LinkedHashMap<>());
        assertNotNull(args);
    }

    @Test
    public void coreSamplerAcceptsPosAndOptionalDepth() {
        ApiRegistry.register("prospect_core", "instrument", "core test", ProspectingApi.class);
        final ApiFunction fn = ApiRegistry.function("prospect_core.instrument.core_sampler");
        assertNotNull(fn);
        assertEquals(2, fn.params().size());
        assertEquals(ApiFunction.Role.REQUIRED, fn.params().get(0).role());
        assertEquals(ApiFunction.Role.OPTIONAL, fn.params().get(1).role());
    }
}
