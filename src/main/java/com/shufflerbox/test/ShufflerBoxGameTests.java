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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
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

    /**
     * A Copycats+ block, present only when Copycats+ is installed. The tests that use it skip
     * themselves when it is absent, so a plain checkout still runs green;
     * {@code tools/fetch_dev_mods.py} is what puts it there.
     */
    private static final ResourceLocation COPYCAT_WALL =
            ResourceLocation.fromNamespaceAndPath("copycats", "copycat_wall");

    /** A Copycats+ block with a placement helper, which is what the arrow-placement test needs. */
    private static final ResourceLocation COPYCAT_SLOPE =
            ResourceLocation.fromNamespaceAndPath("copycats", "copycat_slope");

    /**
     * What a copycat with no material of its own reports as its material: Create stands
     * {@code create:copycat_base} in for null rather than leaving the key out, so this is what
     * "plain" looks like from the outside.
     */
    private static final String PLAIN = "create:copycat_base";

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

    /**
     * Create fills a freshly placed copycat from whatever is in the placer's <i>off hand</i>
     * ({@code CopycatBlock#setPlacedBy}), and a Shuffler Box is a plain full cube, which is
     * exactly what its material test accepts. So a copycat drawn out of the box is placed while
     * the box itself is sitting in the hand Create reads: the box becomes the copycat's material
     * and gets consumed out of the player's hand.
     *
     * <p>Skipped when Copycats+ is not installed; {@code tools/fetch_dev_mods.py} installs it.
     */
    @GameTest(template = "platform")
    public static void aCopycatDrawnFromTheBoxIsNotMadeOfTheBox(GameTestHelper helper) {
        ItemStack copycats = copycatWalls(4);
        if (copycats.isEmpty()) {
            helper.succeed();
            return;
        }

        helper.setBlock(FLOOR, Blocks.STONE);

        ItemStack box = boxOf(copycats);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, box);

        UseItemOnBlockEvent event = clickTopOf(helper, player, FLOOR, InteractionHand.OFF_HAND);
        NeoForge.EVENT_BUS.post(event);

        helper.assertTrue(event.isCanceled(), "the box should have drawn the copycat");
        helper.assertBlockPresent(((BlockItem) copycats.getItem()).getBlock(), FLOOR.above());

        helper.assertTrue(player.getOffhandItem().is(SBItems.SHUFFLER_BOX.get()),
                "the box was taken out of the off hand as the copycat's material; the hand now holds "
                        + player.getOffhandItem().getHoverName().getString());
        String material = material(helper, FLOOR.above());
        helper.assertTrue(PLAIN.equals(material),
                "the copycat came out made of " + material + "; one drawn out of the box should arrive"
                        + " with the material it was carrying, and carry nothing when it had none");
        helper.succeed();
    }

    /**
     * Copycat mode, end to end: the shape comes from the hand and the material comes from the box.
     * A box of nothing but cobblestone makes the draw deterministic, so what is really under test
     * is who paid for what -- the hand for the copycat, the box for the material.
     *
     * <p>Skipped when Copycats+ is not installed; {@code tools/fetch_dev_mods.py} installs it.
     */
    @GameTest(template = "platform")
    public static void aCopycatInTheHandIsPaintedFromTheBox(GameTestHelper helper) {
        ItemStack copycats = copycatWalls(3);
        if (copycats.isEmpty()) {
            helper.succeed();
            return;
        }

        helper.setBlock(FLOOR, Blocks.STONE);

        ItemStack box = boxOf(new ItemStack(Items.COBBLESTONE, 64));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, copycats);
        player.setItemInHand(InteractionHand.OFF_HAND, box);

        // The first phase of the click: the box lends the hand a material and takes nothing over.
        UseItemOnBlockEvent lend = clickTopOf(helper, player, FLOOR, InteractionHand.MAIN_HAND,
                UseItemOnBlockEvent.UsePhase.ITEM_BEFORE_BLOCK);
        NeoForge.EVENT_BUS.post(lend);

        helper.assertTrue(!lend.isCanceled(), "lending a material should not take the click over");
        helper.assertTrue(player.getOffhandItem().is(Items.COBBLESTONE),
                "the off hand should be holding one cobblestone out of the box by now, not "
                        + player.getOffhandItem().getHoverName().getString());

        // ...and then the copycat is placed by the game's own path, the way it would have been.
        UseOnContext context = lend.getUseOnContext();
        context.getItemInHand().useOn(context);

        helper.assertBlockPresent(((BlockItem) copycats.getItem()).getBlock(), FLOOR.above());
        String material = material(helper, FLOOR.above());
        helper.assertTrue("minecraft:cobblestone".equals(material),
                "the copycat should have come out made of the cobblestone in the box, not "
                        + (PLAIN.equals(material) ? "plain" : material));
        helper.assertTrue(copycats.getCount() == 2,
                "the hand supplies the copycat itself, so it should have gone 3 to 2, not to " + copycats.getCount());

        // The box comes back at the end of the tick, charged for what the copycat actually took.
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(player.getOffhandItem().is(SBItems.SHUFFLER_BOX.get()),
                    "the box has to be back in the off hand once the click is over, not "
                            + player.getOffhandItem().getHoverName().getString());
            helper.assertTrue(countIn(box, Items.COBBLESTONE) == 63,
                    "the box should have paid for the material, but holds " + countIn(box, Items.COBBLESTONE));
            helper.succeed();
        });
    }

    /**
     * The one this shipped broken: clicking a copycat you have already placed offers an arrow, and
     * accepting it puts the next copycat down from the <i>clicked block's</i> turn -- the block
     * phase -- before the item in your hand ever gets one. A box that only watched the item phase
     * lent nothing on those clicks, so the copycat arrived with no material, and a copycat with no
     * material draws nothing at all: an invisible block until a neighbour made it redraw.
     *
     * <p>Skipped when Copycats+ is not installed; {@code tools/fetch_dev_mods.py} installs it.
     */
    @GameTest(template = "platform")
    public static void anArrowPlacementIsPaintedFromTheBoxToo(GameTestHelper helper) {
        Block slope = BuiltInRegistries.BLOCK.getOptional(COPYCAT_SLOPE).orElse(null);
        ItemStack slopes = BuiltInRegistries.ITEM.getOptional(COPYCAT_SLOPE)
                .map(item -> new ItemStack(item, 3))
                .orElse(ItemStack.EMPTY);
        if (slope == null || slopes.isEmpty()) {
            helper.succeed();
            return;
        }

        helper.setBlock(FLOOR, Blocks.STONE);
        // One already down, so that clicking it is the arrow-placement case rather than a plain
        // placement. Plain, because a copycat placed by hand out of a creative inventory is.
        helper.setBlock(FLOOR.above(), slope);

        ItemStack box = boxOf(new ItemStack(Items.COBBLESTONE, 64));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, slopes);
        player.setItemInHand(InteractionHand.OFF_HAND, box);

        UseItemOnBlockEvent lend = clickTopOf(helper, player, FLOOR.above(), InteractionHand.MAIN_HAND,
                UseItemOnBlockEvent.UsePhase.ITEM_BEFORE_BLOCK);
        NeoForge.EVENT_BUS.post(lend);

        // The block's own turn, which is what the game does next and where the helper places.
        UseOnContext context = lend.getUseOnContext();
        BlockHitResult hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(),
                context.getClickedPos(), context.isInside());
        helper.getLevel().getBlockState(context.getClickedPos())
                .useItemOn(context.getItemInHand(), helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);

        BlockPos painted = paintedNear(helper, FLOOR.above(), slope);
        helper.assertTrue(painted != null,
                "the arrow placement put down a copycat with no material: nothing within reach of "
                        + FLOOR.above() + " is made of the cobblestone the box should have lent it");

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(player.getOffhandItem().is(SBItems.SHUFFLER_BOX.get()),
                    "the box has to be back in the off hand once the click is over, not "
                            + player.getOffhandItem().getHoverName().getString());
            helper.assertTrue(countIn(box, Items.COBBLESTONE) == 63,
                    "the box should have paid for the material, but holds " + countIn(box, Items.COBBLESTONE));
            helper.succeed();
        });
    }

    /**
     * A lent material is sitting in the off hand, and the game gives the off hand a turn of its
     * own whenever the main hand's placement did not consume the click. That turn must not place
     * the lent block: it is paint the box is holding out, not a block on offer, and placing it
     * would be a block nobody asked for with the box paying for it.
     *
     * <p>Skipped when Copycats+ is not installed; {@code tools/fetch_dev_mods.py} installs it.
     */
    @GameTest(template = "platform")
    public static void aLentMaterialIsNotPlacedByTheOffHand(GameTestHelper helper) {
        ItemStack copycats = copycatWalls(3);
        if (copycats.isEmpty()) {
            helper.succeed();
            return;
        }

        helper.setBlock(FLOOR, Blocks.STONE);

        ItemStack box = boxOf(new ItemStack(Items.COBBLESTONE, 64));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, copycats);
        player.setItemInHand(InteractionHand.OFF_HAND, box);

        NeoForge.EVENT_BUS.post(clickTopOf(helper, player, FLOOR, InteractionHand.MAIN_HAND,
                UseItemOnBlockEvent.UsePhase.ITEM_BEFORE_BLOCK));
        helper.assertTrue(player.getOffhandItem().is(Items.COBBLESTONE),
                "nothing was lent, so there is nothing for this test to try to place");

        UseItemOnBlockEvent offHandTurn = clickTopOf(helper, player, FLOOR, InteractionHand.OFF_HAND);
        NeoForge.EVENT_BUS.post(offHandTurn);

        helper.assertTrue(offHandTurn.isCanceled(),
                "the off hand's own turn should have been declined while its stack is lent out");
        helper.assertTrue(offHandTurn.getCancellationResult() == ItemInteractionResult.FAIL,
                "declining should fail the click rather than report it handled, not "
                        + offHandTurn.getCancellationResult());
        helper.succeed();
    }

    /**
     * A box holding nothing a copycat could wear stands aside, and the copycat is placed plain by
     * the hand -- what an empty off hand would have given it. Slabs are perfectly placeable, so
     * this is the box declining on the material rule rather than on having nothing in it.
     *
     * <p>Skipped when Copycats+ is not installed; {@code tools/fetch_dev_mods.py} installs it.
     */
    @GameTest(template = "platform")
    public static void aBoxOfNoMaterialsLeavesTheCopycatPlain(GameTestHelper helper) {
        ItemStack copycats = copycatWalls(3);
        if (copycats.isEmpty()) {
            helper.succeed();
            return;
        }

        helper.setBlock(FLOOR, Blocks.STONE);

        ItemStack box = boxOf(new ItemStack(Items.OAK_SLAB, 64));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, copycats);
        player.setItemInHand(InteractionHand.OFF_HAND, box);

        UseItemOnBlockEvent lend = clickTopOf(helper, player, FLOOR, InteractionHand.MAIN_HAND,
                UseItemOnBlockEvent.UsePhase.ITEM_BEFORE_BLOCK);
        NeoForge.EVENT_BUS.post(lend);

        helper.assertTrue(!lend.isCanceled(),
                "a box of slabs has no material a copycat could wear and should have stood aside");
        helper.assertTrue(player.getOffhandItem().is(SBItems.SHUFFLER_BOX.get()),
                "nothing should have been lent, so the box should still be in the off hand, not "
                        + player.getOffhandItem().getHoverName().getString());

        UseOnContext context = lend.getUseOnContext();
        context.getItemInHand().useOn(context);

        String material = material(helper, FLOOR.above());
        helper.assertTrue(PLAIN.equals(material),
                "with nothing lent, the copycat should have gone down plain, not made of " + material);
        helper.assertTrue(countIn(box, Items.OAK_SLAB) == 64,
                "the box should not have been charged for a material it never gave");
        helper.succeed();
    }

    // --- rigging -----------------------------------------------------------------------------

    /**
     * A block of {@code kind} near {@code centre} whose material came out of the box, or null if
     * every one of them is plain. Deliberately not pinned to a position: which side the arrow
     * offers is the copycat's own business, and a test that asserted it would fail the day that
     * changed for reasons that have nothing to do with this mod.
     */
    private static BlockPos paintedNear(GameTestHelper helper, BlockPos centre, Block kind) {
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-1, -1, -1), centre.offset(1, 1, 1))) {
            if (helper.getBlockState(pos).is(kind) && "minecraft:cobblestone".equals(material(helper, pos))) {
                return pos.immutable();
            }
        }

        return null;
    }

    /** {@code count} Copycats+ walls, or an empty stack when Copycats+ is not installed. */
    private static ItemStack copycatWalls(int count) {
        return BuiltInRegistries.ITEM.getOptional(COPYCAT_WALL)
                .map(item -> new ItemStack(item, count))
                .orElse(ItemStack.EMPTY);
    }

    /**
     * The material a placed copycat ended up with, by NBT key, or empty if it has none. Read out
     * of the saved block entity so that nothing here has to compile against Create.
     */
    private static String material(GameTestHelper helper, BlockPos relative) {
        BlockEntity blockEntity = helper.getBlockEntity(relative);
        if (blockEntity == null) {
            return "";
        }

        return blockEntity.saveWithoutMetadata(helper.getLevel().registryAccess())
                .getCompound("Material")
                .getString("Name");
    }

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
        return clickTopOf(helper, player, relative, hand, UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK);
    }

    /**
     * The same click in a chosen phase. A real click walks all three in order --
     * {@code ITEM_BEFORE_BLOCK}, then the clicked block's own turn, then {@code ITEM_AFTER_BLOCK}
     * -- so a test that only ever fires the last one cannot see anything that happens in the
     * first two, which is exactly where the copycat placement helper lives.
     */
    private static UseItemOnBlockEvent clickTopOf(GameTestHelper helper, Player player, BlockPos relative,
            InteractionHand hand, UseItemOnBlockEvent.UsePhase phase) {
        BlockPos clicked = helper.absolutePos(relative);
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(clicked).add(0.0, 0.5, 0.0), Direction.UP, clicked, false);

        UseOnContext context = new UseOnContext(player, hand, hit);
        return new UseItemOnBlockEvent(context, phase);
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
