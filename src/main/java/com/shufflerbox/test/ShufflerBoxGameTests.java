package com.shufflerbox.test;

import java.util.List;

import com.shufflerbox.block.ShufflerBoxBlockEntity;
import com.shufflerbox.registry.SBBlocks;
import com.shufflerbox.registry.SBItems;
import com.shufflerbox.ShufflerBox;
import com.shufflerbox.shuffle.Palette;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The two things this mod has to get right: the odds match the slots a player laid out, and
 * building from the box spends the box rather than the hand.
 */
@GameTestHolder(ShufflerBox.ID)
@PrefixGameTestTemplate(false)
public class ShufflerBoxGameTests {

    /** A fixed seed, so a failure here is a real one and not a bad afternoon at the dice. */
    private static final long SEED = 20250821L;

    private static final int DRAWS = 4000;

    /** Four standard deviations of a 4000-draw binomial, rounded up. Never a coin-flip failure. */
    private static final int DRAW_TOLERANCE = 120;

    /**
     * The block whose top face gets clicked. Relative y=1 is the platform's floor -- GameTest
     * lays a structure one block above its structure block -- and the test sets it itself rather
     * than trusting what the template put there.
     */
    private static final BlockPos FLOOR = new BlockPos(4, 1, 4);

    @GameTest(template = "platform")
    public static void oddsFollowSlotsNotStackSizes(GameTestHelper helper) {
        // Three slots of cobblestone against one of stone is 3:1 -- and the single cobblestone
        // in the third slot has to pull the same weight as the two full stacks beside it.
        Palette palette = Palette.of(boxOf(
                new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COBBLESTONE, 1),
                new ItemStack(Items.STONE, 64)));

        RandomSource random = RandomSource.create(SEED);
        int stone = 0;
        for (int draw = 0; draw < DRAWS; draw++) {
            if (palette.sample(palette.draw(random)).is(Items.STONE)) {
                stone++;
            }
        }

        int expected = DRAWS / 4;
        helper.assertTrue(Math.abs(stone - expected) <= DRAW_TOLERANCE,
                "stone came up " + stone + " times in " + DRAWS + " draws, wanted about " + expected);
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void slotsDrainEvenlySoTheOddsHold(GameTestHelper helper) {
        // Three cobblestone slots holding 3, 2 and 1: uneven on purpose, because the policy that
        // matters is "take from the fullest", not "take from the one that was drawn".
        Palette palette = Palette.of(boxOf(
                new ItemStack(Items.COBBLESTONE, 3),
                new ItemStack(Items.COBBLESTONE, 2),
                new ItemStack(Items.COBBLESTONE, 1),
                new ItemStack(Items.STONE, 1)));

        // Six cobblestone in three slots. The three slots survive until every one of them is
        // down to its last block; only then does the palette start losing weight. Drawing from
        // the emptiest slot instead would give 2, 1, 1, 1, 1, 0 -- weight lost on the first take.
        int[] slotsAfterEachTake = {3, 3, 3, 2, 1, 0};

        for (int take = 0; take < slotsAfterEachTake.length; take++) {
            palette.consume(firstSlotOf(palette, Items.COBBLESTONE));

            helper.assertTrue(slotsOf(palette, Items.COBBLESTONE) == slotsAfterEachTake[take],
                    "cobblestone held " + slotsOf(palette, Items.COBBLESTONE) + " slots after "
                            + (take + 1) + " taken, wanted " + slotsAfterEachTake[take]);
            helper.assertTrue(spread(palette, Items.COBBLESTONE) <= 1,
                    "the cobblestone slots drifted " + spread(palette, Items.COBBLESTONE)
                            + " apart after " + (take + 1) + " taken; they should stay within one of each other");
        }

        helper.assertTrue(slotsOf(palette, Items.STONE) == 1, "draining cobblestone should not touch the stone");
        helper.succeed();
    }

    @GameTest(template = "platform")
    public static void itemsItCannotPlaceNeverTakeATurn(GameTestHelper helper) {
        Palette palette = Palette.of(boxOf(new ItemStack(Items.DIAMOND, 64), new ItemStack(Items.STICK, 64)));

        helper.assertTrue(!palette.hasPlaceable(), "a box of diamonds and sticks has nothing to place");
        helper.assertTrue(palette.draw(RandomSource.create(SEED)) == -1, "an unplaceable box should draw nothing");
        helper.assertTrue(palette.strandedSlots() == 2, "both slots are stranded, the tooltip has to say so");
        helper.succeed();
    }

