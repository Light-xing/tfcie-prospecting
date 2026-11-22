package com.tfcie.prospecting.common.survey;

import com.tfcie.prospecting.api.OreTaxonomy;
import net.dries007.tfc.common.blocks.TFCBlocks;
import net.dries007.tfc.common.blocks.rock.Ore;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 图例显示名解析：数据键 → 人类可读名称。
 *
 * <h2>为什么要绕一圈取方块名</h2>
 * 矿种键是 {@code native_copper} 这类 id。若直接用 {@link OreTaxonomy#displayName}
 * 会得到硬编码中文，英文客户端也会显示中文。
 * 这里改用 TFC 自己的矿石方块名（{@code block.getName()}），由<b>客户端</b>按玩家语言解析，
 * 中英文都正确——与钻探样本 GUI 的处理方式一致。
 */
public final class SurveyNames {

    private SurveyNames() {}

    /** 数据键的显示名。 */
    public static Component display(String key) {
        final Block block = oreBlock(key);
        if (block != null) {
            return block.getName();
        }
        if (OreTaxonomy.byId(key) != null) {
            return Component.literal(OreTaxonomy.displayName(key));
        }
        return Component.translatable("survey.tfcie_prospecting.key." + key);
    }

    /** 找到该矿种的一个代表方块（任一岩石类型即可）。 */
    @Nullable
    private static Block oreBlock(String key) {
        if (OreTaxonomy.byId(key) == null) {
            return null;
        }
        // 非 graded 矿石
        for (var byOre : TFCBlocks.ORES.values()) {
            for (var entry : byOre.entrySet()) {
                if (matches(entry.getKey(), key)) {
                    return entry.getValue().get();
                }
            }
        }
        // graded 矿石（贫/中/富归并为同一矿种）
        for (var byOre : TFCBlocks.GRADED_ORES.values()) {
            for (var byGrade : byOre.entrySet()) {
                if (matches(byGrade.getKey(), key)) {
                    for (var entry : byGrade.getValue().entrySet()) {
                        return entry.getValue().get();
                    }
                }
            }
        }
        return null;
    }

    private static boolean matches(Ore ore, String key) {
        return ore.name().toLowerCase(Locale.ROOT).equals(key);
    }
}
