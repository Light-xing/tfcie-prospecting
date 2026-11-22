package com.tfcie.prospecting.common.nbt;

import com.tfcie.prospecting.api.ToolParam;
import com.tfcie.prospecting.api.ToolParameters;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * {@link ToolParameters} 的 NBT 读写（格雷科技式材质参数的落地）。
 *
 * <p>核心逻辑是纯 {@link CompoundTag} 函数（{@link #fromTag}/{@link #toTag}），
 * {@link #read}/{@link #write} 只是 ItemStack 上的薄封装 —— 这样 NBT schema 与
 * 白名单行为可以脱离 Forge 运行时被单测覆盖。
 *
 * <p>读取顺序：{@code NBT → 默认参数}；只有白名单内的参数会被 NBT 覆盖。
 * 存储形态：
 * <pre>
 * tfcie_prospecting:parameters {
 *     material: "bronze"
 *     max_durability: 256
 *     radius: 8
 *     ...
 * }
 * </pre>
 */
public final class ToolParameterNbt {

    /** NBT 根键。 */
    public static final String ROOT = "tfcie_prospecting:parameters";

    /** 从 ItemStack 读取参数；缺失字段回落到 {@code defaults}。 */
    public static ToolParameters read(ItemStack stack, ToolParameters defaults, Set<ToolParam> allowed) {
        return fromTag(stack.getTagElement(ROOT), defaults, allowed);
    }

    /** 把参数写入 ItemStack NBT；只写白名单内的参数。 */
    public static void write(ItemStack stack, ToolParameters params, Set<ToolParam> allowed) {
        stack.getOrCreateTag().put(ROOT, toTag(params, allowed));
    }

    /** 纯 NBT 读取：{@code tag → 默认参数}，只认白名单内的键。 */
    public static ToolParameters fromTag(@Nullable CompoundTag tag, ToolParameters defaults, Set<ToolParam> allowed) {
        ToolParameters p = defaults.copy();
        if (tag == null) {
            return p;
        }

        if (allowed.contains(ToolParam.MATERIAL) && tag.contains(ToolParam.MATERIAL.key())) {
            p.material(tag.getString(ToolParam.MATERIAL.key()));
        }
        if (allowed.contains(ToolParam.MAX_DURABILITY) && tag.contains(ToolParam.MAX_DURABILITY.key())) {
            p.maxDurability(tag.getInt(ToolParam.MAX_DURABILITY.key()));
        }
        if (allowed.contains(ToolParam.RADIUS) && tag.contains(ToolParam.RADIUS.key())) {
            p.radius(tag.getInt(ToolParam.RADIUS.key()));
        }
        if (allowed.contains(ToolParam.SHALLOW_DEPTH) && tag.contains(ToolParam.SHALLOW_DEPTH.key())) {
            p.shallowDepth(tag.getInt(ToolParam.SHALLOW_DEPTH.key()));
        }
        if (allowed.contains(ToolParam.FALSE_NEGATIVE_CHANCE) && tag.contains(ToolParam.FALSE_NEGATIVE_CHANCE.key())) {
            p.falseNegativeChance(tag.getDouble(ToolParam.FALSE_NEGATIVE_CHANCE.key()));
        }
        if (allowed.contains(ToolParam.CONFIDENCE_CAP) && tag.contains(ToolParam.CONFIDENCE_CAP.key())) {
            p.confidenceCap(tag.getString(ToolParam.CONFIDENCE_CAP.key()));
        }
        if (allowed.contains(ToolParam.ENERGY_COST) && tag.contains(ToolParam.ENERGY_COST.key())) {
            p.energyCost(tag.getInt(ToolParam.ENERGY_COST.key()));
        }
        if (allowed.contains(ToolParam.MAX_DEPTH) && tag.contains(ToolParam.MAX_DEPTH.key())) {
            p.maxDepth(tag.getInt(ToolParam.MAX_DEPTH.key()));
        }
        return p;
    }

    /** 纯 NBT 写入：只写白名单内的键。 */
    public static CompoundTag toTag(ToolParameters params, Set<ToolParam> allowed) {
        CompoundTag tag = new CompoundTag();
        if (allowed.contains(ToolParam.MATERIAL)) {
            tag.putString(ToolParam.MATERIAL.key(), params.material());
        }
        if (allowed.contains(ToolParam.MAX_DURABILITY)) {
            tag.putInt(ToolParam.MAX_DURABILITY.key(), params.maxDurability());
        }
        if (allowed.contains(ToolParam.RADIUS)) {
            tag.putInt(ToolParam.RADIUS.key(), params.radius());
        }
        if (allowed.contains(ToolParam.SHALLOW_DEPTH)) {
            tag.putInt(ToolParam.SHALLOW_DEPTH.key(), params.shallowDepth());
        }
        if (allowed.contains(ToolParam.FALSE_NEGATIVE_CHANCE)) {
            tag.putDouble(ToolParam.FALSE_NEGATIVE_CHANCE.key(), params.falseNegativeChance());
        }
        if (allowed.contains(ToolParam.CONFIDENCE_CAP)) {
            tag.putString(ToolParam.CONFIDENCE_CAP.key(), params.confidenceCap());
        }
        if (allowed.contains(ToolParam.ENERGY_COST)) {
            tag.putInt(ToolParam.ENERGY_COST.key(), params.energyCost());
        }
        if (allowed.contains(ToolParam.MAX_DEPTH)) {
            tag.putInt(ToolParam.MAX_DEPTH.key(), params.maxDepth());
        }
        return tag;
    }

    private ToolParameterNbt() {}
}
