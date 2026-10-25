package dev.izzy.factorycore.testing.program;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import dev.izzy.factorycore.testing.Net;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * D3 (ADR 0062): one Interface on a vanilla furnace's west side drives its top (input), north
 * (fuel) and bottom (output) faces; a narrowed binding refuses slots outside it and other sides.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class SideAndSlotsGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(1, 1, 2),
      CONTROLLER = new BlockPos(1, 1, 3),
      PORT = new BlockPos(2, 1, 1),
      MACHINE = new BlockPos(3, 1, 1);

  private record Fixture(
      NetworkPermissions.Actor owner,
      DeviceSavedData saved,
      StorageDriveEntity drive,
      ProgramControllerEntity controller,
      MachineInterfaceEntity port,
      PlatformResourceCodec codec) {
    long stored(Item item) {
      return saved
          .registry()
          .stock(CellHandleData.read(drive.cell(0)), codec.itemKey(new ItemStack(item)))
          .total();
    }

    boolean store(Item item, int count) {
      return saved
              .registry()
              .insert(CellHandleData.read(drive.cell(0)), codec.itemKey(new ItemStack(item)), count)
              .amount()
          == count;
    }
  }

  private SideAndSlotsGameTests() {}

  private static Fixture setup(GameTestHelper h, net.minecraft.world.level.block.Block machine) {
    var owner = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var net = Net.in(h, owner.id());
    net.controller(ROOT);
    var drive = net.drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(CONTROLLER, ProgramContent.BLOCK.get());
    h.setBlock(MACHINE, machine);
    var port = net.machine(PORT, Direction.EAST);
    return new Fixture(
        owner,
        DeviceSavedData.get(h.getLevel().getServer()),
        drive,
        h.getBlockEntity(CONTROLLER),
        port,
        new PlatformResourceCodec(h.getLevel().registryAccess()));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        f.saved
            .registry()
            .stock(
                CellHandleData.read(f.drive.cell(0)), f.codec.itemKey(new ItemStack(Items.STONE)))
            .loaded(),
        "Cell online");
    var networks = WorldNetworks.get(h.getLevel().getServer());
    for (var position : List.of(CONTROLLER, PORT))
      h.assertTrue(
          networks.membership(Positions.of(h.getLevel(), h.absolutePos(position))).status()
              == NetworkTopology.Status.VALID,
          "Network ready");
  }

  private static void label(GameTestHelper h, Fixture f, String name, int first, int count) {
    var decision =
        f.port.applySettings(
            f.owner,
            new ConnectorSettings(
                ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, first, count, 64),
            name);
    h.assertTrue(decision == NetworkAuthority.Decision.ALLOWED, name + " labeled: " + decision);
  }

  private static void deploy(GameTestHelper h, Fixture f, String source) {
    f.controller.energyStorage().receiveEnergy(1000, false);
    f.controller.draft(source);
    var diagnostics = WorldPrograms.get(h.getLevel().getServer()).deploy(f.owner, f.controller);
    h.assertTrue(diagnostics.isEmpty(), "Deployed: " + diagnostics);
  }

  private static ItemStack slot(GameTestHelper h, int index) {
    return ((BaseContainerBlockEntity) h.getBlockEntity(MACHINE)).getItem(index);
  }

  @GameTest(template = "empty", timeoutTicks = 800)
  public static void oneInterfaceFeedsFurnaceTopAndSideAndEmptiesItsBottom(GameTestHelper h) {
    var f = setup(h, Blocks.FURNACE);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              // Bound to its own (west) face's every slot, so other sides are allowed.
              label(h, f, "furnace", 0, 3);
              ((BaseContainerBlockEntity) h.getBlockEntity(MACHINE))
                  .setItem(2, new ItemStack(Items.IRON_INGOT, 5));
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () ->
                h.assertTrue(
                    f.store(Items.RAW_IRON, 4) && f.store(Items.COAL, 2), "Storage stocked"))
        .thenExecute(
            () ->
                deploy(
                    h,
                    f,
                    """
                    every 10 ticks as "furnace" {
                      move item("raw_iron") from storage to machines("furnace") side up limit 4
                      move item("coal") from storage to machines("furnace") side north limit 2
                      move item("iron_ingot") from machines("furnace") side down slots 0 to storage
                    }
                    """))
        .thenWaitUntil(
            () -> {
              var furnace =
                  (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity)
                      h.getBlockEntity(MACHINE);
              h.assertTrue(
                  f.stored(Items.RAW_IRON) == 0
                      && (countOf(h, 0, Items.RAW_IRON) > 0 || furnace.getItem(0).isEmpty()),
                  "Raw iron left storage into the input: stored "
                      + f.stored(Items.RAW_IRON)
                      + " "
                      + f.controller.logs().subList(0, Math.min(4, f.controller.logs().size())));
              h.assertTrue(
                  countOf(h, 1, Items.COAL) > 0 || countOf(h, 0, Items.RAW_IRON) < 4,
                  "Coal is in the fuel slot or already burning the input: " + slot(h, 1));
              h.assertTrue(
                  f.stored(Items.COAL) == 0 && countOf(h, 0, Items.COAL) == 0,
                  "Coal went to the fuel slot from the side, never the input: "
                      + slot(h, 0)
                      + " / "
                      + slot(h, 1));
              h.assertTrue(
                  f.stored(Items.IRON_INGOT) >= 5 && countOf(h, 2, Items.IRON_INGOT) == 0,
                  "Output emptied from the bottom: "
                      + f.stored(Items.IRON_INGOT)
                      + " "
                      + f.controller.logs());
            })
        .thenSucceed();
  }

  private static int countOf(GameTestHelper h, int index, Item item) {
    var stack = slot(h, index);
    return stack.is(item) ? stack.getCount() : 0;
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void aNarrowedBindingRefusesOutsideSlotsAndOtherSides(GameTestHelper h) {
    var f = setup(h, Blocks.CHEST);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              label(h, f, "chest", 0, 4);
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> h.assertTrue(f.store(Items.STONE, 16), "Storage stocked"))
        .thenExecute(
            () ->
                deploy(
                    h,
                    f,
                    """
                    every 10 ticks as "narrow" {
                      move item("stone") from storage to machines("chest") slots 10 limit 1
                      move item("stone") from storage to machines("chest") side up limit 1
                      move item("stone") from storage to machines("chest") slots 2 limit 1
                    }
                    """))
        .thenWaitUntil(
            () -> {
              var logs = f.controller.logs();
              h.assertTrue(
                  logs.stream().anyMatch(l -> l.contains("outside what this Interface may reach"))
                      && logs.stream()
                          .anyMatch(l -> l.contains("another side needs the Interface")),
                  "Both refusals are visible: " + logs);
              h.assertTrue(
                  countOf(h, 2, Items.STONE) >= 1,
                  "Slot 2 inside the window fills; stored "
                      + f.stored(Items.STONE)
                      + ": "
                      + logs.subList(0, Math.min(3, logs.size())));
            })
        .thenExecute(
            () -> {
              for (int index = 0; index < 27; index++)
                if (index != 2)
                  h.assertTrue(
                      slot(h, index).isEmpty(), "Nothing went anywhere but slot 2: " + index);
            })
        .thenSucceed();
  }
}
