package com.tfcie.prospecting.client.terrain;

import com.mojang.blaze3d.platform.NativeImage;
import com.tfcie.prospecting.TfcieProspecting;
import com.tfcie.prospecting.client.gui.GuiKit;
import com.tfcie.prospecting.common.terrain.TerrainMap;
import com.tfcie.prospecting.config.ProspectingConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

/**
 * 遥感扫描仪界面：方形面板 + 三个可切换栏目。
 *
 * <h2>三个栏目</h2>
 * <ol>
 *   <li><b>鸟瞰</b> —— 接管渲染摄像机，从空中俯视真实世界。开口是正方形，
 *       摄像机高度按 {@link BirdseyeCamera} 的公式换算，使开口恰好框住 5×5 区块；</li>
 *   <li><b>平面地图</b> —— 类原版地图（地表色 + 高度明暗），区块边界处像素调灰，
 *       右侧与底部各一条滑动条；</li>
 *   <li><b>等高线</b> —— 以 3 格为一档，档位用黄金角色相标色。</li>
 * </ol>
 *
 * <h2>栏一为什么用「挖洞」而不是离屏渲染</h2>
 * 相机接管后渲染器会铺满整屏。要让它只出现在方形开口里，最省事且<b>画面完全一致</b>的做法是：
 * 用不透明底色盖住整个屏幕、只在开口处留空。相比之下，把 {@code LevelRenderer.renderLevel}
 * 驱动到一个 FBO 再贴图，复杂度和画面偏差都高得多（见构建计划 §六）。
 */
public final class RemoteSensingScreen extends Screen {

    private enum Tab {
        BIRDSEYE("birdseye"),
        MAP("map"),
        CONTOUR("contour");

        private final String key;

        Tab(String key) {
            this.key = key;
        }

        Component title() {
            return Component.translatable("gui.tfcie_prospecting.terrain.tab." + key);
        }
    }

    private static final int TAB_H = 16;
    private static final int PAD = 6;
    private static final int SCROLLBAR = 8;
    private static final int OUTER_PAD = 12;

    /** 栏二/栏三：每个方块占几个像素。取 3 使 144 格宽于常见视口，滑动条才有意义。 */
    private static final int MASK = 0xFF101418;
    private static final int PANEL_BG = 0xF0181C22;
    private static final int BORDER = 0xFF8A9099;
    private static final int TAB_ACTIVE = 0xFF2E3A48;
    private static final int TAB_IDLE = 0xFF1E252C;
    private static final int TEXT_ACTIVE = 0xFFFFFFFF;
    private static final int TEXT_IDLE = 0xFF98A0A8;
    private static final int EMPTY_PIXEL = 0xFF0A0C0E;
    private static final int SCROLL_TRACK = 0x40FFFFFF;
    private static final int SCROLL_THUMB = 0xFFB0B8C0;

    private final TerrainMap map;
    private final int mapSize;

    /**
     * 栏二/栏三的缩放：每方块几个像素。
     *
     * <p>在 {@link #init()} 里按视口自适应 —— 固定值会导致「地图比视口还小」，
     * 那时滑动条与拖动都无事可做（这正是之前栏二拖不动的直接原因）。
     */
    private int pixelsPerBlock = 6;

    private Tab tab = Tab.BIRDSEYE;

    private int panelLeft;
    private int panelTop;
    private int panelSize;
    private int contentLeft;
    private int contentTop;
    private int contentW;
    private int contentH;
    private int viewW;
    private int viewH;

    /** 栏一的方形开口。 */
    private int holeLeft;
    private int holeTop;
    private int holeSize;

    /** 栏一相机（X/Z 可变，Y 由换算给出）。 */
    private double camX;
    private double camZ;
    private int planeY;
    /** 栏一摄像机的活动边界：以地图中心为中心，由 {@code terrain.camera_radius}（默认 11×11 区块）决定。 */
    private double camMinX;
    private double camMaxX;
    private double camMinZ;
    private double camMaxZ;

    /** 栏二/三 的滚动偏移（像素）。 */
    private int scrollX;
    private int scrollY;
    private boolean draggingV;
    private boolean draggingH;
    /** 直接拖动地图（栏二/三）。 */
    private boolean draggingMap;
    /** 直接拖动鸟瞰画面（栏一）。 */
    private boolean draggingBirdseye;
    private double dragAnchorX;
    private double dragAnchorY;
    private int dragStartScrollX;
    private int dragStartScrollY;
    private double dragStartCamX;
    private double dragStartCamZ;

