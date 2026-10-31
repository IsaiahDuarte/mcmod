package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.crafting.JobReason;
import dev.izzy.factorycore.core.crafting.JobStatus;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.crafting.PatternHolderEntity;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalEntity;
import dev.izzy.factorycore.platform.storage.TerminalJobPackets;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class TerminalJobGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      UNIT = new BlockPos(1, 1, 2),
      TERMINAL = new BlockPos(0, 1, 1);

  /** Ticks between commands so the shared per-session command rate gate admits each one. */
  private static final int RATE_GAP = 5;

  private TerminalJobGameTests() {}

  private static ItemStack pattern(int[] cells, Item input, Item output, int count) {
    var stacks = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    for (int cell : cells) stacks.set(cell, new ItemStack(input));
    var stack = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    stack.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, stacks, new ItemStack(output, count), List.of()));
    return stack;
  }

  private static TerminalMenu menu(GameTestHelper h, ServerPlayer player) {
    h.assertTrue(player.containerMenu instanceof TerminalMenu, "Terminal open");
    return (TerminalMenu) player.containerMenu;
  }

  private static void command(
      GameTestHelper h, ServerPlayer player, int action, int selection, int amount) {
    var m = menu(h, player);
    m.jobCommand(
        player,
        new TerminalJobPackets.Command(
            m.containerId, m.session(), m.jobView().revision(), action, selection, amount));
    m.broadcastChanges();
  }

  private static int row(TerminalMenu m, String id) {
    var rows = m.jobView().craftables();
    for (int index = 0; index < rows.size(); index++)
      if (rows.get(index).icon().equals(id)) return index;
    return -1;
  }

  /**
   * P4: 22 craftables from three Crafters scroll one window of eight at a time: any offset, clamped
   * to the last full window, with indices of later commands relative to the visible window.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void craftableListScrollsByWindow(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    Net.in(h, player.getUUID()).controller(ROOT);
    Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    Item[] outputs = {
      Items.STICK,
      Items.TORCH,
      Items.CHEST,
      Items.LADDER,
      Items.BOWL,
      Items.OAK_PLANKS,
      Items.OAK_SLAB,
      Items.OAK_STAIRS,
      Items.OAK_FENCE,
      Items.OAK_DOOR,
      Items.OAK_BUTTON,
      Items.OAK_SIGN,
      Items.OAK_TRAPDOOR,
      Items.OAK_PRESSURE_PLATE,
      Items.OAK_FENCE_GATE,
      Items.OAK_BOAT,
      Items.CRAFTING_TABLE,
      Items.BARREL,
      Items.COMPOSTER,
      Items.BOOKSHELF,
      Items.LECTERN,
      Items.LOOM
    };
    BlockPos[] crafters = {UNIT, new BlockPos(1, 1, 0), new BlockPos(1, 2, 1)};
    for (int index = 0; index < outputs.length; index++) {
      if (index % PatternHolderEntity.SLOTS == 0)
        h.setBlock(crafters[index / PatternHolderEntity.SLOTS], CraftingContent.CRAFTER.get());
      CrafterEntity crafter = h.getBlockEntity(crafters[index / PatternHolderEntity.SLOTS]);
      crafter
          .patterns()
          .insertItem(
              index % PatternHolderEntity.SLOTS,
              pattern(new int[] {0}, Items.OAK_LOG, outputs[index], 1),
              false);
    }
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    TerminalEntity terminal = h.getBlockEntity(TERMINAL);
    var seen = new java.util.TreeSet<String>();
    var firstWindow = new ArrayList<TerminalJobPackets.Entry>();
    var keepTarget = new ArrayList<String>();
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(
            () -> {
              h.useBlock(TERMINAL, player);
              menu(h, player).broadcastChanges();
            })
        .thenWaitUntil(
            () -> {
              var m = menu(h, player);
              m.broadcastChanges();
              h.assertTrue(
                  m.jobView().total() == outputs.length, "All craftables counted: " + m.jobView());
            })
        .thenExecute(
            () -> {
              var first = menu(h, player).jobView();
              h.assertTrue(
                  first.offset() == 0 && first.craftables().size() == TerminalJobPackets.ROWS,
                  "The first window shows eight");
              for (var row : first.craftables()) seen.add(row.icon());
              firstWindow.addAll(first.craftables());
              command(h, player, TerminalJobPackets.WINDOW, 3, 1);
            })
        .thenIdle(RATE_GAP)
        .thenExecute(
            () -> {
              var shifted = menu(h, player).jobView();
              h.assertTrue(
                  shifted.offset() == 3
                      && shifted.craftables().getFirst().equals(firstWindow.get(3)),
                  "Scrolling by three shifts the window by three, not by a page");
              command(h, player, TerminalJobPackets.WINDOW, 9_999, 1);
            })
        .thenIdle(RATE_GAP)
        .thenExecute(
            () -> {
              var last = menu(h, player).jobView();
              h.assertTrue(
                  last.offset() == outputs.length - TerminalJobPackets.ROWS
                      && last.craftables().size() == TerminalJobPackets.ROWS,
                  "A past-the-end offset clamps to the last full window: " + last.offset());
              for (var row : last.craftables()) seen.add(row.icon());
              command(h, player, TerminalJobPackets.WINDOW, 8, 1);
            })
        .thenIdle(RATE_GAP)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              for (var row : m.jobView().craftables()) seen.add(row.icon());
              h.assertTrue(
                  seen.size() == outputs.length, "Windows 0, 8 and 14 reach every craftable");
              keepTarget.add(m.jobView().craftables().get(2).icon());
              command(h, player, TerminalJobPackets.KEEP, 2, 16);
            })
        .thenIdle(RATE_GAP)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              var row = m.jobView().craftables().get(2);
              h.assertTrue(
                  row.icon().equals(keepTarget.getFirst()) && row.amount().equals("16"),
                  "Row indices are relative to the visible window: " + row);
              boolean refused = false;
              try {
                new TerminalJobPackets.Command(
                    m.containerId,
                    m.session(),
                    m.jobView().revision(),
                    TerminalJobPackets.WINDOW,
                    TerminalJobPackets.MAX_OFFSET + 1,
                    1);
              } catch (IllegalArgumentException expected) {
                refused = true;
              }
              h.assertTrue(refused, "An offset past the protocol bound is refused at decode");
            })
        .thenSucceed();
  }

  /**
   * A4 crafting monitor: each player lists their own jobs; Manage also lists others' (named) but
   * may cancel only its own; the detail view shows the waiting processing step with its reason,
   * cancels from there, and closes once the job is gone.
   */
  @GameTest(template = "empty", timeoutTicks = 900)
  public static void monitorShowsStepsAndManageSeesAllJobs(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    var member = TestPlayers.mock(h);
    for (var player : List.of(owner, member)) {
      TestPlayers.connect(player);
      player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    }
    Net.in(h, owner.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    var processing = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
    processing.set(
        CraftingContent.PROCESSING.get(),
        new dev.izzy.factorycore.platform.crafting.ProcessingContents(
            UUID.randomUUID(),
            1,
            0,
            List.of(new ItemStack(Items.RAW_IRON)),
            List.of(new ItemStack(Items.IRON_INGOT))));
    unit.patterns().insertItem(0, processing, false);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    TerminalEntity terminal = h.getBlockEntity(TERMINAL);
    var server = h.getLevel().getServer();
    var saved = DeviceSavedData.get(server);
    DeviceRegistry.Handle cell = CellHandleData.read(drive.cell(0));
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    ResourceKey raw = codec.itemKey(new ItemStack(Items.RAW_IRON));
    ResourceKey ingot = codec.itemKey(new ItemStack(Items.IRON_INGOT));
    var origin = dev.izzy.factorycore.platform.Positions.of(h.getLevel(), h.absolutePos(TERMINAL));
    UUID[] ids = {null, null};
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(owner), "Terminal ready"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  dev.izzy.factorycore.platform.network.WorldNetworks.get(server)
                          .setGrants(
                              new dev.izzy.factorycore.core.network.NetworkPermissions.Actor(
                                  owner.getUUID(), false),
                              origin,
                              member.getUUID(),
                              dev.izzy.factorycore.core.network.NetworkPermissions.AccessRole.MEMBER
                                  .grants())
                      == dev.izzy.factorycore.core.network.NetworkPermissions.Change.OK,
                  "Member granted");
              saved.registry().insert(cell, raw, 2);
              var jobs = WorldCraftingJobs.get(server);
              ids[0] =
                  jobs.submit(
                          new dev.izzy.factorycore.core.network.NetworkPermissions.Actor(
                              owner.getUUID(), false),
                          origin,
                          ingot,
                          1)
                      .job();
              ids[1] =
                  jobs.submit(
                          new dev.izzy.factorycore.core.network.NetworkPermissions.Actor(
                              member.getUUID(), false),
                          origin,
                          ingot,
                          1)
                      .job();
              h.assertTrue(ids[0] != null && ids[1] != null, "Both players started a job");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    saved.craftingJobs().get(ids[0]).reason() == JobReason.MACHINE_MISSING
                        && saved.craftingJobs().get(ids[1]).reason() == JobReason.MACHINE_MISSING,
                    "Both jobs wait: no machine faces the Crafter"))
        .thenExecute(
            () -> {
              h.useBlock(TERMINAL, member);
              var m = menu(h, member);
              m.broadcastChanges();
              h.assertTrue(
                  m.jobView().jobs().size() == 1 && m.jobView().jobs().getFirst().owner().isEmpty(),
                  "Without Manage a player lists only their own job: " + m.jobView().jobs());
              member.closeContainer();
              h.useBlock(TERMINAL, owner);
              m = menu(h, owner);
              m.broadcastChanges();
              var listed = m.jobView().jobs();
              h.assertTrue(
                  listed.size() == 2
                      && listed.get(0).owner().isEmpty()
                      && listed.get(1).owner().equals(member.getGameProfile().getName()),
                  "Manage lists its own job first, then the member's, named: " + listed);
              command(h, owner, TerminalJobPackets.DETAIL, 1, 1);
              var detail = m.jobView().detail();
              h.assertTrue(
                  !detail.cancellable()
                      && detail.job().owner().equals(member.getGameProfile().getName())
                      && detail.steps().size() == 1
                      && detail.steps().getFirst().status() == TerminalJobPackets.StepStatus.WAITING
                      && detail.job().reason() == JobReason.MACHINE_MISSING,
                  "The member's waiting step is shown, read-only: " + detail);
            })
        .thenIdle(RATE_GAP)
        .thenExecute(
            () -> {
              command(h, owner, TerminalJobPackets.CANCEL, 1, 1);
              h.assertTrue(
                  menu(h, owner).jobView().status() == JobStatus.REFUSED
                      && saved.craftingJobs().get(ids[1]).state()
                          != dev.izzy.factorycore.core.crafting.CraftingJob.State.CANCELLING,
                  "Manage cannot cancel another player's job");
            })
        .thenIdle(RATE_GAP)
        .thenExecute(() -> command(h, owner, TerminalJobPackets.DETAIL, 0, 1))
        .thenIdle(RATE_GAP)
        .thenExecute(
            () -> {
              var m = menu(h, owner);
              h.assertTrue(
                  m.jobView().detail().cancellable()
                      && m.jobView().detail().job().owner().isEmpty(),
                  "The owner's own job can be cancelled from its detail view");
              command(h, owner, TerminalJobPackets.CANCEL, 0, 1);
              h.assertTrue(
                  m.jobView().status() == JobStatus.CANCELLING, "Cancel from the detail view");
            })
        .thenWaitUntil(
            () -> {
              var m = menu(h, owner);
              m.broadcastChanges();
              h.assertTrue(
                  saved.craftingJobs().get(ids[0]) == null
                      && m.jobView().detail() == TerminalJobPackets.NO_DETAIL
                      && m.jobView().jobs().size() == 1,
                  "A finished job leaves the list and closes its detail view");
              WorldCraftingJobs.get(server)
                  .cancel(
                      new dev.izzy.factorycore.core.network.NetworkPermissions.Actor(
                          member.getUUID(), false),
                      ids[1]);
            })
        .thenSucceed();
  }

  private static TerminalJobPackets.PlanRow planRow(TerminalMenu m, String id) {
    for (var row : m.jobView().plan()) if (row.item().icon().equals(id)) return row;
    return null;
  }

  /**
   * A2: the preview carries the whole plan per resource (stored, crafted, missing), shortages
   * first, plus a cycle explanation; nothing is reserved by previewing.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void previewSendsFullPlanBreakdown(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    Net.in(h, player.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    unit.patterns()
        .insertItem(0, pattern(new int[] {0}, Items.OAK_LOG, Items.OAK_PLANKS, 4), false);
    unit.patterns()
        .insertItem(1, pattern(new int[] {0, 3}, Items.OAK_PLANKS, Items.STICK, 4), false);
    // A loop: coal from charcoal and charcoal from coal.
    unit.patterns().insertItem(2, pattern(new int[] {0}, Items.CHARCOAL, Items.COAL, 1), false);
    unit.patterns().insertItem(3, pattern(new int[] {0}, Items.COAL, Items.CHARCOAL, 1), false);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    TerminalEntity terminal = h.getBlockEntity(TERMINAL);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    DeviceRegistry.Handle cell = CellHandleData.read(drive.cell(0));
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    ResourceKey log = codec.itemKey(new ItemStack(Items.OAK_LOG));
    ResourceKey planks = codec.itemKey(new ItemStack(Items.OAK_PLANKS));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(
            () -> {
              saved.registry().insert(cell, log, 1);
              saved.registry().insert(cell, planks, 2);
              h.useBlock(TERMINAL, player);
              menu(h, player).broadcastChanges();
            })
        .thenWaitUntil(
            () -> {
              var m = menu(h, player);
              m.broadcastChanges();
              h.assertTrue(row(m, "minecraft:stick") >= 0, "Sticks are craftable");
            })
        .thenExecute(
            () -> {
              var m = menu(h, player);
              // 8 sticks: 4 planks, 2 stored and 4 crafted from the stored log.
              command(h, player, TerminalJobPackets.PREVIEW, row(m, "minecraft:stick"), 8);
              var planksRow = planRow(m, "minecraft:oak_planks");
              var logRow = planRow(m, "minecraft:oak_log");
              var stickRow = planRow(m, "minecraft:stick");
              h.assertTrue(
                  planksRow != null
                      && planksRow.stored() == 2
                      && planksRow.crafted() == 4
                      && logRow != null
                      && logRow.stored() == 1
                      && stickRow != null
                      && stickRow.crafted() == 8
                      && m.jobView().plan().stream().allMatch(r -> r.missing() == 0)
                      && m.jobView().planMore() == 0
                      && saved.registry().stock(cell, log).reserved() == 0,
                  "A two-level plan lists stored and crafted units: " + m.jobView().plan());
            })
        .thenIdle(RATE_GAP)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              // 64 sticks need 32 planks: 2 stored, 4 from the one log, 7 more logs missing.
              command(h, player, TerminalJobPackets.PREVIEW, row(m, "minecraft:stick"), 64);
              var first = m.jobView().plan().getFirst();
              h.assertTrue(
                  m.jobView().status() == JobStatus.MISSING
                      && first.item().icon().equals("minecraft:oak_log")
                      && first.stored() == 1
                      && first.missing() == 7,
                  "The shortage is the first row: " + m.jobView().plan());
            })
        .thenIdle(RATE_GAP)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              command(h, player, TerminalJobPackets.PREVIEW, row(m, "minecraft:coal"), 1);
              var cycles = m.jobView().cycles();
              h.assertTrue(
                  cycles.size() == 1 && cycles.getFirst().equals("Coal → Charcoal → Coal"),
                  "The loop is explained by name: " + cycles);
              h.assertTrue(
                  planRow(m, "minecraft:coal") != null
                      && planRow(m, "minecraft:coal").missing() == 1,
                  "The looped item is missing: " + m.jobView().plan());
            })
        .thenSucceed();
  }

  /** A1: a typed 37 previews and starts exactly; the bound stays 1–4,096 at decode. */
  @GameTest(template = "empty", timeoutTicks = 900)
  public static void typedAmountPreviewsAndStartsExactly(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    Net.in(h, player.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    unit.patterns()
        .insertItem(0, pattern(new int[] {0}, Items.OAK_LOG, Items.OAK_PLANKS, 4), false);
    unit.patterns()
        .insertItem(1, pattern(new int[] {0, 3}, Items.OAK_PLANKS, Items.STICK, 4), false);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    TerminalEntity terminal = h.getBlockEntity(TERMINAL);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    DeviceRegistry.Handle cell = CellHandleData.read(drive.cell(0));
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    ResourceKey log = codec.itemKey(new ItemStack(Items.OAK_LOG));
    ResourceKey stick = codec.itemKey(new ItemStack(Items.STICK));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(
            () -> {
              saved.registry().insert(cell, log, 5);
              h.useBlock(TERMINAL, player);
              menu(h, player).broadcastChanges();
            })
        .thenWaitUntil(
            () -> {
              var m = menu(h, player);
              m.broadcastChanges();
              h.assertTrue(row(m, "minecraft:stick") >= 0, "Sticks are craftable");
            })
        .thenExecute(
            () -> {
              var m = menu(h, player);
              command(h, player, TerminalJobPackets.PREVIEW, row(m, "minecraft:stick"), 37);
              var view = m.jobView();
              // 37 sticks: 10 stick crafts (40) from 20 planks, i.e. 5 log crafts.
              h.assertTrue(
                  view.status() == JobStatus.PREVIEWED
                      && view.preview().amount().equals("37")
                      && view.previewCrafts() == 15,
                  "A typed 37 previews 10 stick and 5 plank crafts: " + view);
            })
        .thenIdle(RATE_GAP)
        .thenExecute(
            () -> {
              command(h, player, TerminalJobPackets.START, 0, 1);
              var jobs = WorldCraftingJobs.get(h.getLevel().getServer()).jobs(player.getUUID());
              h.assertTrue(
                  jobs.size() == 1
                      && jobs.getFirst().amount() == 37
                      && saved.registry().stock(cell, log).reserved() == 5,
                  "The started job is for exactly 37 and reserves all five logs");
              for (int amount : new int[] {0, TerminalJobPackets.MAX_AMOUNT + 1}) {
                boolean refused = false;
                try {
                  new TerminalJobPackets.Command(
                      1, UUID.randomUUID(), 1, TerminalJobPackets.PREVIEW, 0, amount);
                } catch (IllegalArgumentException expected) {
                  refused = true;
                }
                h.assertTrue(refused, "Amount " + amount + " is refused at decode");
              }
            })
        .thenWaitUntil(
            () -> {
              menu(h, player).broadcastChanges();
              h.assertTrue(
                  saved.registry().stock(cell, stick).total() == 40
                      && saved.registry().stock(cell, log).total() == 0,
                  "The job delivers 40 sticks (37 asked, whole crafts) from five logs");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 900)
  public static void terminalPreviewsStartsTracksAndCancelsJobs(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    NetworkNodeEntity root = Net.in(h, player.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    unit.patterns()
        .insertItem(0, pattern(new int[] {0}, Items.OAK_LOG, Items.OAK_PLANKS, 4), false);
    unit.patterns()
        .insertItem(1, pattern(new int[] {0, 3}, Items.OAK_PLANKS, Items.STICK, 4), false);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    TerminalEntity terminal = h.getBlockEntity(TERMINAL);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    DeviceRegistry.Handle cell = CellHandleData.read(drive.cell(0));
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    ResourceKey log = codec.itemKey(new ItemStack(Items.OAK_LOG));
    ResourceKey stick = codec.itemKey(new ItemStack(Items.STICK));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(
            () -> {
              h.useBlock(TERMINAL, player);
              menu(h, player).broadcastChanges();
            })
        .thenWaitUntil(
            () -> {
              var m = menu(h, player);
              m.broadcastChanges();
              h.assertTrue(
                  row(m, "minecraft:stick") >= 0 && row(m, "minecraft:oak_planks") >= 0,
                  "Both pattern outputs are listed as craftables");
            })
        .thenExecute(
            () -> {
              var m = menu(h, player);
              command(h, player, TerminalJobPackets.PREVIEW, row(m, "minecraft:stick"), 8);
              var view = m.jobView();
              h.assertTrue(
                  view.status() == JobStatus.MISSING
                      && view.missing().getFirst().icon().equals("minecraft:oak_log")
                      && view.missing().getFirst().amount().equals("1"),
                  "Preview explains the missing log: " + view);
              saved.registry().insert(cell, log, 4);
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              long stale = m.jobView().revision() - 1;
              m.jobCommand(
                  player,
                  new TerminalJobPackets.Command(
                      m.containerId, m.session(), stale, TerminalJobPackets.START, 0, 1));
              h.assertTrue(
                  WorldCraftingJobs.get(h.getLevel().getServer()).jobs(player.getUUID()).isEmpty(),
                  "Stale start is ignored");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              command(h, player, TerminalJobPackets.PREVIEW, row(m, "minecraft:stick"), 8);
              var view = m.jobView();
              h.assertTrue(
                  view.status() == JobStatus.PREVIEWED
                      && view.previewSteps() == 2
                      && view.previewCrafts() == 3
                      && view.preview().amount().equals("8")
                      && saved.registry().stock(cell, log).reserved() == 0,
                  "Preview shows the plan and reserves nothing");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              command(h, player, TerminalJobPackets.START, 0, 1);
              var view = menu(h, player).jobView();
              h.assertTrue(
                  view.status() == JobStatus.STARTED
                      && view.jobs().size() == 1
                      && view.jobs().getFirst().output().icon().equals("minecraft:stick")
                      && saved.registry().stock(cell, log).reserved() == 1,
                  "Started job is listed and its log reserved");
            })
        .thenWaitUntil(
            () -> {
              menu(h, player).broadcastChanges();
              h.assertTrue(
                  menu(h, player).jobView().jobs().isEmpty()
                      && saved.registry().stock(cell, stick).total() == 8,
                  "Job finishes and leaves the list");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              command(h, player, TerminalJobPackets.PREVIEW, row(m, "minecraft:stick"), 64);
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              h.assertTrue(
                  menu(h, player).jobView().status() == JobStatus.MISSING, "64 sticks need 8 logs");
              // A 24-craft job stays running long enough to be cancelled mid-way.
              saved.registry().insert(cell, log, 8);
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              command(h, player, TerminalJobPackets.PREVIEW, row(m, "minecraft:stick"), 64);
            })
        .thenIdle(3)
        .thenExecute(() -> command(h, player, TerminalJobPackets.START, 0, 1))
        .thenIdle(3)
        .thenExecute(
            () -> {
              h.assertTrue(menu(h, player).jobView().jobs().size() == 1, "Second job listed");
              command(h, player, TerminalJobPackets.CANCEL, 0, 1);
              h.assertTrue(
                  menu(h, player).jobView().status() == JobStatus.CANCELLING, "Cancel accepted");
            })
        .thenWaitUntil(
            () -> {
              menu(h, player).broadcastChanges();
              h.assertTrue(
                  WorldCraftingJobs.get(h.getLevel().getServer()).jobs(player.getUUID()).isEmpty()
                      && saved.registry().stock(cell, log).reserved() == 0,
                  "Cancelled job retires and releases its reservation");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              command(h, player, TerminalJobPackets.KEEP, row(m, "minecraft:stick"), 8);
              m.broadcastChanges();
              h.assertTrue(
                  m.jobView().status() == JobStatus.KEPT
                      && m.jobView()
                          .craftables()
                          .get(row(m, "minecraft:stick"))
                          .amount()
                          .equals("8"),
                  "Keep sets a rule shown on the row: " + m.jobView());
              // Starve the rule: with no sticks, planks or logs left it cannot craft, and the row
              // must say why.
              var planks = codec.itemKey(new ItemStack(Items.OAK_PLANKS));
              for (var key : List.of(stick, planks, log)) {
                long available = saved.registry().stock(cell, key).available();
                if (available > 0) saved.registry().extract(cell, key, available, null);
              }
            })
        .thenWaitUntil(
            () -> {
              var m = menu(h, player);
              m.broadcastChanges();
              var note = m.jobView().craftables().get(row(m, "minecraft:stick")).note();
              h.assertTrue(
                  note == JobStatus.MISSING, "A blocked keep rule is shown: '" + note + "'");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              command(h, player, TerminalJobPackets.KEEP, row(m, "minecraft:stick"), 8);
              m.broadcastChanges();
              h.assertTrue(
                  m.jobView().status() == JobStatus.UNKEPT
                      && m.jobView().craftables().get(row(m, "minecraft:stick")).amount().isEmpty(),
                  "Keep again removes the rule");
            })
        .thenExecute(
            () -> {
              player.closeContainer();
              player.connection.disconnect(Component.literal("Job fixture complete"));
            })
        .thenSucceed();
  }

  /**
   * Q7: Max previews the most the network can make (nine logs: 36 planks); for an item with no
   * ingredients in stock it previews one and names what is missing.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void maxPreviewsTheMostThatCanBeMade(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    Net.in(h, player.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    unit.patterns()
        .insertItem(0, pattern(new int[] {0}, Items.OAK_LOG, Items.OAK_PLANKS, 4), false);
    unit.patterns()
        .insertItem(1, pattern(new int[] {0}, Items.STONE, Items.STONE_BUTTON, 1), false);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    TerminalEntity terminal = h.getBlockEntity(TERMINAL);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    DeviceRegistry.Handle cell = CellHandleData.read(drive.cell(0));
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    ResourceKey log = codec.itemKey(new ItemStack(Items.OAK_LOG));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(
            () -> {
              saved.registry().insert(cell, log, 9);
              h.useBlock(TERMINAL, player);
              menu(h, player).broadcastChanges();
            })
        .thenWaitUntil(
            () -> {
              var m = menu(h, player);
              m.broadcastChanges();
              h.assertTrue(row(m, "minecraft:oak_planks") >= 0, "Planks craftable");
            })
        .thenExecute(
            () ->
                command(
                    h,
                    player,
                    TerminalJobPackets.MAX,
                    row(menu(h, player), "minecraft:oak_planks"),
                    1))
        .thenExecute(
            () -> {
              var view = menu(h, player).jobView();
              h.assertTrue(
                  view.status() == JobStatus.MAXIMUM
                      && view.preview().amount().equals("36")
                      && view.previewReason() == null,
                  "Max previews 36 planks from nine logs: " + view);
            })
        .thenIdle(RATE_GAP)
        .thenExecute(
            () ->
                command(
                    h,
                    player,
                    TerminalJobPackets.MAX,
                    row(menu(h, player), "minecraft:stone_button"),
                    1))
        .thenExecute(
            () -> {
              var view = menu(h, player).jobView();
              h.assertTrue(
                  view.status() == JobStatus.MISSING
                      && view.preview().amount().equals("1")
                      && view.previewReason() == JobStatus.MISSING,
                  "With no stone, Max previews one and names the shortage: " + view);
            })
        .thenSucceed();
  }

  /**
   * Q64: "=N" previews only what is needed to reach N available (none when already there), and
   * QUICK previews and starts in one command (Shift+Start).
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void targetsPreviewOnlyTheShortfallAndQuickStarts(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    Net.in(h, player.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    unit.patterns()
        .insertItem(0, pattern(new int[] {0}, Items.OAK_LOG, Items.OAK_PLANKS, 4), false);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    TerminalEntity terminal = h.getBlockEntity(TERMINAL);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    DeviceRegistry.Handle cell = CellHandleData.read(drive.cell(0));
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    ResourceKey log = codec.itemKey(new ItemStack(Items.OAK_LOG));
    ResourceKey planks = codec.itemKey(new ItemStack(Items.OAK_PLANKS));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(
            () -> {
              saved.registry().insert(cell, log, 9);
              saved.registry().insert(cell, planks, 10);
              h.useBlock(TERMINAL, player);
              menu(h, player).broadcastChanges();
            })
        .thenWaitUntil(
            () -> {
              var m = menu(h, player);
              m.broadcastChanges();
              h.assertTrue(row(m, "minecraft:oak_planks") >= 0, "Planks craftable");
            })
        .thenExecute(
            () ->
                command(
                    h,
                    player,
                    TerminalJobPackets.TARGET,
                    row(menu(h, player), "minecraft:oak_planks"),
                    18))
        .thenExecute(
            () -> {
              var view = menu(h, player).jobView();
              h.assertTrue(
                  view.status() == JobStatus.PREVIEWED && view.preview().amount().equals("8"),
                  "=18 with 10 stored previews the 8 missing: " + view.preview());
            })
        .thenIdle(RATE_GAP)
        .thenExecute(
            () ->
                command(
                    h,
                    player,
                    TerminalJobPackets.TARGET,
                    row(menu(h, player), "minecraft:oak_planks"),
                    10))
        .thenExecute(
            () ->
                h.assertTrue(
                    menu(h, player).jobView().status() == JobStatus.ENOUGH
                        && menu(h, player).jobView().preview().icon().isEmpty(),
                    "=10 with 10 stored has nothing to make"))
        .thenIdle(RATE_GAP)
        .thenExecute(
            () ->
                command(
                    h,
                    player,
                    TerminalJobPackets.QUICK,
                    row(menu(h, player), "minecraft:oak_planks"),
                    4))
        .thenExecute(
            () -> {
              var view = menu(h, player).jobView();
              h.assertTrue(
                  view.status() == JobStatus.STARTED && view.jobs().size() == 1,
                  "Shift+Start previews and starts in one command: " + view.status());
              player.closeContainer();
              player.connection.disconnect(net.minecraft.network.chat.Component.literal("done"));
            })
        .thenSucceed();
  }

  /**
   * Q100: a job step names what runs it: the Crafter (and its label) while no machine faces it,
   * then the furnace placed beside it.
   */
  @GameTest(template = "empty", timeoutTicks = 600)
  public static void jobStepsNameTheirMachine(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    TestPlayers.connect(owner);
    owner.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    Net.in(h, owner.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    var processing = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
    processing.set(
        CraftingContent.PROCESSING.get(),
        new dev.izzy.factorycore.platform.crafting.ProcessingContents(
            UUID.randomUUID(),
            1,
            0,
            List.of(new ItemStack(Items.RAW_IRON)),
            List.of(new ItemStack(Items.IRON_INGOT))));
    unit.patterns().insertItem(0, processing, false);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    TerminalEntity terminal = h.getBlockEntity(TERMINAL);
    var server = h.getLevel().getServer();
    var saved = DeviceSavedData.get(server);
    DeviceRegistry.Handle cell = CellHandleData.read(drive.cell(0));
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    ResourceKey raw = codec.itemKey(new ItemStack(Items.RAW_IRON));
    ResourceKey ingot = codec.itemKey(new ItemStack(Items.IRON_INGOT));
    var origin = dev.izzy.factorycore.platform.Positions.of(h.getLevel(), h.absolutePos(TERMINAL));
    var actor =
        new dev.izzy.factorycore.core.network.NetworkPermissions.Actor(owner.getUUID(), false);
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    terminal.canUse(owner) && saved.registry().stock(cell, raw).loaded(),
                    "Terminal ready"))
        .thenExecute(
            () -> {
              saved.registry().insert(cell, raw, 1);
              id[0] = WorldCraftingJobs.get(server).submit(actor, origin, ingot, 1).job();
              h.assertTrue(id[0] != null, "Job started");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    saved.craftingJobs().get(id[0]).reason() == JobReason.MACHINE_MISSING,
                    "The job waits for a machine"))
        .thenExecute(
            () -> {
              h.useBlock(TERMINAL, owner);
              menu(h, owner).broadcastChanges();
              command(h, owner, TerminalJobPackets.DETAIL, 0, 1);
              var step = menu(h, owner).jobView().detail().steps().getFirst();
              h.assertTrue(
                  step.machine().equals("factorycore:crafter") && step.label().isEmpty(),
                  "With no machine beside it, the Crafter runs the step: " + step);
              // A block beside the Crafter rebuilds the network, which closes the terminal.
              owner.closeContainer();
              unit.label("Smelter");
              h.setBlock(UNIT.above(), Blocks.FURNACE);
            })
        .thenWaitUntil(
            () -> h.assertTrue(terminal.canUse(owner), "Terminal ready again after the rebuild"))
        .thenExecute(
            () -> {
              h.useBlock(TERMINAL, owner);
              menu(h, owner).broadcastChanges();
            })
        .thenIdle(RATE_GAP)
        .thenExecute(() -> command(h, owner, TerminalJobPackets.DETAIL, 0, 1))
        .thenWaitUntil(
            () -> {
              var m = menu(h, owner);
              m.broadcastChanges();
              var steps = m.jobView().detail().steps();
              h.assertTrue(
                  !steps.isEmpty()
                      && steps.getFirst().machine().equals("minecraft:furnace")
                      && steps.getFirst().label().equals("Smelter"),
                  "The furnace beside the labelled Crafter runs it: " + steps);
            })
        .thenExecute(
            () -> {
              owner.closeContainer();
              WorldCraftingJobs.get(server).cancel(actor, id[0]);
            })
        .thenWaitUntil(
            () -> h.assertTrue(saved.craftingJobs().get(id[0]) == null, "Cancelled and retired"))
        .thenSucceed();
  }
}
