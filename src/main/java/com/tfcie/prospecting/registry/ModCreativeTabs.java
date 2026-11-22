package com.tfcie.prospecting.registry;

import com.tfcie.prospecting.TfcieProspecting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 创造模式物品栏标签页。
 *
 * <p>MC 的创造标签页是<b>显式枚举</b>的：物品即使注册成功（{@code /give} 可用），
 * 若不加入任何标签页，就不会出现在创造物品栏里。本类为勘探工具建一个专属标签页，
 * 使全部物品可被直接取用（便于开发期实测），并让 JEI 一并收录。
 *
 * <p>注意：这不涉及「物品制作与获取」——配方与生存获取途径仍属后续阶段。
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, TfcieProspecting.MOD_ID);

    public static final String PROSPECTING_TAB_ID = "prospecting";

    public static final RegistryObject<CreativeModeTab> PROSPECTING =
            CREATIVE_MODE_TABS.register(PROSPECTING_TAB_ID, () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.tfcie_prospecting." + PROSPECTING_TAB_ID))
                    .icon(() -> new ItemStack(ModItems.RECON_PICK.get()))
                    .displayItems((parameters, output) -> {
                        // T1~T6 工具
                        output.accept(ModItems.RECON_PICK.get());
                        output.accept(ModItems.DENSITY_PLUMB.get());
                        output.accept(ModItems.INDUCTION_PROBE.get());
                        output.accept(ModItems.MAGNETIC_NEEDLE.get());
                        output.accept(ModItems.ECHO_PROBE.get());
                        output.accept(ModItems.CORE_SAMPLER.get());
                        // D1 数据物品
                        output.accept(ModItems.CORE_SAMPLE.get());
                        // 基点砝码（密度探锤的校准基准）
                        output.accept(ModBlocks.BASE_WEIGHT.get());
                        // 改进版 + 电子探矿仪
                        output.accept(ModItems.ADVANCED_RECON_PICK.get());
                        output.accept(ModItems.ADVANCED_DENSITY_PLUMB.get());
                        output.accept(ModItems.ADVANCED_INDUCTION_PROBE.get());
                        output.accept(ModItems.ADVANCED_MAGNETIC_NEEDLE.get());
                        output.accept(ModItems.ADVANCED_ECHO_PROBE.get());
                        output.accept(ModItems.ELECTRONIC_PROSPECTOR.get());
                        // 遥感扫描仪
                        output.accept(ModItems.REMOTE_SENSING_SCANNER.get());
                    })
                    .build());

    private ModCreativeTabs() {}
}
