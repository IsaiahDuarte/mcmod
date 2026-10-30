package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.CellItem;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.DriveMenu;
import dev.izzy.factorycore.platform.storage.DrivePackets;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The Drive screen (docs/UX_PLAN.md U2): bay choice, in-place upgrades and refusals. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class DriveScreenGameTests {
  private static final BlockPos DRIVE = new BlockPos(1, 1, 2);
  private static final ResourceKey IRON =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);

  private DriveScreenGameTests() {}

  @GameTest(template = "empty")
  public static void bayClicksInstallTakeAndSendToInventory(GameTestHelper h) {
    var drive = drive(h);
    var player = player(h);
    var menu = open(h, player);
    menu.setCarried(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM), 2));
    h.assertTrue(menu.clickMenuButton(player, 2), "Bay 3 accepts a carried cell");
    h.assertTrue(
        !drive.cell(2).isEmpty() && drive.cell(0).isEmpty() && menu.getCarried().getCount() == 1,
        "The chosen bay, not the first empty one, holds exactly one cell");
    menu.clickMenuButton(player, 2);
    h.assertTrue(menu.getCarried().getCount() == 1, "An occupied bay refuses a second cell");
    menu.setCarried(ItemStack.EMPTY);
    menu.clickMenuButton(player, 2);
    var picked = menu.getCarried();
    h.assertTrue(
        drive.cell(2).isEmpty()
            && picked.getItem() instanceof CellItem
            && registry(h).definition(CellHandleData.read(picked)).location() == null,
        "An empty cursor picks up the bay's cell as a portable lease");
    menu.setCarried(ItemStack.EMPTY);
    drive.installCell(1, picked);
    menu.clickMenuButton(player, DrivePackets.BAYS + 1);
    h.assertTrue(
        drive.cell(1).isEmpty()
            && player.getInventory().items.stream()
                .anyMatch(stack -> stack.getItem() instanceof CellItem),
        "Shift-click sends the cell to the inventory");
    menu.broadcastChanges();
    h.assertTrue(
        menu.view().bays().stream().allMatch(bay -> bay.item().isEmpty()),
        "The view shows every bay empty again");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void moduleUpgradesInPlaceKeepingContentsAndRefusesWrongOrder(GameTestHelper h) {
    var drive = drive(h);
    var registry = registry(h);
    var named = new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM));
    named.set(DataComponents.CUSTOM_NAME, Component.literal("Ores"));
    drive.installCell(3, named);
    activate(h);
    var handle = CellHandleData.read(drive.cell(3));
    registry.insert(handle, IRON, 8000);
    registry.reserve(handle, UUID.randomUUID(), IRON, 500);
    var player = player(h);
    var menu = open(h, player);

    menu.setCarried(new ItemStack(StorageContent.INFINITE.get(), 2));
    menu.clickMenuButton(player, 3);
    h.assertTrue(
        menu.getCarried().getCount() == 2 && CellHandleData.read(drive.cell(3)).equals(handle),
        "A module out of order changes nothing and is not used up");

    menu.setCarried(new ItemStack(StorageContent.EXPANSION.get(), 2));
    h.assertTrue(menu.clickMenuButton(player, 3), "Expansion applies to the placed cell");
    var upgraded = drive.cell(3);
    var after = CellHandleData.read(upgraded);
    h.assertTrue(
        ((CellItem) upgraded.getItem()).tier() == CellTier.EXPANDED_ITEM
            && menu.getCarried().getCount() == 1,
        "The cell is Expanded in the same bay and one module was used: " + refusal(menu));
    h.assertTrue(
        after.backing().equals(handle.backing())
            && registry.definition(after).location() != null
            && upgraded.getHoverName().getString().equals("Ores"),
        "Same backing, still placed, custom name kept");
    activate(h);
    h.assertTrue(
        registry.stock(after, IRON).total() == 8000
            && registry.stock(after, IRON).reserved() == 500,
        "Contents and reservations kept");
    var current = CellHandleData.read(drive.cell(3));
    menu.setCarried(new ItemStack(StorageContent.EXPANSION.get()));
    menu.clickMenuButton(player, 3);
    h.assertTrue(
        CellHandleData.read(drive.cell(3)).equals(current) && menu.getCarried().getCount() == 1,
        "A downgrade/repeated module preserves the placed lease and module");
    menu.broadcastChanges();
    var bay = menu.view().bays().get(3);
    h.assertTrue(
        bay.total() == 8000
            && bay.types() == 1
            && bay.capacity() == CellTier.EXPANDED_ITEM.capacity(),
        "The view reports the new capacity and the same total");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void fullDriveAndFluidInfiniteRefuseWithoutLoss(GameTestHelper h) {
    var drive = drive(h);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_FLUID)));
    for (int i = 1; i < DrivePackets.BAYS; i++)
      drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var player = player(h);
    var menu = open(h, player);
    player.getInventory().setItem(0, new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    int slot = slotOf(menu, 0);
    menu.quickMoveStack(player, slot);
    h.assertTrue(
        player.getInventory().getItem(0).getCount() == 1, "A full drive leaves the cell in hand");
    menu.broadcastChanges();
    h.assertTrue(menu.view().status() == DrivePackets.Status.REFUSED, "The refusal is shown");

    var fluid = CellHandleData.read(drive.cell(0));
    menu.setCarried(new ItemStack(StorageContent.ADVANCED.get()));
    menu.clickMenuButton(player, 0);
    menu.setCarried(new ItemStack(StorageContent.EXPANSION.get()));
    menu.clickMenuButton(player, 0);
    menu.setCarried(new ItemStack(StorageContent.ADVANCED.get()));
    menu.clickMenuButton(player, 0);
    var advanced = CellHandleData.read(drive.cell(0));
    menu.setCarried(new ItemStack(StorageContent.EXPANSION.get()));
    menu.clickMenuButton(player, 0);
    h.assertTrue(
        CellHandleData.read(drive.cell(0)).equals(advanced) && menu.getCarried().getCount() == 1,
        "A lower-tier module cannot downgrade the Advanced cell or consume the module");
    menu.setCarried(new ItemStack(StorageContent.INFINITE.get()));
    menu.clickMenuButton(player, 0);
    h.assertTrue(
        ((CellItem) drive.cell(0).getItem()).tier() == CellTier.ADVANCED_FLUID
            && CellHandleData.read(drive.cell(0)).equals(advanced)
            && advanced.backing().equals(fluid.backing())
            && menu.getCarried().getCount() == 1,
        "Fluid cells stop at Advanced; the Infinite module is refused and kept");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void closedAndDistantScreensCannotMutate(GameTestHelper h) {
    var drive = drive(h);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var player = player(h);
    var menu = open(h, player);
    var original = CellHandleData.read(drive.cell(0));
    player.setPos(player.getX() + 20, player.getY(), player.getZ());
    h.assertTrue(!menu.clickMenuButton(player, 0), "A distant player cannot take a cell");
    player.getInventory().setItem(0, new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    menu.quickMoveStack(player, slotOf(menu, 0));
    h.assertTrue(drive.cell(1).isEmpty(), "A distant player cannot shift-install a cell");
    player.closeContainer();
    var center = h.absolutePos(DRIVE);
    player.setPos(center.getX() + 0.5, center.getY() + 1, center.getZ() - 1.5);
    h.assertTrue(!menu.clickMenuButton(player, 0), "A closed menu cannot take a cell");
    menu.quickMoveStack(player, slotOf(menu, 0));
    h.assertTrue(
        CellHandleData.read(drive.cell(0)).equals(original)
            && drive.cell(1).isEmpty()
            && player.getInventory().getItem(0).getCount() == 1,
        "Denied actions preserve leases and inventory");
    h.succeed();
  }

  private static String refusal(DriveMenu menu) {
    menu.broadcastChanges();
    return menu.view().status() + " " + menu.view().detail();
  }

  private static int slotOf(DriveMenu menu, int inventorySlot) {
    for (int i = 0; i < menu.slots.size(); i++)
      if (menu.slots.get(i).getContainerSlot() == inventorySlot) return i;
    throw new AssertionError("No menu slot for inventory slot " + inventorySlot);
  }

  private static StorageDriveEntity drive(GameTestHelper h) {
    var root = new BlockPos(1, 1, 0);
    h.setBlock(root, NetworkContent.CONTROLLER.get());
    ((NetworkNodeEntity) h.getBlockEntity(root)).commission(UUID.randomUUID());
    h.setBlock(new BlockPos(1, 1, 1), NetworkContent.CABLE.get());
    h.setBlock(DRIVE, StorageContent.DRIVE.get());
    return h.getBlockEntity(DRIVE);
  }

  private static void activate(GameTestHelper h) {
    var runtime = WorldNetworks.get(h.getLevel().getServer());
    var absolute = h.absolutePos(DRIVE);
    var position =
        new NodeRegistry.Position(
            h.getLevel().dimension().location().toString(), absolute.asLong());
    for (int i = 0;
        i < 100 && runtime.membership(position).status() != NetworkTopology.Status.VALID;
        i++) runtime.tick();
  }

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.setGameMode(GameType.SURVIVAL);
    var center = h.absolutePos(DRIVE);
    player.setPos(center.getX() + 0.5, center.getY() + 1, center.getZ() - 1.5);
    return player;
  }

  private static DriveMenu open(GameTestHelper h, ServerPlayer player) {
    h.useBlock(DRIVE, player);
    h.assertTrue(player.containerMenu instanceof DriveMenu, "Empty-hand use opens the drive");
    var menu = (DriveMenu) player.containerMenu;
    menu.broadcastChanges();
    return menu;
  }

  private static DeviceRegistry registry(GameTestHelper h) {
    return DeviceSavedData.get(h.getLevel().getServer()).registry();
  }
}
