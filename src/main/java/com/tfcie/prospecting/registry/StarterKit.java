package com.tfcie.prospecting.registry;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * 「基础包」清单：开局补给（numen {@code CompanionEvent.SPAWN}）与 {@code /prospect kit}
 * <b>共用的唯一一份</b>发放清单。
 *
 * <h2>为什么必须只有一份</h2>
 * 两条通道都曾按「所有 {@code AbstractProspectingItem}」发放，于是 5 件改进型与
 * electronic_prospector / remote_sensing_scanner 也进了身体背包 —— 而 MCP 侧按设计
 * <b>刻意不暴露</b>这 6 件（见报告 13 §6.2 / §11.4 / §12.2）：它们自带「直接给矿种 / 坐标」
 * 或「大范围探查」的能力，一旦能持在手上，智能体就绕开了「多源联合推断」的评测设计。
 * 把清单收敛到本类后，两条通道不可能再走偏。
 *
 * <h2>内容</h2>
 * <ul>
 *   <li><b>六件基础工具</b>：踏勘镐、密度探锤、感应探棒、磁力探针、回声探针、钻探取样器
 *       —— 与 {@code NumenBridge} 暴露给模型的 7 个工具里的 6 个一一对应；</li>
 *   <li><b>一个基点砝码</b>：密度探锤的 {@code at_base} 漂移复位基准。报告 13 §11.5 的实测项
 *       就是因为补给包不发它而无法验证 —— 没有砝码，探锤的漂移只能累积不能复位。</li>
 * </ul>
 *
 * <p>刻意<b>不含</b>：改进型 5 件、电子探矿仪、遥感扫描仪，以及数据物品「钻探样本」
 * （样本由钻探产出，不是补给品）。
 *
 * <h2>为什么存 {@link RegistryObject} 而不是 {@code Item}</h2>
 * 本类可能在注册完成前被类加载（静态字段只保存引用，不调用 {@code get()}），
 * 真正取物品是在发放时刻，那时注册早已完成。这样不会因为类初始化顺序而拿到 null。
 */
public final class StarterKit {

    /** 基础包内容，顺序即发放顺序（工具在前、基点砝码在后）。 */
    public static final List<RegistryObject<? extends Item>> ITEMS = List.of(
            ModItems.RECON_PICK,
            ModItems.DENSITY_PLUMB,
            ModItems.INDUCTION_PROBE,
            ModItems.MAGNETIC_NEEDLE,
            ModItems.ECHO_PROBE,
            ModItems.CORE_SAMPLER,
            ModBlocks.BASE_WEIGHT_ITEM);

    private StarterKit() {}
}