    /**
     * The whole feature, end to end. A box of nothing but cobblestone makes the outcome
     * deterministic, so the test is about where the block came from and who paid for it.
     */
    @GameTest(template = "platform")
    public static void buildingFromTheBoxSpendsTheBoxAndNotTheHand(GameTestHelper helper) {
        helper.setBlock(FLOOR, Blocks.STONE);

        ItemStack box = boxOf(new ItemStack(Items.COBBLESTONE, 64));
        ItemStack held = new ItemStack(Items.DIRT, 5);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.setItemInHand(InteractionHand.OFF_HAND, box);

        UseItemOnBlockEvent event = clickTopOf(helper, player, FLOOR, InteractionHand.MAIN_HAND);
        NeoForge.EVENT_BUS.post(event);

        helper.assertTrue(event.isCanceled(), "the box should have taken the placement over");
        helper.assertTrue(event.getCancellationResult() == ItemInteractionResult.SUCCESS,
                "an off-hand box that placed a block should report success, not " + event.getCancellationResult());
        helper.assertBlockPresent(Blocks.COBBLESTONE, FLOOR.above());

        helper.assertTrue(held.getCount() == 5,
                "the held stack is a stencil, not a supply, but it went from 5 to " + held.getCount());
        helper.assertTrue(countIn(box, Items.COBBLESTONE) == 63,
                "the box should have paid for the block, but holds " + countIn(box, Items.COBBLESTONE));
        helper.succeed();
    }

    /**
     * An empty main hand takes no turn of its own, so the click falls through to the off hand --
     * where the box is. Reaching the box that way used to place the box.
     */
    @GameTest(template = "platform")
    public static void anEmptyMainHandStillDrawsFromTheBox(GameTestHelper helper) {
        helper.setBlock(FLOOR, Blocks.STONE);

        ItemStack box = boxOf(new ItemStack(Items.COBBLESTONE, 64));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, box);

        UseItemOnBlockEvent event = clickTopOf(helper, player, FLOOR, InteractionHand.OFF_HAND);
        NeoForge.EVENT_BUS.post(event);

