package com.tfcie.prospecting.client;

import com.tfcie.prospecting.common.item.CoreSampleItem;
import com.tfcie.prospecting.common.prospecting.CoreSampleData;
import com.tfcie.prospecting.common.prospecting.EnumNames;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 钻探样本界面（D1）。纯客户端展示：数据直接取自 ItemStack 的 NBT，无需网络同步。
 *
 * <p>四段式布局，对齐设计文档的 GUI 规格：
 * <ol>
 *   <li><b>柱状图</b>：按 y 从上到下的竖条，厚度按比例着色，含矿层高亮，松散层半透明；</li>
 *   <li><b>分层列表</b>：{@code 泥土 ×5  y74 → y70}；未取芯的段标注「（未取芯）」；</li>
 *   <li><b>摘要行</b>：按层序压缩的串；</li>
 *   <li><b>页脚</b>：终止原因、取芯恢复率、总深度。</li>
 * </ol>
 *
 * <p>层名在<b>客户端</b>按方块的注册名本地化，因此中英文客户端都显示正确语言，
 * 而不是服务端渲染时锁定的那一种。
 */
public final class CoreSampleScreen extends Screen {

    private static final int PANEL_W = 340;
    private static final int PANEL_H = 224;
    private static final int COLUMN_W = 24;
    private static final int PAD = 10;
    private static final int LINE_H = 10;

    private static final int COLOR_ORE = 0xFFD4A017;
    private static final int COLOR_UNCORED = 0x66FFFFFF;

    private final CoreSampleData data;
    /** 含矿层的定位键（block@yFrom），用于柱状图与列表的高亮判定。 */
    private final Set<String> oreLayers = new HashSet<>();

    public CoreSampleScreen(ItemStack stack) {
        super(titleOf(stack));
        this.data = CoreSampleItem.read(stack);
        for (CoreSampleData.OreHit hit : this.data.oreHits()) {
            this.oreLayers.add(hit.block() + "@" + hit.y());
        }
    }

    private static Component titleOf(ItemStack stack) {
        final CoreSampleData d = CoreSampleItem.read(stack);
        return Component.translatable("gui.tfcie_prospecting.core_sample.title",
                d.origin().getX(), d.origin().getY(), d.origin().getZ());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        final int left = (this.width - PANEL_W) / 2;
        final int top = (this.height - PANEL_H) / 2;

        graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xE0101010);
        graphics.renderOutline(left, top, PANEL_W, PANEL_H, 0xFF9A9A9A);

        graphics.drawCenteredString(this.font, this.title, left + PANEL_W / 2, top + PAD, 0xFFFFFFFF);

        final int bodyTop = top + PAD + 14;
        final int bodyH = PANEL_H - (PAD * 2) - 14 - 30;

        drawColumn(graphics, left + PAD, bodyTop, COLUMN_W, bodyH);
        drawLayers(graphics, left + PAD + COLUMN_W + 14, bodyTop,
                PANEL_W - (PAD * 3) - COLUMN_W - 14, bodyH);

        final int footY = bodyTop + bodyH + 6;
        graphics.drawString(this.font,
                Component.translatable("gui.tfcie_prospecting.core_sample.summary", this.data.summary()),
                left + PAD, footY, 0xFFC0C0C0, false);
        graphics.drawString(this.font, footerLine(), left + PAD, footY + 11, 0xFF909090, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private Component footerLine() {
        return Component.translatable("gui.tfcie_prospecting.core_sample.footer",
                EnumNames.of("terminated_by", this.data.terminatedBy()),
                Math.round(this.data.recoveryRate() * 100.0D) + "%",
                this.data.depth());
    }

    // ---------------- 柱状图 ----------------

    private void drawColumn(GuiGraphics graphics, int x, int y, int w, int h) {
        final int total = this.data.runs().stream().mapToInt(CoreSampleData.Layer::thickness).sum();
        if (total <= 0 || h <= 0) {
            return;
        }
        int cursor = y;
        for (CoreSampleData.Layer layer : this.data.runs()) {
            int seg = Math.max(1, Math.round((float) layer.thickness() / (float) total * (float) h));
            if (cursor + seg > y + h) {
                seg = Math.max(1, y + h - cursor);
            }
            graphics.fill(x, cursor, x + w, cursor + seg, colorOf(layer));
            graphics.renderOutline(x, cursor, w, seg, 0x60000000);
            cursor += seg;
            if (cursor >= y + h) {
                break;
            }
        }
        graphics.renderOutline(x, y, w, h, 0xFF707070);
    }

    /** 含矿层高亮；未取芯半透明；松散层降不透明度；其余按方块名派生稳定色相。 */
    private int colorOf(CoreSampleData.Layer layer) {
        if (isOre(layer)) {
            return COLOR_ORE;
        }
        if (!layer.cored()) {
            return COLOR_UNCORED;
        }
        final float hue = (float) (Math.abs(layer.block().hashCode() % 360)) / 360.0F;
        final int rgb = Mth.hsvToRgb(hue, 0.32F, 0.78F);
        final int alpha = isLoose(layer.block()) ? 0xB0 : 0xFF;
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    // ---------------- 分层列表 ----------------

    private void drawLayers(GuiGraphics graphics, int x, int y, int w, int h) {
        final int maxLines = Math.max(1, h / LINE_H);
        final int size = this.data.runs().size();
        final int shown = Math.min(maxLines, size);

        for (int i = 0; i < shown; i++) {
            final CoreSampleData.Layer layer = this.data.runs().get(i);
            String text = displayOf(layer) + " ×" + layer.thickness()
                    + "  y" + layer.yFrom() + " → y" + layer.yTo();
            if (!layer.cored()) {
                text = text + " " + Component.translatable(
                        "gui.tfcie_prospecting.core_sample.not_cored").getString();
            }
            final int color = isOre(layer) ? COLOR_ORE : (layer.cored() ? 0xFFE0E0E0 : 0xFF909090);
            graphics.drawString(this.font, this.font.plainSubstrByWidth(text, w),
                    x, y + i * LINE_H, color, false);
        }
        if (size > shown) {
            graphics.drawString(this.font, "… +" + (size - shown),
                    x, y + shown * LINE_H, 0xFF909090, false);
        }
    }

    // ---------------- 工具 ----------------

    private boolean isOre(CoreSampleData.Layer layer) {
        return this.oreLayers.contains(layer.block() + "@" + layer.yFrom());
    }

    /** 优先用注册名在客户端本地化，取不到再退回服务端存的显示名。 */
    private static String displayOf(CoreSampleData.Layer layer) {
        final ResourceLocation key = ResourceLocation.tryParse(layer.block());
        if (key != null) {
            final Block block = ForgeRegistries.BLOCKS.getValue(key);
            if (block != null) {
                return block.getName().getString();
            }
        }
        return layer.display();
    }

    private static boolean isLoose(String blockId) {
        final String path = blockId.toLowerCase(Locale.ROOT);
        return path.startsWith("tfc:dirt") || path.contains("/dirt") || path.contains("sand")
                || path.contains("gravel") || path.contains("clay") || path.contains("topsoil");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
