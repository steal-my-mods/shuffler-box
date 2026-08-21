package com.shufflerbox.registry;

import com.shufflerbox.ShufflerBox;
import com.shufflerbox.block.ShufflerBoxBlock;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class SBBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ShufflerBox.ID);

    public static final DeferredBlock<ShufflerBoxBlock> SHUFFLER_BOX = BLOCKS.registerBlock("shuffler_box",
            ShufflerBoxBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(2.0F)
                    .sound(SoundType.STONE)
                    // A pushed box would have to spill or vanish; a shulker box refuses to be
                    // pushed at all, and a box full of someone's palette should do the same.
                    .pushReaction(PushReaction.DESTROY));
}
