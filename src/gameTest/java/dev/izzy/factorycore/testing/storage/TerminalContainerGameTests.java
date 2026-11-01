package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.EnergyBankEntity;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalEntity;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.testing.EnergyFixture;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestMenus;
import dev.izzy.factorycore.testing.TestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class TerminalContainerGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      BANK = new BlockPos(1, 1, 2),
      TERMINAL = new BlockPos(0, 1, 1);

  private TerminalContainerGameTests() {}

  private static void command(ServerPlayer player, int action, int selection) {
    var m = TestMenus.terminal(player);
    m.command(
        player,
        new TerminalPackets.Command(
            m.containerId,
            m.session(),
            m.view().revision(),
            action,
            selection,
            1,
            "",
            new byte[32]));
    m.broadcastChanges();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void fluidContainersAndEnergyItemsMoveExactAmountsThroughTheTerminal(
      GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    NetworkNodeEntity root = Net.in(h, player.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_FLUID);
    var cell = CellHandleData.read(drive.cell(0));
    EnergyBankEntity bank = Net.in(h).bank(BANK, EnergyBankTier.STARTER);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    TerminalEntity terminal = h.getBlockEntity(TERMINAL);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    var water =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .fluidKey(new FluidStack(Fluids.WATER, 1));
    var inventory = player.getInventory();
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    terminal.canUse(player)
                        && saved.registry().stock(cell, water).loaded()
                        && bank.getEnergyStored() >= 0
                        && bank.receiveEnergy(0, true) == 0,
                    "Ready"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  saved.registry().insert(cell, water, 2_500).amount() == 2_500, "Water stored");
              h.assertTrue(bank.receiveEnergy(900, false) == 900, "Bank charged");
              inventory.setItem(0, new ItemStack(Items.BUCKET, 2));
              inventory.setItem(8, new ItemStack(EnergyFixture.BATTERY.get()));
              h.useBlock(TERMINAL, player);
              command(player, TerminalPackets.KIND, 1);
            })
        .thenWaitUntil(
            () -> {
              var m = TestMenus.terminal(player);
              m.broadcastChanges();
              h.assertTrue(
                  m.view().status() == TerminalPackets.Status.READY
                      && m.view().rows().size() == 1
                      && m.view().rows().getFirst().total().equals("2500"),
                  "Fluid view lists 2500 mB of water: " + m.view());
            })
        .thenExecute(
            () -> {
              command(player, TerminalPackets.FILL, 0);
              h.assertTrue(
                  saved.registry().stock(cell, water).total() == 1_500
                      && inventory.getItem(0).is(Items.BUCKET)
                      && inventory.getItem(0).getCount() == 1
                      && countOf(player, Items.WATER_BUCKET) == 1,
                  "One bucket filled with exactly 1000 mB: "
                      + TestMenus.terminal(player).view().status());
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              int slot = slotOf(player, Items.WATER_BUCKET);
              command(player, TerminalPackets.EMPTY, slot);
              h.assertTrue(
                  saved.registry().stock(cell, water).total() == 2_500
                      && countOf(player, Items.WATER_BUCKET) == 0
                      && countOf(player, Items.BUCKET) == 2,
                  "Emptying returns exactly 1000 mB and the bucket");
            })
        // Terminal commands share a two-tick throttle.
        .thenIdle(3)
        .thenExecute(
            () -> {
              command(player, TerminalPackets.EMPTY, 0);
              h.assertTrue(
                  TestMenus.terminal(player).view().status() == TerminalPackets.Status.EMPTY
                      && saved.registry().stock(cell, water).total() == 2_500,
                  "Empty buckets have nothing to empty");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              command(player, TerminalPackets.KIND, 2);
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              command(player, TerminalPackets.CHARGE, 8);
              var battery = inventory.getItem(8).getCapability(Capabilities.EnergyStorage.ITEM);
              h.assertTrue(
                  battery.getEnergyStored() == 900 && bank.getEnergyStored() == 0,
                  "Charging moves all 900 FE (under the 1000 FE action limit) from the bank");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              command(player, TerminalPackets.DISCHARGE, 8);
              var battery = inventory.getItem(8).getCapability(Capabilities.EnergyStorage.ITEM);
              h.assertTrue(
                  battery.getEnergyStored() == 0 && bank.getEnergyStored() == 900,
                  "Discharging returns exactly 900 FE");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              command(player, TerminalPackets.CHARGE, 0);
              h.assertTrue(
                  TestMenus.terminal(player).view().status()
                          == TerminalPackets.Status.NO_ENERGY_ITEM
                      && bank.getEnergyStored() == 900,
                  "A bucket stack is not an energy item; nothing moves");
              player.closeContainer();
              player.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }

  /** Q147: the row of {@code kind} (a ResourceKind ordinal) in the current view, or -1. */
  private static int rowOf(ServerPlayer player, int kind) {
    var rows = TestMenus.terminal(player).view().rows();
    for (int index = 0; index < rows.size(); index++)
      if (rows.get(index).kind() == kind) return index;
    return -1;
  }

  /**
   * Q147 (n4): the All view lists items, fluids and energy together, each row with its kind. Fill
   * acts on a fluid row; a take names only item rows, so taking a fluid row moves nothing.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void allKindsViewListsItemsFluidsAndEnergyAndActsPerRow(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    Net.in(h, player.getUUID()).controller(ROOT);
    StorageDriveEntity fluids = Net.in(h).drive(DRIVE, CellTier.STARTER_FLUID);
    StorageDriveEntity items = Net.in(h).drive(new BlockPos(1, 1, 0), CellTier.STARTER_ITEM);
    var fluidCell = CellHandleData.read(fluids.cell(0));
    var itemCell = CellHandleData.read(items.cell(0));
    EnergyBankEntity bank = Net.in(h).bank(BANK, EnergyBankTier.STARTER);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    TerminalEntity terminal = h.getBlockEntity(TERMINAL);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    var water = codec.fluidKey(new FluidStack(Fluids.WATER, 1));
    var stone = codec.itemKey(new ItemStack(Items.STONE));
    var inventory = player.getInventory();
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    terminal.canUse(player)
                        && saved.registry().stock(fluidCell, water).loaded()
                        && saved.registry().stock(itemCell, stone).loaded(),
                    "Ready"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  saved.registry().insert(fluidCell, water, 2_500).amount() == 2_500
                      && saved.registry().insert(itemCell, stone, 5).amount() == 5
                      && bank.receiveEnergy(900, false) == 900,
                  "Stocked");
              inventory.setItem(0, new ItemStack(Items.BUCKET));
              h.useBlock(TERMINAL, player);
              command(player, TerminalPackets.KIND, TerminalPackets.KIND_ALL);
            })
        .thenWaitUntil(
            () -> {
              var m = TestMenus.terminal(player);
              m.broadcastChanges();
              h.assertTrue(
                  m.view().status() == TerminalPackets.Status.READY
                      && m.view().kind() == TerminalPackets.KIND_ALL
                      && rowOf(player, 0) >= 0
                      && rowOf(player, 1) >= 0
                      && rowOf(player, 2) >= 0,
                  "The All view lists an item, a fluid and energy: " + m.view().rows());
            })
        .thenExecute(
            () -> {
              var rows = TestMenus.terminal(player).view().rows();
              h.assertTrue(
                  rows.get(rowOf(player, 0)).total().equals("5")
                      && rows.get(rowOf(player, 1)).total().equals("2500"),
                  "Rows carry their own amounts: " + rows);
              command(player, TerminalPackets.TAKE_CURSOR, rowOf(player, 1));
              h.assertTrue(
                  player.containerMenu.getCarried().isEmpty()
                      && saved.registry().stock(fluidCell, water).total() == 2_500,
                  "Taking a fluid row moves nothing");
            })
        .thenIdle(3)
        .thenExecute(() -> command(player, TerminalPackets.FILL, rowOf(player, 1)))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    saved.registry().stock(fluidCell, water).total() == 1_500
                        && countOf(player, Items.WATER_BUCKET) == 1,
                    "Fill on the fluid row fills the bucket with exactly 1000 mB"))
        .thenIdle(3)
        .thenWaitUntil(
            () -> {
              TestMenus.terminal(player).broadcastChanges();
              h.assertTrue(
                  !TestMenus.terminal(player).view().busy() && rowOf(player, 0) >= 0, "Listed");
            })
        .thenExecute(() -> command(player, TerminalPackets.TAKE_CURSOR, rowOf(player, 0)))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    player.containerMenu.getCarried().is(Items.STONE)
                        && player.containerMenu.getCarried().getCount() == 5
                        && saved.registry().stock(itemCell, stone).total() == 0,
                    "Taking the item row puts all 5 stone on the cursor"))
        .thenExecute(
            () -> {
              player.closeContainer();
              player.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }

  private static int countOf(ServerPlayer player, Item item) {
    int total = 0;
    for (int slot = 0; slot < 36; slot++)
      if (player.getInventory().getItem(slot).is(item))
        total += player.getInventory().getItem(slot).getCount();
    return total;
  }

  private static int slotOf(ServerPlayer player, Item item) {
    for (int slot = 0; slot < 36; slot++)
      if (player.getInventory().getItem(slot).is(item)) return slot;
    return -1;
  }
}
