package com.tfcie.prospecting.client.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import com.tfcie.prospecting.TfcieProspecting;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;

/**
 * 鸟瞰视角的离屏渲染：主渲染照常用玩家相机，这里<b>再做一次只写进方形开口的渲染</b>。
 *
 * <h2>为什么不接管摄像机</h2>
 * 换掉 {@code Minecraft.cameraEntity} 会让整屏都变成鸟瞰、玩家视角消失。
 * 而需求是「GUI 外的主视角不变」。要在同一帧里出现两个相机视角，就必须渲染两次。
 *
 * <h2>为什么用裁剪而不是 FBO</h2>
 * 用一个离屏帧缓冲再贴图当然也可以，但那要自己管理帧缓冲与纹理、还要把纹理送进 GUI 管线。
 * 而渲染顺序是「世界 → 事件（AFTER_LEVEL）→ GUI」，所以可以：
 * <ol>
 *   <li>主渲染：玩家相机，铺满整屏 —— <b>主视角完全不受影响</b>；</li>
 *   <li>本类：开启裁剪矩形（方形开口），<b>清掉该区域的深度</b>，用鸟瞰相机把世界再画一遍
 *       —— 只有开口那一块被覆盖；</li>
 *   <li>GUI：用不透明边框盖住开口以外的区域，开口处便透出鸟瞰画面。</li>
 * </ol>
 * 深度必须清：否则鸟瞰几何会被主渲染留下的深度挡住。
 *
 * <h2>两个必须自己准备的依赖</h2>
 * {@code LevelRenderer.renderLevel} 需要一个 {@code LightTexture}，而
 * {@code GameRenderer.lightTexture} 是 private 且无访问器。好在 {@code LightTexture}
 * 的构造器与 {@code updateLightTexture} 都是 public，因此自己维护一个并在每次使用前更新即可 ——
 * 这比反射读私有字段稳当。
 */
@Mod.EventBusSubscriber(modid = TfcieProspecting.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BirdseyeRenderer {

    /** 本帧的渲染请求；由界面每帧写入，为空表示不需要鸟瞰。 */
    private static boolean requested;

    /** 是否隐藏原版 HUD；由界面在栏目切换时设置。 */
    private static boolean hideHud;
    private static double camX;
    private static double camY;
    private static double camZ;
    private static float camYaw;

    /** 裁剪矩形（帧缓冲像素，原点在左下）。 */
    private static int scissorX;
    private static int scissorY;
    private static int scissorW;
    private static int scissorH;

    @Nullable
    private static Camera camera;
    @Nullable
    private static MarkerCameraEntity probe;
    @Nullable
    private static LightTexture lightTexture;
    @Nullable
    private static Level probeLevel;

    private BirdseyeRenderer() {}

    /** 界面每帧调用：请求在给定裁剪矩形内渲染鸟瞰。 */
    public static void request(double x, double y, double z, float yaw,
                               int scissorX, int scissorY, int scissorW, int scissorH) {
        requested = true;
        camX = x;
        camY = y;
        camZ = z;
        camYaw = yaw;
        BirdseyeRenderer.scissorX = scissorX;
        BirdseyeRenderer.scissorY = scissorY;
        BirdseyeRenderer.scissorW = Math.max(1, scissorW);
        BirdseyeRenderer.scissorH = Math.max(1, scissorH);
    }

    /** 界面每帧调用：本帧不需要鸟瞰。 */
    public static void clearRequest() {
        requested = false;
    }

    /**
     * 鸟瞰栏目激活时是否隐藏原版 HUD（含快捷栏）。
     *
     * <p>由界面在<b>栏目切换时</b>设置，而不是每帧设置 —— 渲染顺序是
     * {@code GameRenderer.render} 第 966 行画 HUD、第 986 行画 Screen，
     * <b>HUD 先于我们的界面</b>。若按帧设置，打开界面的第一帧仍会漏出 HUD。
     */
    public static void setHideHud(boolean hide) {
        hideHud = hide;
    }

    /**
     * 隐藏原版 HUD。
     *
     * <h2>为什么必须这么做</h2>
     * 鸟瞰开口是<b>透空</b>的（靠不透明边框遮挡四周，只留方形开口透出真实世界）。
     * 而 HUD 在这一帧里比 Screen 更早绘制，于是快捷栏、血量等会从开口里透出来。
     *
     * <h2>为什么取消这个事件是安全的</h2>
     * {@code ForgeGui.render}（ForgeGui:112）只负责遍历并绘制 HUD 图层，
     * 取消后它直接 {@code return}；而 Screen 的绘制在 {@code GameRenderer.render} 的
     * 另一条分支上（986 行 {@code ForgeHooksClient.drawScreen}），**不受影响**。
     */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Pre event) {
        if (hideHud) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !requested) {
            return;
        }
        render(event.getPartialTick());
    }

    private static void render(float partialTick) {
        final Minecraft minecraft = Minecraft.getInstance();
        final Level level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }

        // 换维度时重建探针（实体与关卡绑定）
        if (probe == null || probeLevel != level) {
            probe = new MarkerCameraEntity(level);
            probeLevel = level;
            camera = null;
        }
        probe.moveTo(camX, camY, camZ, camYaw, BirdseyeCamera.DEFAULT_PITCH);

        if (camera == null) {
            camera = new Camera();
        }
        camera.setup(level, probe, false, false, partialTick);

        if (lightTexture == null) {
            lightTexture = new LightTexture(minecraft.gameRenderer, minecraft);
        }
        lightTexture.updateLightTexture(partialTick);

        final var window = minecraft.getWindow();
        final float aspect = (float) window.getWidth() / (float) window.getHeight();
        final float far = Math.max(256.0F,
                (float) minecraft.options.getEffectiveRenderDistance() * 16.0F * 4.0F);
        final Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(BirdseyeCamera.verticalFov()), aspect, 0.05F, far);

        final PoseStack pose = new PoseStack();
        pose.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
        pose.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0F));

        // 与 vanilla GameRenderer.renderLevel 的 1140~1147 行严格对齐：
        //  1) 由 modelview 的法线矩阵求逆，供依赖它的着色器使用；
        //  2) 用 modelview × projection 重建剔除视锥；
        //  3) 设置投影，再渲染。
        //
        // 【曾经的错误】这里原先会 push 一次 modelview 栈再乘上本 pose。
        // 但区块着色器的 ModelViewMat 是 LevelRenderer 直接从传入的 PoseStack 取的
        // （LevelRenderer:1514），与 modelview 栈无关；手动推栈只会把全局
        // modelViewMatrix 污染成「主视角旋转 × 鸟瞰旋转」，导致画面歪斜。
        // vanilla 全程不碰这个栈，故此处也不碰。
        RenderSystem.setInverseViewRotationMatrix(new Matrix3f(pose.last().normal()).invert());
        // 传副本：Frustum 的构造在部分 JOML 版本里会改写传入的投影矩阵
        minecraft.levelRenderer.prepareCullFrustum(pose, camera.getPosition(), new Matrix4f(projection));

        RenderSystem.backupProjectionMatrix();
        RenderSystem.enableScissor(scissorX, scissorY, scissorW, scissorH);
        try {
            // 主渲染已写满该区域的深度，不清掉的话鸟瞰几何会被它挡住
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);

            lightTexture.turnOnLightLayer();
            minecraft.levelRenderer.renderLevel(pose, partialTick, Util.getNanos(), false,
                    camera, minecraft.gameRenderer, lightTexture, projection);
            lightTexture.turnOffLightLayer();
        } finally {
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.disableScissor();
        }
    }
}