    /**
     * 像素图与贴图。二者<b>同生共死</b>：{@code DynamicTexture} 会接管
     * {@link NativeImage} 的所有权，并在关闭时把它一并释放。
     *
     * <p><b>因此绝不能跨多次注册复用同一个 {@code NativeImage}</b> —— 见
     * {@link #init()} 里的说明。两张都在 {@code init()} 中成对重建。
     */
    @Nullable
    private NativeImage image;
    @Nullable
    private DynamicTexture texture;
    private final ResourceLocation textureId;
    @Nullable
    private Tab renderedTab;

    public RemoteSensingScreen(TerrainMap map) {
        super(Component.translatable("gui.tfcie_prospecting.terrain.title"));
        this.map = map;
        this.mapSize = map.size();
        this.textureId = new ResourceLocation(TfcieProspecting.MOD_ID, "terrain_map");

        final var player = Minecraft.getInstance().player;
        if (player != null) {
            this.camX = player.getX();
            this.camZ = player.getZ();
            this.planeY = player.getBlockY();
        }
    }

    @Override
    protected void init() {
        this.panelSize = Math.min(this.width, this.height) - OUTER_PAD * 2;
        this.panelLeft = (this.width - panelSize) / 2;
        this.panelTop = (this.height - panelSize) / 2;

        this.contentLeft = panelLeft + PAD;
        this.contentTop = panelTop + PAD + TAB_H + PAD;
        this.contentW = panelSize - PAD * 2;
        this.contentH = panelSize - (contentTop - panelTop) - PAD;

        // 为右侧与底部滑动条预留
        this.viewW = Math.max(1, contentW - SCROLLBAR);
        this.viewH = Math.max(1, contentH - SCROLLBAR);

        // 栏一开口取内容区较窄的一边，保证是正方形（这正是「5×5 恰好」能成立的前提）
        this.holeSize = Math.max(1, Math.min(contentW, contentH));
        this.holeLeft = contentLeft + (contentW - holeSize) / 2;
        this.holeTop = contentTop + (contentH - holeSize) / 2;

        // 每次 init（含窗口缩放）都必须成对重建「图像 + 贴图」：
        //
        // TextureManager.register 在放入新纹理时会 safeClose 掉同 id 的旧纹理，
        // 而 DynamicTexture.close() 会连带关闭它持有的 NativeImage。
        // 若多张 DynamicTexture 复用同一个 NativeImage，旧纹理被关闭时
        // 就会把共享的图像一并释放 —— 之后任何 setPixelRGBA 都会抛
        // IllegalStateException: Image is not allocated（切到栏二时首次重建即崩）。
        this.image = new NativeImage(mapSize, mapSize, false);
        this.texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
        // 新图是空白的：标记为「未渲染」，切到栏二/栏三时会被重建
        this.renderedTab = null;

        // 自适应缩放：让地图比视口大约 1.6 倍，滑动条与拖动才有实际作用区间。
        // 之前固定 3 像素/方块时地图（432px）比视口还小，maxScroll 恒为 0 —— 这就是拖不动的原因。
        final int longestSide = Math.max(viewW, viewH);
        this.pixelsPerBlock = Mth.clamp(
                (int) Math.ceil(longestSide / (double) mapSize * 1.6D), 2, 24);

        // 栏一摄像机的活动边界：以地图中心为中心，camera_radius 决定半径
        final int cameraChunks = Math.max(1,
                ProspectingConfig.intOr(ProspectingConfig.Terrain.cameraRadius, 6)) * 2 - 1;
        final double cameraHalf = cameraChunks * 16 / 2.0D;
        final double centerX = map.originX() + mapSize / 2.0D;
        final double centerZ = map.originZ() + mapSize / 2.0D;
        this.camMinX = centerX - cameraHalf;
        this.camMaxX = centerX + cameraHalf;
        this.camMinZ = centerZ - cameraHalf;
        this.camMaxZ = centerZ + cameraHalf;

        // 首次进入以地图中心为准；切栏时再由 applyCenter 同步
        applyCenter(viewCenterX(), viewCenterZ());
        updateHudVisibility();

        // 栏一渲染的是真实世界、不需要像素图；但若 init 时正停在栏二/三，必须立刻填充新图
        if (tab != Tab.BIRDSEYE) {
            rebuildImage();
        }
    }

