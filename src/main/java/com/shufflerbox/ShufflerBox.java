package com.shufflerbox;

import com.shufflerbox.registry.SBBlockEntities;
import com.shufflerbox.registry.SBBlocks;
import com.shufflerbox.registry.SBItems;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * Shuffler Box: a shulker box that builds for you.
 *
 * <p>Fill it with a palette of blocks, hold it in your off hand, and every block you place is
 * drawn at random from the box instead of from your hand. Everything else about it is a shulker
 * box: 27 slots, contents kept when it is broken and carried, nothing nested inside it.
 */
@Mod(ShufflerBox.ID)
public class ShufflerBox {

    public static final String ID = "shufflerbox";

    public ShufflerBox(IEventBus modBus) {
        SBBlocks.BLOCKS.register(modBus);
        SBBlockEntities.BLOCK_ENTITIES.register(modBus);
        SBItems.ITEMS.register(modBus);

        modBus.addListener(SBItems::addToCreativeTab);
    }

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }
}
