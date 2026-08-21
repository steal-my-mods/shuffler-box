package com.shufflerbox.registry;

import com.shufflerbox.ShufflerBox;
import com.shufflerbox.item.ShufflerBoxItem;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class SBItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ShufflerBox.ID);

    public static final DeferredItem<ShufflerBoxItem> SHUFFLER_BOX = ITEMS.registerItem("shuffler_box",
            properties -> new ShufflerBoxItem(SBBlocks.SHUFFLER_BOX.get(), properties),
            new Item.Properties()
                    .stacksTo(1)
                    // Declared empty rather than absent so a fresh box already carries the
                    // component the shuffle reads and the screen writes.
                    .component(DataComponents.CONTAINER, ItemContainerContents.EMPTY));

    /**
     * One item does not earn a creative tab of its own. It sits with the shulker boxes and the
     * other containers.
     */
    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(SHUFFLER_BOX);
        }
    }
}
