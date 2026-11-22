package com.tfcie.prospecting.compat.numen;

import com.tfcie.prospecting.TfcieProspecting;

/**
 * {@code compat.numen} 里负责「探测 numen 插件门是否在场」的那一层。
 *
 * <p>本类<b>不引用任何 numen 类型</b>：只用 {@link Class#forName} 判断
 * {@code NumenPlugins} / {@code NumenPlugin} / {@code sdk.Fn} 是否存在，
 * 确认在场后才去调 {@link NumenLuaPlugin#install()}（那个类才持有编译期 Numen 契约）。
 * 这样 numen 缺席（或只有 0.1.3 这类没有插件门的旧版）时，JVM 永远不会解析
 * {@code NumenLuaPlugin} 的常量池，不会抛 {@code NoClassDefFoundError}。
 */
public final class NumenLuaBridge {

    private static final String CLS_NUMEN_PLUGINS = "com.dwinovo.numen.api.NumenPlugins";
    private static final String CLS_NUMEN_PLUGIN = "com.dwinovo.numen.api.NumenPlugin";
    private static final String CLS_FN = "com.dwinovo.numen.api.sdk.Fn";

    /** 一次探测是否已做过（numen 的登记是进程级的）。 */
    private static boolean attempted;

    private NumenLuaBridge() {}

    /**
     * 若 numen 的插件门在场则登记 Lua API 插件；否则静默跳过。
     * 由 {@link NumenBridge#registerIfPresent()} 在模组构造期调用（那里同时也负责顶层工具）。
     */
    public static void registerIfPresent() {
        if (attempted) {
            return;
        }
        attempted = true;

        try {
            Class.forName(CLS_NUMEN_PLUGINS);
            Class.forName(CLS_NUMEN_PLUGIN);
            Class.forName(CLS_FN);
        } catch (ClassNotFoundException | LinkageError absent) {
            TfcieProspecting.LOGGER.info(
                    "[tfcie_prospecting] 未检测到 numen 插件门（需要 0.1.5+），跳过 Lua ① 层 API 注册（顶层工具不受影响）");
            return;
        }

        try {
            NumenLuaPlugin.install();
        } catch (Throwable failure) {
            // 注册失败不该拖垮模组加载：顶层工具与命令行通道仍然可用
            TfcieProspecting.LOGGER.warn("[tfcie_prospecting] numen Lua API 注册失败（已忽略）", failure);
        }
    }
}
