package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.CellSummary;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.DriveBay;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveBlock;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.testing.Net;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q72: a drive bay's lamp follows its cell's fill and the network (online, near full at three
 * quarters, full, offline), and a cell taken out carries a summary its tooltip shows: stored of
 * capacity, percent, types and its largest entries.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class DriveFeedbackGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1), DRIVE = new BlockPos(2, 1, 1);

  private DriveFeedbackGameTests() {}

  private static DriveBay bay(GameTestHelper h) {
    return h.getBlockState(DRIVE).getValue(StorageDriveBlock.BAYS.get(0));
  }

  private static void put(GameTestHelper h, StorageDriveEntity drive, Item item, long amount) {
    DeviceSavedData.get(h.getLevel().getServer())
        .registry()
        .insert(
            CellHandleData.read(drive.cell(0)),
            new PlatformResourceCodec(h.getLevel().registryAccess()).itemKey(new ItemStack(item)),
            amount);
  }

  /** The tooltip line with {@code key}, or null. */
  private static TranslatableContents line(List<Component> lines, String key) {
    for (var line : lines)
      if (line.getContents() instanceof TranslatableContents contents
          && contents.getKey().equals(key)) return contents;
    return null;
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void bayLampsFollowFillAndNetworkAndARemovedCellSummarizesItsContents(
      GameTestHelper h) {
    var net = Net.in(h, UUID.randomUUID());
    net.controller(ROOT);
    var drive = net.drive(DRIVE, CellTier.STARTER_ITEM);
    long capacity = CellTier.STARTER_ITEM.capacity();
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(DRIVE))
        .thenWaitUntil(
            () -> h.assertTrue(bay(h) == DriveBay.ONLINE, "Empty cell online: " + bay(h)))
        .thenExecute(
            () -> {
              put(h, drive, Items.COBBLESTONE, capacity * 3 / 4 - 100);
              put(h, drive, Items.OAK_LOG, 60);
              put(h, drive, Items.IRON_INGOT, 40);
            })
        .thenWaitUntil(
            () -> h.assertTrue(bay(h) == DriveBay.NEAR_FULL, "Three quarters: near full " + bay(h)))
        .thenExecute(() -> put(h, drive, Items.COBBLESTONE, capacity / 4))
        .thenWaitUntil(() -> h.assertTrue(bay(h) == DriveBay.FULL, "No room left: full " + bay(h)))
        .thenExecute(() -> h.setBlock(ROOT, Blocks.AIR))
        .thenWaitUntil(
            () -> h.assertTrue(bay(h) == DriveBay.OFFLINE, "Controller gone: offline " + bay(h)))
        .thenExecute(
            () -> {
              var cell = drive.removeCell(0);
              h.assertTrue(bay(h) == DriveBay.EMPTY, "Taking the cell out empties the bay");
              var summary = cell.get(StorageContent.SUMMARY.get());
              h.assertTrue(
                  summary != null
                      && summary.stored() == capacity
                      && summary.capacity() == capacity
                      && summary.percent() == 100
                      && summary.types() == 3,
                  "The cell summarizes what it holds: " + summary);
              h.assertTrue(
                  summary
                      .entries()
                      .equals(
                          List.of(
                              new CellSummary.Entry("minecraft:cobblestone", false, capacity - 100),
                              new CellSummary.Entry("minecraft:oak_log", false, 60),
                              new CellSummary.Entry("minecraft:iron_ingot", false, 40))),
                  "Entries, largest first: " + summary.entries());
              var lines =
                  cell.getTooltipLines(
                      Item.TooltipContext.of(h.getLevel()), null, TooltipFlag.NORMAL);
              var stored = line(lines, "tooltip.factorycore.cell_stored");
              h.assertTrue(
                  stored != null
                      && List.of(stored.getArgs()).equals(List.of(capacity, capacity, 100, 3)),
                  "The tooltip reads stored / capacity, percent and types: " + lines);
              h.assertTrue(
                  cell.getTooltipImage().orElse(null) == summary,
                  "The tooltip draws the entries as icons");
            })
        .thenSucceed();
  }

  /**
   * Q78: a creative player's copy of a stored cell becomes a fresh, empty, unbound cell, and of two
   * copies of a loose cell only the later one does. The original keeps its ledger and stock; a
   * survival player's inventory is left alone.
   */
  @GameTest(template = "empty", timeoutTicks = 200)
  public static void creativeCopyOfAStoredCellIsAFreshCell(GameTestHelper h) {
    var player = dev.izzy.factorycore.testing.TestPlayers.mock(h);
    dev.izzy.factorycore.testing.TestPlayers.connect(player);
    Net.in(h, player.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    var original = CellHandleData.read(drive.cell(0));
    var registry = DeviceSavedData.get(h.getLevel().getServer()).registry();
    var cobble =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.COBBLESTONE));
    var inventory = player.getInventory();
    var network = Net.in(h);
    h.startSequence()
        .thenWaitUntil(() -> network.assertValid(DRIVE))
        .thenExecute(
            () -> {
              put(h, drive, Items.COBBLESTONE, 40);
              // A survival player holding a copy is not touched (they cannot clone).
              player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
              inventory.setItem(0, drive.cell(0).copy());
              inventory.tick();
              h.assertTrue(
                  original.equals(CellHandleData.read(inventory.getItem(0))),
                  "A survival inventory is left alone");
              // The creative clone of a cell in a drive bay.
              player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
              inventory.tick();
              var copy = inventory.getItem(0);
              h.assertTrue(
                  copy.getItem() instanceof dev.izzy.factorycore.platform.storage.CellItem
                      && CellHandleData.read(copy) == null
                      && !copy.has(StorageContent.SUMMARY.get()),
                  "The copy is a fresh, unbound cell");
              h.assertTrue(
                  original.equals(CellHandleData.read(drive.cell(0))),
                  "The stored cell keeps its ledger");
              h.assertTrue(
                  registry.stock(original, cobble).total() == 40,
                  "The stored cell keeps its stock: " + registry.stock(original, cobble));
              // Two copies of a cell taken out: the later one is fresh.
              var removed = drive.removeCell(0);
              inventory.setItem(0, removed);
              inventory.setItem(1, removed.copy());
              inventory.tick();
              // Taking a cell out moves its handle to a new generation; the ledger is the same.
              var first = CellHandleData.read(inventory.getItem(0));
              h.assertTrue(
                  first != null
                      && first.backing().equals(original.backing())
                      && CellHandleData.read(inventory.getItem(1)) == null,
                  "The first keeps the ledger, the second is fresh");
              player.connection.disconnect(Component.literal("Fresh cell fixture complete"));
            })
        .thenSucceed();
  }
}
