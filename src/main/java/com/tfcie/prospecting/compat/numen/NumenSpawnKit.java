package com.tfcie.prospecting.compat.numen;

import com.tfcie.prospecting.TfcieProspecting;
import com.tfcie.prospecting.registry.StarterKit;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.RegistryObject;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 开局补给：同伴的身体一进世界（召唤、从休眠唤醒、重生），把「基础包」补进它的背包。
 *
 * <h2>为什么还要这个，明明有 /prospect kit</h2>
 * {@code /prospect kit} 是给人用的<b>手动</b>通道；报告 13 §7.1 的问题却是「新建的身体背包全空，
 * agent 得先讨工具才能开始勘探」。建体时 owner 未必记得敲命令，评测里也没人替它敲 ——
 * 所以挂到 numen 的 {@code CompanionEvent.SPAWN} 上自动补。两条通道并存，互不替代。
 *
 * <h2>发什么由 {@link StarterKit} 决定</h2>
 * 这里<b>不再</b>按 {@code AbstractProspectingItem} 全量发放：那会把改进型与筛选类工具也塞进
 * 背包，而它们按设计刻意不暴露给智能体（报告 13 §11.4 / §12.2）。清单只在 {@link StarterKit}
 * 维护一份，SPAWN 与 {@code /prospect kit} 共用，两条通道不会走偏。
 *
 * <h2>接入方式：照旧反射，且只碰声明类型</h2>
 * 与 {@link NumenBridge} 同一套理由（numen-api 没发布制品，不能声明编译期依赖）与同一条纪律
 * （对<b>声明类型</b>取 Method/Field，绝不对 numen 传来的实例取 {@code getClass()}）。
 * 这里用到三处声明类型：{@code NumenPlugins#register(String, NumenPlugin)}、
 * {@code NumenApi#on(CompanionEvent, Consumer)}、{@code CompanionEvent.SPAWN}；
 * 插件接口与事件处理器都用 {@link Proxy} 实现。numen 缺席或版本过旧时静默跳过。
 *
 * <h2>只补缺席的</h2>
 * 重生（原版会换一具新身体）与重登都会再发一次 {@code SPAWN}。背包里已有该物品就跳过，
 * 所以不会越活越多；补了什么、还是没补，都经聊天事件报给模型（见下）。
 *
 * <h2>为什么读数要推迟到本刻末尾</h2>
 * {@code SPAWN} 是在 numen 加入身体的过程中、标记「已到达」<b>之前</b>发出的，而 numen 在到达之前
 * 会丢弃所有发给她的聊天（目的：避免把她自己刚上线时的加入广播当成对她说的话）。
 * 因此物品当场补，聊天读数用 {@link MinecraftServer#execute} 排到本刻任务队列末尾再发 ——
 * 那时已到达，读数才进得了 {@code server_message} 事件流。
 */
public final class NumenSpawnKit {

    private static final String CLS_NUMEN_PLUGINS = "com.dwinovo.numen.api.NumenPlugins";
    private static final String CLS_NUMEN_PLUGIN = "com.dwinovo.numen.api.NumenPlugin";
    private static final String CLS_NUMEN_API = "com.dwinovo.numen.api.NumenApi";
    private static final String CLS_COMPANION_EVENT = "com.dwinovo.numen.api.CompanionEvent";

    /** 插件名字空间：小写字母开头、只含 [a-z0-9_]，通常就是 mod id。 */
    private static final String NAMESPACE = "tfcie_prospecting";

    /** 一次登记是否已尝试过（重复登记同一名字空间会被 numen 当成重复插件）。 */
    private static boolean attempted;

    private NumenSpawnKit() {}

    /**
     * 若 numen 在场则登记开局补给插件；否则静默跳过。
     * 由主类在模组构造期调用 —— 与 {@link NumenBridge#registerIfPresent()} 同一时机，numen 会自己等就绪。
     */
    public static void registerIfPresent() {
        if (attempted) {
            return;
        }
        attempted = true;

        final Class<?> pluginsClass;
        final Class<?> pluginInterface;
        final Class<?> apiClass;
        final Class<?> eventClass;
        try {
            pluginsClass = Class.forName(CLS_NUMEN_PLUGINS);
            pluginInterface = Class.forName(CLS_NUMEN_PLUGIN);
            apiClass = Class.forName(CLS_NUMEN_API);
            eventClass = Class.forName(CLS_COMPANION_EVENT);
        } catch (ClassNotFoundException absent) {
            TfcieProspecting.LOGGER.info(
                    "[tfcie_prospecting] 未检测到 numen 插件接口，跳过开局补给（工具仍可用 /prospect kit 手动发放）");
            return;
        }

        try {
            // 三个句柄全部取自声明类型：插件登记入口、事件订阅入口、SPAWN 常量
            final Method register = pluginsClass.getMethod("register", String.class, pluginInterface);
            final Method on = apiClass.getMethod("on", eventClass, Consumer.class);
            final Object spawn = eventClass.getField("SPAWN").get(null);

            final Object plugin = Proxy.newProxyInstance(
                    pluginInterface.getClassLoader(),
                    new Class<?>[]{pluginInterface},
                    new PluginHandler(on, spawn));
            register.invoke(null, NAMESPACE, plugin);
            TfcieProspecting.LOGGER.info("[tfcie_prospecting] 已向 numen 登记开局补给插件（同伴出生时自动补工具）");
        } catch (Throwable failure) {
            TfcieProspecting.LOGGER.warn(
                    "[tfcie_prospecting] numen 开局补给登记失败（已忽略，工具仍可用 /prospect kit 手动发放）", failure);
        }
    }

    // ---------------- 代理处理 ----------------

    /**
     * {@code NumenPlugin#setup(NumenApi)} 的代理：拿到门之后订阅一次 {@code SPAWN}。
     *
     * <p>{@code on} 与 {@code spawn} 都是在登记时对声明类型取好的句柄；{@code setup} 的参数 0 是
     * numen 递来的 {@code NumenApi}，直接当方法接收者用即可，不需要也不允许对它反射取成员。
     */
    private record PluginHandler(Method on, Object spawn) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("setup".equals(method.getName()) && args != null && args.length == 1 && args[0] != null) {
                final Object sink = Proxy.newProxyInstance(
                        NumenSpawnKit.class.getClassLoader(),
                        new Class<?>[]{Consumer.class},
                        new SpawnHandler());
                on.invoke(args[0], spawn, sink);
                return null;
            }
            return switch (method.getName()) {
                case "toString" -> "tfcie_prospecting starter kit plugin";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                default -> null;
            };
        }
    }

    /**
     * {@code Consumer<NumenPlayer>} 的代理：载荷是 {@code NumenPlayer}，它 extends {@code ServerPlayer}，
     * 因此这里直接按 {@link Player} 判断，不必认识 numen 的类型。
     */
    private static final class SpawnHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("accept".equals(method.getName()) && args != null && args.length == 1
                    && args[0] instanceof Player player) {
                try {
                    grantMissingTools(player);
                } catch (RuntimeException ex) {
                    // numen 的事件总线会隔离订阅者异常，这里再收一层：补给失败不该影响她进世界
                    TfcieProspecting.LOGGER.warn("[tfcie_prospecting] 开局补给失败", ex);
                }
                return null;
            }
            return switch (method.getName()) {
                case "toString" -> "tfcie_prospecting starter kit";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                default -> null;
            };
        }
    }

    // ---------------- 发放 ----------------

    /**
     * 补发背包里缺席的基础包物品，并把结果报给模型。
     *
     * <p>「有没有」按物品类型判断，不用 {@code Inventory#contains}：那个比的是整份物品与 NBT，
     * 而工具的耐久、采样状态都存在 NBT 里 —— 背包里那把很可能已经被用过，NBT 与原样不同。
     */
    private static void grantMissingTools(Player player) {
        final List<Item> missing = new ArrayList<>();
        for (RegistryObject<? extends Item> entry : StarterKit.ITEMS) {
            final Item item = entry.get();
            if (!hasItem(player, item)) {
                missing.add(item);
            }
        }

        if (missing.isEmpty()) {
            // 报给模型：这次 SPAWN 没补任何东西，免得它误以为又发了一遍
            announceLater(player, Component.translatable(
                    "message.tfcie_prospecting.spawn_kit.present"));
            return;
        }

        final MutableComponent names = Component.empty();
        boolean first = true;
        for (Item item : missing) {
            if (!first) {
                names.append(Component.translatable("message.tfcie_prospecting.spawn_kit.separator"));
            }
            first = false;
            final ItemStack stack = new ItemStack(item);
            names.append(stack.getHoverName());
            player.getInventory().placeItemBackInInventory(stack);
        }
        announceLater(player, Component.translatable(
                "message.tfcie_prospecting.spawn_kit.given", names));
    }

    /** 背包（含双手）里是否已有该物品；只看物品类型，见 {@link #grantMissingTools}。 */
    private static boolean hasItem(Player player, Item item) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把读数排到本刻任务队列末尾再发（原因见类注释最后一段）。
     * 没有服务端（理论上不会走到）就当场发，至少不丢信息。
     */
    private static void announceLater(Player player, Component message) {
        final MinecraftServer server = player.getServer();
        if (server == null) {
            player.displayClientMessage(message, false);
            return;
        }
        server.execute(() -> player.displayClientMessage(message, false));
    }
}
