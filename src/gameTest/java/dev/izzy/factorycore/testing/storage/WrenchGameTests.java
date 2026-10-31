package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourcePort;
import dev.izzy.factorycore.core.resource.TransferContext;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.DeviceNames;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.PortContent;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalEntity;
import dev.izzy.factorycore.platform.storage.WrenchContent;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Q22: the Wrench turns a port to its next touching face, refuses busy turns, and picks up. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WrenchGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 2, 1),
      DRIVE = new BlockPos(1, 2, 2),
      PORT = new BlockPos(2, 2, 1),
      EAST_CHEST = new BlockPos(3, 2, 1),
      SOUTH_CHEST = new BlockPos(2, 2, 2);

  private WrenchGameTests() {}

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.setGameMode(GameType.SURVIVAL);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    return player;
  }

  private static void end(ServerPlayer... players) {
    for (var player : players)
      player.connection.disconnect(Component.literal("Wrench fixture complete"));
  }

  /**
   * An Exporter facing east into one chest, with a second chest to its south. Down, up and north
   * are cleared so south is the next face (face order: down, up, north, south, west, east).
   */
  private static MachineInterfaceEntity exporter(GameTestHelper h, Net net, ServerPlayer owner) {
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    for (var clear : List.of(PORT.below(), PORT.above(), PORT.north()))
      h.setBlock(clear, Blocks.AIR);
    h.setBlock(EAST_CHEST, Blocks.CHEST);
    h.setBlock(SOUTH_CHEST, Blocks.CHEST);
    var block = PortContent.EXPORTER.get();
    h.setBlock(
        PORT, block.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
    var position = h.absolutePos(PORT);
    var state = h.getLevel().getBlockState(position);
    state.getBlock().setPlacedBy(h.getLevel(), position, state, owner, new ItemStack(block));
    return h.getBlockEntity(PORT);
  }

  private static InteractionResult wrench(
      GameTestHelper h, ServerPlayer player, BlockPos at, boolean sneak) {
    player.setShiftKeyDown(sneak);
    var stack = new ItemStack(WrenchContent.WRENCH.get());
    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
    var absolute = h.absolutePos(at);
    var result =
        stack
            .getItem()
            .onItemUseFirst(
                stack,
                new UseOnContext(
                    player,
                    InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false)));
    player.setShiftKeyDown(false);
    return result;
  }

  private static int count(ChestBlockEntity chest) {
    int total = 0;
    for (int slot = 0; slot < chest.getContainerSize(); slot++)
      total += chest.getItem(slot).getCount();
    return total;
  }

  private static Direction facing(GameTestHelper h) {
    return h.getBlockState(PORT).getValue(BlockStateProperties.FACING);
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void aTurnedExporterFeedsItsNewFace(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    var exporter = exporter(h, net, owner);
    ChestBlockEntity east = h.getBlockEntity(EAST_CHEST);
    ChestBlockEntity south = h.getBlockEntity(SOUTH_CHEST);
    var iron =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(new ResourceFilter.Exact(net.key(Items.IRON_INGOT))));
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenIdle(
            (MachineInterfaceEntity.RESUME_ATTEMPTS + 2) * MachineInterfaceEntity.RESUME_TICKS)
        .thenExecute(
            () -> {
              net.saved().registry().insert(net.cell(DRIVE, 0), net.key(Items.IRON_INGOT), 64);
              exporter.deployPreset(Actors.of(owner), MachinePresets.Mode.STOCK, iron, 8);
            })
        .thenWaitUntil(() -> h.assertTrue(count(east) == 8, "The east chest is kept at 8"))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    wrench(h, owner, PORT, false) == InteractionResult.CONSUME,
                    "The owner turns the Exporter (retried while a move is in transit)"))
        .thenExecute(
            () ->
                h.assertTrue(
                    facing(h) == Direction.SOUTH
                        && h.getBlockEntity(PORT) == exporter
                        && exporter.handle() != null,
                    "It turns to the next touching face, south, keeping the device"))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    count(south) == 8,
                    "The turned Exporter now keeps the south chest at 8: "
                        + count(south)
                        + " "
                        + net.runtime().machinePresets().status(exporter)))
        .thenExecute(
            () -> {
              h.assertTrue(count(east) == 8, "The old chest is left as it was");
              end(owner);
            })
        .thenSucceed();
  }

  /**
   * A staged move naming the Exporter as its destination makes a turn wait; a player without Edit
   * is refused; neither changes the face.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void aTurnWaitsForAMoveInTransitAndNeedsEdit(GameTestHelper h) {
    var owner = player(h);
    var stranger = player(h);
    var net = Net.in(h, owner.getUUID());
    var exporter = exporter(h, net, owner);
    var id = UUID.randomUUID();
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenExecute(
            () -> {
              var key = net.key(Items.IRON_INGOT);
              var source = UUID.randomUUID();
              var target = UUID.randomUUID();
              var context =
                  new TransferContext(
                      net.saved().registry().world(),
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      exporter.position(),
                      new TransferContext.Endpoint(
                          TransferContext.Type.DIGITAL, source, source, 1, 0),
                      new TransferContext.Endpoint(
                          TransferContext.Type.EXTERNAL, exporter.handle().id(), target, 1, 1));
              net.saved()
                  .transfers()
                  .start(
                      id,
                      owner.getUUID(),
                      key,
                      port(source, true),
                      port(target, false),
                      16,
                      16,
                      () -> true,
                      context);
              h.assertTrue(
                  net.saved().transfers().unresolvedDevice(exporter.handle().id()) != null,
                  "A move into the Exporter is staged and unresolved");
              h.assertTrue(
                  wrench(h, owner, PORT, false) == InteractionResult.FAIL
                      && facing(h) == Direction.EAST,
                  "The turn waits while the move is in transit");
              // Return the fixture move and drop its record: the registry is shared by all tests.
              net.saved().transfers().cancel(id);
              net.saved().transfers().retire(id);
              h.assertTrue(
                  net.saved().transfers().unresolvedDevice(exporter.handle().id()) == null,
                  "The fixture move is returned");
              h.assertTrue(
                  wrench(h, stranger, PORT, false) == InteractionResult.FAIL
                      && facing(h) == Direction.EAST,
                  "A player without Edit cannot turn it");
              h.assertTrue(
                  wrench(h, owner, PORT, false) == InteractionResult.CONSUME
                      && facing(h) == Direction.SOUTH,
                  "Once nothing is in transit the owner turns it");
              end(owner, stranger);
            })
        .thenSucceed();
  }

  /** A digital fixture endpoint: the source gives what is asked, the target takes nothing. */
  private static ResourcePort port(UUID backing, boolean source) {
    return new ResourcePort() {
      public UUID backingId() {
        return backing;
      }

      public long simulateInsert(ResourceKey resource, long amount) {
        return amount;
      }

      public long insert(ResourceKey resource, long amount) {
        return source ? amount : 0;
      }

      public long extract(ResourceKey resource, long amount) {
        return source ? amount : 0;
      }
    };
  }

  /** Sneak-use picks the Exporter up into the inventory; placed again, it keeps its settings. */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void sneakPickUpKeepsSettings(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    var exporter = exporter(h, net, owner);
    var id = new UUID[1];
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenExecute(
            () -> {
              id[0] = exporter.handle().id();
              exporter.configure(
                  Actors.of(owner),
                  new ConnectorSettings(
                      ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 27, 32));
            })
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenExecute(
            () -> {
              h.assertTrue(
                  wrench(h, owner, PORT, true) == InteractionResult.CONSUME
                      && h.getBlockState(PORT).isAir(),
                  "Sneak-use picks the Exporter up");
              var picked = ItemStack.EMPTY;
              for (var stack : owner.getInventory().items)
                if (stack.is(PortContent.EXPORTER.get().asItem())) picked = stack;
              h.assertTrue(!picked.isEmpty(), "The Exporter is in the inventory");
              h.assertTrue(
                  h.getLevel()
                      .getEntitiesOfClass(
                          ItemEntity.class, new AABB(h.absolutePos(PORT)).inflate(3))
                      .isEmpty(),
                  "Nothing is dropped in the world");
              var block = PortContent.EXPORTER.get();
              h.setBlock(
                  PORT,
                  block.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
              var position = h.absolutePos(PORT);
              var state = h.getLevel().getBlockState(position);
              state.getBlock().setPlacedBy(h.getLevel(), position, state, owner, picked.copy());
              MachineInterfaceEntity placed = h.getBlockEntity(PORT);
              h.assertTrue(
                  placed.handle() != null
                      && placed.handle().id().equals(id[0])
                      && placed.settings().rate() == 32,
                  "Placed again it is the same device with its rate: "
                      + (placed.handle() == null ? "no lease" : placed.settings().rate()));
              end(owner);
            })
        .thenSucceed();
  }

  /**
   * Q77: an anvil-named Exporter shows its name as its screen title, keeps it through a save and
   * load, and keeps it when the wrench picks it up and it is placed again. A Terminal (a loot-table
   * device) drops its item with the name.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void deviceNamesShowAsTitlesSurviveReloadAndPickUp(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(EAST_CHEST, Blocks.CHEST);
    var name = Component.literal("Ore line");
    var block = PortContent.EXPORTER.get();
    var named = new ItemStack(block);
    named.set(DataComponents.CUSTOM_NAME, name);
    var registries = h.getLevel().registryAccess();
    Runnable placeNamed =
        () -> {
          h.setBlock(
              PORT,
              block.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
          var position = h.absolutePos(PORT);
          var state = h.getLevel().getBlockState(position);
          // As BlockItem.place does: the item's components first, then the placement hook.
          h.getLevel().getBlockEntity(position).applyComponentsFromItemStack(named);
          state.getBlock().setPlacedBy(h.getLevel(), position, state, owner, named.copy());
        };
    placeNamed.run();
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenExecute(
            () -> {
              MachineInterfaceEntity exporter = h.getBlockEntity(PORT);
              h.assertTrue(
                  exporter.getDisplayName().equals(name),
                  "The screen title is the name: " + exporter.getDisplayName().getString());
              var saved = exporter.saveWithFullMetadata(registries);
              var loaded =
                  BlockEntity.loadStatic(
                      exporter.getBlockPos(), exporter.getBlockState(), saved, registries);
              h.assertTrue(
                  loaded != null && name.equals(DeviceNames.name(loaded)),
                  "The name survives a save and load");
              h.assertTrue(
                  wrench(h, owner, PORT, true) == InteractionResult.CONSUME
                      && h.getBlockState(PORT).isAir(),
                  "Sneak-use picks the Exporter up");
              var picked = ItemStack.EMPTY;
              for (var stack : owner.getInventory().items)
                if (stack.is(block.asItem())) picked = stack;
              h.assertTrue(
                  name.equals(picked.get(DataComponents.CUSTOM_NAME)),
                  "The picked-up item keeps the name: " + picked.getHoverName().getString());
              named.applyComponents(picked.getComponents());
              placeNamed.run();
            })
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenExecute(
            () -> {
              MachineInterfaceEntity again = h.getBlockEntity(PORT);
              h.assertTrue(again.getDisplayName().equals(name), "Placed again it keeps the name");
              var terminalAt = new BlockPos(1, 2, 3);
              h.setBlock(terminalAt, TerminalContent.BLOCK.get());
              var terminalStack = new ItemStack(TerminalContent.BLOCK.get());
              terminalStack.set(DataComponents.CUSTOM_NAME, Component.literal("Front desk"));
              TerminalEntity terminal = h.getBlockEntity(terminalAt);
              terminal.applyComponentsFromItemStack(terminalStack);
              h.assertTrue(
                  terminal.getDisplayName().getString().equals("Front desk"),
                  "A named Terminal's title is its name");
              var drops =
                  Block.getDrops(
                      terminal.getBlockState(), h.getLevel(), terminal.getBlockPos(), terminal);
              h.assertTrue(
                  drops.size() == 1
                      && drops.getFirst().getHoverName().getString().equals("Front desk"),
                  "Its drop keeps the name: " + drops);
              end(owner);
            })
        .thenSucceed();
  }
}
