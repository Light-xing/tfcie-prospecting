package com.tfcie.prospecting.client.survey;

import com.mojang.blaze3d.platform.NativeImage;
import com.tfcie.prospecting.TfcieProspecting;
import com.tfcie.prospecting.client.gui.GuiKit;
import com.tfcie.prospecting.common.survey.OreColors;
import com.tfcie.prospecting.common.survey.SurveyMap;
import com.tfcie.prospecting.common.survey.SurveyNames;
import com.tfcie.prospecting.common.survey.SurveyPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 探查地图界面：左侧为 xOz 平面图，右侧为可点击的颜色图例。
 *
 * <h2>为什么用动态纹理而不是逐格 fill</h2>
 * 默认 9×9 区块 = 144×144 = 20,736 个格子。若每帧用 {@code GuiGraphics.fill} 逐格绘制，
 * 就是每帧两万多个四边形，必然掉帧。这里照搬 GT 的做法：
 * 把结果画进 {@link NativeImage}，只在<b>数据或过滤条件变化时</b>重建并 upload 一次，
 * 之后每帧只是一次 {@code blit}。
 *
 * <h2>地图缩放是自适应的</h2>
 * 见 {@link #layout()}：固定倍数会让 9×9 的面板（约 606×462）在 GUI 缩放 3~4 的
 * 小尺寸屏幕上溢出被裁，玩家看到的就是残缺地图。
 *
 * <h2>冲突规则</h2>
 * 同一格可能挂多条数据，绘制时按 {@link SurveyPoint#rgbWeight()}（RGB 之和）取最大者，
 * 这是用户指定的规则，且保证结果确定性。
 */
public final class SurveyMapScreen extends Screen {

    /** 地图最大放大倍数；实际倍数在 {@link #layout()} 里按屏幕尺寸自适应。 */
    private static final int MAX_MAP_SCALE = 4;
    private static final int MAX_LEGEND_WIDTH = 150;
    private static final int ROW_H = 11;
    private static final int PAD = 8;
    /** 图例行内文字的左缩进（让开左侧色块）。 */
    private static final int NAME_INDENT = 12;
    /** 计数与面板右边界的间距。 */
    private static final int COUNT_MARGIN = 6;

    private static final int PANEL_BG = 0xE0101010;
    private static final int MAP_BG = 0xFF2A2A2A;
    private static final int GRID_LINE = 0xFF000000;
    private static final int HOVER = 0x30FFFFFF;
    private static final int SELECTED = 0x60FFFFFF;

    private final SurveyMap map;
    private final String toolId;
    private final List<String> keys;
    private final int mapPx;

    /** 以下四项在 {@link #layout()} 中按当前 GUI 尺寸算出，故非 final。 */
    private int mapScale;
    private int mapDraw;
    private int legendWidth;

    @Nullable
    private String filter;
    private int scroll;

    /**
     * 像素图与贴图，二者<b>同生共死</b>：{@code DynamicTexture} 接管 {@link NativeImage}
     * 的所有权，关闭时会把它一并释放。故每次 {@link #init()} 都要成对重建，
     * <b>绝不能跨多次注册复用同一个 NativeImage</b>。
     */
    @Nullable
    private NativeImage image;
    private final ResourceLocation textureId;
    @Nullable
    private DynamicTexture texture;

    private int panelLeft;
    private int panelTop;
    private int panelW;
    private int panelH;
    private int mapLeft;
    private int mapTop;
    private int legendLeft;
    private int legendTop;
    private int legendHeight;

    public SurveyMapScreen(SurveyMap map, String toolId) {
        super(Component.translatable("gui.tfcie_prospecting.survey.title",
                Component.translatable("item.tfcie_prospecting." + toolId)));
        this.map = map;
        this.toolId = toolId;
        this.mapPx = map.size();

        // 图例按点数降序：先看主要矿种
        final var counts = map.counts();
        this.keys = new ArrayList<>(map.keys());
        this.keys.sort(Comparator
                .comparingInt((String key) -> counts.getOrDefault(key, 0)).reversed()
                .thenComparing(Comparator.naturalOrder()));

        this.textureId = new ResourceLocation(TfcieProspecting.MOD_ID, "survey_map");
    }

    @Override
    protected void init() {
        layout();

        this.panelLeft = (this.width - panelW) / 2;
        this.panelTop = (this.height - panelH) / 2;

        this.mapLeft = panelLeft + PAD;
        this.mapTop = panelTop + PAD + 26;
        this.legendLeft = mapLeft + mapDraw + PAD;
        this.legendTop = mapTop + ROW_H + 4;
        this.legendHeight = panelH - (legendTop - panelTop) - PAD;

        // TextureManager.register 会 safeClose 掉同 id 的旧纹理，而 DynamicTexture.close()
        // 会连带关闭它持有的 NativeImage。因此每次 init（含窗口缩放）都必须新建一张图，
        // 不能与旧纹理共用 —— 否则旧纹理被关闭时会释放共享图像，
        // 之后写入即抛 IllegalStateException: Image is not allocated。
        this.image = new NativeImage(mapPx, mapPx, false);
        // DynamicTexture 由 NativeImage 构造，默认 blur=false / mipmap=false，
        // 即最近的像素放大（GL_NEAREST），正是我们需要的不模糊效果，无需再设过滤。
        this.texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(textureId, texture);

        rebuild();
    }

    /**
     * 按当前 GUI 尺寸计算地图放大倍数与图例宽度。
     *
     * <h2>为什么必须自适应</h2>
     * 9×9 区块的地图是 144×144 像素。若固定 3× 放大，地图就是 432 px，加上图例与边距
     * 面板宽达 <b>606 px、高 462 px</b>——而 MC 的 GUI 缩放 3 时屏幕仅 640×360、
     * 缩放 4 时仅 480×270。面板会超出屏幕被裁掉，玩家看到的就是<b>残缺的地图</b>
     * （看起来像范围变小了）。因此这里按可用空间反推倍数，保证整块面板一定装得下。
     */
    private void layout() {
        final int availW = Math.max(120, this.width - 16);
        final int availH = Math.max(120, this.height - 16);

        // 图例先占位（窄屏时压缩），剩下的宽度留给地图
        this.legendWidth = Math.max(70, Math.min(MAX_LEGEND_WIDTH, availW / 3));

        final int chromeW = legendWidth + PAD * 3;
        final int chromeH = 26 + PAD * 2;
        final int scaleByWidth = (availW - chromeW) / mapPx;
        final int scaleByHeight = (availH - chromeH) / mapPx;
        this.mapScale = Math.max(1, Math.min(MAX_MAP_SCALE, Math.min(scaleByWidth, scaleByHeight)));
        this.mapDraw = mapPx * mapScale;

        // 面板高度至少容纳图例几行，避免极窄屏下高度塌成负数
        this.panelW = mapDraw + legendWidth + PAD * 3;
        this.panelH = Math.max(mapDraw, 6 * ROW_H + 8) + PAD * 2 + 26;
    }

    @Override
    public void removed() {
        Minecraft.getInstance().getTextureManager().release(textureId);
        // release 会把贴图连同其 NativeImage 一起关闭；置空以免后续误用已释放的图像
        this.texture = null;
        this.image = null;
        super.removed();
    }

    // ---------------- 构建图像 ----------------

    /** 按当前过滤条件重建像素图；只在数据/过滤变化时调用。 */
    private void rebuild() {
        final NativeImage target = image;
        final DynamicTexture tex = texture;
        if (target == null || tex == null) {
            return;   // 尚未 init，或已被 removed 释放
        }
        for (int x = 0; x < mapPx; x++) {
            for (int z = 0; z < mapPx; z++) {
                final SurveyPoint point = map.displayAt(map.originX() + x, map.originZ() + z, filter);
                int color = point != null ? point.color() : MAP_BG;
                // 区块分隔线（与 GT 的 x%16==0 一致）
                if (x % 16 == 0 || z % 16 == 0) {
                    color = darken(color);
                }
                target.setPixelRGBA(x, z, GuiKit.argbToAbgr(color));
            }
        }
        tex.upload();
    }

    private static int darken(int argb) {
        final int r = (int) (((argb >> 16) & 0xFF) * 0.35F);
        final int g = (int) (((argb >> 8) & 0xFF) * 0.35F);
        final int b = (int) ((argb & 0xFF) * 0.35F);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        graphics.fill(panelLeft, panelTop, panelLeft + panelW, panelTop + panelH, PANEL_BG);
        graphics.renderOutline(panelLeft, panelTop, panelW, panelH, 0xFF9A9A9A);
        graphics.drawCenteredString(this.font, this.title, panelLeft + panelW / 2, panelTop + 5, 0xFFFFFFFF);
        // 副标题只给探测点原坐标（玩家右键的那个方块）——范围/方块数属实现细节，不占标题区
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.tfcie_prospecting.survey.position",
                        map.probeX(), map.probeZ()),
                panelLeft + panelW / 2, panelTop + 17, 0xFFA8A8A8);

        graphics.blit(textureId, mapLeft, mapTop, mapDraw, mapDraw, 0.0F, 0.0F, mapPx, mapPx, mapPx, mapPx);
        graphics.renderOutline(mapLeft, mapTop, mapDraw, mapDraw, 0xFF707070);

        drawPlayerMarker(graphics);
        drawLegend(graphics, mouseX, mouseY);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * 玩家所在位置画一个<b>黄色发光点</b>。
     *
     * <p>光晕的画法与其余地图界面共用 {@link GuiKit#drawPlayerMarker}，
     * 这里只负责把「玩家方块坐标」换算成屏幕坐标并给出可见区域。
     */
    private void drawPlayerMarker(GuiGraphics graphics) {
        final var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // 落在该方块像素的中心
        final int centerX = mapLeft + (player.getBlockX() - map.originX()) * mapScale + mapScale / 2;
        final int centerY = mapTop + (player.getBlockZ() - map.originZ()) * mapScale + mapScale / 2;
        GuiKit.drawPlayerMarker(graphics, centerX, centerY, mapScale,
                mapLeft, mapTop, mapLeft + mapDraw, mapTop + mapDraw);
    }

    private void drawLegend(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = mapTop;

        // 顶部「全部显示」条目
        final boolean allHovered = GuiKit.inRect(mouseX, mouseY, legendLeft, y, legendWidth, ROW_H);
        if (allHovered || filter == null) {
            graphics.fill(legendLeft, y, legendLeft + legendWidth, y + ROW_H, filter == null ? SELECTED : HOVER);
        }
        graphics.drawString(this.font,
                Component.translatable("gui.tfcie_prospecting.survey.show_all"),
                legendLeft + 3, y + 2, filter == null ? 0xFFFFD070 : 0xFFC0C0C0, false);
        y += ROW_H + 4;

        for (int i = scroll; i < keys.size(); i++) {
            if (y + ROW_H > mapTop + legendHeight) {
                break;
            }
            final String key = keys.get(i);
            final int color = OreColors.of(key);
            final boolean selected = key.equals(filter);
            final boolean hovered = GuiKit.inRect(mouseX, mouseY, legendLeft, y, legendWidth, ROW_H);

            if (selected || hovered) {
                graphics.fill(legendLeft, y, legendLeft + legendWidth, y + ROW_H, selected ? SELECTED : HOVER);
            }
            graphics.fill(legendLeft + 2, y + 2, legendLeft + 9, y + ROW_H - 2, color);

            final var name = SurveyNames.display(key);
            final String countText = String.valueOf(map.counts().getOrDefault(key, 0));

            // 计数按实际文字宽度右对齐：固定左端点在 5 位数时会冲出面板右边界。
            // MC 默认字体每位数字宽 6px，5 位数 30px —— 原先从 -18 起画恰好溢出 12px。
            final int countX = legendLeft + legendWidth - COUNT_MARGIN - this.font.width(countText);
            graphics.drawString(this.font, countText, countX, y + 2, 0xFF909090, false);

            // 矿名可用的宽度截止到计数列左侧（留 4px 间隙），长名截断避免压住数字
            final int nameRoom = countX - (legendLeft + NAME_INDENT) - 4;
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(name.getString(), Math.max(0, nameRoom)),
                    legendLeft + NAME_INDENT, y + 2, color, false);
            y += ROW_H;
        }
    }

    // ---------------- 交互 ----------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // 「全部显示」
        if (GuiKit.inRect(mouseX, mouseY, legendLeft, mapTop, legendWidth, ROW_H)) {
            setFilter(null);
            return true;
        }

        // 图例行：单选过滤，再点一次取消
        int y = mapTop + ROW_H + 4;
        for (int i = scroll; i < keys.size(); i++) {
            if (y + ROW_H > mapTop + legendHeight) {
                break;
            }
            if (GuiKit.inRect(mouseX, mouseY, legendLeft, y, legendWidth, ROW_H)) {
                final String key = keys.get(i);
                setFilter(key.equals(filter) ? null : key);
                return true;
            }
            y += ROW_H;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (GuiKit.inRect(mouseX, mouseY, legendLeft, mapTop, legendWidth, legendHeight)) {
            final int visible = Math.max(1, legendHeight / ROW_H);
            final int maxScroll = Math.max(0, keys.size() - visible + 1);
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void setFilter(@Nullable String key) {
        this.filter = key;
        rebuild();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
