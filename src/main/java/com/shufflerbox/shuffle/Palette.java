package com.shufflerbox.shuffle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * The blocks a Shuffler Box hands out, and the rules for picking between them.
 *
 * <p><b>Weighting is by slot, not by stack size.</b> Three slots of cobblestone against one of
 * stone is three cobblestone for every stone, whether those slots hold a full stack each or a
 * single block each. Laying items out across slots is the only weighting a player can express by
 * hand, so it is the one the box honours.
 *
 * <p>That is also why {@link #consume} drains the <i>fullest</i> slot of a kind rather than the
 * one that was drawn. Draining the drawn slot would empty one slot of cobblestone long before the
 * others and quietly retune the palette from 3:1 to 2:1 to 1:1 as the player built. Taking from
 * the fullest instead means a slot only empties once every slot of its kind is down to its last
 * item, so the ratio the player laid out survives until the box is nearly exhausted.
 *
 * <p>A Palette is a detached copy of a box's contents. Read one with {@link #of}, change it, and
 * write it back with {@link #saveTo}.
 */
public final class Palette {

    /** Slots in a Shuffler Box. The same 27 as a shulker box, so the vanilla screen fits it. */
    public static final int SLOT_COUNT = 27;

    private final NonNullList<ItemStack> slots;

    private Palette(NonNullList<ItemStack> slots) {
        this.slots = slots;
    }

    /** Reads the palette out of a Shuffler Box item stack. */
    public static Palette of(ItemStack box) {
        NonNullList<ItemStack> slots = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(slots);
        return new Palette(slots);
    }

    /** Writes the palette back into a Shuffler Box item stack. */
    public void saveTo(ItemStack box) {
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(this.slots));
    }

    /**
     * Whether the box holds anything it could place. Anything that is not a block -- a stack of
     * diamonds someone parked in there -- is not part of the palette and never takes a turn.
     */
    public boolean hasPlaceable() {
        return hasAny(stack -> true);
    }

    /** Whether the box holds anything placeable that {@code eligible} also accepts. */
    public boolean hasAny(Predicate<ItemStack> eligible) {
        for (ItemStack stack : this.slots) {
            if (isPlaceable(stack) && eligible.test(stack)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Picks a slot, every placeable slot equally likely.
     *
     * @return the slot index, or -1 if the box holds nothing placeable
     */
    public int draw(RandomSource random) {
        return draw(random, stack -> true);
    }

    /**
     * Picks a slot from those {@code eligible} accepts, each one equally likely -- the same
     * slot-weighted draw over a narrower palette. Drawing a copycat's material is one of these:
     * only some of what a box holds can be a material, and the odds should be read off the slots
     * that can, not off the whole box.
     *
     * @return the slot index, or -1 if no placeable slot is eligible
     */
    public int draw(RandomSource random, Predicate<ItemStack> eligible) {
        int candidates = 0;
        for (ItemStack stack : this.slots) {
            if (isPlaceable(stack) && eligible.test(stack)) {
                candidates++;
            }
        }

        if (candidates == 0) {
            return -1;
        }

        int chosen = random.nextInt(candidates);
        for (int slot = 0; slot < this.slots.size(); slot++) {
            if (isPlaceable(this.slots.get(slot)) && eligible.test(this.slots.get(slot)) && chosen-- == 0) {
                return slot;
            }
        }

        throw new IllegalStateException("counted " + candidates + " eligible slots but ran out of them");
    }

    /** The 27 slots as they currently stand, in order. Read-only. */
    public List<ItemStack> slots() {
        return Collections.unmodifiableList(this.slots);
    }

    /** A single item of the kind in {@code slot}, ready to be placed. */
    public ItemStack sample(int slot) {
        return this.slots.get(slot).copyWithCount(1);
    }

    /**
     * Removes one item of the kind held in {@code slot}, taking it from the fullest slot of that
     * kind so the palette's slot weighting outlives its stacks. See the class note.
     */
    public void consume(int slot) {
        ItemStack kind = this.slots.get(slot);
        int fullest = slot;

        for (int other = 0; other < this.slots.size(); other++) {
            if (ItemStack.isSameItemSameComponents(this.slots.get(other), kind)
                    && this.slots.get(other).getCount() > this.slots.get(fullest).getCount()) {
                fullest = other;
            }
        }

        this.slots.get(fullest).shrink(1);
        if (this.slots.get(fullest).isEmpty()) {
            this.slots.set(fullest, ItemStack.EMPTY);
        }
    }

    /**
     * How the palette is currently weighted: one entry per distinct block, in the order the
     * blocks first appear in the box. Used for the tooltip -- the whole point of the box is the
     * ratio, so the ratio is what it shows.
     */
    public List<Weight> weights() {
        List<Weight> weights = new ArrayList<>();

        for (ItemStack stack : this.slots) {
            if (!isPlaceable(stack)) {
                continue;
            }

            int existing = -1;
            for (int i = 0; i < weights.size(); i++) {
                if (ItemStack.isSameItemSameComponents(weights.get(i).sample(), stack)) {
                    existing = i;
                    break;
                }
            }

            if (existing < 0) {
                weights.add(new Weight(stack.copyWithCount(1), 1));
            } else {
                weights.set(existing, new Weight(weights.get(existing).sample(), weights.get(existing).slots() + 1));
            }
        }

        return weights;
    }

    /** Slots holding something the box cannot place, and so cannot draw. */
    public int strandedSlots() {
        int stranded = 0;
        for (ItemStack stack : this.slots) {
            if (!stack.isEmpty() && !isPlaceable(stack)) {
                stranded++;
            }
        }

        return stranded;
    }

    private static boolean isPlaceable(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem blockItem
                && !blockItem.getBlock().defaultBlockState().isAir();
    }

    /** One block of the palette and how many of the box's slots it occupies. */
    public record Weight(ItemStack sample, int slots) {}
}
