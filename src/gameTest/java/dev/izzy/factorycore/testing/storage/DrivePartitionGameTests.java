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
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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

  private record Fixture(ServerPlayer player, StorageDriveEntity drive, TerminalEntity terminal) {}

  private DrivePartitionGameTests() {}

  private static Fixture setup(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    Net.in(h, player.getUUID()).controller(ROOT);
    var drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    return new Fixture(player, drive, h.getBlockEntity(TERMINAL));
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
    var m = terminal(f);
    m.command(
        f.player,
        new TerminalPackets.Command(
            m.containerId,
            m.session(),
            m.view().revision(),
            TerminalPackets.DEPOSIT,
            0,
            count,
            "",
            TerminalPackets.fingerprint(key(h, item))));
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
        .thenExecute(() -> h.useBlock(TERMINAL, f.player))
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
            () ->
                h.assertTrue(
                    f.player.getInventory().getItem(0).isEmpty()
                        && stored(h, f, 0, Items.COBBLESTONE) == 7,
                    "Cobblestone goes into the partitioned cell"))
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
        .thenExecute(() -> h.useBlock(TERMINAL, f.player))
        .thenIdle(GAP)
        .thenExecute(() -> deposit(h, f, Items.OAK_LOG, 5))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    stored(h, f, 1, Items.OAK_LOG) == 5 && stored(h, f, 0, Items.OAK_LOG) == 0,
                    "With an open cell, logs go there and never into the partitioned one"))
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
