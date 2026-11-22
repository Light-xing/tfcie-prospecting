package com.tfcie.prospecting.registry;

import com.tfcie.prospecting.TfcieProspecting;
import com.tfcie.prospecting.common.block.BaseWeightBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 方块注册。
 *
 * <p>阶段 4 新增「基点砝码」—— 密度探锤（重力法）的校准基准，
 * 对应设计文档 T2 的「须放置基点砝码校准」与漂移复位机制。
 * 其配方属「物品制作与获取阶段」，本次不涉及。
 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, TfcieProspecting.MOD_ID);

    public static final RegistryObject<Block> BASE_WEIGHT =
            BLOCKS.register(BaseWeightBlock.ID, BaseWeightBlock::new);

    /** 方块的物品形态（沿用 {@link ModItems} 的物品注册表）。 */
    public static final RegistryObject<BlockItem> BASE_WEIGHT_ITEM =
            ModItems.ITEMS.register(BaseWeightBlock.ID,
                    () -> new BlockItem(BASE_WEIGHT.get(), new Item.Properties()));

    private ModBlocks() {}
}
