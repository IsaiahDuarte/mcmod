package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalEntity;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
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

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class TerminalCraftingGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      TERMINAL = new BlockPos(1, 1, 2);

  private record Fixture(
      ServerPlayer player,
      TerminalEntity terminal,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      PlatformResourceCodec codec) {}

  private TerminalCraftingGameTests() {}

  @SuppressWarnings("removal") // Pinned helper performs actual PlayerList authentication.
  private static Fixture setup(GameTestHelper helper) {
    var player = helper.makeMockServerPlayerInLevel();
    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
        player.connection.getConnection());
    player.moveTo(Vec3.atCenterOf(helper.absolutePos(ROOT)));
    player.getInventory().clearContent();
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(player.getUUID());
    helper.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    helper.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    return new Fixture(
        player,
        helper.getBlockEntity(TERMINAL),
        DeviceSavedData.get(player.server),
        CellHandleData.read(drive.cell(0)),
        new PlatformResourceCodec(player.registryAccess()));
  }

  private static TerminalMenu menu(Fixture f) {
    return (TerminalMenu) f.player.containerMenu;
  }

  private static void open(GameTestHelper helper, Fixture f) {
    helper.assertTrue(f.terminal.canUse(f.player), "Wired terminal authorizes owner");
    helper.useBlock(TERMINAL, f.player);
    helper.assertTrue(f.player.containerMenu instanceof TerminalMenu, "Native menu opens");
    menu(f).broadcastChanges();
  }

  private static void loaded(GameTestHelper helper, Fixture f) {
    helper.assertTrue(f.player.containerMenu instanceof TerminalMenu, "Terminal still open");
    menu(f).broadcastChanges();
    helper.assertTrue(menu(f).view().status().equals("ready"), "Search ready");
  }

  private static void grid(Fixture f, int action, int cell, int source, int crafts, byte[] print) {
    var m = menu(f);
    m.gridCommand(
        f.player,
        new TerminalCraftingPackets.Command(
            m.containerId,
            m.session(),
            m.view().revision(),
            m.gridView().gridRevision(),
            action,
            cell,
            source,
            crafts,
            print));
    m.broadcastChanges();
  }

  private static void ghost(Fixture f, int cell, int slot) {
    var key = f.codec.itemKey(f.player.getInventory().getItem(slot));
    grid(f, TerminalCraftingPackets.SET_OWN, cell, slot, 1, TerminalPackets.fingerprint(key));
  }

  private static int count(Fixture f, Item item) {
    int total = 0;
    for (int slot = 0; slot < 36; slot++) {
      var stack = f.player.getInventory().getItem(slot);
      if (stack.is(item)) total += stack.getCount();
    }
    return total;
  }

  private static void logout(Fixture f) {
    f.player.closeContainer();
    f.player.connection.disconnect(Component.literal("Crafting fixture complete"));
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void cakeConsumesExactIngredientsAndReturnsBucketRemainders(GameTestHelper h) {
    var f = setup(h);
    var inventory = f.player.getInventory();
    for (int slot = 0; slot < 3; slot++) inventory.setItem(slot, new ItemStack(Items.MILK_BUCKET));
    inventory.setItem(3, new ItemStack(Items.SUGAR, 5));
    inventory.setItem(4, new ItemStack(Items.EGG, 2));
    inventory.setItem(5, new ItemStack(Items.WHEAT, 4));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.terminal.canUse(f.player), "Network ready"))
        .thenExecute(() -> open(h, f))
        .thenWaitUntil(() -> loaded(h, f))
        .thenExecute(
            () -> {
              for (int cell = 0; cell < 3; cell++) ghost(f, cell, 0);
              ghost(f, 3, 3);
              ghost(f, 4, 4);
              ghost(f, 5, 3);
              for (int cell = 6; cell < 9; cell++) ghost(f, cell, 5);
              var view = menu(f).gridView();
              h.assertTrue(
                  view.result().equals("minecraft:cake") && view.resultCount() == 1,
                  "Ghost grid previews the matched recipe: " + view);
              h.assertTrue(
                  count(f, Items.MILK_BUCKET) == 3 && count(f, Items.SUGAR) == 5,
                  "Ghost selections move or hold nothing");
              grid(f, TerminalCraftingPackets.CRAFT, -1, 0, 1, new byte[32]);
              h.assertTrue(
                  count(f, Items.CAKE) == 1
                      && count(f, Items.BUCKET) == 3
                      && count(f, Items.MILK_BUCKET) == 0
                      && count(f, Items.SUGAR) == 3
                      && count(f, Items.EGG) == 1
                      && count(f, Items.WHEAT) == 1
                      && menu(f).gridView().status().equals("crafted"),
                  "One cake, three bucket remainders and exact ingredient use");
            })
        .thenIdle(3)
        .thenExecute(() -> grid(f, TerminalCraftingPackets.CRAFT, -1, 0, 1, new byte[32]))
        .thenWaitUntil(
            () -> {
              menu(f).broadcastChanges();
              h.assertTrue(
                  menu(f).gridView().status().equals("shortage"),
                  "Gathering finds no milk: " + menu(f).gridView().status());
            })
        .thenExecute(
            () -> {
              h.assertTrue(
                  count(f, Items.CAKE) == 1
                      && count(f, Items.SUGAR) == 3
                      && count(f, Items.EGG) == 1
                      && count(f, Items.WHEAT) == 1,
                  "Missing milk stops cleanly without consuming the rest");
              logout(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void fullInventoryRefusesBeforeConsumingAnything(GameTestHelper h) {
    var f = setup(h);
    var inventory = f.player.getInventory();
    inventory.setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
    for (int slot = 1; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.STONE, 64));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.terminal.canUse(f.player), "Network ready"))
        .thenExecute(() -> open(h, f))
        .thenWaitUntil(() -> loaded(h, f))
        .thenExecute(
            () -> {
              ghost(f, 0, 0);
              ghost(f, 3, 0);
              h.assertTrue(
                  menu(f).gridView().result().equals("minecraft:stick"), "Stick recipe matched");
              grid(f, TerminalCraftingPackets.CRAFT, -1, 0, 1, new byte[32]);
              h.assertTrue(
                  count(f, Items.OAK_PLANKS) == 64
                      && count(f, Items.STICK) == 0
                      && menu(f).gridView().status().equals("full"),
                  "No room for output means nothing is consumed");
              inventory.setItem(35, ItemStack.EMPTY);
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              grid(f, TerminalCraftingPackets.CRAFT, -1, 0, 64, new byte[32]);
            })
        .thenWaitUntil(
            () -> {
              menu(f).broadcastChanges();
              h.assertTrue(
                  menu(f).gridView().status().equals("full"),
                  "Gathering finds no planks, then crafting fills the free slot");
            })
        .thenExecute(
            () -> {
              h.assertTrue(
                  count(f, Items.STICK) == 64
                      && count(f, Items.OAK_PLANKS) == 32
                      && menu(f).gridView().crafted() == 16,
                  "Bulk crafting stops cleanly once output space is used: sticks "
                      + count(f, Items.STICK)
                      + " planks "
                      + count(f, Items.OAK_PLANKS));
              logout(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void missingIngredientsAreWithdrawnFromStorageThenCrafted(GameTestHelper h) {
    var f = setup(h);
    var log = f.codec.itemKey(new ItemStack(Items.OAK_LOG));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.terminal.canUse(f.player), "Network ready"))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, log, 5);
              open(h, f);
            })
        .thenWaitUntil(() -> loaded(h, f))
        .thenExecute(
            () -> {
              h.assertTrue(
                  menu(f).view().rows().size() == 1
                      && menu(f).view().rows().getFirst().icon().equals("minecraft:oak_log"),
                  "Network log row visible");
              grid(f, TerminalCraftingPackets.SET_ROW, 0, 0, 1, new byte[32]);
              h.assertTrue(
                  menu(f).gridView().result().equals("minecraft:oak_planks")
                      && f.saved.registry().stock(f.cell, log).total() == 5,
                  "Row ghost previews without moving storage");
              grid(f, TerminalCraftingPackets.CRAFT, -1, 0, 4, new byte[32]);
            })
        .thenWaitUntil(
            () -> {
              menu(f).broadcastChanges();
              h.assertTrue(
                  count(f, Items.OAK_PLANKS) == 16 && menu(f).gridView().status().equals("crafted"),
                  "Gathered logs are crafted: " + menu(f).gridView().status());
            })
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.saved.registry().stock(f.cell, log).total() == 1
                      && count(f, Items.OAK_LOG) == 0,
                  "Exactly four logs left storage and all four were consumed");
            })
        // Crafts share the menu's two-tick command throttle; a same-tick follow-up is ignored.
        .thenIdle(3)
        .thenExecute(() -> grid(f, TerminalCraftingPackets.CRAFT, -1, 0, 3, new byte[32]))
        .thenWaitUntil(
            () -> {
              menu(f).broadcastChanges();
              h.assertTrue(
                  menu(f).gridView().status().equals("partial")
                      && menu(f).gridView().crafted() == 1
                      && count(f, Items.OAK_PLANKS) == 20,
                  "Storage shortage crafts what was gathered: " + menu(f).gridView().status());
            })
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.saved.registry().stock(f.cell, log).total() == 0
                      && count(f, Items.OAK_LOG) == 0,
                  "All five logs became twenty planks");
              logout(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void staleGhostAndRevisionCommandsChangeNothing(GameTestHelper h) {
    var f = setup(h);
    var inventory = f.player.getInventory();
    inventory.setItem(0, new ItemStack(Items.OAK_LOG, 2));
    inventory.setItem(1, new ItemStack(Items.BIRCH_LOG, 2));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.terminal.canUse(f.player), "Network ready"))
        .thenExecute(() -> open(h, f))
        .thenWaitUntil(() -> loaded(h, f))
        .thenExecute(
            () -> {
              var oak = f.codec.itemKey(new ItemStack(Items.OAK_LOG));
              grid(f, TerminalCraftingPackets.SET_OWN, 0, 1, 1, TerminalPackets.fingerprint(oak));
              h.assertTrue(
                  menu(f).gridView().cells().get(0).isEmpty(),
                  "A fingerprint for a different slot item is ignored");
              ghost(f, 0, 0);
              long revision = menu(f).gridView().gridRevision();
              ghost(f, 0, 1);
              var m = menu(f);
              m.gridCommand(
                  f.player,
                  new TerminalCraftingPackets.Command(
                      m.containerId,
                      m.session(),
                      m.view().revision(),
                      revision,
                      TerminalCraftingPackets.CRAFT,
                      -1,
                      0,
                      1,
                      new byte[32]));
              h.assertTrue(
                  count(f, Items.OAK_LOG) == 2
                      && count(f, Items.BIRCH_LOG) == 2
                      && count(f, Items.OAK_PLANKS) == 0,
                  "A craft for an older grid revision is ignored");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              grid(f, TerminalCraftingPackets.CLEAR, -1, 0, 1, new byte[32]);
              h.assertTrue(
                  menu(f).gridView().cells().stream().allMatch(String::isEmpty)
                      && menu(f).gridView().result().isEmpty(),
                  "Clear removes ghosts only");
              ghost(f, 4, 1);
              f.player.closeContainer();
              h.assertTrue(
                  count(f, Items.OAK_LOG) == 2 && count(f, Items.BIRCH_LOG) == 2,
                  "Closing the screen with ghosts set loses or drops nothing");
              f.player.connection.disconnect(Component.literal("Crafting fixture complete"));
            })
        .thenSucceed();
  }
}
