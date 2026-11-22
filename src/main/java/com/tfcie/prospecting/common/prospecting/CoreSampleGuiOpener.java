package com.tfcie.prospecting.common.prospecting;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/**
 * 打开钻探样本界面的安全入口。
 *
 * <p>GUI 是纯客户端的事物，而 {@code CoreSampleItem} 是双端类。这里用
 * {@link DistExecutor#unsafeRunWhenOn} 包一层：客户端类只在真正的客户端进程里被解析与加载，
 * 专用服务端不会因引用到它而崩溃。
 *
 * <p>之所以不需要网络包：样本数据就在这份 {@link ItemStack} 的 NBT 里，客户端本来就有。
 */
public final class CoreSampleGuiOpener {

    public static void open(ItemStack stack) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.tfcie.prospecting.client.ClientGuiHooks.openCoreSample(stack));
    }

    private CoreSampleGuiOpener() {}
}
