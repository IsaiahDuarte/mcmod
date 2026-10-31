package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.ProcessingContents;
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
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
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

  private static Fixture setup(GameTestHelper helper) {
    var player = TestPlayers.mock(helper);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(helper.absolutePos(ROOT)));
    player.getInventory().clearContent();
    NetworkNodeEntity root = Net.in(helper, player.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(helper).drive(DRIVE, CellTier.STARTER_ITEM);
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
    helper.assertTrue(menu(f).view().status() == TerminalPackets.Status.READY, "Search ready");
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
                      && menu(f).gridView().status() == TerminalCraftingPackets.GridStatus.CRAFTED,
                  "One cake, three bucket remainders and exact ingredient use");
            })
        .thenIdle(3)
        .thenExecute(() -> grid(f, TerminalCraftingPackets.CRAFT, -1, 0, 1, new byte[32]))
        .thenWaitUntil(
            () -> {
              menu(f).broadcastChanges();
              h.assertTrue(
                  menu(f).gridView().status() == TerminalCraftingPackets.GridStatus.SHORTAGE,
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
                      && menu(f).gridView().status() == TerminalCraftingPackets.GridStatus.FULL,
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
                  menu(f).gridView().status() == TerminalCraftingPackets.GridStatus.FULL,
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
                  count(f, Items.OAK_PLANKS) == 16
                      && menu(f).gridView().status() == TerminalCraftingPackets.GridStatus.CRAFTED,
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
                  menu(f).gridView().status() == TerminalCraftingPackets.GridStatus.PARTIAL
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

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void recipeViewerFillSetsGhostCellsByRegistryIdentityOnly(GameTestHelper h) {
    var f = setup(h);
    var planks = "minecraft:oak_planks";
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.terminal.canUse(f.player), "Network ready"))
        .thenExecute(() -> open(h, f))
        .thenWaitUntil(() -> loaded(h, f))
        .thenExecute(
            () -> {
              grid(f, TerminalCraftingPackets.MODE, 1, 0, 1, new byte[32]);
              var m = menu(f);
              m.gridFill(
                  f.player,
                  new TerminalCraftingPackets.Fill(
                      m.containerId,
                      m.session(),
                      List.of(
                          planks,
                          planks,
                          "",
                          planks,
                          planks,
                          "",
                          "",
                          "",
                          "minecraft:not_an_item")));
              m.broadcastChanges();
              var view = m.gridView();
              h.assertTrue(
                  !view.processing()
                      && view.status() == TerminalCraftingPackets.GridStatus.FILLED
                      && view.result().equals("minecraft:crafting_table")
                      && view.cells().get(0).equals(planks)
                      && view.cells().get(8).isEmpty(),
                  "Fill switches to crafting, matches the recipe and clears unknown IDs: " + view);
              h.assertTrue(
                  count(f, Items.OAK_PLANKS) == 0 && count(f, Items.CRAFTING_TABLE) == 0,
                  "A fill moves and creates nothing");
              boolean rejected;
              try {
                new TerminalCraftingPackets.Fill(
                    m.containerId, m.session(), List.of(planks, planks));
                rejected = false;
              } catch (IllegalArgumentException expected) {
                rejected = true;
              }
              h.assertTrue(rejected, "Fills must name exactly nine cells");
              logout(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void fluidRowsEncodeAsProcessingInputsInQuarterBucketUnits(GameTestHelper h) {
    var f = setup(h);
    StorageDriveEntity drive = h.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_FLUID)));
    var tank = CellHandleData.read(drive.cell(1));
    var water = f.codec.fluidKey(new FluidStack(Fluids.WATER, 1));
    var inventory = f.player.getInventory();
    inventory.setItem(0, new ItemStack(Items.STONE));
    inventory.setItem(1, new ItemStack(CraftingContent.BLANK_PATTERN.get()));
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.terminal.canUse(f.player) && f.saved.registry().stock(tank, water).loaded(),
                    "Network ready"))
        .thenExecute(
            () -> {
              f.saved.registry().insert(tank, water, 4_000);
              open(h, f);
            })
        .thenWaitUntil(() -> loaded(h, f))
        .thenExecute(
            () -> {
              var m = menu(f);
              m.command(
                  f.player,
                  new TerminalPackets.Command(
                      m.containerId,
                      m.session(),
                      m.view().revision(),
                      TerminalPackets.KIND,
                      1,
                      1,
                      "",
                      new byte[32]));
              grid(f, TerminalCraftingPackets.SET_ROW, 0, 0, 1, new byte[32]);
            })
        .thenWaitUntil(
            () -> {
              menu(f).broadcastChanges();
              h.assertTrue(
                  menu(f).view().status() == TerminalPackets.Status.READY
                      && menu(f).view().rows().size() == 1,
                  "Fluid view lists water");
            })
        .thenExecute(
            () -> {
              grid(f, TerminalCraftingPackets.SET_ROW, 0, 0, 1, new byte[32]);
              h.assertTrue(
                  menu(f).gridView().cells().get(0).isEmpty(),
                  "Outside processing mode a fluid row is not a grid ingredient");
              grid(f, TerminalCraftingPackets.MODE, 1, 0, 1, new byte[32]);
              grid(f, TerminalCraftingPackets.SET_ROW, 0, 0, 1, new byte[32]);
              grid(f, TerminalCraftingPackets.COUNT, 0, 0, 4, new byte[32]);
              ghost(f, 9, 0);
              h.assertTrue(
                  menu(f).gridView().cells().get(0).equals("minecraft:water")
                      && menu(f).gridView().fluid(0)
                      && !menu(f).gridView().fluid(9)
                      && menu(f).gridView().amount(0) == 1000,
                  "Processing mode accepts the fluid row: " + menu(f).gridView());
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              grid(f, TerminalCraftingPackets.ENCODE, -1, 0, 1, new byte[32]);
              ItemStack encoded = ItemStack.EMPTY;
              for (int slot = 0; slot < 36; slot++)
                if (inventory.getItem(slot).is(CraftingContent.PROCESSING_PATTERN.get()))
                  encoded = inventory.getItem(slot);
              var contents = encoded.get(CraftingContent.PROCESSING.get());
              h.assertTrue(
                  contents != null
                      && contents.inputs().isEmpty()
                      && contents.fluidInputs().size() == 1
                      && contents.fluidInputs().getFirst().getAmount() == 1_000
                      && contents.outputs().getFirst().is(Items.STONE),
                  "Four quarter-bucket units encode exactly 1000 mB water: "
                      + menu(f).gridView().status());
              grid(f, TerminalCraftingPackets.MODE, 0, 0, 1, new byte[32]);
              h.assertTrue(
                  menu(f).gridView().cells().get(0).isEmpty()
                      && menu(f).gridView().fluidMask() == 0,
                  "Leaving processing mode drops fluid cells");
              logout(f);
            })
        .thenSucceed();
  }

  private static int slotOf(Fixture f, Item item) {
    for (int slot = 0; slot < 36; slot++)
      if (f.player.getInventory().getItem(slot).is(item)) return slot;
    return -1;
  }

  /**
   * Q4: an encoded crafting pattern loads back into the Table grid with its Substitutes choice;
   * after a change, Encode rewrites that pattern in its slot without needing a blank.
   */
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void aCraftingPatternLoadsAndEncodeRewritesIt(GameTestHelper h) {
    var f = setup(h);
    var inventory = f.player.getInventory();
    inventory.setItem(0, new ItemStack(Items.OAK_PLANKS));
    inventory.setItem(1, new ItemStack(CraftingContent.BLANK_PATTERN.get()));
    inventory.setItem(2, new ItemStack(Items.OAK_LOG));
    int[] patternSlot = {-1};
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.terminal.canUse(f.player), "Network ready"))
        .thenExecute(() -> open(h, f))
        .thenWaitUntil(() -> loaded(h, f))
        .thenExecute(
            () -> {
              ghost(f, 0, 0);
              ghost(f, 3, 0);
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              grid(f, TerminalCraftingPackets.ENCODE, -1, 1, 1, new byte[32]);
              patternSlot[0] = slotOf(f, CraftingContent.CRAFTING_PATTERN.get());
              h.assertTrue(
                  patternSlot[0] >= 0 && count(f, CraftingContent.BLANK_PATTERN.get()) == 0,
                  "A stick pattern with substitutes is encoded on the only blank");
              grid(f, TerminalCraftingPackets.CLEAR, -1, 0, 1, new byte[32]);
              grid(f, TerminalCraftingPackets.LOAD, -1, patternSlot[0], 1, new byte[32]);
              var view = menu(f).gridView();
              h.assertTrue(
                  view.status() == TerminalCraftingPackets.GridStatus.LOADED_SUBSTITUTES
                      && !view.processing()
                      && view.cells().get(0).equals("minecraft:oak_planks")
                      && view.cells().get(3).equals("minecraft:oak_planks")
                      && view.cells().get(1).isEmpty()
                      && view.result().equals("minecraft:stick"),
                  "Loading restores the Table grid and its substitutes: " + view);
              grid(f, TerminalCraftingPackets.CLEAR, 3, 0, 1, new byte[32]);
              ghost(f, 0, 2);
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              grid(f, TerminalCraftingPackets.ENCODE, -1, 0, 1, new byte[32]);
              var rewritten = inventory.getItem(patternSlot[0]);
              var contents = rewritten.get(CraftingContent.PATTERN.get());
              h.assertTrue(
                  menu(f).gridView().status() == TerminalCraftingPackets.GridStatus.REWRITTEN
                      && contents != null
                      && contents.output().is(Items.OAK_PLANKS)
                      && !contents.substitutes()
                      && count(f, CraftingContent.CRAFTING_PATTERN.get()) == 1,
                  "Encode rewrites the loaded pattern in place, without a blank: "
                      + menu(f).gridView().status());
              logout(f);
            })
        .thenSucceed();
  }

  /**
   * Q4: a processing pattern loads as Inputs and Outputs with item counts and quarter-bucket fluid
   * units; a changed count is rewritten. A fluid amount the grid cannot show is refused and the
   * grid is left as it was.
   */
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void aProcessingPatternLoadsWithAmountsAndIsRewritten(GameTestHelper h) {
    var f = setup(h);
    var inventory = f.player.getInventory();
    var declared = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
    declared.set(
        CraftingContent.PROCESSING.get(),
        new ProcessingContents(
            UUID.randomUUID(),
            1,
            0,
            List.of(new ItemStack(Items.RAW_IRON, 2)),
            List.of(new ItemStack(Items.IRON_INGOT, 2)),
            List.of(new FluidStack(Fluids.WATER, 500)),
            List.of()));
    var odd = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
    odd.set(
        CraftingContent.PROCESSING.get(),
        new ProcessingContents(
            UUID.randomUUID(),
            1,
            0,
            List.of(new ItemStack(Items.SAND)),
            List.of(new ItemStack(Items.GLASS)),
            List.of(new FluidStack(Fluids.WATER, 300)),
            List.of()));
    inventory.setItem(4, declared);
    inventory.setItem(5, odd);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.terminal.canUse(f.player), "Network ready"))
        .thenExecute(() -> open(h, f))
        .thenWaitUntil(() -> loaded(h, f))
        .thenExecute(
            () -> {
              grid(f, TerminalCraftingPackets.LOAD, -1, 4, 1, new byte[32]);
              var view = menu(f).gridView();
              h.assertTrue(
                  view.status() == TerminalCraftingPackets.GridStatus.LOADED
                      && view.processing()
                      && view.cells().get(0).equals("minecraft:raw_iron")
                      && view.amount(0) == 2
                      && view.cells().get(1).equals("minecraft:water")
                      && view.fluid(1)
                      && view.amount(1) == 500
                      && view.cells().get(9).equals("minecraft:iron_ingot")
                      && view.amount(9) == 2,
                  "Inputs and outputs load with their amounts: " + view);
              grid(f, TerminalCraftingPackets.LOAD, -1, 5, 1, new byte[32]);
              var refused = menu(f).gridView();
              h.assertTrue(
                  refused.status() == TerminalCraftingPackets.GridStatus.UNSUPPORTED
                      && refused.cells().get(0).equals("minecraft:raw_iron"),
                  "300 mB is not whole grid units: refused, grid unchanged: " + refused);
              grid(f, TerminalCraftingPackets.COUNT, 9, 0, 3, new byte[32]);
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              grid(f, TerminalCraftingPackets.ENCODE, -1, 0, 1, new byte[32]);
              var contents = inventory.getItem(4).get(CraftingContent.PROCESSING.get());
              h.assertTrue(
                  menu(f).gridView().status() == TerminalCraftingPackets.GridStatus.REWRITTEN
                      && contents != null
                      && contents.outputs().getFirst().getCount() == 3
                      && contents.inputs().getFirst().getCount() == 2
                      && contents.fluidInputs().getFirst().getAmount() == 500,
                  "The changed output count is rewritten into the same pattern: "
                      + menu(f).gridView().status());
              logout(f);
            })
        .thenSucceed();
  }

  /**
   * Q4: an encoded pattern alone in a crafting grid makes a blank, and sneak-use in the air wipes
   * it to a blank too.
   */
  @GameTest(template = "empty")
  public static void anEncodedPatternWipesToABlank(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    var encoded = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
    encoded.set(
        CraftingContent.PROCESSING.get(),
        new ProcessingContents(
            UUID.randomUUID(),
            1,
            0,
            List.of(new ItemStack(Items.SAND)),
            List.of(new ItemStack(Items.GLASS))));
    var input = CraftingInput.of(1, 1, List.of(encoded.copy()));
    var recipe =
        h.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, h.getLevel());
    h.assertTrue(
        recipe.isPresent()
            && recipe
                .get()
                .value()
                .assemble(input, h.getLevel().registryAccess())
                .is(CraftingContent.BLANK_PATTERN.get()),
        "A pattern alone in a crafting grid makes a blank");
    player.setItemInHand(InteractionHand.MAIN_HAND, encoded);
    player.setShiftKeyDown(true);
    var wiped = encoded.getItem().use(h.getLevel(), player, InteractionHand.MAIN_HAND);
    h.assertTrue(
        wiped.getObject().is(CraftingContent.BLANK_PATTERN.get()),
        "Sneak-use in the air wipes it to a blank");
    player.setShiftKeyDown(false);
    var kept = encoded.getItem().use(h.getLevel(), player, InteractionHand.MAIN_HAND);
    h.assertTrue(kept.getObject() == encoded, "A plain use leaves the pattern alone");
    h.succeed();
  }

  private static void command(Fixture f, int action, int selected, int amount, byte[] print) {
    var m = menu(f);
    m.command(
        f.player,
        new TerminalPackets.Command(
            m.containerId, m.session(), m.view().revision(), action, selected, amount, "", print));
    m.broadcastChanges();
  }

  private static List<String> icons(Fixture f) {
    return menu(f).view().rows().stream().map(TerminalPackets.Row::icon).toList();
  }

  /**
   * Q109: planks crafted in the grid and put back lead the Items grid ahead of the name order,
   * windows over the index still show each row once, and the pin ends when the screen closes.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void craftedItemsLeadTheGridUntilClosed(GameTestHelper h) {
    var f = setup(h);
    var log = f.codec.itemKey(new ItemStack(Items.OAK_LOG));
    var planks = f.codec.itemKey(new ItemStack(Items.OAK_PLANKS));
    var named =
        List.of(
            "minecraft:acacia_log", "minecraft:apple", "minecraft:birch_log", "minecraft:oak_log");
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.terminal.canUse(f.player), "Network ready"))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, log, 5);
              f.saved
                  .registry()
                  .insert(f.cell, f.codec.itemKey(new ItemStack(Items.ACACIA_LOG)), 3);
              f.saved.registry().insert(f.cell, f.codec.itemKey(new ItemStack(Items.APPLE)), 2);
              f.saved.registry().insert(f.cell, f.codec.itemKey(new ItemStack(Items.BIRCH_LOG)), 1);
              open(h, f);
            })
        .thenWaitUntil(() -> loaded(h, f))
        .thenExecute(
            () -> {
              h.assertTrue(icons(f).equals(named), "Name order before crafting: " + icons(f));
              grid(f, TerminalCraftingPackets.SET_ROW, 0, 3, 1, new byte[32]);
              grid(f, TerminalCraftingPackets.CRAFT, -1, 0, 1, new byte[32]);
            })
        .thenWaitUntil(() -> h.assertTrue(count(f, Items.OAK_PLANKS) == 4, "Four planks crafted"))
        .thenIdle(3)
        .thenExecute(
            () -> {
              int slot =
                  f.player.getInventory().findSlotMatchingItem(new ItemStack(Items.OAK_PLANKS));
              command(f, TerminalPackets.DEPOSIT, slot, 4, TerminalPackets.fingerprint(planks));
            })
        .thenWaitUntil(
            () -> {
              menu(f).broadcastChanges();
              h.assertTrue(
                  icons(f).size() == 5 && icons(f).getFirst().equals("minecraft:oak_planks"),
                  "Crafted planks lead the grid: " + icons(f));
            })
        .thenIdle(3)
        .thenExecute(() -> command(f, TerminalPackets.WINDOW, 3, 2, new byte[32]))
        .thenWaitUntil(
            () -> {
              menu(f).broadcastChanges();
              h.assertTrue(
                  menu(f).view().offset() == 3
                      && icons(f).equals(List.of("minecraft:birch_log", "minecraft:oak_log"))
                      && menu(f).view().total() == 5,
                  "A window after the pin continues the name order: " + icons(f));
            })
        .thenExecute(
            () -> {
              f.player.closeContainer();
              open(h, f);
            })
        .thenWaitUntil(() -> loaded(h, f))
        .thenExecute(
            () -> {
              h.assertTrue(
                  icons(f).getFirst().equals("minecraft:acacia_log")
                      && icons(f).getLast().equals("minecraft:oak_planks"),
                  "After reopening the name order returns: " + icons(f));
              logout(f);
            })
        .thenSucceed();
  }

  /**
   * Q112: a typed amount sets a processing slot's count, and Main swaps a later output into first
   * place, so the encoded pattern makes it and keeps the old main output as a byproduct.
   */
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void aProcessingPatternTakesTypedAmountsAndAChosenMainOutput(GameTestHelper h) {
    var f = setup(h);
    var inventory = f.player.getInventory();
    inventory.setItem(0, new ItemStack(Items.RAW_IRON));
    inventory.setItem(1, new ItemStack(CraftingContent.BLANK_PATTERN.get()));
    inventory.setItem(2, new ItemStack(Items.IRON_INGOT));
    inventory.setItem(3, new ItemStack(Items.STONE));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.terminal.canUse(f.player), "Network ready"))
        .thenExecute(() -> open(h, f))
        .thenWaitUntil(() -> loaded(h, f))
        .thenExecute(
            () -> {
              grid(f, TerminalCraftingPackets.MODE, 1, 0, 1, new byte[32]);
              ghost(f, 0, 0);
              grid(f, TerminalCraftingPackets.COUNT, 0, 0, 3, new byte[32]);
              ghost(f, 9, 2);
              ghost(f, 10, 3);
              grid(f, TerminalCraftingPackets.COUNT, 10, 0, 2, new byte[32]);
              grid(f, TerminalCraftingPackets.MAIN, 10, 0, 1, new byte[32]);
              var view = menu(f).gridView();
              h.assertTrue(
                  view.cells().get(9).equals("minecraft:stone")
                      && view.counts().get(9) == 2
                      && view.cells().get(10).equals("minecraft:iron_ingot")
                      && view.counts().get(10) == 1,
                  "Main swaps stone (with its count) into the first output: " + view);
              boolean refused;
              try {
                new TerminalCraftingPackets.Command(
                    1,
                    java.util.UUID.randomUUID(),
                    0,
                    0,
                    TerminalCraftingPackets.MAIN,
                    9,
                    0,
                    1,
                    new byte[32]);
                refused = false;
              } catch (IllegalArgumentException expected) {
                refused = true;
              }
              h.assertTrue(refused, "Only outputs 2 and 3 can be made main");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              grid(f, TerminalCraftingPackets.ENCODE, -1, 0, 1, new byte[32]);
              ItemStack encoded = ItemStack.EMPTY;
              for (int slot = 0; slot < 36; slot++)
                if (inventory.getItem(slot).is(CraftingContent.PROCESSING_PATTERN.get()))
                  encoded = inventory.getItem(slot);
              var contents = encoded.get(CraftingContent.PROCESSING.get());
              h.assertTrue(
                  contents != null
                      && contents.inputs().size() == 1
                      && contents.inputs().getFirst().is(Items.RAW_IRON)
                      && contents.inputs().getFirst().getCount() == 3
                      && contents.outputs().getFirst().is(Items.STONE)
                      && contents.outputs().getFirst().getCount() == 2
                      && contents.outputs().get(1).is(Items.IRON_INGOT),
                  "Encoded: 3 raw iron makes 2 stone, with an ingot byproduct: "
                      + menu(f).gridView().status());
              logout(f);
            })
        .thenSucceed();
  }
}
