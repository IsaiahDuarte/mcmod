package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.DriveMenu;
import dev.izzy.factorycore.platform.storage.DrivePackets;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalEntity;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * B3: a cell partition limits what the network puts into that cell, travels with the cell through
 * an upgrade, and never hides or locks what the cell already holds.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class DrivePartitionGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      TERMINAL = new BlockPos(1, 1, 2);

  /** Ticks between commands so the session's command rate gate admits each one. */
  private static final int GAP = 5;

  /** {@code offered} holds the test tick of the last deposit sent (see {@link #offer}). */
  private record Fixture(
      ServerPlayer player, StorageDriveEntity drive, TerminalEntity terminal, long[] offered) {}

  private DrivePartitionGameTests() {}

  private static Fixture setup(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    Net.in(h, player.getUUID()).controller(ROOT);
    var drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    return new Fixture(player, drive, h.getBlockEntity(TERMINAL), new long[] {-GAP - 1});
  }

  private static ResourceKey key(GameTestHelper h, Item item) {
    return new PlatformResourceCodec(h.getLevel().registryAccess()).itemKey(new ItemStack(item));
  }

  private static long stored(GameTestHelper h, Fixture f, int slot, Item item) {
    return DeviceSavedData.get(h.getLevel().getServer())
        .registry()
        .stock(CellHandleData.read(f.drive.cell(slot)), key(h, item))
        .total();
  }

  private static TerminalMenu terminal(Fixture f) {
    return (TerminalMenu) f.player.containerMenu;
  }

  private static void deposit(GameTestHelper h, Fixture f, Item item, int count) {
    f.player.getInventory().setItem(0, new ItemStack(item, count));
    offer(h, f, item);
  }

  /**
   * Opens the terminal (again, if a network rebuild closed it) and asserts its view is ready. A
   * partition or cell change rebuilds the network, which can close an open terminal a few ticks
   * later; how many depends on server load, so steps wait for this instead of a fixed gap.
   */
  private static void ready(GameTestHelper h, Fixture f) {
    if (!(f.player.containerMenu instanceof TerminalMenu)) h.useBlock(TERMINAL, f.player);
    h.assertTrue(f.player.containerMenu instanceof TerminalMenu, "Terminal open");
    terminal(f).broadcastChanges();
    h.assertTrue(terminal(f).view().status() != TerminalPackets.Status.LOADING, "Terminal loaded");
  }

  /**
   * Deposits what slot 0 still holds of {@code item} through a ready terminal, at most once per
   * {@link #GAP} ticks so the session's rate gate admits it. Safe to repeat in a waiting step: an
   * emptied slot sends nothing.
   */
  private static void offer(GameTestHelper h, Fixture f, Item item) {
    var held = f.player.getInventory().getItem(0);
    if (h.getTick() - f.offered()[0] <= GAP) return;
    if (!held.is(item) || !(f.player.containerMenu instanceof TerminalMenu m)) {
      if (held.is(item) && !(f.player.containerMenu instanceof TerminalMenu))
        h.useBlock(TERMINAL, f.player);
      return;
    }
    m.command(
        f.player,
        new TerminalPackets.Command(
            m.containerId,
            m.session(),
            m.view().revision(),
            TerminalPackets.DEPOSIT,
            0,
            held.getCount(),
            "",
            TerminalPackets.fingerprint(key(h, item))));
    f.offered()[0] = h.getTick();
    m.broadcastChanges();
  }

  private static void end(Fixture f) {
    f.player.closeContainer();
    f.player.connection.disconnect(Component.literal("Drive partition fixture complete"));
  }

  @GameTest(template = "empty", timeoutTicks = 800)
  public static void partitionLimitsInsertsSurvivesUpgradeAndKeepsContentsReachable(
      GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.terminal.canUse(f.player)
                        && DeviceSavedData.get(h.getLevel().getServer())
                            .registry()
                            .stock(CellHandleData.read(f.drive.cell(0)), key(h, Items.DIRT))
                            .loaded(),
                    "Network and cell ready"))
        .thenExecute(
            () -> {
              // Dirt was stored before the partition; it must stay reachable afterwards.
              DeviceSavedData.get(h.getLevel().getServer())
                  .registry()
                  .insert(CellHandleData.read(f.drive.cell(0)), key(h, Items.DIRT), 4);
              // The partition is edited through the Drive screen's own command.
              h.useBlock(DRIVE, f.player);
              var menu = (DriveMenu) f.player.containerMenu;
              menu.broadcastChanges();
              menu.filter(
                  f.player,
                  new DrivePackets.FilterCommand(
                      menu.containerId,
                      menu.view().session(),
                      0,
                      DrivePackets.FilterCommand.REGISTRY,
                      0,
                      "minecraft:cobblestone"));
              var partition = f.drive.cell(0).get(StorageContent.PARTITION.get());
              h.assertTrue(
                  partition != null
                      && partition.filter().mode() == ResourceFilter.Mode.INCLUDE
                      && partition.filter().terms().size() == 1,
                  "The first slot makes a cobblestone-only partition: " + partition);
              f.player.closeContainer();
            })
        .thenIdle(GAP)
        .thenWaitUntil(() -> ready(h, f))
        .thenIdle(GAP)
        .thenExecute(() -> deposit(h, f, Items.OAK_LOG, 5))
        .thenIdle(20)
        .thenExecute(
            () ->
                h.assertTrue(
                    f.player.getInventory().getItem(0).getCount() == 5
                        && stored(h, f, 0, Items.OAK_LOG) == 0,
                    "Logs are refused by the only cell, so they stay with the player"))
        .thenExecute(() -> deposit(h, f, Items.COBBLESTONE, 7))
        .thenWaitUntil(
            () -> {
              offer(h, f, Items.COBBLESTONE);
              h.assertTrue(
                  f.player.getInventory().getItem(0).isEmpty()
                      && stored(h, f, 0, Items.COBBLESTONE) == 7,
                  "Cobblestone goes into the partitioned cell");
            })
        .thenExecute(
            () -> {
              f.player.closeContainer();
              f.drive.installCell(1, new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    DeviceSavedData.get(h.getLevel().getServer())
                            .registry()
                            .stock(CellHandleData.read(f.drive.cell(1)), key(h, Items.OAK_LOG))
                            .loaded()
                        && f.terminal.canUse(f.player),
                    "Second cell online"))
        .thenWaitUntil(() -> ready(h, f))
        .thenIdle(GAP)
        .thenExecute(() -> deposit(h, f, Items.OAK_LOG, 5))
        .thenWaitUntil(
            () -> {
              offer(h, f, Items.OAK_LOG);
              h.assertTrue(
                  stored(h, f, 1, Items.OAK_LOG) == 5 && stored(h, f, 0, Items.OAK_LOG) == 0,
                  "With an open cell, logs go there and never into the partitioned one");
            })
        .thenWaitUntil(
            () -> {
              terminal(f).broadcastChanges();
              var rows = terminal(f).view().rows();
              h.assertTrue(
                  rows.stream().anyMatch(row -> row.icon().equals("minecraft:dirt")),
                  "Dirt outside the partition is still listed: " + rows);
            })
        .thenIdle(GAP)
        .thenExecute(
            () -> {
              var rows = terminal(f).view().rows();
              int dirt = -1;
              for (int i = 0; i < rows.size(); i++)
                if (rows.get(i).icon().equals("minecraft:dirt")) dirt = i;
              f.player.getInventory().clearContent();
              var m = terminal(f);
              m.command(
                  f.player,
                  new TerminalPackets.Command(
                      m.containerId,
                      m.session(),
                      m.view().revision(),
                      TerminalPackets.WITHDRAW,
                      dirt,
                      4,
                      "",
                      new byte[32]));
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.player.getInventory().countItem(Items.DIRT) == 4
                        && stored(h, f, 0, Items.DIRT) == 0,
                    "Dirt the partition would refuse can still be taken out"))
        .thenExecute(
            () -> {
              f.player.closeContainer();
              var before = f.drive.cell(0).get(StorageContent.PARTITION.get());
              var result = f.drive.upgradeCell(0, 1);
              var after = f.drive.cell(0).get(StorageContent.PARTITION.get());
              h.assertTrue(
                  result.upgraded() && result.portable().isEmpty(), "Cell upgraded in place");
              h.assertTrue(
                  after != null && after.equals(before), "The partition survives the upgrade");
              h.assertTrue(
                  stored(h, f, 0, Items.COBBLESTONE) == 7, "Contents survive the upgrade too");
              end(f);
            })
        .thenSucceed();
  }

  /**
   * B4: a priority-10 drive with a cobblestone partition is filled before a priority-0 chest; other
   * items skip it. The priority is set through the Drive screen and saved with the drive.
   */
  @GameTest(template = "empty", timeoutTicks = 800)
  public static void drivePriorityFillsThePartitionedCellBeforeAChest(GameTestHelper h) {
    var f = setup(h);
    var chestPort = new BlockPos(0, 1, 1);
    var chest = new BlockPos(0, 1, 0);
    h.setBlock(
        chestPort,
        dev.izzy.factorycore.platform.storage.InterfaceContent.state(
            dev.izzy.factorycore.platform.storage.InterfaceKind.STORAGE,
            net.minecraft.core.Direction.NORTH));
    dev.izzy.factorycore.platform.storage.ConnectorEntity port = h.getBlockEntity(chestPort);
    port.place(new ItemStack(dev.izzy.factorycore.platform.storage.InterfaceContent.ITEM.get()));
    h.setBlock(chest, net.minecraft.world.level.block.Blocks.CHEST);
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.terminal.canUse(f.player)
                        && port.canUse(f.player)
                        && DeviceSavedData.get(h.getLevel().getServer())
                            .registry()
                            .stock(CellHandleData.read(f.drive.cell(0)), key(h, Items.DIRT))
                            .loaded(),
                    "Drive, chest and terminal ready"))
        .thenExecute(
            () -> {
              h.useBlock(DRIVE, f.player);
              var menu = (DriveMenu) f.player.containerMenu;
              menu.broadcastChanges();
              menu.filter(
                  f.player,
                  new DrivePackets.FilterCommand(
                      menu.containerId,
                      menu.view().session(),
                      0,
                      DrivePackets.FilterCommand.REGISTRY,
                      0,
                      "minecraft:cobblestone"));
            })
        .thenIdle(GAP)
        .thenExecute(
            () -> {
              var menu = (DriveMenu) f.player.containerMenu;
              menu.filter(
                  f.player,
                  new DrivePackets.FilterCommand(
                      menu.containerId,
                      menu.view().session(),
                      0,
                      DrivePackets.FilterCommand.PRIORITY,
                      0,
                      "10"));
              h.assertTrue(f.drive.priority() == 10, "The screen sets the drive priority");
              var saved = f.drive.saveWithoutMetadata(h.getLevel().registryAccess());
              h.assertTrue(
                  saved.getInt("CellSchema") == 2 && saved.getInt("Priority") == 10,
                  "A nonzero priority is saved with the drive: " + saved);
              f.drive.loadWithComponents(saved, h.getLevel().registryAccess());
              h.assertTrue(
                  f.drive.priority() == 10
                      && f.drive.diagnostic().isEmpty()
                      && f.drive.cell(0).get(StorageContent.PARTITION.get()) != null,
                  "Reloading restores the priority and the partitioned cell");
              f.player.closeContainer();
            })
        .thenIdle(GAP)
        .thenWaitUntil(() -> ready(h, f))
        .thenIdle(GAP)
        .thenExecute(() -> deposit(h, f, Items.COBBLESTONE, 12))
        .thenWaitUntil(
            () -> {
              offer(h, f, Items.COBBLESTONE);
              Container box = h.getBlockEntity(chest);
              h.assertTrue(
                  stored(h, f, 0, Items.COBBLESTONE) == 12 && box.isEmpty(),
                  "Priority 10 puts cobblestone in the partitioned cell, not the chest");
            })
        .thenIdle(GAP)
        .thenExecute(() -> deposit(h, f, Items.OAK_LOG, 5))
        .thenWaitUntil(
            () -> {
              offer(h, f, Items.OAK_LOG);
              Container box = h.getBlockEntity(chest);
              int logs = 0;
              for (int slot = 0; slot < box.getContainerSize(); slot++)
                if (box.getItem(slot).is(Items.OAK_LOG)) logs += box.getItem(slot).getCount();
              h.assertTrue(
                  logs == 5 && stored(h, f, 0, Items.OAK_LOG) == 0,
                  "Logs skip the partitioned cell and go to the chest");
            })
        .thenExecute(() -> end(f))
        .thenSucceed();
  }

  /** Q23: "From contents" replaces the slots with the cell's stored types. */
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void fromContentsPartitionsTheCellToWhatItHolds(GameTestHelper h) {
    var f = setup(h);
    Runnable fromContents =
        () -> {
          if (!(f.player.containerMenu instanceof DriveMenu)) h.useBlock(DRIVE, f.player);
          var menu = (DriveMenu) f.player.containerMenu;
          menu.broadcastChanges();
          menu.filter(
              f.player,
              new DrivePackets.FilterCommand(
                  menu.containerId,
                  menu.view().session(),
                  0,
                  DrivePackets.FilterCommand.CONTENTS,
                  0,
                  ""));
        };
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.terminal.canUse(f.player)
                        && DeviceSavedData.get(h.getLevel().getServer())
                            .registry()
                            .stock(CellHandleData.read(f.drive.cell(0)), key(h, Items.DIRT))
                            .loaded(),
                    "Network and cell ready"))
        .thenExecute(
            () -> {
              fromContents.run();
              h.assertTrue(
                  f.drive.cell(0).get(StorageContent.PARTITION.get()) == null,
                  "An empty cell has no contents to partition by");
              var registry = DeviceSavedData.get(h.getLevel().getServer()).registry();
              var cell = CellHandleData.read(f.drive.cell(0));
              registry.insert(cell, key(h, Items.DIRT), 4);
              registry.insert(cell, key(h, Items.STONE), 9);
              registry.insert(cell, key(h, Items.OAK_LOG), 2);
            })
        .thenIdle(GAP)
        .thenExecute(fromContents)
        .thenIdle(GAP)
        // Twice: the button replaces the slots rather than adding to them.
        .thenExecute(fromContents)
        .thenExecute(
            () -> {
              var partition = f.drive.cell(0).get(StorageContent.PARTITION.get());
              h.assertTrue(
                  partition != null
                      && partition.filter().mode() == ResourceFilter.Mode.INCLUDE
                      && Set.copyOf(partition.filter().terms())
                          .equals(
                              Set.of(
                                  new ResourceFilter.Registry("minecraft:dirt"),
                                  new ResourceFilter.Registry("minecraft:stone"),
                                  new ResourceFilter.Registry("minecraft:oak_log")))
                      && partition.filter().terms().size() == 3,
                  "The partition lists exactly the three stored types: " + partition);
              end(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void partitionKindMustMatchTheCell(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.terminal.canUse(f.player), "Network ready"))
        .thenExecute(
            () -> {
              boolean refused = false;
              try {
                f.drive.setPartition(
                    0,
                    new ResourceFilter(
                        ResourceKind.FLUID,
                        ResourceFilter.Mode.INCLUDE,
                        List.of(new ResourceFilter.Registry("minecraft:water"))));
              } catch (IllegalArgumentException expected) {
                refused = true;
              }
              h.assertTrue(
                  refused && f.drive.cell(0).get(StorageContent.PARTITION.get()) == null,
                  "A fluid partition is refused on an item cell");
              end(f);
            })
        .thenSucceed();
  }
}