    @Override
    public void removed() {
        BirdseyeRenderer.clearRequest();
        BirdseyeRenderer.setHideHud(false);
        Minecraft.getInstance().getTextureManager().release(textureId);
        // release 会把贴图连同其 NativeImage 一起关闭；置空以免后续误用已释放的图像
        this.texture = null;
        this.image = null;
        super.removed();
    }

    /**
     * 同步 HUD 可见性：只有鸟瞰栏目需要隐藏它。
     *
     * <p>鸟瞰开口是透空的（四周用不透明边框遮挡），而 HUD 比 Screen 更早绘制，
     * 不隐藏的话快捷栏会从开口里透出来。栏二/栏三的面板本身不透明，无需处理。
     */
    private void updateHudVisibility() {
        BirdseyeRenderer.setHideHud(tab == Tab.BIRDSEYE);
    }

    // ---------------- 视角中心：三栏共用一套语义 ----------------

    /**
     * 当前栏目的视角中心（世界方块坐标）。栏一即相机所在处；栏二/三即视口正中对应的方块。
     */
    private double viewCenterX() {
        return tab == Tab.BIRDSEYE ? camX : panCenterX();
    }

    private double viewCenterZ() {
        return tab == Tab.BIRDSEYE ? camZ : panCenterZ();
    }

    /** 栏二/三：由滚动偏移反推视口中心。 */
    private double panCenterX() {
        return map.originX() + (scrollX + viewW / 2.0D) / pixelsPerBlock;
    }

    private double panCenterZ() {
        return map.originZ() + (scrollY + viewH / 2.0D) / pixelsPerBlock;
    }

    /**
     * 把某个中心应用到<b>当前</b>栏目，并按该栏目的合法范围钳制。
     *
     * <h2>为什么必须钳制</h2>
     * 栏一只能活动 11×11 区块，而栏二/三绘制 15×15 区块 —— 两者同心但大小不同。
     * 从栏二靠边的位置切到栏一时，相机就会落到活动范围之外；反之从栏一的边缘切到栏二时，
     * 视口也可能伸出已扫描的地图之外（画不满）。两种情况的处理一致：
     * <b>把中心移到最靠近原位置的合法边缘</b>。
     */
    private void applyCenter(double x, double z) {
        if (tab == Tab.BIRDSEYE) {
            this.camX = Mth.clamp(x, camMinX, camMaxX);
            this.camZ = Mth.clamp(z, camMinZ, camMaxZ);
        } else {
            this.scrollX = GuiKit.clampScroll(scrollForCenterX(x), maxScrollX());
            this.scrollY = GuiKit.clampScroll(scrollForCenterZ(z), maxScrollY());
        }
    }

    private int scrollForCenterX(double x) {
        return (int) Math.round((x - map.originX()) * pixelsPerBlock - viewW / 2.0D);
    }

    private int scrollForCenterZ(double z) {
        return (int) Math.round((z - map.originZ()) * pixelsPerBlock - viewH / 2.0D);
    }

    /** 切换栏目，并把当前中心同步过去（按新栏目的范围钳制）。 */
    private void switchTab(Tab next) {
        if (next == tab) {
            return;
        }
        final double centerX = viewCenterX();
        final double centerZ = viewCenterZ();
        this.tab = next;
        applyCenter(centerX, centerZ);
        updateHudVisibility();
    }

    // ---------------- 栏一：摄像机 ----------------

    /**
     * 把开口的 GUI 矩形换算成帧缓冲像素矩形（原点在左下），交给离屏渲染器。
     *
     * <p>本方法<b>不改变玩家相机</b> —— 鸟瞰只画在开口里，主视角保持原样。
     */
    private void requestBirdseye() {
        final var window = Minecraft.getInstance().getWindow();
        final double scale = window.getGuiScale();
        final int fbHeight = window.getHeight();
        final int sx = (int) Math.round(holeLeft * scale);
        final int sw = (int) Math.round(holeSize * scale);
        final int sh = (int) Math.round(holeSize * scale);
        // GUI 原点在左上、裁剪原点在左下，故纵向要翻过来
        final int sy = fbHeight - (int) Math.round((holeTop + holeSize) * scale);

        final double y = BirdseyeCamera.cameraY(this.height, holeSize, planeY);
        BirdseyeRenderer.request(camX, y, camZ, BirdseyeCamera.DEFAULT_YAW, sx, sy, sw, sh);
    }

