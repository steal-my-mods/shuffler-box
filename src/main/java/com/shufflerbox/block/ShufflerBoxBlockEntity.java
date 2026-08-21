package com.shufflerbox.block;

import com.shufflerbox.registry.SBBlockEntities;
import com.shufflerbox.shuffle.Palette;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The 27 slots of a placed Shuffler Box.
 *
 * <p>{@link BaseContainerBlockEntity} already moves those slots in and out of the item's
 * {@code minecraft:container} component, which is what lets a broken box keep what was in it and
 * what the off-hand shuffle reads from. The menu is the vanilla shulker box menu, so the vanilla
 * screen renders it and no client code is needed.
 */
public class ShufflerBoxBlockEntity extends BaseContainerBlockEntity {

    private NonNullList<ItemStack> items = NonNullList.withSize(Palette.SLOT_COUNT, ItemStack.EMPTY);

    /** Viewers, so the lid sounds play once on the first opener and once on the last to leave. */
    private int openCount;

    public ShufflerBoxBlockEntity(BlockPos pos, BlockState state) {
        super(SBBlockEntities.SHUFFLER_BOX.get(), pos, state);
    }

    @Override
    public int getContainerSize() {
        return Palette.SLOT_COUNT;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.shufflerbox.shuffler_box");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new ShulkerBoxMenu(containerId, inventory, this);
    }

    /** No box inside a box, the same rule a shulker box follows. */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return stack.canFitInsideContainerItems();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, this.items, registries);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, this.items, false, registries);
    }

    @Override
    public void startOpen(Player player) {
        if (!this.remove && !player.isSpectator() && this.openCount++ == 0) {
            this.playLidSound(SoundEvents.SHULKER_BOX_OPEN);
        }
    }

    @Override
    public void stopOpen(Player player) {
        if (!this.remove && !player.isSpectator() && --this.openCount <= 0) {
            this.openCount = 0;
            this.playLidSound(SoundEvents.SHULKER_BOX_CLOSE);
        }
    }

    private void playLidSound(SoundEvent sound) {
        if (this.level != null) {
            this.level.playSound(null, this.getBlockPos(), sound, SoundSource.BLOCKS, 0.5F,
                    this.level.random.nextFloat() * 0.1F + 0.9F);
        }
    }
}
