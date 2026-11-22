package com.tfcie.prospecting.client.terrain;

import net.minecraft.client.Minecraft;

/**
 * 鸟瞰取景的<b>纯换算</b>（无状态、无副作用）。
 *
 * <h2>为什么不再接管摄像机</h2>
 * 早期实现走的是「换掉 {@code Minecraft.cameraEntity}」，那会让<b>整屏</b>都变成鸟瞰 ——
 * 玩家自己的视角消失。而需求是「GUI 外的主视角不变，只有面板内是鸟瞰」。
 * 现在改为：主渲染照常用玩家相机，另外<b>再做一次只写进方形开口的渲染</b>
 * （见 {@link BirdseyeRenderer}），因此本类退化为纯数学。
 *
 * <h2>俯仰的符号</h2>
 * MC 的 pitch <b>正值向下</b>（{@code Entity.calculateViewVector} 里 {@code y = -sin(xRot)}），
 * 正俯视是 {@code +90}。要「垂直略偏」，取 {@code +75}（偏离正俯视 15°）。
 * <b>写成负数会朝向天空</b> —— 这正是之前那个「一片天蓝色」的原因。
 *
 * <h2>高度换算</h2>
 * 开口是方形，但裁剪只是限制写入区域，<b>投射矩阵仍然覆盖整个帧缓冲</b>。
 * 设 {@code H} = 屏幕高、{@code S} = 开口边长（同一单位即可，比例与像素无关），
 * 开口占整屏纵向的比例为 {@code S/H}。要让开口纵向恰好看到 80 格：
 *
 * <pre>
 *   (S/H) · 2h·tan(f/2) = 80      →      h = 80·H / ( 2·S·tan(f/2) )
 * </pre>
 *
 * 又因为裁剪区域是正方形、且 GUI 两轴缩放一致，横向可视格数与纵向相同 ——
 * 即「5×5 恰好」在方形开口下两个方向同时成立。
 */
public final class BirdseyeCamera {

    /** 目标：开口纵向恰好看到 5×5 区块。 */
    public static final int TARGET_BLOCKS = 5 * 16;

    /** 默认俯仰：正俯视（+90）向下偏 15°。注意符号 —— 负值会朝上。 */
    public static final float DEFAULT_PITCH = 75.0F;

    /**
     * 默认偏航：<b>固定 180°（北朝上、东朝右）</b>，刻意<b>不</b>跟随玩家朝向。
     *
     * <h2>为什么必须固定</h2>
     * 在近俯视（俯角 75°）下，绕竖直轴转 γ 与在屏幕上把画面转 γ <b>看起来几乎一样</b>。
     * 若偏航取玩家朝向，那么玩家面朝 45° 时，俯瞰到的方块棱就会相对准星倾斜 45°
     * （俯角正常，只有「转」不对）；方向键的移动又是相对摄像机自身的，
     * 于是相对方块网格也成了斜向 —— 三个现象同一个根因。
     *
     * <p>取 180° 还让本栏目与栏二/栏三的地图<b>朝向一致</b>：
     * 那两栏的像素图是「+X 向右、+Z 向下」，即北在上、东在右；此处亦同
     * （yaw=180 时摄像机右侧向量为 +X，屏幕上方为 -Z）。
     */
    public static final float DEFAULT_YAW = 180.0F;

    private BirdseyeCamera() {}

    /**
     * 相机相对目标高度平面的高度差（格）。
     *
     * @param screenHeight 整个屏幕高度（GUI 单位）
     * @param holeSize     方形开口边长（GUI 单位）
     */
    public static double heightOffset(int screenHeight, int holeSize) {
        final double tanHalf = Math.tan(Math.toRadians(verticalFov()) / 2.0D);
        if (tanHalf <= 1.0E-6D || holeSize <= 0) {
            return 64.0D;
        }
        final double fullExtent = TARGET_BLOCKS * (double) screenHeight / (double) holeSize;
        return fullExtent / (2.0D * tanHalf);
    }

    /** 相机应处的 Y。 */
    public static double cameraY(int screenHeight, int holeSize, double planeY) {
        return planeY + heightOffset(screenHeight, holeSize);
    }

    /** 玩家当前的垂直 FOV（度）。投射矩阵按垂直 FOV 构造，故直接用该值。 */
    public static double verticalFov() {
        final Integer fov = Minecraft.getInstance().options.fov().get();
        return fov == null ? 70.0D : fov.doubleValue();
    }
}
