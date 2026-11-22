package com.tfcie.prospecting.client;

import com.tfcie.prospecting.client.survey.SurveyMapScreen;
import com.tfcie.prospecting.client.terrain.RemoteSensingScreen;
import com.tfcie.prospecting.common.survey.SurveyMap;
import com.tfcie.prospecting.common.terrain.TerrainMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端 GUI 入口集合。<b>仅客户端</b> —— 只能从服务端安全入口调用：
 * 钻探样本经 {@link com.tfcie.prospecting.common.prospecting.CoreSampleGuiOpener}，
 * 探查地图与地形图经各自网络包的主线程回调。
 */
public final class ClientGuiHooks {

    public static void openCoreSample(ItemStack stack) {
        Minecraft.getInstance().setScreen(new CoreSampleScreen(stack));
    }

    /** 收到探查地图后打开地图界面。 */
    public static void openSurveyMap(SurveyMap map, String toolId) {
        Minecraft.getInstance().setScreen(new SurveyMapScreen(map, toolId));
    }

    /** 收到地形图后打开遥感扫描仪界面（三栏目）。 */
    public static void openTerrainMap(TerrainMap map) {
        Minecraft.getInstance().setScreen(new RemoteSensingScreen(map));
    }

    private ClientGuiHooks() {}
}