    /**
     * 方向键平移摄像机。
     *
     * <p>因为俯瞰的偏航是固定的 {@link BirdseyeCamera#DEFAULT_YAW}（北朝上、东朝右），
     * 四个方向就是纯粹的世界轴方向，<b>不需要任何三角函数</b>：
     * <ul>
     *   <li>上 = 北 = −Z　下 = 南 = +Z</li>
     *   <li>左 = 西 = −X　右 = 东 = +X</li>
     * </ul>
     * 这也正是屏幕上看到的方向 —— 与方块网格严格对齐，不会再出现斜向移动。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        final double step = 4.0D;
        final double dx;
        final double dz;
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> { dx = 0.0D; dz = -step; }
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> { dx = 0.0D; dz = step; }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> { dx = -step; dz = 0.0D; }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> { dx = step; dz = 0.0D; }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        // 三栏共用一套「视角中心」语义，方向键在任意栏目下都可用
        applyCenter(viewCenterX() + dx, viewCenterZ() + dz);
        return true;
    }

    // ---------------- 图像构建 ----------------

    private void rebuildImage() {
        final NativeImage target = image;
        final DynamicTexture tex = texture;
        if (target == null || tex == null) {
            return;   // 尚未 init，或已被 removed 释放
        }
        for (int lz = 0; lz < mapSize; lz++) {
            for (int lx = 0; lx < mapSize; lx++) {
                final int index = lz * mapSize + lx;
                final int height = map.heightAtIndex(index);
                final int argb;
                if (height == TerrainMap.NO_DATA) {
                    argb = EMPTY_PIXEL;
                } else if (tab == Tab.MAP) {
                    argb = mapPixel(lx, lz, index, height);
                } else {
                    argb = contourPixel(lx, lz, index, height);
                }
                target.setPixelRGBA(lx, lz, GuiKit.argbToAbgr(argb));
            }
        }
        tex.upload();
        this.renderedTab = tab;
    }

    private int mapPixel(int lx, int lz, int index, int height) {
        final int worldX = map.originX() + lx;
        final int worldZ = map.originZ() + lz;
        // 北侧（z-1）同列高度；越界或无数据时用自身，即该处不计坡度
        final int northHeight = lz > 0 ? map.heightAtIndex(index - mapSize) : TerrainMap.NO_DATA;
        final int slopeReference = northHeight == TerrainMap.NO_DATA ? height : northHeight;

        int argb = TerrainPalette.shadeBySlope(
                map.colorAtIndex(index), height, slopeReference, worldX, worldZ);
        if (TerrainPalette.isChunkBorder(worldX, worldZ)) {
            argb = TerrainPalette.grayOut(argb, TerrainPalette.CHUNK_BORDER_GRAY);
        }
        return argb;
    }

    private int contourPixel(int lx, int lz, int index, int height) {
        if (TerrainPalette.isContourPixel(height,
                heightAt(lx + 1, lz), heightAt(lx - 1, lz),
                heightAt(lx, lz - 1), heightAt(lx, lz + 1))) {
            return TerrainPalette.contourColor(TerrainPalette.bandOf(height));
        }
        return TerrainPalette.contourBackdrop(map.colorAtIndex(index));
    }

    /** 邻列高度；越界或无数号时返回自身高度（即不在该方向画线）。 */
    private int heightAt(int lx, int lz) {
        if (lx < 0 || lx >= mapSize || lz < 0 || lz >= mapSize) {
            return TerrainMap.NO_DATA;
        }
        return map.heightAtIndex(lz * mapSize + lx);
    }

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 栏一不需要像素图；只在栏二/栏三且栏目变化时重建
        if (tab != Tab.BIRDSEYE && renderedTab != tab) {
            rebuildImage();
        }

        if (tab == Tab.BIRDSEYE) {
            // 鸟瞰只画在开口里；主视角由玩家相机照常渲染，不受影响
            requestBirdseye();
            // 不调 renderBackground：那会把开口也压暗
            maskAroundHole(graphics);
        } else {
            BirdseyeRenderer.clearRequest();
            this.renderBackground(graphics);
            graphics.fill(panelLeft, panelTop, panelLeft + panelSize, panelTop + panelSize, PANEL_BG);
        }

        graphics.renderOutline(panelLeft, panelTop, panelSize, panelSize, BORDER);
        drawTabs(graphics, mouseX, mouseY);

