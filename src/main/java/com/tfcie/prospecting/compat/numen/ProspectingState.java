package com.tfcie.prospecting.compat.numen;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.tfcie.prospecting.common.item.AbstractProspectingItem;
import com.tfcie.prospecting.common.item.CoreSampleItem;
import com.tfcie.prospecting.common.item.CoreSamplerItem;
import com.tfcie.prospecting.common.prospecting.DensityPlumbProspector;
import com.tfcie.prospecting.config.ProspectingConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每轮 {@code <runtime_state>} 里的 {@code <prospecting_state>} 片段。
 *
 * <p>经 {@code NumenApi#contributeBodyState} 注册，由 numen 在检查身体状态时调用（服务端、只读）。
 * 报告 16 Phase 2 要求挂三件事：密度探锤的漂移/是否需校准、上一磁测站坐标、各仪器耐久与数量。
 *
 * <h2>为什么自己读 NBT</h2>
 * 两个状态的读取器在 {@code common.prospecting} 里是私有的（{@code readStation}、
 * {@code readDrift} 只暴露了 {@code driftOf}）。为了不改动正在并行修改的算法类，
 * 这里按<b>同一份 NBT 契约</b>只读：根键 {@code tfcie_prospecting:density_drift}
 * （{@code uses}）与 {@code tfcie_prospecting:magnetic_station}（{@code x/y/z}）。
 * 键名若在算法类里改名，这里要跟着改（两处常量都写死在各自文件里）。
 *
 * <h2>成本与变化检测</h2>
 * 只扫背包 + 读 NBT，不做方块扫描（{@code is_at_base} 那种 27×5×27 的查询会随每次状态检查变贵）。
 * 输出的字符串只在「使用工具」时变化，不会每 tick 推包。
 */
public final class ProspectingState {

    /** 密度探锤漂移的 NBT 根键与字段（镜像 DensityPlumbProspector 的私有常量）。 */
    private static final String DENSITY_ROOT = "tfcie_prospecting:density_drift";
    private static final String KEY_USES = "uses";

    /** 上一磁测站的 NBT 根键与字段（镜像 MagneticNeedleProspector 的私有常量）。 */
    private static final String MAGNETIC_ROOT = "tfcie_prospecting:magnetic_station";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";

    private ProspectingState() {}

    /**
     * 一段状态；身上没有任何勘探仪器时返回空串（numen 不会输出空片段）。
     * 抛异常由 numen 隔离，但这里仍自己收一层，保证片段永远只是一段文字。
     */
    public static String describe(NumenPlayer body) {
        try {
            return render(body);
        } catch (RuntimeException failure) {
            return "";
        }
    }

    private static String render(NumenPlayer body) {
        final Map<String, Stat> carried = carried(body);
        if (carried.isEmpty()) {
            return "";
        }

        final StringBuilder sb = new StringBuilder("<prospecting_state>");
        boolean first = true;

        // ---- 密度探锤：漂移计数 + 是否必须回基点复测 ----
        final Stat plumb = carried.get("density_plumb");
        if (plumb != null) {
            final CompoundTag drift = plumb.stack.getTagElement(DENSITY_ROOT);
            final int uses = drift == null ? 0 : Math.max(0, drift.getInt(KEY_USES));
            final int threshold = ProspectingConfig.intOr(ProspectingConfig.DensityPlumb.driftResetAfterUses, 3);
            first = append(sb, first, "density_plumb drift=" + DensityPlumbProspector.driftOf(plumb.stack)
                    + " blocks uses=" + uses + "/" + threshold
                    + (uses >= threshold ? " needs_calibration" : ""));
        }

        // ---- 磁力探针：上一测站坐标（两点法走向的另一端） ----
        final Stat needle = carried.get("magnetic_needle");
        if (needle != null) {
            final CompoundTag station = needle.stack.getTagElement(MAGNETIC_ROOT);
            if (station != null && station.contains(KEY_X) && station.contains(KEY_Y) && station.contains(KEY_Z)) {
                first = append(sb, first, "magnetic_needle last_station=(" + station.getInt(KEY_X) + ","
                        + station.getInt(KEY_Y) + "," + station.getInt(KEY_Z) + ")");
            }
        }

        // ---- 钻探取样器：当前钻深档位 + 背包已有钻探样本数 ----
        final Stat sampler = carried.get("core_sampler");
        final int samples = sampleCount(body);
        if (sampler != null || samples > 0) {
            final StringBuilder drill = new StringBuilder();
            if (sampler != null) {
                drill.append("core_sampler depth=").append(CoreSamplerItem.currentDepth(sampler.stack));
            }
            if (samples > 0) {
                if (drill.length() > 0) {
                    drill.append(' ');
                }
                drill.append("core_samples=").append(samples);
            }
            first = append(sb, first, drill.toString());
        }

        // ---- 各仪器数量与耐久（按 id 字典序，输出稳定） ----
        final StringBuilder tools = new StringBuilder();
        carried.forEach((id, stat) -> {
            if (tools.length() > 0) {
                tools.append(", ");
            }
            tools.append(id).append(" x").append(stat.count);
            if (stat.maxDurability > 0) {
                tools.append(" wear ").append(stat.wear).append('/').append(stat.maxDurability);
            }
        });
        append(sb, first, "tools " + tools);

        return sb.append("</prospecting_state>").toString();
    }

    /** 一件携带工具的首次出现位置、数量与耐久。 */
    private record Stat(ItemStack stack, int count, int wear, int maxDurability) {

        static Stat of(ItemStack stack, AbstractProspectingItem item) {
            return new Stat(stack, 1, item.wear(stack), item.maxDurability(stack));
        }

        Stat plus(AbstractProspectingItem item) {
            return new Stat(stack, count + 1, wear, maxDurability);
        }
    }

    /** 背包（含双手与护甲槽，原版 getContainerSize）里按工具 id 汇总；空手返回空表。 */
    private static Map<String, Stat> carried(NumenPlayer body) {
        final Map<String, Stat> out = new LinkedHashMap<>();
        final var inventory = body.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof AbstractProspectingItem item)) {
                continue;
            }
            final Stat previous = out.get(item.toolId());
            out.put(item.toolId(), previous == null ? Stat.of(stack, item) : previous.plus(item));
        }
        return out;
    }

    private static int sampleCount(NumenPlayer body) {
        int count = 0;
        final var inventory = body.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof CoreSampleItem) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean append(StringBuilder sb, boolean first, String fragment) {
        if (!first) {
            sb.append("; ");
        }
        sb.append(fragment);
        return false;
    }
}
