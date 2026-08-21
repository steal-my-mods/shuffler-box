package com.shufflerbox.shuffle;

import com.shufflerbox.registry.SBItems;
import com.shufflerbox.ShufflerBox;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;

/**
 * Turns an ordinary block placement into a draw from the Shuffler Box in the player's off hand.
 *
 * <p>Anything in the main hand is a stencil, not a supply: it decides <i>that</i> a block goes
 * here, and the box decides <i>which</i>. So the held stack is never spent -- the box pays for
 * every placement, and one block placed is always one block gone from the box. An empty main hand
 * works just as well; the box is the only thing the placement needs.
 *
 * <p>The one way to place the box itself is to hold it in your <i>main</i> hand.
 */
@EventBusSubscriber(modid = ShufflerBox.ID)
public final class ShuffleHandler {

    /**
     * Set while the drawn block is being placed. The block goes down through the same call the
     * player's own hand would use, which fires this event a second time for the drawn block; the
     * flag is what stops the box from drawing again from inside its own draw.
     *
     * <p>Thread-local rather than a plain field because a single-player game runs an integrated
     * server: the client thread and the server thread are both in here.
     */
    private static final ThreadLocal<Boolean> DRAWING = ThreadLocal.withInitial(() -> false);

    private ShuffleHandler() {}

    @SubscribeEvent
    public static void onUseItemOnBlock(UseItemOnBlockEvent event) {
        if (DRAWING.get()) {
            return;
        }

        // ITEM_AFTER_BLOCK is the phase where vanilla would have placed the held block: the
        // clicked block has already declined the click, so opening a chest still opens the chest.
        if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        ItemStack box = player.getOffhandItem();
        if (!box.is(SBItems.SHUFFLER_BOX.get())) {
            return;
        }

        if (!isRequestToShuffle(event, box)) {
            return;
        }

        Palette palette = Palette.of(box);
        // A box with nothing to give is just a box. Both sides read the same synced component,
        // so both reach this decision the same way.
        if (!palette.hasPlaceable()) {
            return;
        }

        // The client has to take the interaction too, even though the server does the work.
        // Minecraft#startUseItem walks the hands in order and stops at the first that consumes
        // the click -- if the client let a main-hand turn pass, it would go on to try the off
        // hand and place the box itself. It places nothing of its own: with no prediction to
        // correct, the drawn block simply appears when the server's update arrives, rather than
        // the held block appearing first and being swapped out a moment later.
        if (event.getLevel().isClientSide) {
            event.cancelWithResult(ItemInteractionResult.SUCCESS);
            return;
        }

        event.cancelWithResult(place(player, event.getUseOnContext(), box, palette));
    }

    /**
     * Whether this click is a player asking the box for a block.
     *
     * <p>Two ways in, because the game gives the hands two separate turns. A block in the main
     * hand gets the first turn and would place itself, so the box steps in there. An empty main
     * hand -- or one holding something with nothing to do with this block, like a sword -- takes
     * no turn at all, and the click falls through to the off hand, where the box is: reaching
     * that point means nothing else wanted the click, so the box answers with a block rather
     * than placing itself.
     */
    private static boolean isRequestToShuffle(UseItemOnBlockEvent event, ItemStack box) {
        ItemStack held = event.getItemStack();

        if (event.getHand() == InteractionHand.MAIN_HAND) {
            // Placing a second box is placing a box. Anything that is not a block was never
            // going to place anything, so leave it to the off-hand turn below.
            return !held.is(SBItems.SHUFFLER_BOX.get()) && held.getItem() instanceof BlockItem;
        }

        // The off-hand turn, which is the box's own. (Not simply true: another mod can build a
        // context around any stack it likes.)
        return held == box;
    }

    private static ItemInteractionResult place(Player player, UseOnContext context, ItemStack box, Palette palette) {
        int slot = palette.draw(player.getRandom());
        ItemStack drawn = palette.sample(slot);

        // UseOnContext keeps its hit result to itself, but exposes every part of it.
        BlockHitResult hit = new BlockHitResult(
                context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), context.isInside());
        UseOnContext drawnContext =
                new UseOnContext(context.getLevel(), player, context.getHand(), drawn, hit);

        // Placing through ItemStack#useOn rather than reaching for BlockItem#place is what keeps
        // the rest of the game working: the adventure-mode check, the block-place event that
        // land-protection mods veto placements with, and every other mod's own listener all hang
        // off this call. Modded blocks get placed by their own code for the same reason.
        InteractionResult result;
        DRAWING.set(true);
        try {
            result = drawn.useOn(drawnContext);
        } finally {
            DRAWING.set(false);
        }

        if (!result.consumesAction()) {
            // The drawn block would not go there, or something vetoed it. Fail rather than
            // falling through to the held block: the client has already committed this click to
            // the main hand, and placing a different block than the box promised is worse than
            // placing nothing.
            return ItemInteractionResult.FAIL;
        }

        // Creative hands out blocks for free, and the placement has already declined to charge
        // the stack it was given, so the box should not be charged either.
        if (!player.hasInfiniteMaterials()) {
            palette.consume(slot);
            palette.saveTo(box);
        }

        return ItemInteractionResult.SUCCESS;
    }
}