        // 栏一除了开口本身没有别的装饰：提示文案已按要求移除，故无需分支
        if (tab != Tab.BIRDSEYE) {
            drawPixelView(graphics);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 用不透明底色盖住整屏，只在方形开口处留空 —— 那里透出真实世界渲染。 */
    private void maskAroundHole(GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, holeTop, MASK);
        graphics.fill(0, holeTop + holeSize, this.width, this.height, MASK);
        graphics.fill(0, holeTop, holeLeft, holeTop + holeSize, MASK);
        graphics.fill(holeLeft + holeSize, holeTop, this.width, holeTop + holeSize, MASK);
        graphics.renderOutline(holeLeft, holeTop, holeSize, holeSize, BORDER);
    }

    private void drawTabs(GuiGraphics graphics, int mouseX, int mouseY) {
        final Tab[] tabs = Tab.values();
        final int tabW = (panelSize - PAD * 2) / tabs.length;
        int x = panelLeft + PAD;
        for (Tab value : tabs) {
            final boolean active = value == tab;
            final boolean hovered = GuiKit.inRect(mouseX, mouseY, x, panelTop + PAD, tabW, TAB_H);
            graphics.fill(x, panelTop + PAD, x + tabW, panelTop + PAD + TAB_H,
                    active ? TAB_ACTIVE : TAB_IDLE);
            if (hovered && !active) {
                graphics.fill(x, panelTop + PAD, x + tabW, panelTop + PAD + TAB_H, 0x20FFFFFF);
            }
            graphics.drawCenteredString(this.font, value.title(),
                    x + tabW / 2, panelTop + PAD + (TAB_H - this.font.lineHeight) / 2 + 1,
                    active ? TEXT_ACTIVE : TEXT_IDLE);
            x += tabW;
        }
    }

    /** 栏二 / 栏三：贴出当前可视范围 + 两条滑动条 + 玩家标记。 */
    private void drawPixelView(GuiGraphics graphics) {
        graphics.fill(contentLeft, contentTop, contentLeft + viewW, contentTop + viewH, EMPTY_PIXEL);

        final float u = scrollX / (float) pixelsPerBlock;
        final float v = scrollY / (float) pixelsPerBlock;
        final int uW = Math.min(mapSize, Mth.ceil(viewW / (float) pixelsPerBlock));
        final int vH = Math.min(mapSize, Mth.ceil(viewH / (float) pixelsPerBlock));
        graphics.blit(textureId, contentLeft, contentTop, viewW, viewH,
                u, v, uW, vH, mapSize, mapSize);

        drawPlayerMarker(graphics);
        drawScrollbars(graphics);
    }

    /**
     * 玩家所在位置画一个<b>黄色发光点</b>，与探查地图同一套画法
     * （{@link GuiKit#drawPlayerMarker}）。这里只负责坐标换算与可见区域。
     */
    private void drawPlayerMarker(GuiGraphics graphics) {
        final var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // 落在该方块像素的中心
        final int centerX = contentLeft
                + (player.getBlockX() - map.originX()) * pixelsPerBlock - scrollX + pixelsPerBlock / 2;
        final int centerY = contentTop
                + (player.getBlockZ() - map.originZ()) * pixelsPerBlock - scrollY + pixelsPerBlock / 2;
        GuiKit.drawPlayerMarker(graphics, centerX, centerY, pixelsPerBlock,
                contentLeft, contentTop, contentLeft + viewW, contentTop + viewH);
    }

    private void drawScrollbars(GuiGraphics graphics) {
        // 纵向
        final int trackX = contentLeft + viewW;
        graphics.fill(trackX, contentTop, trackX + SCROLLBAR, contentTop + viewH, SCROLL_TRACK);
        final int thumbH = thumbSize(viewH);
        final int thumbY = contentTop + thumbOffset(viewH, thumbH, scrollY, maxScrollY());
        graphics.fill(trackX + 1, thumbY, trackX + SCROLLBAR - 1, thumbY + thumbH, SCROLL_THUMB);

        // 横向
        final int trackY = contentTop + viewH;
        graphics.fill(contentLeft, trackY, contentLeft + viewW, trackY + SCROLLBAR, SCROLL_TRACK);
        final int thumbW = thumbSize(viewW);
        final int thumbX = contentLeft + thumbOffset(viewW, thumbW, scrollX, maxScrollX());
        graphics.fill(thumbX, trackY + 1, thumbX + thumbW, trackY + SCROLLBAR - 1, SCROLL_THUMB);
    }

