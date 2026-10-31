package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourcePort;
import dev.izzy.factorycore.core.resource.TransferContext;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestMenus;
import dev.izzy.factorycore.testing.TestPlayers;
import java.math.BigInteger;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class TerminalGridGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      TERMINAL = new BlockPos(1, 1, 2);

  private record Fixture(
      ServerPlayer player,
      NetworkNodeEntity root,
      TerminalEntity terminal,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      ResourceKey key) {
    TerminalMenu menu() {
      return TestMenus.terminal(player);
    }
  }

  private TerminalGridGameTests() {}

  private static ItemStack named(String name, int count) {
    var stack = new ItemStack(Items.IRON_INGOT, count);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
    return stack;
  }

  private static Fixture setup(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    NetworkNodeEntity root = Net.in(h, player.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    return new Fixture(
        player,
        root,
        h.getBlockEntity(TERMINAL),
        DeviceSavedData.get(player.server),
        CellHandleData.read(drive.cell(0)),
        new PlatformResourceCodec(player.registryAccess()).itemKey(named("Apple", 1)));
  }

  private static void open(GameTestHelper h, Fixture f) {
    h.useBlock(TERMINAL, f.player);
    h.assertTrue(f.player.containerMenu instanceof TerminalMenu, "Terminal opened");
    f.menu().broadcastChanges();
  }

  private static void ready(GameTestHelper h, Fixture f) {
    f.menu().broadcastChanges();
    h.assertTrue(
        !f.menu().view().rows().isEmpty()
            && f.menu().view().status() != TerminalPackets.Status.LOADING
            && !f.menu().view().busy(),
        "Observation ready");
  }

  private static void command(Fixture f, int action, int selection, int amount) {
    var menu = f.menu();
    var carried = menu.getCarried();
    byte[] digest =
        carried.isEmpty()
            ? new byte[32]
            : TerminalPackets.fingerprint(
                new PlatformResourceCodec(f.player.registryAccess()).itemKey(carried));
    menu.command(
        f.player,
        new TerminalPackets.Command(
            menu.containerId,
            menu.session(),
            menu.view().revision(),
            action,
            selection,
            amount,
            "",
            digest));
  }

  private static void close(Fixture f) {
    f.player.closeContainer();
    f.player.connection.disconnect(Component.literal("Grid fixture complete"));
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void scrollingWindowAndStandardClicksPreserveExactComponents(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(
            () ->
                TestMenus.ready(
                    h, f.terminal.canUse(f.player), f.terminal.getBlockPos(), "Network valid"))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 100);
              var codec = new PlatformResourceCodec(f.player.registryAccess());
              for (int i = 0; i < 100; i++)
                f.saved
                    .registry()
                    .insert(
                        f.cell,
                        codec.itemKey(
                            named(String.format(java.util.Locale.ROOT, "Row %03d", i), 1)),
                        2);
              open(h, f);
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenIdle(3)
        .thenExecute(() -> command(f, TerminalPackets.WINDOW, 40, 40))
        .thenWaitUntil(
            () -> {
              ready(h, f);
              var view = f.menu().view();
              h.assertTrue(
                  view.offset() == 40 && view.rows().size() == 40 && view.total() == 101,
                  "Scrolled window beyond eight keys");
              h.assertTrue(
                  view.rows().getFirst().name().equals("Row 039")
                      && view.rows().getLast().token() == 39,
                  "Sorted exact row keys have bounded local tokens");
              h.assertTrue(
                  view.used().equals("300") && view.capacity().equals("8192"),
                  "Capacity covers the whole network, not this window");
            })
        .thenIdle(3)
        .thenExecute(() -> command(f, TerminalPackets.WINDOW, 0, 80))
        .thenWaitUntil(
            () -> {
              ready(h, f);
              h.assertTrue(
                  f.menu().view().offset() == 0 && f.menu().view().rows().size() == 80,
                  "Large first window");
            })
        .thenIdle(3)
        .thenExecute(() -> command(f, TerminalPackets.HALF_CURSOR, 0, 64))
        .thenWaitUntil(
            () -> {
              ready(h, f);
              h.assertTrue(
                  f.menu().getCarried().getCount() == 32
                      && f.saved.registry().stock(f.cell, f.key).total() == 68,
                  "Right click takes half a stack");
              h.assertTrue(
                  ItemStack.isSameItemSameComponents(f.menu().getCarried(), named("Apple", 1)),
                  "Cursor retains exact components");
            })
        .thenIdle(3)
        .thenExecute(() -> command(f, TerminalPackets.DEPOSIT_CURSOR, 0, 64))
        .thenWaitUntil(
            () -> {
              ready(h, f);
              h.assertTrue(
                  f.menu().getCarried().isEmpty()
                      && f.saved.registry().stock(f.cell, f.key).total() == 100,
                  "Cursor deposit returns all units");
            })
        .thenIdle(3)
        .thenExecute(() -> command(f, TerminalPackets.WITHDRAW, 0, 64))
        .thenWaitUntil(
            () -> {
              ready(h, f);
              h.assertTrue(
                  f.menu().getCarried().isEmpty()
                      && f.player.getInventory().countItem(Items.IRON_INGOT) == 64,
                  "Shift click sends a stack to inventory");
            })
        .thenIdle(3)
        .thenExecute(() -> command(f, TerminalPackets.TAKE_CURSOR, 0, 64))
        .thenWaitUntil(
            () -> {
              ready(h, f);
              h.assertTrue(
                  f.menu().getCarried().getCount() == 36
                      && f.saved.registry().stock(f.cell, f.key).total() == 0,
                  "Left click takes up to one available stack");
            })
        .thenExecute(
            () -> {
              f.player.closeContainer();
              h.assertTrue(
                  f.player.getInventory().countItem(Items.IRON_INGOT) == 100,
                  "Vanilla closure returns cursor items exactly once");
              close(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void capacityCountsEmptyAndInfiniteItemCellsButNotFluidOrPrivateChild(
      GameTestHelper h) {
    var f = setup(h);
    StorageDriveEntity drive = h.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_FLUID)));
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.INFINITE_ITEM)));
    var infinite = CellHandleData.read(drive.cell(3));
    var gateway = new BlockPos(3, 1, 1);
    h.setBlock(
        gateway,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    ((NetworkNodeEntity) h.getBlockEntity(gateway)).commission(f.player.getUUID());
    Net.in(h).cable(new BlockPos(4, 1, 1));
    var childDrive = Net.in(h).drive(new BlockPos(5, 1, 1), CellTier.INFINITE_ITEM);
    var child = CellHandleData.read(childDrive.cell(0));
    String used = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(100)).toString();
    String capacity = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(16384)).toString();
    h.startSequence()
        .thenWaitUntil(
            () -> {
              h.assertTrue(
                  f.terminal.canUse(f.player) && f.saved.registry().stock(child, f.key).loaded(),
                  "Both scopes validate");
            })
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 100);
              f.saved.registry().reserve(f.cell, UUID.randomUUID(), f.key, 50);
              f.saved.registry().insert(infinite, f.key, Long.MAX_VALUE);
              f.saved.registry().insert(child, f.key, 10000);
              var fluid = CellHandleData.read(drive.cell(2));
              var water =
                  new PlatformResourceCodec(f.player.registryAccess())
                      .fluidKey(new FluidStack(Fluids.WATER, 1));
              f.saved.registry().insert(fluid, water, 4000);
              open(h, f);
            })
        .thenWaitUntil(
            () -> {
              ready(h, f);
              var view = f.menu().view();
              h.assertTrue(
                  view.used().equals(used)
                      && view.capacity().equals(capacity)
                      && view.infinite()
                      && !view.external(),
                  "Exact arbitrary-precision totals include empty item cells and reservations, exclude fluid and private child");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var menu = f.menu();
              menu.command(
                  f.player,
                  new TerminalPackets.Command(
                      menu.containerId,
                      menu.session(),
                      menu.view().revision(),
                      TerminalPackets.SEARCH,
                      0,
                      1,
                      "absent",
                      new byte[32]));
            })
        .thenWaitUntil(
            () -> {
              var menu = f.menu();
              menu.broadcastChanges();
              h.assertTrue(
                  menu.view().status() == TerminalPackets.Status.READY
                      && menu.view().rows().isEmpty(),
                  "Search has no matches");
              h.assertTrue(
                  menu.view().used().equals(used) && menu.view().capacity().equals(capacity),
                  "Filtering does not change network capacity");
            })
        .thenExecute(() -> close(f))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 350)
  public static void cursorDepositRetainsPartialRemainderAndStaleFingerprintRefuses(
      GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(
            () ->
                TestMenus.ready(
                    h, f.terminal.canUse(f.player), f.terminal.getBlockPos(), "Network valid"))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 8190);
              open(h, f);
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.menu().setCarried(named("Apple", 10));
              var m = f.menu();
              m.command(
                  f.player,
                  new TerminalPackets.Command(
                      m.containerId,
                      m.session(),
                      m.view().revision(),
                      TerminalPackets.DEPOSIT_CURSOR,
                      0,
                      10,
                      "",
                      new byte[32]));
              h.assertTrue(
                  !m.view().busy() && m.getCarried().getCount() == 10,
                  "Wrong cursor fingerprint refuses before extraction");
            })
        .thenIdle(3)
        .thenIdle(3)
        .thenExecute(() -> command(f, TerminalPackets.DEPOSIT_CURSOR, 0, 10))
        .thenWaitUntil(
            () -> {
              ready(h, f);
              h.assertTrue(
                  f.menu().getCarried().getCount() == 8
                      && f.saved.registry().stock(f.cell, f.key).total() == 8192,
                  "Partial insertion leaves eight real cursor units");
            })
        .thenExecute(() -> close(f))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void closedCursorStagingReturnsToInventoryAndCannotUseReplacementCursor(
      GameTestHelper h) {
    var f = setup(h);
    var runtime = WorldNetworks.get(f.player.server);
    var actor = new NetworkPermissions.Actor(f.player.getUUID(), false);
    var id = UUID.randomUUID();
    WorldStorageTransfers.PlayerInventory[] old = {null};
    WorldStorageTransfers.Result[] results = {null, null};
    h.startSequence()
        .thenWaitUntil(
            () ->
                TestMenus.ready(
                    h, f.terminal.canUse(f.player), f.terminal.getBlockPos(), "Network valid"))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 5);
              open(h, f);
              var menu = f.menu();
              menu.setCarried(named("Apple", 10));
              old[0] = runtime.transfers().inventory(f.player, 36);
              var origin = Positions.of(h.getLevel(), h.absolutePos(TERMINAL));
              var scope = runtime.membership(origin).scope();
              var context =
                  new TransferContext(
                      f.saved.registry().world(),
                      scope.network(),
                      scope.segment(),
                      origin,
                      new TransferContext.Endpoint(
                          TransferContext.Type.PLAYER,
                          f.player.getUUID(),
                          old[0].backing(),
                          old[0].connection(),
                          38),
                      new TransferContext.Endpoint(
                          TransferContext.Type.DIGITAL,
                          f.cell.backing(),
                          f.cell.backing(),
                          f.cell.generation(),
                          0));
              // A deterministic post-extraction rejection leaves ten known real cursor units
              // staged.
              var source =
                  new ResourcePort() {
                    public UUID backingId() {
                      return old[0].backing();
                    }

                    public long simulateInsert(ResourceKey key, long amount) {
                      return 0;
                    }

                    public long insert(ResourceKey key, long amount) {
                      return 0;
                    }

                    public long extract(ResourceKey key, long amount) {
                      return menu.getCarried().split((int) amount).getCount();
                    }
                  };
              var destination =
                  new ResourcePort() {
                    public UUID backingId() {
                      return f.cell.backing();
                    }

                    public long simulateInsert(ResourceKey key, long amount) {
                      return amount;
                    }

                    public long insert(ResourceKey key, long amount) {
                      return 0;
                    }

                    public long extract(ResourceKey key, long amount) {
                      throw new AssertionError("Recovery never extracts destination stock");
                    }
                  };
              var move =
                  f.saved
                      .transfers()
                      .start(
                          id,
                          f.player.getUUID(),
                          f.key,
                          source,
                          destination,
                          10,
                          10,
                          () -> true,
                          context);
              h.assertTrue(
                  move.staged() == 10 && menu.getCarried().isEmpty(),
                  "Known units moved from the real cursor into staging");
              f.player.closeContainer();
              open(h, f);
              f.menu().setCarried(new ItemStack(Items.STONE, 3));
              runtime.transfers().progress(id, actor, true, result -> results[0] = result);
            })
        .thenWaitUntil(() -> h.assertTrue(results[0] != null, "Known cursor recovery callback"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  results[0].transfer().staged() == 0
                      && results[0].transfer().returned() == 10
                      && f.player.getInventory().countItem(Items.IRON_INGOT) == 10
                      && f.menu().getCarried().is(Items.STONE)
                      && f.menu().getCarried().getCount() == 3
                      && f.saved.registry().stock(f.cell, f.key).total() == 5,
                  "Closure recovery returns exactly once to inventory and preserves the replacement cursor and cell");
              f.saved.transfers().retire(id);
              var origin = Positions.of(h.getLevel(), h.absolutePos(TERMINAL));
              runtime
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      actor,
                      origin,
                      new WorldStorageTransfers.Digital(f.cell),
                      old[0],
                      f.key,
                      5,
                      result -> results[1] = result);
            })
        .thenWaitUntil(() -> h.assertTrue(results[1] != null, "Stale cursor endpoint callback"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  results[1].reason() == OperationResult.Reason.DENIED
                      && f.saved.registry().stock(f.cell, f.key).total() == 5
                      && f.menu().getCarried().getCount() == 3,
                  "An old menu endpoint cannot mutate the replacement cursor");
              close(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 350)
  public static void closedAndRevokedCursorRequestsCannotExtract(GameTestHelper h) {
    var f = setup(h);
    TerminalMenu[] closed = {null};
    h.startSequence()
        .thenWaitUntil(
            () ->
                TestMenus.ready(
                    h, f.terminal.canUse(f.player), f.terminal.getBlockPos(), "Network valid"))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 100);
              open(h, f);
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              closed[0] = f.menu();
              command(f, TerminalPackets.TAKE_CURSOR, 0, 64);
              f.player.closeContainer();
            })
        .thenIdle(10)
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.saved.registry().stock(f.cell, f.key).total() == 100,
                  "Closing cancels queued cursor pickup before extraction");
              closed[0].command(
                  f.player,
                  new TerminalPackets.Command(
                      closed[0].containerId,
                      closed[0].session(),
                      closed[0].view().revision(),
                      TerminalPackets.TAKE_CURSOR,
                      0,
                      64,
                      "",
                      new byte[32]));
              open(h, f);
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              command(f, TerminalPackets.TAKE_CURSOR, 0, 64);
              var guest = TestPlayers.mock(h);
              var actor = new NetworkPermissions.Actor(f.player.getUUID(), false);
              f.saved.network(f.root.handle().id()).transferOwnership(actor, guest.getUUID());
              f.saved
                  .network(f.root.handle().id())
                  .setGrants(
                      new NetworkPermissions.Actor(guest.getUUID(), false),
                      f.player.getUUID(),
                      Set.of(NetworkPermissions.Permission.VIEW));
            })
        .thenIdle(10)
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.saved.registry().stock(f.cell, f.key).total() == 100
                      && f.menu().getCarried().isEmpty(),
                  "Live withdrawal revocation stops queued cursor mutation");
              close(f);
            })
        .thenSucceed();
  }
}
