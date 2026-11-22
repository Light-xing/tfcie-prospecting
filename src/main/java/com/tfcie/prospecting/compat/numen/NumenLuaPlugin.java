package com.tfcie.prospecting.compat.numen;

import com.dwinovo.numen.api.NumenPlugins;
import com.tfcie.prospecting.TfcieProspecting;

/**
 * numen 插件登记：把勘探仪器挂成 Lua ① 层 API，并给每轮 {@code <runtime_state>} 加一段
 * {@code <prospecting_state>}。
 *
 * <h2>只有这一个类持有 numen 的编译期契约</h2>
 * 本类在 numen 缺席时<b>不会被加载</b>（入口见 {@link NumenLuaBridge}）。编译依赖是
 * numen 的 {@code -api.jar}，{@code compileOnly}，不打包。
 *
 * <h2>名字空间与组</h2>
 * numen SDK 的调用名固定是三段 {@code <namespace>.<group>.<fn>}（{@code ScriptCatalog} +
 * {@code LuaEngine.pathName}），因此报告里写法的 {@code prospect.recon_pick} 在实现上是
 * {@code prospect.instrument.recon_pick}：名字空间取 {@code prospect}，组取领域名词 {@code instrument}。
 * 全部函数见 {@link ProspectingApi}。
 */
public final class NumenLuaPlugin {

    /** 名字空间：她的脚本里写成 {@code prospect.instrument.…}。 */
    public static final String NAMESPACE = "prospect";

    /** 组名：领域名词（numen 的风格是 {@code maid} / {@code quest} 这一类）。 */
    public static final String GROUP = "instrument";

    /** numen 的插件登记是进程级的；重复 setup 会撞「组已有主」。 */
    private static boolean installed;

    private NumenLuaPlugin() {}

    /** 登记一次。异常交给调用方（{@link NumenLuaBridge}）记日志。 */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;

        NumenPlugins.register(NAMESPACE, numen -> {
            numen.api(GROUP,
                    "Read the TFC × Immersive Engineering prospecting instruments. Every function runs the "
                            + "same reading a player gets by right-clicking: reach (4.5 blocks), exposed face and "
                            + "line-of-sight checks, wear, cooldown and energy all still apply. "
                            + "Use them to compose survey programs inside lua.",
                    ProspectingApi.class);

            // 报告 16 Phase 2：漂移/校准、上一磁测站、各仪器耐久与数量，自动进每轮 <runtime_state>
            numen.contributeBodyState(ProspectingState::describe);
        });

        TfcieProspecting.LOGGER.info(
                "[tfcie_prospecting] 已向 numen 登记 Lua API 组 {}.{}（7 个勘探函数 + tools/history）与 <prospecting_state>",
                NAMESPACE, GROUP);
    }
}