    private int thumbSize(int viewLength) {
        final int full = mapSize * pixelsPerBlock;
        return Math.max(12, viewLength * viewLength / Math.max(1, full));
    }

    private static int thumbOffset(int viewLength, int thumbLength, int scroll, int maxScroll) {
        if (maxScroll <= 0) {
            return 0;
        }
        return (viewLength - thumbLength) * scroll / maxScroll;
    }

    private int maxScrollX() {
        return Math.max(0, mapSize * pixelsPerBlock - viewW);
    }

    private int maxScrollY() {
        return Math.max(0, mapSize * pixelsPerBlock - viewH);
    }

    // ---------------- 交互 ----------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // 栏目切换：把当前视角中心同步到新栏目（见 switchTab）
        final Tab[] tabs = Tab.values();
        final int tabW = (panelSize - PAD * 2) / tabs.length;
        int x = panelLeft + PAD;
        for (Tab value : tabs) {
            if (GuiKit.inRect(mouseX, mouseY, x, panelTop + PAD, tabW, TAB_H)) {
                switchTab(value);
                return true;
            }
            x += tabW;
        }

        if (tab == Tab.BIRDSEYE) {
            // 拖动鸟瞰画面即可移动摄像机（与栏二/三的拖动方向一致）
            if (GuiKit.inRect(mouseX, mouseY, holeLeft, holeTop, holeSize, holeSize)) {
                draggingBirdseye = true;
                dragAnchorX = mouseX;
                dragAnchorY = mouseY;
                dragStartCamX = camX;
                dragStartCamZ = camZ;
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // 滑动条拖动
        final int trackX = contentLeft + viewW;
        if (GuiKit.inRect(mouseX, mouseY, trackX, contentTop, SCROLLBAR, viewH) && maxScrollY() > 0) {
            draggingV = true;
            return true;
        }
        final int trackY = contentTop + viewH;
        if (GuiKit.inRect(mouseX, mouseY, contentLeft, trackY, viewW, SCROLLBAR) && maxScrollX() > 0) {
            draggingH = true;
            return true;
        }
        // 直接拖动地图本身（用户要求的「用鼠标拖动」）
        if (GuiKit.inRect(mouseX, mouseY, contentLeft, contentTop, viewW, viewH)
                && (maxScrollX() > 0 || maxScrollY() > 0)) {
            draggingMap = true;
            dragAnchorX = mouseX;
            dragAnchorY = mouseY;
            dragStartScrollX = scrollX;
            dragStartScrollY = scrollY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingBirdseye) {
            // 开口纵向正好看到 5×5 区块，故每 GUI 像素对应 80/holeSize 格
            final double blocksPerPixel = BirdseyeCamera.TARGET_BLOCKS / (double) holeSize;
            final double moveX = (mouseX - dragAnchorX) * blocksPerPixel;
            final double moveZ = (mouseY - dragAnchorY) * blocksPerPixel;
            // 往右/下拖 → 画面跟着右/下走 → 摄像机往西/北，故取负（与栏二/三一致）
            applyCenter(dragStartCamX - moveX, dragStartCamZ - moveZ);
            return true;
        }
        if (draggingV) {
            scrollY = scrollFromMouse((int) mouseY, contentTop, viewH, thumbSize(viewH), maxScrollY());
            return true;
        }
        if (draggingH) {
            scrollX = scrollFromMouse((int) mouseX, contentLeft, viewW, thumbSize(viewW), maxScrollX());
            return true;
        }
        if (draggingMap) {
            // 往右/下拖 → 视野往左/上走，故取负
            scrollX = GuiKit.clampScroll(dragStartScrollX - (int) Math.round(mouseX - dragAnchorX), maxScrollX());
            scrollY = GuiKit.clampScroll(dragStartScrollY - (int) Math.round(mouseY - dragAnchorY), maxScrollY());
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingV = false;
        draggingH = false;
        draggingMap = false;
        draggingBirdseye = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private static int scrollFromMouse(int mouse, int trackStart, int viewLength, int thumbLength, int maxScroll) {
        if (maxScroll <= 0) {
            return 0;
        }
        final int span = viewLength - thumbLength;
        if (span <= 0) {
            return 0;
        }
        final int offset = mouse - trackStart - thumbLength / 2;
        return GuiKit.clampScroll(Math.round((float) offset * maxScroll / span), maxScroll);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
