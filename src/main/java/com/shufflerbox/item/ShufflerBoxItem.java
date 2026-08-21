package com.shufflerbox.item;

import java.util.List;

import com.shufflerbox.shuffle.Palette;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/**
 * The Shuffler Box as an item.
 *
 * <p>The tooltip shows the palette as odds rather than as a list of stacks. A shulker box lists
 * what is inside because that is all there is to know; here the ratio between the blocks is the
 * setting the player configured, and it is not something they can read off the slots at a glance.
 */
public class ShufflerBoxItem extends BlockItem {

    /** Blocks listed before the tooltip gives up and counts the rest. */
    private static final int MAX_LISTED = 5;

    public ShufflerBoxItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** No box inside a box, the same rule a shulker box follows. */
    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);

        Palette palette = Palette.of(stack);
        List<Palette.Weight> weights = palette.weights();

        if (weights.isEmpty()) {
            lines.add(Component.translatable("tooltip.shufflerbox.empty").withStyle(ChatFormatting.GRAY));
        } else {
            int total = weights.stream().mapToInt(Palette.Weight::slots).sum();

            for (Palette.Weight weight : weights.subList(0, Math.min(MAX_LISTED, weights.size()))) {
                lines.add(Component.translatable("tooltip.shufflerbox.entry",
                        weight.sample().getHoverName(),
                        Math.round(100.0F * weight.slots() / total)).withStyle(ChatFormatting.GRAY));
            }

            if (weights.size() > MAX_LISTED) {
                lines.add(Component.translatable("tooltip.shufflerbox.more", weights.size() - MAX_LISTED)
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
        }

        int stranded = palette.strandedSlots();
        if (stranded > 0) {
            lines.add(Component.translatable("tooltip.shufflerbox.stranded", stranded)
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }

        lines.add(Component.translatable("tooltip.shufflerbox.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
