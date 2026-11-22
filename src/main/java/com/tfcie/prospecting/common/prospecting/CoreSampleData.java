package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.enums.TerminatedBy;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 钻探样本的数据结构（D1 数据物品的 NBT 载荷）。
 *
 * <h2>真值工具的唯一产物</h2>
 * 钻探是四物探工具之外<b>唯一的真值工具</b>：它给的是精确深度、厚度与岩层序列，
 * 但代价是昂贵、缓慢、且只能测正下方一个点。红线是<b>不产出任何矿物掉落物</b> ——
 * 它只产出数据。
 *
 * <h2>语言处理</h2>
 * 每层同时存 {@code block}（注册名，如 {@code tfc:dirt}）与 {@code display}
 * （服务端渲染的显示名）。GUI 优先用 {@code block} 在<b>客户端</b>按玩家语言本地化，
 * 因此中英文客户端都能正确显示；{@code display} 与 {@code summary} 保留给
 * 智能体侧 / 日志这类纯文本场景。
 */
public record CoreSampleData(BlockPos origin,
                             ResourceLocation dimension,
                             int startY,
                             int endY,
                             int depth,
                             TerminatedBy terminatedBy,
                             double recoveryRate,
                             List<Layer> runs,
                             List<OreHit> oreHits,
                             String summary,
                             long recordedAt) {

    /** 工具 NBT 中的根键。 */
    public static final String NBT_ROOT = "tfcie_prospecting:core_sample";

    /**
     * 一个岩层段（相邻同类方块合并而来）。
     *
     * @param block     方块注册名（用于客户端本地化）
     * @param display   服务端渲染的显示名（纯文本场景用）
     * @param thickness 厚度（格）
     * @param yFrom     起始 y（较大，位于上方）
     * @param yTo       结束 y（较小，位于下方）
     * @param cored     本段是否成功取芯（松散层可能未取芯）
     */
    public record Layer(String block, String display, int thickness, int yFrom, int yTo, boolean cored) {}

    /** 含矿层（真值：精确深度与厚度）。 */
    public record OreHit(String block, String display, int y, int thickness) {}

    public CoreSampleData {
        runs = List.copyOf(runs);
        oreHits = List.copyOf(oreHits);
    }

    /** 含矿层段数（含矿层按段计）。 */
    public int oreLayerCount() {
        return oreHits.size();
    }

    /** 是否存在未取芯的缺失段。 */
    public boolean hasUncored() {
        return runs.stream().anyMatch(l -> !l.cored());
    }

    /** 智能体侧的结构化载荷（供 {@code ProspectingResult.data}）。 */
    public java.util.Map<String, Object> toData() {
        final java.util.Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("origin_x", origin.getX());
        d.put("origin_y", origin.getY());
        d.put("origin_z", origin.getZ());
        d.put("dimension", dimension.toString());
        d.put("start_y", startY);
        d.put("end_y", endY);
        d.put("depth", depth);
        d.put("terminated_by", terminatedBy);
        d.put("recovery_rate", recoveryRate);
        d.put("ore_layer_count", oreHits.size());
        d.put("ore_hits", oreHits);
        d.put("summary", summary);
        d.put("recorded_at", recordedAt);
        return d;
    }

    // ---------------- NBT ----------------

    public CompoundTag toTag() {
        final CompoundTag root = new CompoundTag();

        final CompoundTag originTag = new CompoundTag();
        originTag.putInt("x", origin.getX());
        originTag.putInt("y", origin.getY());
        originTag.putInt("z", origin.getZ());
        root.put("origin", originTag);

        root.putString("dimension", dimension.toString());
        root.putInt("start_y", startY);
        root.putInt("end_y", endY);
        root.putInt("depth", depth);
        root.putString("terminated_by", terminatedBy.wire());
        root.putDouble("recovery_rate", recoveryRate);
        root.putString("summary", summary);
        root.putLong("recorded_at", recordedAt);

        final ListTag runList = new ListTag();
        for (Layer layer : runs) {
            final CompoundTag t = new CompoundTag();
            t.putString("block", layer.block());
            t.putString("display", layer.display());
            t.putInt("thickness", layer.thickness());
            t.putInt("y_from", layer.yFrom());
            t.putInt("y_to", layer.yTo());
            t.putBoolean("cored", layer.cored());
            runList.add(t);
        }
        root.put("runs", runList);

        final ListTag oreList = new ListTag();
        for (OreHit hit : oreHits) {
            final CompoundTag t = new CompoundTag();
            t.putString("block", hit.block());
            t.putString("display", hit.display());
            t.putInt("y", hit.y());
            t.putInt("thickness", hit.thickness());
            oreList.add(t);
        }
        root.put("ore_hits", oreList);

        return root;
    }

    public static CoreSampleData fromTag(CompoundTag root) {
        final CompoundTag originTag = root.getCompound("origin");
        final BlockPos origin = new BlockPos(
                originTag.getInt("x"), originTag.getInt("y"), originTag.getInt("z"));

        final ResourceLocation dimension = root.contains("dimension")
                ? ResourceLocation.tryParse(root.getString("dimension"))
                : null;

        final List<Layer> runs = new ArrayList<>();
        final ListTag runList = root.getList("runs", Tag.TAG_COMPOUND);
        for (int i = 0; i < runList.size(); i++) {
            final CompoundTag t = runList.getCompound(i);
            runs.add(new Layer(
                    t.getString("block"),
                    t.getString("display"),
                    t.getInt("thickness"),
                    t.getInt("y_from"),
                    t.getInt("y_to"),
                    !t.contains("cored") || t.getBoolean("cored")));
        }

        final List<OreHit> ores = new ArrayList<>();
        final ListTag oreList = root.getList("ore_hits", Tag.TAG_COMPOUND);
        for (int i = 0; i < oreList.size(); i++) {
            final CompoundTag t = oreList.getCompound(i);
            ores.add(new OreHit(
                    t.getString("block"),
                    t.getString("display"),
                    t.getInt("y"),
                    t.getInt("thickness")));
        }

        final TerminatedBy terminated = com.tfcie.prospecting.api.WireEnum.fromWire(
                TerminatedBy.class,
                root.contains("terminated_by") ? root.getString("terminated_by") : null,
                TerminatedBy.MAX_DEPTH);

        return new CoreSampleData(
                origin,
                dimension == null ? ResourceLocation.withDefaultNamespace("overworld") : dimension,
                root.getInt("start_y"),
                root.getInt("end_y"),
                root.getInt("depth"),
                terminated,
                root.getDouble("recovery_rate"),
                runs,
                ores,
                root.getString("summary"),
                root.getLong("recorded_at"));
    }

    /** 空样本（理论上不应出现，用于读取失败时的安全回落）。 */
    public static CoreSampleData empty() {
        return new CoreSampleData(BlockPos.ZERO, ResourceLocation.withDefaultNamespace("overworld"),
                0, 0, 0, TerminatedBy.MAX_DEPTH, 0.0D,
                Collections.emptyList(), Collections.emptyList(), "", 0L);
    }
}
