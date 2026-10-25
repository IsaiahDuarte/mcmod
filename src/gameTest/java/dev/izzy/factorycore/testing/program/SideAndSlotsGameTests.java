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
                    }
                    every 10 ticks as "output" {
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
                      + " slots "
                      + slot(h, 0)
                      + "/"
                      + slot(h, 1)
                      + "/"
                      + slot(h, 2)
                      + " "
                      + f.controller.logs()
                      + " transfers "
                      + transfers(h, f));
            })
        .thenSucceed();
  }

  /** Iron-ingot transfers started from this fixture's Interface, for failure messages. */
  private static List<String> transfers(GameTestHelper h, Fixture f) {
    var origin = Positions.of(h.getLevel(), h.absolutePos(PORT));
    return f.saved.transfers().snapshot().stream()
        .filter(t -> t.transfer().key().registryId().equals("minecraft:iron_ingot"))
        .filter(t -> t.context() != null && t.context().origin().equals(origin))
        .map(t -> t.transfer().toString())
        .toList();
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

  /** D4: a `has` condition reads the furnace's fuel through the binding (its west face). */
  @GameTest(template = "empty", timeoutTicks = 800)
  public static void refuelsAFurnaceOnlyWhileItHasUnderEightCoal(GameTestHelper h) {
    var f = setup(h, Blocks.FURNACE);
    int[] fuel = {0};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> label(h, f, "furnace", 0, 3))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> h.assertTrue(f.store(Items.COAL, 64), "Storage stocked"))
        .thenExecute(
            () ->
                deploy(
                    h,
                    f,
                    """
                    every 10 ticks as "refuel" {
                      for f in machines("furnace") {
                        if f has < 8 of item("coal") {
                          move item("coal") from storage to f side north limit 4
                        } else {
                          log "fuel ok"
                        }
                      }
                    }
                    """))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    countOf(h, 1, Items.COAL) >= 8,
                    "Refuelled to 8: slot " + slot(h, 1) + ", stored " + f.stored(Items.COAL)))
        .thenIdle(40)
        .thenExecute(() -> fuel[0] = countOf(h, 1, Items.COAL))
        .thenIdle(60)
        .thenExecute(
            () -> {
              // A has read cannot see a move still in flight, so one move of 4 may overshoot.
              h.assertTrue(
                  fuel[0] >= 8 && fuel[0] <= 12 && countOf(h, 1, Items.COAL) == fuel[0],
                  "At 8 or more it stops: " + fuel[0] + " then " + slot(h, 1));
              h.assertTrue(
                  f.stored(Items.COAL) + fuel[0] == 64, "Coal conserved: " + f.stored(Items.COAL));
              h.assertTrue(
                  f.controller.logs().stream().anyMatch(l -> l.contains("fuel ok")),
                  "The else branch runs once full");
            })
        .thenSucceed();
  }

  /**
   * Regression: a program whose own move was refused mid-way (items staged under its deployer)
   * never resumed it and then refused every later move on that machine. It now continues it.
   */
  @GameTest(template = "empty", timeoutTicks = 600)
  public static void aProgramResumesItsOwnStagedMoveBeforeMovingMore(GameTestHelper h) {
    var f = setup(h, Blocks.CHEST);
    var chest = (BaseContainerBlockEntity) h.getBlockEntity(MACHINE);
    chest.setItem(0, new ItemStack(Items.IRON_INGOT, 8));
    var ingot = f.codec.itemKey(new ItemStack(Items.IRON_INGOT));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> label(h, f, "out", 0, 27))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var networks = WorldNetworks.get(h.getLevel().getServer());
              var handle = networks.connectors().bind(f.port).handle();
              var cell = CellHandleData.read(f.drive.cell(0));
              var source =
                  new dev.izzy.factorycore.core.resource.ResourcePort() {
                    public UUID backingId() {
                      return handle.backing();
                    }

                    public long simulateInsert(
                        dev.izzy.factorycore.core.resource.ResourceKey key, long amount) {
                      return amount;
                    }

                    public long insert(
                        dev.izzy.factorycore.core.resource.ResourceKey key, long amount) {
                      throw new AssertionError("Not a return");
                    }

                    public long extract(
                        dev.izzy.factorycore.core.resource.ResourceKey key, long amount) {
                      return chest.removeItem(0, (int) amount).getCount();
                    }
                  };
              var refusing =
                  new dev.izzy.factorycore.core.resource.ResourcePort() {
                    public UUID backingId() {
                      return cell.backing();
                    }

                    public long simulateInsert(
                        dev.izzy.factorycore.core.resource.ResourceKey key, long amount) {
                      return amount;
                    }

                    public long insert(
                        dev.izzy.factorycore.core.resource.ResourceKey key, long amount) {
                      return 0;
                    }

                    public long extract(
                        dev.izzy.factorycore.core.resource.ResourceKey key, long amount) {
                      throw new AssertionError("Never re-extracts the target");
                    }
                  };
              var origin = Positions.of(h.getLevel(), h.absolutePos(PORT));
              var scope = networks.membership(origin).scope();
              var context =
                  new dev.izzy.factorycore.core.resource.TransferContext(
                      f.saved.registry().world(),
                      scope.network(),
                      scope.segment(),
                      origin,
                      new dev.izzy.factorycore.core.resource.TransferContext.Endpoint(
                          dev.izzy.factorycore.core.resource.TransferContext.Type.EXTERNAL,
                          handle.connector().id(),
                          handle.backing(),
                          handle.connector().generation(),
                          handle.generation()),
                      new dev.izzy.factorycore.core.resource.TransferContext.Endpoint(
                          dev.izzy.factorycore.core.resource.TransferContext.Type.DIGITAL,
                          cell.backing(),
                          cell.backing(),
                          cell.generation(),
                          0));
              var id = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(id, f.owner.id(), ingot, source, refusing, 5, 5, () -> true, context);
              f.saved.transfers().unbind(id);
              h.assertTrue(
                  f.saved.transfers().get(id).staged() == 5 && countOf(h, 0, Items.IRON_INGOT) == 3,
                  "Five ingots are staged under the deployer, three stay in the chest");
            })
        .thenExecute(
            () ->
                deploy(
                    h,
                    f,
                    "every 10 ticks as \"out\" { move item(\"iron_ingot\") from machines(\"out\") to storage }"))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.stored(Items.IRON_INGOT) == 8 && countOf(h, 0, Items.IRON_INGOT) == 0,
                    "The staged five finish, then the last three move: stored "
                        + f.stored(Items.IRON_INGOT)
                        + " "
                        + f.controller.logs()))
        .thenSucceed();
  }

  /** D5: every ingot but iron leaves; iron and cobblestone stay. */
  @GameTest(template = "empty", timeoutTicks = 600)
  public static void movesEveryIngotExceptIron(GameTestHelper h) {
    var f = setup(h, Blocks.CHEST);
    var chest = (BaseContainerBlockEntity) h.getBlockEntity(MACHINE);
    chest.setItem(0, new ItemStack(Items.IRON_INGOT, 4));
    chest.setItem(1, new ItemStack(Items.GOLD_INGOT, 4));
    chest.setItem(2, new ItemStack(Items.COPPER_INGOT, 4));
    chest.setItem(3, new ItemStack(Items.COBBLESTONE, 4));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> label(h, f, "out", 0, 27))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () ->
                deploy(
                    h,
                    f,
                    "every 10 ticks as \"ingots\" { move item(\"*_ingot\") except item(\"iron_ingot\") from machines(\"out\") to storage }"))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.stored(Items.GOLD_INGOT) == 4 && f.stored(Items.COPPER_INGOT) == 4,
                    "Gold and copper leave: " + f.controller.logs()))
        .thenIdle(40)
        .thenExecute(
            () ->
                h.assertTrue(
                    countOf(h, 0, Items.IRON_INGOT) == 4
                        && countOf(h, 3, Items.COBBLESTONE) == 4
                        && f.stored(Items.IRON_INGOT) == 0
                        && f.stored(Items.COBBLESTONE) == 0,
                    "Iron (excepted) and cobblestone (not an ingot) stay"))
        .thenSucceed();
  }
}
