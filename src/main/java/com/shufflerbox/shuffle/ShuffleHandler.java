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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps a hand full of the Shuffler Box's blocks while the box is in the player's off hand.
 *
 * <p><b>The box does not take placements over.</b> Every block goes down the way the game placed
 * it, out of the main hand and spent from the main hand, so a block picked on purpose is placed on
 * purpose and a stack of sand stays a stack of sand. What the box does is <i>top the hand up</i>:
 * whenever a placement leaves the main hand empty, it hands over one more block, drawn from the
 * palette. One at a time, so every block placed is its own fresh draw -- which is what makes a
 * wall come out mottled, and what keeps the odds on every single block rather than on the stack.
 *
 * <p>Two ways in besides that. An <b>empty main hand</b> takes no turn of its own, so the click
 * falls through to the off hand and the box answers it directly: it places a drawn block and then
 * arms the hand, which is what starts the loop. And a <b>copycat</b> in the main hand is filled
 * rather than fed -- it takes its material from whatever is in the off hand as it lands, so the box
 * lends it one out of the palette instead of handing over a block to place. Shape from the hand,
 * paint from the box. See {@link Copycats}.
 *
 * <p>The one way to place the box itself is still to hold it in your main hand, which is now simply
 * what happens rather than something this class arranges.
 *
 * <p><b>Creative bends the rule</b>, because nothing is spent there and so a hand that empties never
 * fills again on its own. There the box replaces the block it handed over once that block has been
 * placed, which keeps the loop running after an empty-handed click has started it. What it does
 * <i>not</i> do is go looking for empty hands to fill. That was tried: since the hand is whichever
 * hotbar slot happens to be selected, scrolling across an empty slot selected it and the box loaded
 * it, so running along the hotbar came back with a random block in every empty slot.
 *
 * <p>Lending is done by putting the drawn material in the off hand at the first phase of the click
 * and putting the box back at the end of the tick, so whichever path the game uses to place the
 * copycat finds a material there. Watching the item's own placement covers only half of it: a
 * copycat's placement helper -- the arrow offering the far edge of the block you are looking at --
 * places during the *block* phase, and a box that only watched the item phase never got asked.
 * That is what left arrow-placed copycats with no material, and a copycat with no material draws
 * nothing at all.
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

    /** Placements made this tick, by player, and so the hands that may need topping up. */
    private static final Map<UUID, Placement> TOP_UPS = new ConcurrentHashMap<>();

    /**
     * The stack the box last handed each player, by identity. Only creative needs it: nothing is
     * spent there, so "you have used up what the box gave you" cannot be read off an empty hand
     * and has to be read off the stack still being the one the box handed over.
     *
     * <p>A marker on the item itself would be the other way to do this, and would be worse: a
     * component changes an item's identity, so the box's cobblestone would refuse to stack with
     * the player's own.
     */
    private static final Map<UUID, ItemStack> HANDED_OUT = new ConcurrentHashMap<>();

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

        // A copycat can also be standing there already, blank -- put down out of a creative
        // inventory, or placed on a click the box had no material for. Create paints one of those
        // from whatever hand clicked it, and reads that hand fresh when it does (NeoForge's own
        // patch: `blockstate.useItemOn(player.getItemInHand(hand), ...)`), so lending in the first
        // phase is the whole of it here too: Create's own paint then consumes the click and the
        // box never reaches its turn below.
        //
        // Safe to offer on every such click, because Create refuses to repaint a copycat that
        // already wears something (`hasCustomMaterial`, which returns the click unconsumed). So a
        // painted copycat still gets the box's ordinary turn and a block goes down against its
        // face, which is what building alongside one has always done.
        if (event.getUsePhase() == UseItemOnBlockEvent.UsePhase.ITEM_BEFORE_BLOCK
                && isTheBoxsOwnTurn(event, box)
                && Copycats.fillsFromOffHand(clickedState(event))) {
            lendMaterial(event, player, box);
            return;
        }

        // ITEM_AFTER_BLOCK is the phase where vanilla would have placed the held block: the
        // clicked block has already declined the click, so opening a chest still opens the chest.
        if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK) {
            return;
        }

        if (!isTheBoxsOwnTurn(event, box)) {
            return;
        }

        Palette palette = Palette.of(box);
        // A box with nothing to give is just a box. Both sides read the same synced component,
        // so both reach this decision the same way.
        if (!palette.hasPlaceable()) {
            return;
        }

        // The client has to take this interaction too, even though the server does the work: if it
        // let the off-hand turn pass it would place the box itself. It places nothing of its own,
        // so the drawn block simply appears when the server's update arrives rather than the box
        // appearing first and being corrected a moment later.
        if (event.getLevel().isClientSide) {
            event.cancelWithResult(ItemInteractionResult.SUCCESS);
            return;
        }

        event.cancelWithResult(place(player, event.getUseOnContext(), box, palette));
    }

    /** The block this click landed on. */
    private static BlockState clickedState(UseItemOnBlockEvent event) {
        return event.getLevel().getBlockState(event.getUseOnContext().getClickedPos());
    }

    /**
     * Whether this click is the box's own turn to place something, which is only ever the off-hand
     * turn.
     *
     * <p>The main hand is left alone on purpose. A block held there takes the first turn and places
     * itself -- that is the whole rule now, and the box tops the hand up afterwards instead of
     * taking the placement over, so a block held deliberately is placed deliberately. An empty main
     * hand takes no turn at all ({@code ServerPlayerGameMode#useItemOn} skips an empty stack, so no
     * event fires for that hand), and the click falls through to the off hand, where the box is.
     * Reaching here means nothing else wanted the click, so the box answers it with a block rather
     * than placing itself. (Not simply true: another mod can build a context around any stack it
     * likes.)
     */
    private static boolean isTheBoxsOwnTurn(UseItemOnBlockEvent event, ItemStack box) {
        return event.getHand() == InteractionHand.OFF_HAND && event.getItemStack() == box;
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
        // Loans first: a lent material is in the off hand where the box should be, and topping a
        // hand up starts by looking for the box in that hand.
        for (Loan loan : LOANS.values()) {
            settle(loan);
        }

        LOANS.clear();

        for (Placement placement : TOP_UPS.values()) {
            // Only the slot that was building. A player who scrolled away between placing and the
            // end of the tick has moved on, and a block appearing in whichever slot they landed on
            // is exactly the sort of unasked-for helpfulness this mod should not do.
            if (placement.player().getInventory().selected == placement.slot()) {
                topUp(placement.player());
            }
        }

        TOP_UPS.clear();
    }

    /**
     * Nothing the box remembers about a player outlives their session. All three maps are keyed by
     * player, and every one of those keys is a reference the server would otherwise hold for as
     * long as it runs -- and a top-up owed to somebody who has left is a block handed into an
     * inventory that has already been written to disk.
     *
     * <p>An outstanding loan is deliberately left where it is: the tick still to finish is what
     * hands the box back, and dropping the entry here would be dropping the box with it.
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID player = event.getEntity().getUUID();
        HANDED_OUT.remove(player);
        TOP_UPS.remove(player);
    }

    /**
     * Every block placed is a placement that might have emptied a hand, whoever made it and by
     * whichever route -- the player's own placement, the box's own turn, a copycat's placement
     * helper. Which of them actually emptied a hand is a question for the end of the tick, once the
     * placement has taken what it needed out of it.
     *
     * <p>Last of everyone listening, because this event is what land protection vetoes a placement
     * with, and a vetoed placement must not leave a top-up owed: the hand it emptied never emptied.
     * The bus does not call a listener for an event already cancelled, so going last is what makes
     * a veto silence this rather than something to test for -- by the time an `isCanceled` check
     * could run, running at all means it was not.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }

        if (event.getEntity() instanceof Player player) {
            TOP_UPS.put(player.getUUID(), new Placement(player, player.getInventory().selected));
        }
    }

    /**
     * Hands one drawn block to a player whose placement used up what they were holding, and charges
     * the box for it. One block, never a stack: a fresh draw for every block placed is what a
     * shuffled wall is made of, and it keeps the palette's odds on each block rather than on the
     * first of a stack of sixty-four.
     *
     * <p>Only ever called for a hand that has just placed something, and only for the slot that
     * placed it. Nothing here goes looking for empty hands: doing that was tried, and it is what
     * filled a whole hotbar with random blocks, because "the hand" is whichever slot is selected and
     * scrolling past an empty one selects it.
     */
    private static void topUp(Player player) {
        // Died or left between the placement and the end of the tick. That inventory is either
        // already on the floor or already on disk, so a block put into it goes nowhere -- and the
        // box would be charged for it.
        if (!player.isAlive()) {
            return;
        }

        ItemStack box = player.getOffhandItem();
        if (!box.is(SBItems.SHUFFLER_BOX.get())) {
            return;
        }

        if (!isSpent(player, player.getMainHandItem())) {
            return;
        }

        Palette palette = Palette.of(box);
        int slot = palette.draw(player.getRandom());
        if (slot < 0) {
            // An empty box, or one holding nothing it can place. The hand stays as it is.
            return;
        }

        ItemStack drawn = palette.sample(slot);
        player.setItemInHand(InteractionHand.MAIN_HAND, drawn);
        HANDED_OUT.put(player.getUUID(), drawn);

        // Creative pays for nothing, the same way it did not spend the stack it just placed from.
        if (!player.hasInfiniteMaterials()) {
            charge(box, palette, slot);
        }
    }

    /**
     * Whether the placement just made used up what the hand was holding.
     *
     * <p>In survival that is an empty hand and nothing else. Creative spends nothing, so there the
     * question has to be asked from the other side: the block the box handed over is still sitting
     * in the hand, and placing it was what it was for, so it is replaced. Anything else in the hand
     * is something the player chose, and is left alone -- which is the entire point of the box no
     * longer touching placements.
     */
    private static boolean isSpent(Player player, ItemStack held) {
        if (held.isEmpty()) {
            return true;
        }

        return player.hasInfiniteMaterials() && held == HANDED_OUT.get(player.getUUID());
    }

    /**
     * Puts the box back in the hand it was lent out of, and charges it for the material if the
     * copycat took one.
     */
    private static void settle(Loan loan) {
        Player player = loan.player();

        // Empty because the copycat took the last of what it was offered, or the same stack
        // because it did not. Anything else means something else moved that hand while the click
        // ran, and putting the box back there would overwrite whatever it put there -- so the box
        // goes into the bag instead, and onto the floor if the bag is full. The one thing that must
        // not happen is the box quietly ceasing to exist: it is only reachable from this loan, so
        // declining to hand it back is deleting it.
        ItemStack offHand = player.getOffhandItem();
        if (!player.isAlive()) {
            // Died or logged out with the material still out. That inventory has already been
            // emptied onto the floor or written to disk, so the box joins whatever else they lost
            // rather than going into a copy of it that nobody will read.
            player.drop(loan.box(), false);
        } else if (offHand == loan.material() || offHand.isEmpty()) {
            player.setItemInHand(InteractionHand.OFF_HAND, loan.box());
        } else {
            player.getInventory().placeItemBackInInventory(loan.box());
        }

        // The copycat takes its material out of the stack it found it in, and takes nothing at all
        // in creative. Charging the box for exactly what went missing gets both cases right
        // without this side having to know which one it is in -- and a material offered but
        // declined leaves a plain copycat and a full box.
        if (loan.material().isEmpty()) {
            charge(loan.box(), loan.palette(), loan.slot());
        }
    }

    /**
     * Takes one item out of the box, for a block it has just handed over or a material a copycat
     * has just taken. Whether the box pays at all is the caller's question; this is what paying is.
     */
    private static void charge(ItemStack box, Palette palette, int slot) {
        palette.consume(slot);
        palette.saveTo(box);
    }

    /** Whether this click is the game trying to place a material the box lent to a copycat. */
    private static boolean isLentMaterial(Player player, UseItemOnBlockEvent event) {
        Loan loan = LOANS.get(player.getUUID());
        return loan != null && event.getItemStack() == loan.material();
    }

    /** A material lent out of a box, and everything needed to square it up afterwards. */
    private record Loan(Player player, ItemStack box, ItemStack material, Palette palette, int slot) {}

    /** A placement waiting to be judged at the end of the tick, and the hotbar slot that made it. */
    private record Placement(Player player, int slot) {}

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
            charge(box, palette, slot);
        }

        return ItemInteractionResult.SUCCESS;
    }
}
