package com.shufflerbox.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The placed Shuffler Box. A plain full cube -- a shulker box's lid is an animated entity model,
 * and a box that opens on a hinge would be a lot of renderer for a block whose job happens while
 * it is in your hand.
 *
 * <p>Contents leave with the item rather than spilling on the floor, which the loot table handles
 * by copying the {@code minecraft:container} component onto the drop.
 */
public class ShufflerBoxBlock extends BaseEntityBlock {

    public static final MapCodec<ShufflerBoxBlock> CODEC = simpleCodec(ShufflerBoxBlock::new);

    public ShufflerBoxBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShufflerBoxBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (player.isSpectator()) {
            return InteractionResult.CONSUME;
        }

        if (level.getBlockEntity(pos) instanceof ShufflerBoxBlockEntity box) {
            player.openMenu(box);
            player.awardStat(Stats.OPEN_SHULKER_BOX);
            PiglinAi.angerNearbyPiglins(player, true);
            return InteractionResult.CONSUME;
        }

        return InteractionResult.PASS;
    }

    /**
     * A creative player never triggers the loot table, so hand them the filled box directly --
     * otherwise breaking one in creative silently deletes the palette inside it.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative()
                && level.getBlockEntity(pos) instanceof ShufflerBoxBlockEntity box && !box.isEmpty()) {
            ItemStack dropped = new ItemStack(this);
            dropped.applyComponents(box.collectComponents());

            ItemEntity entity = new ItemEntity(
                    level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, dropped);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }

        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos));
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            super.onRemove(state, level, pos, newState, isMoving);
            level.updateNeighbourForOutputSignal(pos, this);
        }
    }
}
