package com.shufflerbox.registry;

import com.shufflerbox.ShufflerBox;
import com.shufflerbox.block.ShufflerBoxBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class SBBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ShufflerBox.ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShufflerBoxBlockEntity>> SHUFFLER_BOX =
            BLOCK_ENTITIES.register("shuffler_box", () -> BlockEntityType.Builder
                    .of(ShufflerBoxBlockEntity::new, SBBlocks.SHUFFLER_BOX.get())
                    .build(null));
}
