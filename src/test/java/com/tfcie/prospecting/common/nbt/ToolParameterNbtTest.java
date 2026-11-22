package com.tfcie.prospecting.common.nbt;

import com.tfcie.prospecting.api.ToolParam;
import com.tfcie.prospecting.api.ToolParameters;
import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/** 验证材质参数 NBT 读写与白名单行为（阶段 2 验收：参数可被 NBT 覆盖）。 */
public class ToolParameterNbtTest {

    /** 踏勘镐的参数白名单（与 ReconPickItem 一致）。 */
    private static final Set<ToolParam> RECON_WHITELIST = EnumSet.of(
            ToolParam.MATERIAL,
            ToolParam.MAX_DURABILITY,
            ToolParam.RADIUS,
            ToolParam.SHALLOW_DEPTH,
            ToolParam.FALSE_NEGATIVE_CHANCE,
            ToolParam.CONFIDENCE_CAP);

    private static ToolParameters defaults() {
        return new ToolParameters()
                .material("bronze")
                .maxDurability(256)
                .radius(8)
                .shallowDepth(24)
                .falseNegativeChance(0.2)
                .confidenceCap("medium");
    }

    @Test
    public void emptyTag_returnsDefaults() {
        ToolParameters p = ToolParameterNbt.fromTag(null, defaults(), RECON_WHITELIST);
        assertEquals("bronze", p.material());
        assertEquals(256, p.maxDurability());
        assertEquals(8, p.radius());
        assertEquals(24, p.shallowDepth());
    }

    @Test
    public void tagOverridesWhitelistedParams() {
        CompoundTag tag = new CompoundTag();
        tag.putString(ToolParam.MATERIAL.key(), "iron");
        tag.putInt(ToolParam.MAX_DURABILITY.key(), 512);
        tag.putDouble(ToolParam.FALSE_NEGATIVE_CHANCE.key(), 0.1);

        ToolParameters p = ToolParameterNbt.fromTag(tag, defaults(), RECON_WHITELIST);
        assertEquals("iron", p.material());
        assertEquals(512, p.maxDurability());
        assertEquals(0.1, p.falseNegativeChance(), 1e-9);
        assertEquals(8, p.radius()); // 未被覆盖 → 保持默认
    }

    @Test
    public void nonWhitelistedParamsAreIgnored() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(ToolParam.ENERGY_COST.key(), 9999); // 踏勘镐白名单外

        ToolParameters p = ToolParameterNbt.fromTag(tag, defaults(), RECON_WHITELIST);
        assertEquals(0, p.energyCost()); // 未被覆盖
    }

    @Test
    public void toTagRoundTrips() {
        ToolParameters src = defaults().material("steel").maxDurability(1024).radius(12);
        CompoundTag tag = ToolParameterNbt.toTag(src, RECON_WHITELIST);
        ToolParameters back = ToolParameterNbt.fromTag(tag, defaults(), RECON_WHITELIST);

        assertEquals("steel", back.material());
        assertEquals(1024, back.maxDurability());
        assertEquals(12, back.radius());
        assertEquals(0.2, back.falseNegativeChance(), 1e-9);
    }
}
