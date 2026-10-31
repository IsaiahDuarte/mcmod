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
