package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.CraftingInterfaceEntity;
import dev.izzy.factorycore.platform.crafting.ProcessingContents;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
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
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class ProcessingJobGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      INTERFACE = new BlockPos(1, 1, 2),
      FURNACE = new BlockPos(1, 1, 3),
      TERMINAL = new BlockPos(0, 1, 1);

  private record Fixture(
      UUID owner,
      NodeRegistry.Position origin,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      CraftingInterfaceEntity face,
      BlastFurnaceBlockEntity furnace,
      ResourceKey ore,
      ResourceKey ingot) {
    long stock(ResourceKey key) {
      return saved.registry().stock(cell, key).total();
    }
  }

  private ProcessingJobGameTests() {}

  private static ItemStack smelting() {
    var stack = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
    stack.set(
        CraftingContent.PROCESSING.get(),
        new ProcessingContents(
            UUID.randomUUID(),
            1,
            0,
            List.of(new ItemStack(Items.RAW_IRON)),
            List.of(new ItemStack(Items.IRON_INGOT))));
    return stack;
  }

  private static Fixture setup(GameTestHelper h) {
    var owner = UUID.randomUUID();
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner);
    h.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    h.setBlock(FURNACE, Blocks.BLAST_FURNACE);
    BlastFurnaceBlockEntity furnace = h.getBlockEntity(FURNACE);
    furnace.setItem(1, new ItemStack(Items.COAL, 8));
    h.setBlock(
        INTERFACE,
        CraftingContent.INTERFACE
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.SOUTH));
    CraftingInterfaceEntity face = h.getBlockEntity(INTERFACE);
    face.patterns().insertItem(0, smelting(), false);
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    return new Fixture(
        owner,
        new NodeRegistry.Position(
            h.getLevel().dimension().location().toString(), h.absolutePos(INTERFACE).asLong()),
        DeviceSavedData.get(h.getLevel().getServer()),
        CellHandleData.read(drive.cell(0)),
        face,
        furnace,
        codec.itemKey(new ItemStack(Items.RAW_IRON)),
        codec.itemKey(new ItemStack(Items.IRON_INGOT)));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer()).membership(f.origin).status()
                == NetworkTopology.Status.VALID
            && f.saved.registry().stock(f.cell, f.ore).loaded(),
        "Network ready");
  }

  private static WorldCraftingJobs jobs(GameTestHelper h) {
    return WorldCraftingJobs.get(h.getLevel().getServer());
  }

  private static NetworkPermissions.Actor actor(UUID id) {
    return new NetworkPermissions.Actor(id, false);
  }

  @GameTest(template = "empty", timeoutTicks = 1200)
  public static void blastFurnaceJobSmeltsThroughLeasedInterfaceAndCollectsExactOutputs(
      GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.ore, 2);
              var submission = jobs(h).submit(actor(f.owner), f.origin, f.ingot, 2);
              h.assertTrue(submission.job() != null, "Submitted: " + submission.reason());
              id[0] = submission.job();
              h.assertTrue(
                  f.saved.registry().stock(f.cell, f.ore).reserved() == 2, "Ore reserved once");
            })
        .thenWaitUntil(
            () -> {
              var job = f.saved.craftingJobs().get(id[0]);
              h.assertTrue(
                  job != null && job.dispatch() != null && f.furnace.getItem(0).is(Items.RAW_IRON),
                  "Ore inserted into the leased furnace");
            })
        .thenWaitUntil(
            () -> h.assertTrue(f.saved.craftingJobs().get(id[0]) == null, "Job finishes"))
        .thenExecute(
            () ->
                h.assertTrue(
                    f.stock(f.ingot) == 2
                        && f.stock(f.ore) == 0
                        && f.furnace.getItem(2).isEmpty()
                        && f.furnace.getItem(0).isEmpty()
                        && f.saved.registry().stock(f.cell, f.ore).reserved() == 0,
                    "Two ingots collected from the furnace and stored; nothing left behind"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 1200)
  public static void dirtyOutputSlotBlocksDispatchUntilCleared(GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 5));
              f.saved.registry().insert(f.cell, f.ore, 1);
              id[0] = jobs(h).submit(actor(f.owner), f.origin, f.ingot, 1).job();
            })
        .thenWaitUntil(
            () -> {
              var job = f.saved.craftingJobs().get(id[0]);
              h.assertTrue(
                  job.state() == CraftingJob.State.WAITING
                      && job.reason().equals("output_not_clean")
                      && job.dispatch() == null
                      && f.furnace.getItem(0).isEmpty(),
                  "A machine already holding the output is never dispatched");
            })
        .thenExecute(() -> f.furnace.setItem(2, ItemStack.EMPTY))
        .thenWaitUntil(
            () -> h.assertTrue(f.saved.craftingJobs().get(id[0]) == null, "Job finishes"))
        .thenExecute(
            () -> h.assertTrue(f.stock(f.ingot) == 1, "Exactly the job's own ingot stored"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 1600)
  public static void secondJobWaitsBusyWhileTheMachineIsLeased(GameTestHelper h) {
    var f = setup(h);
    UUID[] ids = {null, null};
    boolean[] sawBusy = {false};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.ore, 2);
              ids[0] = jobs(h).submit(actor(f.owner), f.origin, f.ingot, 1).job();
              ids[1] = jobs(h).submit(actor(f.owner), f.origin, f.ingot, 1).job();
              h.assertTrue(ids[0] != null && ids[1] != null, "Both jobs reserve one ore each");
            })
        .thenWaitUntil(
            () -> {
              var first = f.saved.craftingJobs().get(ids[0]);
              var second = f.saved.craftingJobs().get(ids[1]);
              if (second != null && second.reason().equals("busy")) sawBusy[0] = true;
              h.assertTrue(first == null && second == null, "Both jobs finish one after another");
            })
        .thenExecute(
            () -> {
              h.assertTrue(sawBusy[0], "The second job waited for the lease");
              h.assertTrue(f.stock(f.ingot) == 2, "Each job collected only its own ingot");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 1600)
  public static void cancelDuringSmeltingKeepsACleanupRecordUntilTheOutputIsCollected(
      GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.ore, 3);
              id[0] = jobs(h).submit(actor(f.owner), f.origin, f.ingot, 3).job();
            })
        .thenWaitUntil(
            () -> {
              var job = f.saved.craftingJobs().get(id[0]);
              h.assertTrue(
                  job.dispatch() != null && !job.dispatch().inserted().isEmpty(), "Ore inserted");
            })
        .thenExecute(
            () -> h.assertTrue(jobs(h).cancel(actor(f.owner), id[0]), "Cancelled mid-smelt"))
        .thenWaitUntil(
            () -> h.assertTrue(f.saved.craftingJobs().get(id[0]) == null, "Cleanup finishes"))
        .thenExecute(
            () ->
                h.assertTrue(
                    f.stock(f.ore) + f.stock(f.ingot) == 3
                        && f.furnace.getItem(2).isEmpty()
                        && f.saved.registry().stock(f.cell, f.ore).reserved() == 0,
                    "Unused ore released, the in-flight ingot collected; three units conserved"))
        .thenSucceed();
  }

  @SuppressWarnings("removal") // Pinned helper performs actual PlayerList authentication.
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void terminalEncodesDeclaredProcessingPattern(GameTestHelper h) {
    var player = h.makeMockServerPlayerInLevel();
    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
        player.connection.getConnection());
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(player.getUUID());
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    TerminalEntity terminal = h.getBlockEntity(TERMINAL);
    var inventory = player.getInventory();
    inventory.setItem(0, new ItemStack(Items.RAW_GOLD));
    inventory.setItem(1, new ItemStack(Items.GOLD_INGOT));
    inventory.setItem(2, new ItemStack(CraftingContent.BLANK_PATTERN.get()));
    var codec = new PlatformResourceCodec(player.registryAccess());
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(() -> h.useBlock(TERMINAL, player))
        .thenExecute(
            () -> {
              var m = (TerminalMenu) player.containerMenu;
              grid(m, player, TerminalCraftingPackets.MODE, 1, 0, 1, new byte[32]);
              grid(
                  m,
                  player,
                  TerminalCraftingPackets.SET_OWN,
                  0,
                  0,
                  1,
                  TerminalPackets.fingerprint(codec.itemKey(inventory.getItem(0))));
              grid(m, player, TerminalCraftingPackets.COUNT, 0, 0, 2, new byte[32]);
              grid(
                  m,
                  player,
                  TerminalCraftingPackets.SET_OWN,
                  9,
                  1,
                  1,
                  TerminalPackets.fingerprint(codec.itemKey(inventory.getItem(1))));
              grid(m, player, TerminalCraftingPackets.COUNT, 9, 0, 2, new byte[32]);
              h.assertTrue(
                  m.gridView().processing()
                      && m.gridView().counts().get(0) == 2
                      && m.gridView().result().equals("minecraft:gold_ingot")
                      && m.gridView().resultCount() == 2,
                  "Processing grid shows declared output: " + m.gridView());
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var m = (TerminalMenu) player.containerMenu;
              grid(m, player, TerminalCraftingPackets.CRAFT, -1, 0, 1, new byte[32]);
              h.assertTrue(
                  m.gridView().status().equals("processing_mode"),
                  "No manual craft in processing mode");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var m = (TerminalMenu) player.containerMenu;
              grid(m, player, TerminalCraftingPackets.ENCODE, -1, 0, 1, new byte[32]);
              h.assertTrue(
                  m.gridView().status().equals("encoded"), "Encoded: " + m.gridView().status());
              var encoded = inventory.getItem(2);
              var contents = encoded.get(CraftingContent.PROCESSING.get());
              h.assertTrue(contents != null, "Blank became a processing pattern");
              var pattern = contents.pattern(codec);
              h.assertTrue(
                  pattern.inputs().get(codec.itemKey(new ItemStack(Items.RAW_GOLD))) == 2
                      && pattern.output().equals(codec.itemKey(new ItemStack(Items.GOLD_INGOT)))
                      && pattern.outputAmount() == 2
                      && inventory.getItem(0).is(Items.RAW_GOLD),
                  "Declared 2 raw gold → 2 gold ingots; examples untouched");
              player.closeContainer();
              player.connection.disconnect(Component.literal("Processing fixture complete"));
            })
        .thenSucceed();
  }

  private static void grid(
      TerminalMenu m,
      net.minecraft.server.level.ServerPlayer player,
      int action,
      int cell,
      int source,
      int crafts,
      byte[] print) {
    m.gridCommand(
        player,
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
}