        helper.assertTrue(event.isCanceled(), "the box should have answered an empty-handed click");
        helper.assertBlockPresent(Blocks.COBBLESTONE, FLOOR.above());
        helper.assertTrue(countIn(box, Items.COBBLESTONE) == 63,
                "the box should have paid for the block, but holds " + countIn(box, Items.COBBLESTONE));
        helper.succeed();
    }

    /** The one way to put a box down: hold it in the main hand. It must not shuffle itself away. */
    @GameTest(template = "platform")
    public static void aBoxInTheMainHandStillPlacesItself(GameTestHelper helper) {
        helper.setBlock(FLOOR, Blocks.STONE);

        ItemStack box = boxOf(new ItemStack(Items.COBBLESTONE, 64));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, box);
        player.setItemInHand(InteractionHand.OFF_HAND, boxOf(new ItemStack(Items.STONE, 64)));

        UseItemOnBlockEvent event = clickTopOf(helper, player, FLOOR, InteractionHand.MAIN_HAND);
        NeoForge.EVENT_BUS.post(event);

        helper.assertTrue(!event.isCanceled(),
                "a box held in the main hand is being placed, not being asked for a block");
        helper.succeed();
    }

    /** Without a box in the off hand nothing is intercepted, and the held block places itself. */
    @GameTest(template = "platform")
    public static void anEmptyBoxLeavesTheHeldBlockAlone(GameTestHelper helper) {
        helper.setBlock(FLOOR, Blocks.STONE);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 5));
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(SBItems.SHUFFLER_BOX.get()));

        UseItemOnBlockEvent event = clickTopOf(helper, player, FLOOR, InteractionHand.MAIN_HAND);
        NeoForge.EVENT_BUS.post(event);

        helper.assertTrue(!event.isCanceled(), "an empty box has nothing to give and should stand aside");
        helper.succeed();
    }

    /**
     * Breaking a box has to hand back what was inside it, which is the loot table's job -- the one
     * piece of this mod that is data rather than code, and so the one nothing else would catch.
     */
    @GameTest(template = "platform")
    public static void breakingAPlacedBoxKeepsWhatWasInIt(GameTestHelper helper) {
        helper.setBlock(FLOOR, SBBlocks.SHUFFLER_BOX.get());
        helper.assertTrue(helper.getBlockEntity(FLOOR) instanceof ShufflerBoxBlockEntity,
                "the box should have brought its block entity with it");

        ShufflerBoxBlockEntity placed = (ShufflerBoxBlockEntity) helper.getBlockEntity(FLOOR);
        placed.setItem(0, new ItemStack(Items.COBBLESTONE, 17));
        placed.setItem(1, new ItemStack(Items.STONE, 5));

        helper.getLevel().destroyBlock(helper.absolutePos(FLOOR), true);

        ItemStack dropped = onlyDroppedBox(helper);
        helper.assertTrue(countIn(dropped, Items.COBBLESTONE) == 17,
                "the dropped box holds " + countIn(dropped, Items.COBBLESTONE) + " cobblestone, wanted 17");
        helper.assertTrue(countIn(dropped, Items.STONE) == 5,
                "the dropped box holds " + countIn(dropped, Items.STONE) + " stone, wanted 5");
        helper.succeed();
    }

    /**
     * A recipe that fails to parse is dropped with a line in the log and nothing else -- the game
     * starts, the mod works, and the item is simply uncraftable. Worth a test for that reason
     * alone: this one shipped broken until it had one.
     */
    @GameTest(template = "platform")
    public static void theRecipeLoadsAndYieldsABox(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CraftingInput bench = CraftingInput.of(3, 3, List.of(
                ItemStack.EMPTY, new ItemStack(Items.BONE_BLOCK), ItemStack.EMPTY,
                new ItemStack(Items.SHULKER_SHELL), new ItemStack(Items.CHEST), new ItemStack(Items.SHULKER_SHELL),
                ItemStack.EMPTY, new ItemStack(Items.INK_SAC), ItemStack.EMPTY));

        RecipeHolder<CraftingRecipe> recipe = level.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, bench, level)
                .orElseThrow(() -> new GameTestAssertException("no crafting recipe matches the Shuffler Box pattern"));

        ItemStack crafted = recipe.value().assemble(bench, level.registryAccess());
        helper.assertTrue(crafted.is(SBItems.SHUFFLER_BOX.get()),
                "the pattern crafts " + crafted.getHoverName().getString() + ", not a Shuffler Box");
        helper.succeed();
    }

    // --- rigging -----------------------------------------------------------------------------

    /** The single Shuffler Box lying on the platform, and a clear failure if there is not one. */
    private static ItemStack onlyDroppedBox(GameTestHelper helper) {
        List<ItemEntity> dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                AABB.ofSize(Vec3.atCenterOf(helper.absolutePos(FLOOR)), 6.0, 6.0, 6.0),
                entity -> entity.getItem().is(SBItems.SHUFFLER_BOX.get()));

        if (dropped.size() != 1) {
            throw new GameTestAssertException("wanted one dropped box, found " + dropped.size());
        }

        return dropped.get(0).getItem();
    }

    /** A Shuffler Box item holding {@code contents}, one stack per slot in the order given. */
    private static ItemStack boxOf(ItemStack... contents) {
        NonNullList<ItemStack> slots = NonNullList.withSize(Palette.SLOT_COUNT, ItemStack.EMPTY);
        for (int slot = 0; slot < contents.length; slot++) {
            slots.set(slot, contents[slot]);
        }

        ItemStack box = new ItemStack(SBItems.SHUFFLER_BOX.get());
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
        return box;
    }

    /** The event the game fires when a player right-clicks the top of a block with {@code hand}. */
    private static UseItemOnBlockEvent clickTopOf(
            GameTestHelper helper, Player player, BlockPos relative, InteractionHand hand) {
        BlockPos clicked = helper.absolutePos(relative);
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(clicked).add(0.0, 0.5, 0.0), Direction.UP, clicked, false);

        UseOnContext context = new UseOnContext(player, hand, hit);
        return new UseItemOnBlockEvent(context, UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK);
    }

    /** The gap between the fullest and emptiest slot holding {@code item}. */
    private static int spread(Palette palette, net.minecraft.world.item.Item item) {
        int most = 0;
        int least = Integer.MAX_VALUE;

        for (ItemStack stack : palette.slots()) {
            if (stack.is(item)) {
                most = Math.max(most, stack.getCount());
                least = Math.min(least, stack.getCount());
            }
        }

        return least == Integer.MAX_VALUE ? 0 : most - least;
    }

    /** consume() reads the kind to drain off the slot it is handed, so it needs a live one. */
    private static int firstSlotOf(Palette palette, net.minecraft.world.item.Item item) {
        List<ItemStack> slots = palette.slots();
        for (int slot = 0; slot < slots.size(); slot++) {
            if (slots.get(slot).is(item)) {
                return slot;
            }
        }

        throw new IllegalStateException("no slot holds " + item);
    }

    private static int slotsOf(Palette palette, net.minecraft.world.item.Item item) {
        List<Palette.Weight> weights = palette.weights();
        for (Palette.Weight weight : weights) {
            if (weight.sample().is(item)) {
                return weight.slots();
            }
        }

        return 0;
    }

    private static int countIn(ItemStack box, net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemStack stack : box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems()) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }

        return count;
    }
}
