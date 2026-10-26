package com.dex.boxcore.item;

import com.dex.boxcore.util.ErrorReporter;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.block.Block;
import org.bukkit.block.Crafter;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Update 05 E1: honey bottles and water bottles carrying boost data are never drunk, crafted or brewed. */
class BottleVanillaTest {

    private final BottleKeys keys = BottleKeys.of("boxcore");
    private final BottleListener listener = new BottleListener(null, null, null, new BoostItemReader(keys),
            new ErrorReporter(Logger.getLogger("test")));
    private final ItemStack bottle = item(true);
    private final ItemStack plain = item(false);

    private ItemStack item(boolean boost) {
        ItemStack item = mock(ItemStack.class);
        when(item.isEmpty()).thenReturn(false);
        when(item.hasItemMeta()).thenReturn(true);
        PersistentDataContainerView pdc = mock(PersistentDataContainerView.class);
        when(pdc.has(keys.type())).thenReturn(boost);
        when(item.getPersistentDataContainer()).thenReturn(pdc);
        return item;
    }

    @Test
    void neverDrunk() {
        PlayerItemConsumeEvent drink = mock(PlayerItemConsumeEvent.class);
        when(drink.getItem()).thenReturn(bottle);
        listener.onConsume(drink);
        verify(drink).setCancelled(true);

        PlayerItemConsumeEvent honey = mock(PlayerItemConsumeEvent.class);
        when(honey.getItem()).thenReturn(plain);
        listener.onConsume(honey);
        verify(honey, never()).setCancelled(anyBoolean());
    }

    @Test
    void neverCrafted() {
        CraftingInventory grid = mock(CraftingInventory.class);
        when(grid.getMatrix()).thenReturn(new ItemStack[]{null, plain, bottle});
        PrepareItemCraftEvent prepare = mock(PrepareItemCraftEvent.class);
        when(prepare.getInventory()).thenReturn(grid);
        listener.onPrepareCraft(prepare);
        verify(grid).setResult(null);

        CraftItemEvent craft = mock(CraftItemEvent.class);
        when(craft.getInventory()).thenReturn(grid);
        listener.onCraft(craft);
        verify(craft).setCancelled(true);

        CraftingInventory normal = mock(CraftingInventory.class);
        when(normal.getMatrix()).thenReturn(new ItemStack[]{plain, plain, null});
        PrepareItemCraftEvent vanilla = mock(PrepareItemCraftEvent.class);
        when(vanilla.getInventory()).thenReturn(normal);
        listener.onPrepareCraft(vanilla);
        verify(normal, never()).setResult(any());
    }

    @Test
    void neverCraftedByACrafter() {
        Crafter state = mock(Crafter.class);
        Inventory contents = mock(Inventory.class);
        when(contents.getContents()).thenReturn(new ItemStack[]{bottle});
        when(state.getInventory()).thenReturn(contents);
        Block block = mock(Block.class);
        when(block.getState(false)).thenReturn(state);
        CrafterCraftEvent event = mock(CrafterCraftEvent.class);
        when(event.getBlock()).thenReturn(block);
        listener.onCrafter(event);
        verify(event).setCancelled(true);
    }

    @Test
    void neverBrewed() {
        BrewerInventory stand = mock(BrewerInventory.class);
        when(stand.getContents()).thenReturn(new ItemStack[]{plain, bottle, null, null, null});
        BrewEvent brew = mock(BrewEvent.class);
        when(brew.getContents()).thenReturn(stand);
        listener.onBrew(brew);
        verify(brew).setCancelled(true);
    }

    @Test
    void cannotBePutIntoABrewingStand() {
        BrewerInventory stand = mock(BrewerInventory.class);
        when(stand.getSize()).thenReturn(5);
        PlayerInventory own = mock(PlayerInventory.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(stand);

        InventoryClickEvent place = mock(InventoryClickEvent.class);
        when(place.getView()).thenReturn(view);
        when(place.getClickedInventory()).thenReturn(stand);
        when(place.getCursor()).thenReturn(bottle);
        when(place.getHotbarButton()).thenReturn(-1);
        listener.onBrewerClick(place);
        verify(place).setCancelled(true);

        InventoryClickEvent shift = mock(InventoryClickEvent.class);
        when(shift.getView()).thenReturn(view);
        when(shift.getClickedInventory()).thenReturn(own);
        when(shift.isShiftClick()).thenReturn(true);
        when(shift.getCurrentItem()).thenReturn(bottle);
        when(shift.getHotbarButton()).thenReturn(-1);
        listener.onBrewerClick(shift);
        verify(shift).setCancelled(true);

        HumanEntity who = mock(HumanEntity.class);
        when(who.getInventory()).thenReturn(own);
        when(own.getItem(2)).thenReturn(bottle);
        InventoryClickEvent key = mock(InventoryClickEvent.class);
        when(key.getView()).thenReturn(view);
        when(key.getClickedInventory()).thenReturn(stand);
        when(key.getCursor()).thenReturn(plain);
        when(key.getHotbarButton()).thenReturn(2);
        when(key.getWhoClicked()).thenReturn(who);
        listener.onBrewerClick(key);
        verify(key).setCancelled(true);

        InventoryClickEvent normal = mock(InventoryClickEvent.class);
        when(normal.getView()).thenReturn(view);
        when(normal.getClickedInventory()).thenReturn(stand);
        when(normal.getCursor()).thenReturn(plain);
        when(normal.getHotbarButton()).thenReturn(-1);
        listener.onBrewerClick(normal);
        verify(normal, never()).setCancelled(anyBoolean());

        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);
        when(drag.getOldCursor()).thenReturn(bottle);
        when(drag.getRawSlots()).thenReturn(Set.of(30, 1));
        listener.onBrewerDrag(drag);
        verify(drag).setCancelled(true);

        InventoryMoveItemEvent hopper = mock(InventoryMoveItemEvent.class);
        when(hopper.getDestination()).thenReturn(stand);
        when(hopper.getItem()).thenReturn(bottle);
        listener.onMove(hopper);
        verify(hopper).setCancelled(true);
    }
}
