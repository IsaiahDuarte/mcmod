package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.PortableContent;
import dev.izzy.factorycore.platform.storage.PortableStorageMenu;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.testing.TestPlayers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q32 (QD8): a Portable Storage opens on its one cell with no network, takes and stores through its
 * screen, spends charge per move and refuses without it, keeps the cell detached between moves, and
 * refuses every player but its owner.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class PortableStorageGameTests {
  /** Menu indices: 0 cell, 1–45 shown, then main inventory 9–35, then the hotbar 0–8. */
  private static final int SHOWN_FIRST = 1, MAIN = PortableStorageMenu.SHOWN + 1;

  private PortableStorageGameTests() {}

  private static int menuIndex(int inventorySlot) {
    return inventorySlot < 9 ? MAIN + 27 + inventorySlot : MAIN + inventorySlot - 9;
  }

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.setGameMode(GameType.SURVIVAL);
    player.getInventory().clearContent();
    player.getInventory().selected = 0;
    return player;
  }

  private static int energy(ItemStack portable) {
    return portable.getCapability(Capabilities.EnergyStorage.ITEM).getEnergyStored();
  }

  private static ItemStack cellIn(ItemStack portable) {
    return portable.get(DataComponents.CONTAINER).copyOne();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void opensTakesStoresAndSpendsCharge(GameTestHelper h) {
    var player = player(h);
    var inventory = player.getInventory();
    var portable = new ItemStack(PortableContent.ITEM.get());
    // Charged full (charging itself takes 1,000 FE per tick, like the Wireless Terminal).
    portable.set(dev.izzy.factorycore.platform.wireless.WirelessContent.ENERGY.get(), 20_000);
    inventory.setItem(0, portable);
    inventory.setItem(9, new ItemStack(Items.COBBLESTONE, 20));
    inventory.setItem(10, new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var registry = DeviceSavedData.get(h.getLevel().getServer()).registry();
    var cobble =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.COBBLESTONE));

    portable.getItem().use(h.getLevel(), player, InteractionHand.MAIN_HAND);
    h.assertTrue(
        player.containerMenu instanceof PortableStorageMenu
            && portable.get(PortableContent.OWNER.get()).equals(player.getUUID()),
        "It opens with no network and the first user owns it");
    var menu = (PortableStorageMenu) player.containerMenu;
    menu.quickMoveStack(player, menuIndex(10));
    h.assertTrue(
        inventory.getItem(10).isEmpty() && menu.hasCell(), "Shift-click puts the cell in its slot");

    // Store: Shift-click 20 cobblestone from the inventory.
    menu.quickMoveStack(player, menuIndex(9));
    var handle = CellHandleData.read(cellIn(portable));
    h.assertTrue(
        inventory.countItem(Items.COBBLESTONE) == 0
            && registry.stock(handle, cobble).total() == 20
            && energy(portable) == 20_000 - PortableStorageMenu.COST,
        "Stored 20 for one move's charge: " + energy(portable));
    h.assertTrue(
        registry.definition(handle).location() == null,
        "Between moves the cell is detached, as in an inventory");
    menu.broadcastChanges();
    h.assertTrue(
        menu.getSlot(SHOWN_FIRST).getItem().is(Items.COBBLESTONE)
            && menu.getSlot(SHOWN_FIRST).getItem().getCount() == 20,
        "The page shows 20 cobblestone");

    // Take onto the cursor, store from the cursor, then take into the inventory.
    menu.clicked(SHOWN_FIRST, 0, ClickType.PICKUP, player);
    h.assertTrue(
        menu.getCarried().getCount() == 20
            && registry.stock(CellHandleData.read(cellIn(portable)), cobble).total() == 0,
        "Clicking takes them onto the cursor");
    menu.clicked(SHOWN_FIRST, 1, ClickType.PICKUP, player);
    h.assertTrue(
        menu.getCarried().getCount() == 19
            && registry.stock(CellHandleData.read(cellIn(portable)), cobble).total() == 1,
        "A right click with a stack stores one");
    menu.clicked(SHOWN_FIRST, 0, ClickType.PICKUP, player);
    menu.broadcastChanges();
    menu.clicked(SHOWN_FIRST, 0, ClickType.QUICK_MOVE, player);
    h.assertTrue(
        menu.getCarried().isEmpty()
            && inventory.countItem(Items.COBBLESTONE) == 20
            && registry.stock(CellHandleData.read(cellIn(portable)), cobble).total() == 0
            && energy(portable) == 20_000 - 5 * PortableStorageMenu.COST,
        "Shift-click takes them into the inventory; five moves, five charges: " + energy(portable));

    // A page shows the stored amount, not one stack: 3 × 64 shows as 192.
    for (int slot = 20; slot < 23; slot++) {
      inventory.setItem(slot, new ItemStack(Items.STONE, 64));
      menu.quickMoveStack(player, menuIndex(slot));
    }
    menu.broadcastChanges();
    h.assertTrue(
        menu.getSlot(SHOWN_FIRST).getItem().is(Items.STONE)
            && menu.getSlot(SHOWN_FIRST).getItem().getCount() == 192,
        "The page shows 192 stone: " + menu.getSlot(SHOWN_FIRST).getItem());

    // Without charge nothing moves.
    portable.set(dev.izzy.factorycore.platform.wireless.WirelessContent.ENERGY.get(), 10);
    menu.quickMoveStack(
        player, menuIndex(inventory.findSlotMatchingItem(new ItemStack(Items.COBBLESTONE))));
    h.assertTrue(
        inventory.countItem(Items.COBBLESTONE) == 20
            && registry.stock(CellHandleData.read(cellIn(portable)), cobble).total() == 0
            && energy(portable) == 10,
        "With 10 FE a store is refused and nothing moves");

    // The Portable Storage cannot be swapped out of the hand while open.
    menu.clicked(menuIndex(0), 0, ClickType.PICKUP, player);
    h.assertTrue(
        inventory.getItem(0) == portable && menu.getCarried().isEmpty(),
        "The open Portable Storage cannot be picked up");
    player.connection.disconnect(Component.literal("done"));
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void anotherPlayerIsRefused(GameTestHelper h) {
    var owner = player(h);
    var other = player(h);
    var portable = new ItemStack(PortableContent.ITEM.get());
    portable.set(PortableContent.OWNER.get(), owner.getUUID());
    other.getInventory().setItem(0, portable);
    portable.getItem().use(h.getLevel(), other, InteractionHand.MAIN_HAND);
    h.assertFalse(
        other.containerMenu instanceof PortableStorageMenu, "Someone else's does not open");
    owner.getInventory().setItem(0, portable);
    portable.getItem().use(h.getLevel(), owner, InteractionHand.MAIN_HAND);
    h.assertTrue(owner.containerMenu instanceof PortableStorageMenu, "Its owner's does");
    owner.connection.disconnect(Component.literal("done"));
    other.connection.disconnect(Component.literal("done"));
    h.succeed();
  }
}
