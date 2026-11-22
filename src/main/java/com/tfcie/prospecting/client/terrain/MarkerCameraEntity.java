package com.tfcie.prospecting.client.terrain;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;

/**
 * 仅客户端存在的「哑相机实体」—— 遥感扫描仪鸟瞰视角的取景锚点。
 *
 * <h2>为什么用这个方法而不是 mixin</h2>
 * 读了本项目所用 Forge 版本（1.20.1-47.4.6）的源码后确认：
 * <ul>
 *   <li>{@code Camera} 的 {@code setPosition/move/setRotation} 全是 {@code protected}，
 *       外部<b>无法</b>直接移动渲染相机；Forge 只新增了公开的 {@code setAnglesInternal}（仅角度）；</li>
 *   <li>但 {@code Minecraft.cameraEntity} 是 <b>public 字段</b>，且
 *       {@code setCameraEntity(e)} 只做一次赋值，<b>没有</b> {@code allChanged()} 之类的副作用；</li>
 *   <li>{@code GameRenderer} 取 {@code getCameraEntity()} 作为相机实体，
 *       且第一人称下传入 {@code detached = false}，相机<b>正好落在实体位置</b>，
 *       不做第三人称的回退偏移。</li>
 * </ul>
 * 因此「换掉摄像机实体」就能让渲染器从任意位置取景，得到与真实飞行<b>完全相同</b>的画面 ——
 * 因为用的就是同一套渲染管线。
 *
 * <h2>两个必须处理的细节</h2>
 * <ol>
 *   <li><b>眼高归零</b>：{@link net.minecraft.client.Camera} 会把相机抬到实体眼高，
 *       故重写 {@link #getEyeHeight()} 返回 0，使相机严格落在给定坐标；</li>
 *   <li><b>插值基准</b>：相机位置按 {@code lerp(partialTick, xo, getX())} 取。
 *       本实体不加入世界、不参与 tick，{@code xo/yo/zo} 不会自动更新，
 *       故移动时必须手动同步，否则相机会一直插值到旧位置。</li>
 * </ol>
 */
public final class MarkerCameraEntity extends Entity {

    public MarkerCameraEntity(Level level) {
        // MARKER 是无碰撞、无渲染的空实体类型，正合用
        super(EntityType.MARKER, level);
        this.noCulling = true;
    }

    /**
     * 眼高归零：相机严格落在实体坐标，不被抬高。
     *
     * <p>注意覆盖的是<b>这一层</b>而不是 {@code getEyeHeight()}——后者在 1.20.1 里是
     * {@code public final}，无法覆盖；而 {@code Entity} 的构造函数正是经本方法把结果写进
     * {@code eyeHeight} 字段，再由 {@code Camera.setup} 读走。覆盖这里即可一步到位。
     */
    @Override
    protected float getEyeHeight(Pose pose, EntityDimensions dimensions) {
        return 0.0F;
    }

    /** 移动并把插值基准同步到目标位置，避免相机滞后在旧位置。 */
    public void moveTo(double x, double y, double z, float yaw, float pitch) {
        this.setPos(x, y, z);
        this.xo = x;
        this.yo = y;
        this.zo = z;
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.yRotO = yaw;
        this.xRotO = pitch;
    }

    // 不加入世界、不存档，以下三个抽象方法均为空实现

    @Override
    protected void defineSynchedData() {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
