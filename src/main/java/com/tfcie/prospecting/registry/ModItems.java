package com.tfcie.prospecting.registry;

import com.tfcie.prospecting.TfcieProspecting;
import com.tfcie.prospecting.common.item.CoreSampleItem;
import com.tfcie.prospecting.common.item.CoreSamplerItem;
import com.tfcie.prospecting.common.item.DensityPlumbItem;
import com.tfcie.prospecting.common.item.EchoProbeItem;
import com.tfcie.prospecting.common.item.InductionProbeItem;
import com.tfcie.prospecting.common.item.MagneticNeedleItem;
import com.tfcie.prospecting.common.item.ReconPickItem;
import com.tfcie.prospecting.common.item.RemoteSensingScannerItem;
import com.tfcie.prospecting.common.item.SurveyToolItem;
import com.tfcie.prospecting.common.survey.SurveyFilter;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 物品注册。共 13 项：
 * <ul>
 *   <li>T1~T6 工具 + D1 钻探样本（7 项，生存可用）；</li>
 *   <li><b>5 件改进版 + 电子探矿仪</b>（6 项）—— 不进常规生存获取，
 *       改用 {@link SurveyToolItem} 共用一套探查地图框架。</li>
 * </ul>
 *
 * <p>范围决策 D6：旧勘矿镐（prospectors_pick）本次完全不涉及，故不在此注册。
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, TfcieProspecting.MOD_ID);

    // T1 踏勘镐
    public static final RegistryObject<ReconPickItem> RECON_PICK =
            ITEMS.register(ReconPickItem.ID, ReconPickItem::new);
    // T2 密度探锤
    public static final RegistryObject<DensityPlumbItem> DENSITY_PLUMB =
            ITEMS.register(DensityPlumbItem.ID, DensityPlumbItem::new);
    // T3 感应探棒
    public static final RegistryObject<InductionProbeItem> INDUCTION_PROBE =
            ITEMS.register(InductionProbeItem.ID, InductionProbeItem::new);
    // T4 磁力探针
    public static final RegistryObject<MagneticNeedleItem> MAGNETIC_NEEDLE =
            ITEMS.register(MagneticNeedleItem.ID, MagneticNeedleItem::new);
    // T5 回声探针
    public static final RegistryObject<EchoProbeItem> ECHO_PROBE =
            ITEMS.register(EchoProbeItem.ID, EchoProbeItem::new);
    // T6 钻探取样器
    public static final RegistryObject<CoreSamplerItem> CORE_SAMPLER =
            ITEMS.register(CoreSamplerItem.ID, CoreSamplerItem::new);
    // D1 钻探样本（数据物品，不可堆叠；右键打开层序 GUI）
    public static final RegistryObject<CoreSampleItem> CORE_SAMPLE =
            ITEMS.register(CoreSampleItem.ID, CoreSampleItem::new);

    // ---- 改进版 + 电子探矿仪：6 件共用 SurveyToolItem，仅过滤器不同 ----

    /** 改进版踏勘镐：浅带内全部矿物。 */
    public static final RegistryObject<SurveyToolItem> ADVANCED_RECON_PICK =
            ITEMS.register(SurveyFilter.RECON.toolId(), () -> new SurveyToolItem(SurveyFilter.RECON));
    /** 改进版密度探锤：高/低密度矿物 + 空腔 + 流体（内建校准，不再需要基点砝码）。 */
    public static final RegistryObject<SurveyToolItem> ADVANCED_DENSITY_PLUMB =
            ITEMS.register(SurveyFilter.DENSITY.toolId(), () -> new SurveyToolItem(SurveyFilter.DENSITY));
    /** 改进版感应探棒：仅导电矿物。 */
    public static final RegistryObject<SurveyToolItem> ADVANCED_INDUCTION_PROBE =
            ITEMS.register(SurveyFilter.CONDUCTIVE.toolId(), () -> new SurveyToolItem(SurveyFilter.CONDUCTIVE));
    /** 改进版磁力探针：仅磁性矿物。 */
    public static final RegistryObject<SurveyToolItem> ADVANCED_MAGNETIC_NEEDLE =
            ITEMS.register(SurveyFilter.MAGNETIC.toolId(), () -> new SurveyToolItem(SurveyFilter.MAGNETIC));
    /** 改进版回声探针：空腔 / 流体 / 洞壁暴露矿面。 */
    public static final RegistryObject<SurveyToolItem> ADVANCED_ECHO_PROBE =
            ITEMS.register(SurveyFilter.ECHO.toolId(), () -> new SurveyToolItem(SurveyFilter.ECHO));
    /** 电子探矿仪：直接给出全部矿种及其坐标。 */
    public static final RegistryObject<SurveyToolItem> ELECTRONIC_PROSPECTOR =
            ITEMS.register(SurveyFilter.ALL_ORES.toolId(), () -> new SurveyToolItem(SurveyFilter.ALL_ORES));

    /** 遥感扫描仪：探测周围地形，三栏目面板（鸟瞰 / 平面地图 / 等高线）。 */
    public static final RegistryObject<RemoteSensingScannerItem> REMOTE_SENSING_SCANNER =
            ITEMS.register(RemoteSensingScannerItem.ID, RemoteSensingScannerItem::new);

    private ModItems() {}
}
