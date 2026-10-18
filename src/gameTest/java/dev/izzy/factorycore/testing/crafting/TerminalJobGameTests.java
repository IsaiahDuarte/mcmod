package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.CraftingUnitEntity;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalEntity;
import dev.izzy.factorycore.platform.storage.TerminalJobPackets;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
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

  @SuppressWarnings("removal") // Pinned helper performs actual PlayerList authentication.
  @GameTest(template = "empty", timeoutTicks = 600)
  public static void terminalPreviewsStartsTracksAndCancelsJobs(GameTestHelper h) {
    var player = h.makeMockServerPlayerInLevel();
    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
        player.connection.getConnection());
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(player.getUUID());
    h.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    h.setBlock(UNIT, CraftingContent.UNIT.get());
    CraftingUnitEntity unit = h.getBlockEntity(UNIT);
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
                  view.status().equals("missing")
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
              h.assertTrue(saved.craftingJobs().all().isEmpty(), "Stale start is ignored");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              command(h, player, TerminalJobPackets.PREVIEW, row(m, "minecraft:stick"), 8);
              var view = m.jobView();
              h.assertTrue(
                  view.status().equals("previewed")
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
                  view.status().equals("started")
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
                  menu(h, player).jobView().status().equals("missing"), "64 sticks need 8 logs");
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
                  menu(h, player).jobView().status().equals("cancelling"), "Cancel accepted");
            })
        .thenWaitUntil(
            () -> {
              menu(h, player).broadcastChanges();
              h.assertTrue(
                  saved.craftingJobs().all().isEmpty()
                      && saved.registry().stock(cell, log).reserved() == 0,
                  "Cancelled job retires and releases its reservation");
            })
        .thenExecute(
            () -> {
              player.closeContainer();
              player.connection.disconnect(Component.literal("Job fixture complete"));
            })
        .thenSucceed();
  }
}
