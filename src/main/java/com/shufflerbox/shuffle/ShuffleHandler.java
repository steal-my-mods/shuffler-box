package com.shufflerbox.shuffle;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.shufflerbox.compat.Copycats;
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
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Turns an ordinary block placement into a draw from the Shuffler Box in the player's off hand.
 *
 * <p>Anything in the main hand is a stencil, not a supply: it decides <i>that</i> a block goes
 * here, and the box decides <i>which</i>. So the held stack is never spent -- the box pays for
 * every placement, and one block placed is always one block gone from the box. An empty main hand
 * works just as well; the box is the only thing the placement needs.
 *
 * <p>The one way to place the box itself is to hold it in your <i>main</i> hand.
 *
 * <p>A copycat block in the main hand is the exception, because it wants the off hand for itself:
 * a copycat takes its material from whatever is held there as it is placed. So the box does not
 * replace it -- the copycat goes down from the hand, and the box supplies its material, drawn from
 * the palette by the same slot-weighted draw. Shape from the hand, paint from the box. See
 * {@link Copycats}.
 *
 * <p>That one is done by <i>lending</i> rather than by intercepting: the drawn material is put in
 * the off hand at the first phase of the click and the box goes back at the end of the tick, so
 * whichever path the game uses to place the copycat finds a material there. Intercepting the
 * item's own placement instead only covers half of it -- a copycat's placement helper, the arrow
 * that offers the far edge of the block you are looking at, places the block during the *block*
 * phase, and a box that only watches the item phase never gets asked. That is what left copycats
 * placed by the arrow with no material at all, and a copycat with no material draws nothing:
 * an invisible block until something nearby forces it to redraw.
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

    /**
     * Materials lent out of a box to a copycat, by player, each for the length of one click. Only
     * ever written on the server thread, where the lending and the settling both happen, but read
     * from the client thread as well, which is what the concurrent map is for.
     */
    private static final Map<UUID, Loan> LOANS = new ConcurrentHashMap<>();

    private ShuffleHandler() {}

    @SubscribeEvent
    public static void onUseItemOnBlock(UseItemOnBlockEvent event) {
        if (DRAWING.get()) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        // A material lent to a copycat is being worn, not offered. It is sitting in the off hand
        // while the click runs, so the game's own off-hand turn would otherwise place it as a
        // block -- which is a block the player never asked for, paid for out of the box.
        if (isLentMaterial(player, event)) {
            event.cancelWithResult(ItemInteractionResult.FAIL);
            return;
        }

        ItemStack box = player.getOffhandItem();
        if (!box.is(SBItems.SHUFFLER_BOX.get())) {
            return;
        }

        // A copycat in the main hand wants this hand for a material rather than for a block, and
        // it has to have one before the first phase is out: a copycat's placement helper -- the
        // arrow offering to put the next one on the far edge of the one you are looking at --
        // runs in the *block* phase, before the item ever gets a turn of its own. So the box
        // lends the hand a material and then stays out of the way, and the copycat is placed by
        // whichever of the game's own paths was going to place it.
        if (Copycats.fillsFromOffHand(event.getItemStack())) {
            lendMaterial(event, player, box);
            return;
        }

        // ITEM_AFTER_BLOCK is the phase where vanilla would have placed the held block: the
        // clicked block has already declined the click, so opening a chest still opens the chest.
        if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK) {
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

    /**
     * Puts a drawn material in the off hand for the length of one click, in place of the box.
     *
     * <p>Nothing is cancelled and nothing is placed here. The copycat is placed by the game, down
     * whichever path it was already taking -- the placement helper in the block phase, or the
     * item's own turn after it -- and reads the material out of the hand as it lands, exactly as
     * it would if the player were holding the material themselves. That is the whole point of
     * lending rather than intercepting: paths this mod has never heard of work too.
     */
    private static void lendMaterial(UseItemOnBlockEvent event, Player player, ItemStack box) {
        if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_BEFORE_BLOCK
                || event.getLevel().isClientSide) {
            return;
        }

        UseOnContext context = event.getUseOnContext();
        Palette palette = Palette.of(box);
        int slot = palette.draw(player.getRandom(),
                stack -> Copycats.isMaterial(stack, context.getLevel(), context.getClickedPos()));
        if (slot < 0) {
            // Nothing in the box could be a material -- a box of slabs, or of diamonds. The
            // copycat goes down plain, which is what an empty off hand would have given it.
            return;
        }

        ItemStack material = palette.sample(slot);
        player.setItemInHand(InteractionHand.OFF_HAND, material);
        LOANS.put(player.getUUID(), new Loan(player, box, material, palette, slot));
    }

    /**
     * Ends every loan outstanding at the end of a tick, which is every loan there is: a click runs
     * to completion inside one tick, and this fires from the tail of the server's own tick, after
     * every phase of it and before the next tick syncs the player's inventory. So the client is
     * never told the box left that hand, and has no material to be confused by.
     *
     * <p>Not the server's task executor, which is the obvious thing to reach for and is wrong:
     * asked from the server thread it runs the task immediately rather than queueing it, which
     * put the box back before the copycat was ever placed. Everything looked exactly as it had
     * before the fix.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (LOANS.isEmpty()) {
            return;
        }

        for (Loan loan : LOANS.values()) {
            settle(loan);
        }

        LOANS.clear();
    }

    /**
     * Puts the box back in the hand it was lent out of, and charges it for the material if the
     * copycat took one.
     */
    private static void settle(Loan loan) {
        // Empty because the copycat took the last of what it was offered, or the same stack
        // because it did not. Anything else means something else moved that hand while the click
        // ran, and putting the box back would overwrite whatever it put there.
        ItemStack offHand = loan.player().getOffhandItem();
        if (offHand == loan.material() || offHand.isEmpty()) {
            loan.player().setItemInHand(InteractionHand.OFF_HAND, loan.box());
        }

        // The copycat takes its material out of the stack it found it in, and takes nothing at all
        // in creative. Charging the box for exactly what went missing gets both cases right
        // without this side having to know which one it is in -- and a material offered but
        // declined leaves a plain copycat and a full box.
        if (loan.material().isEmpty()) {
            loan.palette().consume(loan.slot());
            loan.palette().saveTo(loan.box());
        }
    }

    /** Whether this click is the game trying to place a material the box lent to a copycat. */
    private static boolean isLentMaterial(Player player, UseItemOnBlockEvent event) {
        Loan loan = LOANS.get(player.getUUID());
        return loan != null && event.getItemStack() == loan.material();
    }

    /** A material lent out of a box, and everything needed to square it up afterwards. */
    private record Loan(Player player, ItemStack box, ItemStack material, Palette palette, int slot) {}

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
