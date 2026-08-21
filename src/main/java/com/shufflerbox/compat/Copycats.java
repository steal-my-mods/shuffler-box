package com.shufflerbox.compat;

import com.shufflerbox.ShufflerBox;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Copycat blocks, as seen from outside Create.
 *
 * <p>A copycat is a block that wears another block's texture, and it picks that material up from
 * whatever the placer is holding in their <i>off hand</i> as it is placed -- which is the hand the
 * Shuffler Box wants. So when the main hand holds a copycat, the box changes what it is for: the
 * shape comes from the hand and the box supplies the material, drawn at random from its palette
 * exactly the way it usually draws a block. {@code ShuffleHandler} does that; this class is the
 * two questions it has to answer first.
 *
 * <p>Nothing here compiles against Create, so the mod still builds and runs with neither Create
 * nor Copycats+ installed. Both questions are asked through tags and vanilla types instead.
 */
public final class Copycats {

    /**
     * The blocks that take their material from the off hand: every copycat in Create and
     * Copycats+, listed in {@code data/shufflerbox/tags/block/fills_from_off_hand.json} with
     * {@code required: false} so the tag loads whether or not those mods are present. A tag
     * rather than a class check because it is also the extension point -- a datapack can add
     * another mod's copycat-like block without this mod knowing it exists.
     */
    public static final TagKey<Block> FILLS_FROM_OFF_HAND =
            TagKey.create(Registries.BLOCK, ShufflerBox.asResource("fills_from_off_hand"));

    /** Create's own overrides for what may be a copycat material. Read, never written. */
    private static final TagKey<Block> ALLOWED_MATERIAL = createTag("copycat_allow");
    private static final TagKey<Block> DENIED_MATERIAL = createTag("copycat_deny");

    private Copycats() {}

    /** Whether placing {@code held} would fill it from the off hand. */
    public static boolean fillsFromOffHand(ItemStack held) {
        return held.getItem() instanceof BlockItem blockItem
                && fillsFromOffHand(blockItem.getBlock().defaultBlockState());
    }

    /**
     * Whether a block already standing in the world takes its material out of the off hand.
     *
     * <p>The same question as the one above, asked of a copycat that has already been placed:
     * Create will paint a blank one from whatever hand clicked it, and that hand is where the box
     * lives, so the box has a material to lend on those clicks too.
     */
    public static boolean fillsFromOffHand(BlockState clicked) {
        return clicked.is(FILLS_FROM_OFF_HAND);
    }

    /**
     * Whether {@code candidate} could be the material of a copycat placed at {@code pos}.
     *
     * <p>This mirrors Create's own test (`CopycatBlock#getAcceptedBlockState`): its two tags
     * first, then no block entities, no stairs, and a full cube that something can stand on.
     * Mirrored rather than called, because calling it would mean compiling against Create and
     * every install would then need it.
     *
     * <p>{@code pos} is indicative rather than exact: for a copycat being placed it is the block
     * that was clicked, while Create asks about the cell the copycat will occupy, and for the
     * arrow-placement path the copycat's cell is not knowable from here at all. Every vanilla
     * block derives both shapes from its state alone, so the two agree; a modded candidate that
     * reads the level could be judged against the neighbouring cell. Deliberately not "fixed" into
     * a precision this side cannot have -- and it costs nothing, because the box is only ever
     * charged for a material Create actually took.
     *
     * <p>The mirror is deliberately the conservative half of that test. An individual copycat can
     * widen what it accepts (`isAcceptedRegardless`, false in Create's base class), so Create may
     * take a material this skips. That costs nothing: the box only ever pays for a material Create
     * actually took, so a material offered and declined leaves a plain copycat and a full box.
     */
    public static boolean isMaterial(ItemStack candidate, Level level, BlockPos pos) {
        if (!(candidate.getItem() instanceof BlockItem blockItem)) {
            return false;
        }

        Block block = blockItem.getBlock();
        BlockState state = block.defaultBlockState();

        if (state.is(ALLOWED_MATERIAL)) {
            return true;
        }

        if (state.is(DENIED_MATERIAL) || block instanceof EntityBlock || block instanceof StairBlock) {
            return false;
        }

        // A Shuffler Box is turned away here too, by being an EntityBlock above: a copycat made
        // of shuffler boxes would be a box eaten out of the hand that was filling it.
        //
        // Asked whether it is empty before anything else is asked of it, because an empty shape
        // has no bounds to compare: VoxelShape#bounds throws on one. Create's own test opens with
        // the same guard. Without it, a block with no outline at all -- a light block, and
        // whatever a mod makes of one -- takes the exception out through the click that drew it,
        // which is a crash rather than a material the box turns down.
        VoxelShape outline = state.getShape(level, pos);
        return !outline.isEmpty()
                && outline.bounds().equals(Shapes.block().bounds())
                && !state.getCollisionShape(level, pos).isEmpty();
    }

    private static TagKey<Block> createTag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("create", path));
    }
}
