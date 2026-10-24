package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.platform.wireless.LinkModule;
import dev.izzy.factorycore.platform.wireless.LinkPairing;
import dev.izzy.factorycore.testing.EnergyFixture;
import dev.izzy.factorycore.testing.Links;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real paired topology, terminal sessions and native item capabilities. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class TerminalWirelessContainerGameTests {
  private static final BlockPos ROOT = new BlockPos(0, 1, 1),
      DRIVE = ROOT.above(),
      BANK = ROOT.south(),
      PARENT_GATE = new BlockPos(1, 1, 1),
      A = new BlockPos(2, 1, 1),
      B = new BlockPos(4, 1, 1),
      CHILD_GATE = new BlockPos(5, 1, 1),
      TERMINAL = new BlockPos(6, 1, 1);

  private TerminalWirelessContainerGameTests() {}

  private static NodeRegistry.Position position(GameTestHelper h, BlockPos relative) {
    return Positions.of(h.getLevel(), h.absolutePos(relative));
  }

  private static String action(
      GameTestHelper h, ServerPlayer player, int action, int slot, boolean reopen) {
    if (reopen) {
      player.closeContainer();
      h.useBlock(TERMINAL, player);
    }
    var menu = (TerminalMenu) player.containerMenu;
    menu.command(
        player,
        new TerminalPackets.Command(
            menu.containerId,
            menu.session(),
            menu.view().revision(),
            action,
            slot,
            1,
            "",
            new byte[32]));
    return menu.view().status();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void indivisibleContainersAndEnergyCopiesRespectSharedBidirectionalCap(
      GameTestHelper h) {
    ServerPlayer player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(TERMINAL)));
    player.getInventory().clearContent();
    NetworkNodeEntity root = Net.in(h, player.getUUID()).controller(ROOT);
    for (var pos : new BlockPos[] {PARENT_GATE, CHILD_GATE}) {
      h.setBlock(
          pos,
          NetworkContent.GATEWAY
              .get()
              .defaultBlockState()
              .setValue(BlockStateProperties.FACING, Direction.EAST));
      NetworkNodeEntity gateway = h.getBlockEntity(pos);
      gateway.commission(null);
    }
    // The links live in the gateways the old endpoints touched (ADR 0061).
    LinkModule a = Links.install(h, PARENT_GATE, Direction.EAST, player.getUUID());
    LinkModule b = Links.install(h, CHILD_GATE, Direction.WEST, player.getUUID());
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_FLUID);
    var cell = CellHandleData.read(drive.cell(0));
    EnergyBankEntity bank = Net.in(h).bank(BANK, EnergyBankTier.STARTER);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    var runtime = WorldNetworks.get(player.server);
    var saved = DeviceSavedData.get(player.server);
    var water =
        new PlatformResourceCodec(player.registryAccess())
            .fluidKey(new FluidStack(Fluids.WATER, 1));
    var terminal = position(h, TERMINAL);
    var source = position(h, DRIVE);
    var bankPosition = position(h, BANK);
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    runtime.membership(position(h, ROOT)).status() == NetworkTopology.Status.VALID,
                    "Parent is commissioned"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  LinkPairing.pair(player, a.position(), b.position()).equals("paired"),
                  "Actual link pairs");
              a.energyStorage().receiveEnergy(1000, false);
              b.energyStorage().receiveEnergy(1000, false);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    runtime.membership(terminal).status() == NetworkTopology.Status.VALID
                        && saved.registry().stock(cell, water).loaded()
                        && saved.registry().stock(bank.handle(), ResourceKey.ENERGY).loaded(),
                    "Child terminal sees powered topology"))
        .thenExecute(
            () -> {
              saved.registry().insert(cell, water, 2500);
              saved.registry().insert(bank.handle(), ResourceKey.ENERGY, 900);
              var inventory = player.getInventory();
              inventory.setItem(0, new ItemStack(Items.BUCKET, 2));
              inventory.setItem(1, new ItemStack(Items.WATER_BUCKET));
              inventory.setItem(8, new ItemStack(EnergyFixture.BATTERY.get()));
              inventory.selected = 7;
              h.useBlock(TERMINAL, player);
              action(h, player, TerminalPackets.KIND, 1, false);
            })
        .thenWaitUntil(
            () -> {
              var menu = (TerminalMenu) player.containerMenu;
              menu.broadcastChanges();
              h.assertTrue(
                  menu.view().status().equals("ready") && menu.view().rows().size() == 1,
                  "Real child terminal fluid catalog is ready");
            })
        .thenExecute(
            () -> {
              var inventory = player.getInventory();
              var fluidUse = runtime.movement(source, terminal, water, 3500);
              h.assertTrue(fluidUse.amount() == 3500, "Shared fluid cap starts at4000");
              fluidUse.finish(3500);
              h.assertTrue(
                  action(h, player, TerminalPackets.FILL, 0, false).equals("rate_limit")
                      && action(h, player, TerminalPackets.EMPTY, 1, true).equals("rate_limit"),
                  "Indivisible bucket refuses residual500 both directions");
              h.assertTrue(
                  saved.registry().stock(cell, water).total() == 2500
                      && inventory.getItem(0).getCount() == 2
                      && inventory.getItem(1).is(Items.WATER_BUCKET)
                      && runtime.movementAvailable(source, terminal, water, 4000) == 500,
                  "Rejected bucket plans neither move fluid nor consume the residual allowance");
              var energyUse = runtime.movement(bankPosition, terminal, ResourceKey.ENERGY, 7500);
              h.assertTrue(energyUse.amount() == 7500, "Shared energy cap starts at8000");
              energyUse.finish(7500);
              h.assertTrue(
                  action(h, player, TerminalPackets.CHARGE, 8, true).equals("charged")
                      && inventory
                              .getItem(8)
                              .getCapability(Capabilities.EnergyStorage.ITEM)
                              .getEnergyStored()
                          == 500
                      && bank.getEnergyStored() == 400,
                  "Partial FE action moves exactly residual500");
              h.assertTrue(
                  action(h, player, TerminalPackets.DISCHARGE, 8, true).equals("rate_limit")
                      && inventory
                              .getItem(8)
                              .getCapability(Capabilities.EnergyStorage.ITEM)
                              .getEnergyStored()
                          == 500
                      && bank.getEnergyStored() == 400,
                  "Reverse action shares exhausted cap and leaves live energy item unchanged");
            })
        .thenIdle(1)
        .thenExecute(
            () -> {
              var inventory = player.getInventory();
              h.assertTrue(
                  action(h, player, TerminalPackets.DISCHARGE, 8, true).equals("discharged")
                      && bank.getEnergyStored() == 900,
                  "Next tick replenishes shared cap and returns exactly500");
              inventory.setItem(8, new ItemStack(EnergyFixture.UNRELIABLE_BATTERY.get()));
              long before =
                  runtime.movementAvailable(bankPosition, terminal, ResourceKey.ENERGY, 8000);
              h.assertTrue(
                  action(h, player, TerminalPackets.CHARGE, 8, true).equals("handler_fault")
                      && inventory
                              .getItem(8)
                              .getCapability(Capabilities.EnergyStorage.ITEM)
                              .getEnergyStored()
                          == 0
                      && bank.getEnergyStored() == 900
                      && runtime.movementAvailable(bankPosition, terminal, ResourceKey.ENERGY, 8000)
                          == before,
                  "A lying item mutates only its disposable copy and releases unspent admission");
              inventory.getItem(8).set(EnergyFixture.ENERGY.get(), 600);
              h.assertTrue(
                  action(h, player, TerminalPackets.DISCHARGE, 8, true).equals("handler_fault")
                      && inventory
                              .getItem(8)
                              .getCapability(Capabilities.EnergyStorage.ITEM)
                              .getEnergyStored()
                          == 600
                      && bank.getEnergyStored() == 900,
                  "Discharge disagreement preserves live item and bank too");
              player.closeContainer();
              player.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
