package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.enums.Confidence;
import com.tfcie.prospecting.api.enums.OreGroup;
import com.tfcie.prospecting.api.enums.Presence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 踏勘镐的采样会话状态机（按区块计），实现设计文档 v1.3 的收敛 / 饱和规则。
 *
 * <h2>规则（v1.3）</h2>
 * <ul>
 *   <li><b>有效采样</b>＝未触发假阴性的采样；假阴性<b>不计数、也不中断</b>
 *       （由调用方在触发假阴性时直接跳过 {@link #accept}，状态原样保留）；</li>
 *   <li><b>判读一致</b>＝矿族大类（{@code ore_group}）一致即视为一致，
 *       疑似矿名取多次采样中的<b>多数</b>结果；</li>
 *   <li>累计 {@code converge_samples}（默认 3）次<b>连续一致</b>的有效采样 → <b>收敛</b>；</li>
 *   <li>某次有效采样矿族与当前累计不一致（<b>分歧</b>）→ 连续计数<b>清零、从零重计</b>
 *       （<b>不立即饱和</b>）；</li>
 *   <li>累计有效采样达 {@code saturation_after}（默认 6）次仍未收敛 → <b>饱和</b>（须换区块）；</li>
 *   <li><b>收敛后</b>继续采样即饱和，仅重复既有结论、无新增信息。</li>
 * </ul>
 *
 * <p>v1.3 的关键修正：{@code saturation_after} 必须 <b>大于</b> {@code converge_samples}，
 * 否则第 3 次采样出现一次分歧就会永久饱和、永远无法收敛。本实现中 {@code validSamples}
 * 只累计、不被分歧清零，因此饱和判据与收敛判据互不锁死。
 *
 * <p>状态存储于工具 NBT（{@code tfcie_prospecting:recon_session}），换区块自动重开。
 */
public record ReconSession(long chunkX,
                           long chunkZ,
                           @Nullable OreGroup group,
                           int consecutiveMatches,
                           int validSamples,
                           Map<String, Integer> nameVotes,
                           boolean converged,
                           boolean saturated) {

    /** 工具 NBT 中的会话根键。 */
    public static final String NBT_ROOT = "tfcie_prospecting:recon_session";

    private static final String KEY_CHUNK_X = "chunk_x";
    private static final String KEY_CHUNK_Z = "chunk_z";
    private static final String KEY_GROUP = "group";
    private static final String KEY_CONSECUTIVE = "consecutive";
    private static final String KEY_VALID = "valid";
    private static final String KEY_VOTES = "votes";
    private static final String KEY_CONVERGED = "converged";
    private static final String KEY_SATURATED = "saturated";

    public ReconSession {
        nameVotes = Map.copyOf(nameVotes);
    }

    /** 新区块的全新会话。 */
    public static ReconSession fresh(long chunkX, long chunkZ) {
        return new ReconSession(chunkX, chunkZ, null, 0, 0, Map.of(), false, false);
    }

    public boolean belongsTo(long cx, long cz) {
        return chunkX == cx && chunkZ == cz;
    }

    /**
     * 接受一次<b>有效采样</b>（假阴性不应调用本方法）。
     *
     * @param oreId            本次判读的矿名 id（可为 null，表示只判出矿族）
     * @param g                本次判读的矿族
     * @param convergeSamples  收敛所需连续一致次数
     * @param saturationAfter  饱和阈值（累计有效采样数）
     * @return 新的会话状态
     */
    public ReconSession accept(@Nullable String oreId, OreGroup g, int convergeSamples, int saturationAfter) {
        if (saturated || converged) {
            // 收敛后继续采样即饱和：仅重复既有结论，无新增信息。
            return saturated ? this
                    : new ReconSession(chunkX, chunkZ, group, consecutiveMatches, validSamples,
                            nameVotes, converged, true);
        }

        final boolean sameGroup = (group == g);
        final int consecutive = sameGroup ? consecutiveMatches + 1 : 1;

        final Map<String, Integer> votes = new LinkedHashMap<>(nameVotes);
        if (oreId != null) {
            votes.merge(oreId, 1, Integer::sum);
        }
        final int valid = validSamples + 1;

        final boolean nowConverged = consecutive >= convergeSamples;
        final boolean nowSaturated = !nowConverged && valid >= saturationAfter;

        return new ReconSession(chunkX, chunkZ, g, consecutive, valid, votes, nowConverged, nowSaturated);
    }

    /** 疑似矿名：多数投票结果（平票时取字典序较小者，保证确定性）。 */
    @Nullable
    public String suspectedOreId() {
        return nameVotes.entrySet().stream()
                .max(Comparator
                        .<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** 置信度：收敛为 medium，否则 low（上限 medium，永不 high）。 */
    public Confidence confidence() {
        return converged ? Confidence.MEDIUM : Confidence.LOW;
    }

    /** 由命中矿石方块数推导迹象三档。 */
    public static Presence presenceOf(int oreBlocks, int strongThreshold) {
        if (oreBlocks <= 0) {
            return Presence.NONE;
        }
        return oreBlocks >= strongThreshold ? Presence.STRONG : Presence.WEAK;
    }

    // ---------------- NBT ----------------

    public CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.putLong(KEY_CHUNK_X, chunkX);
        t.putLong(KEY_CHUNK_Z, chunkZ);
        if (group != null) {
            t.putString(KEY_GROUP, group.wire());
        }
        t.putInt(KEY_CONSECUTIVE, consecutiveMatches);
        t.putInt(KEY_VALID, validSamples);
        CompoundTag votes = new CompoundTag();
        nameVotes.forEach(votes::putInt);
        t.put(KEY_VOTES, votes);
        t.putBoolean(KEY_CONVERGED, converged);
        t.putBoolean(KEY_SATURATED, saturated);
        return t;
    }

    public static ReconSession fromTag(CompoundTag t) {
        OreGroup g = null;
        if (t.contains(KEY_GROUP)) {
            g = OreGroup.fromWire(t.getString(KEY_GROUP));
        }
        Map<String, Integer> votes = new LinkedHashMap<>();
        CompoundTag v = t.getCompound(KEY_VOTES);
        for (String k : v.getAllKeys()) {
            votes.put(k, v.getInt(k));
        }
        return new ReconSession(
                t.getLong(KEY_CHUNK_X),
                t.getLong(KEY_CHUNK_Z),
                g,
                t.getInt(KEY_CONSECUTIVE),
                t.getInt(KEY_VALID),
                votes,
                t.getBoolean(KEY_CONVERGED),
                t.getBoolean(KEY_SATURATED));
    }

    // ---------------- ItemStack 桥接 ----------------

    @Nullable
    public static ReconSession read(ItemStack stack) {
        CompoundTag t = stack.getTagElement(NBT_ROOT);
        return t == null ? null : fromTag(t);
    }

    public static void write(ItemStack stack, ReconSession session) {
        stack.getOrCreateTag().put(NBT_ROOT, session.toTag());
    }

    public static void clear(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(NBT_ROOT);
        }
    }

    /** 取得该区块的会话；换区块则自动重开并写回。 */
    public static ReconSession forChunk(ItemStack stack, long cx, long cz) {
        ReconSession s = read(stack);
        if (s == null || !s.belongsTo(cx, cz)) {
            s = fresh(cx, cz);
            write(stack, s);
        }
        return s;
    }
}
