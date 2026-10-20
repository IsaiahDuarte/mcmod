package dev.izzy.factorycore.testing.wireless;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.CraftingInterfaceEntity;
import dev.izzy.factorycore.platform.crafting.CraftingUnitEntity;
import dev.izzy.factorycore.platform.crafting.PatternContents;
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
import dev.izzy.factorycore.platform.storage.WorldStorageTransfers;
import dev.izzy.factorycore.platform.wireless.LinkEndpointBlock;
import dev.izzy.factorycore.platform.wireless.LinkEndpointEntity;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Recipes and transfer routes compete for one physical wireless pair's real item throughput. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WirelessCraftingGameTests {
  private static final BlockPos ROOT = new BlockPos(0, 1, 1),
      ROOT_DRIVE = new BlockPos(0, 1, 2),
      A = new BlockPos(2, 1, 1),
      B = new BlockPos(4, 1, 1),
      UNIT = new BlockPos(6, 1, 1),
      CHILD_DRIVE = new BlockPos(6, 1, 2),
      FURNACE = new BlockPos(7, 1, 1);

  private record Fixture(
      ServerPlayer owner,
      WorldNetworks networks,
      DeviceSavedData saved,
      DeviceRegistry.Handle rootCell,
      DeviceRegistry.Handle childCell,
      LinkEndpointEntity first,
      LinkEndpointEntity second,
      ResourceKey cobble,
      ResourceKey log,
      ResourceKey planks,
      ResourceKey ore,
      ResourceKey ingot) {
    NetworkPermissions.Actor actor() {
      return new NetworkPermissions.Actor(owner.getUUID(), false);
    }

    long stock(ResourceKey key) {
      return saved.registry().stock(rootCell, key).total();
    }

    void power() {
      first.energyStorage().receiveEnergy(1000, false);
      second.energyStorage().receiveEnergy(1000, false);
    }
  }

  private WirelessCraftingGameTests() {}

  private static NodeRegistry.Position position(GameTestHelper h, BlockPos relative) {
    return new NodeRegistry.Position(
        h.getLevel().dimension().location().toString(), h.absolutePos(relative).asLong());
  }

  private static DeviceRegistry.Handle cell(GameTestHelper h, BlockPos position) {
    h.setBlock(position, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(position);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    return CellHandleData.read(drive.cell(0));
  }

  private static void endpoint(
      GameTestHelper h, ServerPlayer owner, BlockPos position, Direction facing) {
    h.setBlock(
        position,
        WirelessContent.LINK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, facing));
    WirelessContent.LINK
        .get()
        .setPlacedBy(
            h.getLevel(),
            h.absolutePos(position),
            h.getBlockState(position),
            owner,
            new ItemStack(WirelessContent.LINK_ITEM.get()));
  }

  private static ItemStack planksPattern() {
    var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    cells.set(0, new ItemStack(Items.OAK_LOG));
    var pattern = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    pattern.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, cells, new ItemStack(Items.OAK_PLANKS, 4), List.of()));
    return pattern;
  }

  private static Fixture setup(GameTestHelper h, boolean processing) {
    var owner = TestPlayers.mock(h);
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner.getUUID());
    for (var gate : List.of(new BlockPos(1, 1, 1), new BlockPos(5, 1, 1))) {
      h.setBlock(
          gate,
          NetworkContent.GATEWAY
              .get()
              .defaultBlockState()
              .setValue(BlockStateProperties.FACING, Direction.EAST));
      NetworkNodeEntity entity = h.getBlockEntity(gate);
      entity.commission(null);
    }
    endpoint(h, owner, A, Direction.EAST);
    endpoint(h, owner, B, Direction.WEST);
    if (processing) {
      h.setBlock(FURNACE, Blocks.BLAST_FURNACE);
      BlastFurnaceBlockEntity furnace = h.getBlockEntity(FURNACE);
      furnace.setItem(1, new ItemStack(Items.COAL, 2));
      h.setBlock(
          UNIT,
          CraftingContent.INTERFACE
              .get()
              .defaultBlockState()
              .setValue(BlockStateProperties.FACING, Direction.EAST));
      CraftingInterfaceEntity face = h.getBlockEntity(UNIT);
      var pattern = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
      pattern.set(
          CraftingContent.PROCESSING.get(),
          new ProcessingContents(
              UUID.randomUUID(),
              1,
              0,
              List.of(new ItemStack(Items.RAW_IRON)),
              List.of(new ItemStack(Items.IRON_INGOT))));
      face.patterns().insertItem(0, pattern, false);
    } else {
      h.setBlock(UNIT, CraftingContent.UNIT.get());
      CraftingUnitEntity unit = h.getBlockEntity(UNIT);
      unit.patterns().insertItem(0, planksPattern(), false);
    }
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    return new Fixture(
        owner,
        WorldNetworks.get(h.getLevel().getServer()),
        DeviceSavedData.get(h.getLevel().getServer()),
        cell(h, ROOT_DRIVE),
        cell(h, CHILD_DRIVE),
        h.getBlockEntity(A),
        h.getBlockEntity(B),
        codec.itemKey(new ItemStack(Items.COBBLESTONE)),
        codec.itemKey(new ItemStack(Items.OAK_LOG)),
        codec.itemKey(new ItemStack(Items.OAK_PLANKS)),
        codec.itemKey(new ItemStack(Items.RAW_IRON)),
        codec.itemKey(new ItemStack(Items.IRON_INGOT)));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    f.power();
    h.assertTrue(
        f.networks.membership(position(h, UNIT)).status() == NetworkTopology.Status.VALID
            && f.saved.registry().stock(f.rootCell, f.cobble).loaded()
            && f.saved.registry().stock(f.childCell, f.cobble).loaded(),
        "Published remote recipe and storage are loaded");
  }

  /** Uses the public scheduler/transfer service, preserving the actual Minecraft tick. */
  /** Competing routes move whole transfer quanta of at most 128 items. */
  private static void move(GameTestHelper h, Fixture f, int amount) {
    for (int moved = 0; moved < amount; moved += 128) moveOne(h, f, Math.min(128, amount - moved));
  }

  private static void moveOne(GameTestHelper h, Fixture f, int amount) {
    WorldStorageTransfers.Result[] result = {null};
    var admission =
        f.networks
            .transfers()
            .submit(
                UUID.randomUUID(),
                f.actor(),
                position(h, CHILD_DRIVE),
                f.rootCell,
                f.childCell,
                f.cobble,
                amount,
                value -> result[0] = value);
    h.assertTrue(
        admission.admission() == FairScheduler.Admission.ACCEPTED, "Competing route admitted");
    for (int attempt = 0; attempt < 100 && result[0] == null; attempt++)
      f.networks.scheduler().tick();
    h.assertTrue(
        result[0] != null
            && result[0].transfer() != null
            && result[0].transfer().delivered() == amount,
        "Competing route consumes actual pair units");
  }

  private static void runJobs(GameTestHelper h, Fixture f) {
    WorldCraftingJobs.get(h.getLevel().getServer()).tick();
    for (int attempt = 0; attempt < 10; attempt++) f.networks.scheduler().tick();
  }

  @GameTest(template = "empty", batch = "wireless_crafting", timeoutTicks = 600)
  public static void publishedTableCraftWaitsForItsWholeInputAndOutputQuantum(GameTestHelper h) {
    var f = setup(h, false);
    UUID[] id = {null};
    h.startSequence()
        .thenIdle(3)
        .thenExecute(
            () -> {
              h.assertTrue(
                  LinkEndpointBlock.pair(f.owner, h.absolutePos(A), h.absolutePos(B))
                      .equals("paired"),
                  "Pair owned");
              f.power();
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.rootCell, f.cobble, 1000);
              f.saved.registry().insert(f.rootCell, f.log, 10);
              move(h, f, 252);
              var submission =
                  WorldCraftingJobs.get(h.getLevel().getServer())
                      .submit(f.actor(), position(h, ROOT), f.planks, 4);
              h.assertTrue(
                  submission.job() != null,
                  "Published table job submitted: " + submission.reason());
              id[0] = submission.job();
              runJobs(h, f);
              var job = f.saved.craftingJobs().get(id[0]);
              h.assertTrue(
                  job != null
                      && job.steps().getFirst().done() == 0
                      && job.stagedAmount(f.log) == 1
                      && job.reason().equals("rate_limit"),
                  "Four remaining units cannot atomically carry1 log and4 planks; input remains staged");
              h.assertTrue(
                  f.stock(f.log) == 9
                      && f.stock(f.planks) == 0
                      && f.networks.movementAvailable(
                              position(h, ROOT), position(h, UNIT), f.log, 256)
                          == 4,
                  "Failed whole-recipe permit releases its partial claims and produces nothing");
            })
        .thenWaitUntil(
            () -> {
              f.power();
              h.assertTrue(
                  f.saved.craftingJobs().get(id[0]) == null,
                  "Recipe resumes on replenished pair budget and completes");
            })
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.stock(f.log) == 9
                      && f.stock(f.planks) == 4
                      && f.saved.registry().stock(f.rootCell, f.log).reserved() == 0,
                  "Exactly one log consumed and four planks returned to root, with no residual reservation");
              h.assertTrue(
                  f.stock(f.cobble) == 748
                      && f.saved.registry().stock(f.childCell, f.cobble).total() == 252,
                  "Competing storage transfer remains conserved");
              f.owner.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", batch = "wireless_crafting", timeoutTicks = 1200)
  public static void publishedProcessingInsertionAndCollectionShareRealTransferBudget(
      GameTestHelper h) {
    var f = setup(h, true);
    BlastFurnaceBlockEntity furnace = h.getBlockEntity(FURNACE);
    UUID[] id = {null};
    long[] routed = {256};
    int[] heldTicks = {0};
    h.startSequence()
        .thenIdle(3)
        .thenExecute(
            () -> {
              h.assertTrue(
                  LinkEndpointBlock.pair(f.owner, h.absolutePos(A), h.absolutePos(B))
                      .equals("paired"),
                  "Pair owned");
              f.power();
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.rootCell, f.cobble, 8000);
              f.saved.registry().insert(f.rootCell, f.ore, 1);
              move(h, f, 256);
              var submission =
                  WorldCraftingJobs.get(h.getLevel().getServer())
                      .submit(f.actor(), position(h, ROOT), f.ingot, 1);
              h.assertTrue(
                  submission.job() != null,
                  "Published processing job submitted: " + submission.reason());
              id[0] = submission.job();
              runJobs(h, f);
              var job = f.saved.craftingJobs().get(id[0]);
              h.assertTrue(
                  job != null
                      && job.dispatch() != null
                      && job.stagedAmount(f.ore) == 1
                      && job.dispatch().inserted().isEmpty()
                      && furnace.getItem(0).isEmpty(),
                  "Exhausted shared pair leaves the exact processing input in job staging before insertion");
              h.assertTrue(
                  f.stock(f.ore) == 0
                      && f.saved.registry().stock(f.rootCell, f.ore).reserved() == 0,
                  "Ore pulled once into owned staging, never reserved or copied twice");
            })
        .thenWaitUntil(
            () -> {
              f.power();
              h.assertTrue(
                  furnace.getItem(2).is(Items.IRON_INGOT),
                  "Natural furnace produces its leased output");
            })
        .thenWaitUntil(
            () -> {
              f.power();
              // GameTests tick after world machines but before the mod's server-post scheduler.
              // Keep this exact output behind real transfer contention for more than a retry
              // period.
              // Order the competing transfer first even when the previous post-tick queued a craft
              // task.
              // Only scheduler work is replaced; the job, dispatch and reservations remain
              // canonical.
              var pending = f.saved.craftingJobs().get(id[0]);
              h.assertTrue(pending != null, "Output still belongs to its processing job");
              f.networks
                  .scheduler()
                  .cancelTask(pending.network(), pending.principal(), "craft:" + id[0]);
              move(h, f, 256);
              routed[0] += 256;
              runJobs(h, f);
              var job = f.saved.craftingJobs().get(id[0]);
              h.assertTrue(
                  job != null
                      && job.dispatch() != null
                      && job.dispatch().collected().isEmpty()
                      && job.stagedAmount(f.ingot) == 0
                      && furnace.getItem(2).is(Items.IRON_INGOT),
                  "Exhausted pair leaves the real smelted output in its leased furnace, never fabricating collection");
              heldTicks[0]++;
              h.assertTrue(
                  heldTicks[0] > WorldCraftingJobs.RETRY_TICKS,
                  "Maintain collection contention beyond one full processing retry interval");
            })
        .thenWaitUntil(
            () -> {
              f.power();
              h.assertTrue(
                  f.saved.craftingJobs().get(id[0]) == null,
                  "Leased collection finishes when actual transfer contention ends");
            })
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.stock(f.ore) == 0
                      && f.stock(f.ingot) == 1
                      && furnace.getItem(0).isEmpty()
                      && furnace.getItem(2).isEmpty(),
                  "Exactly one ore is consumed, one ingot is collected and no machine item remains");
              h.assertTrue(
                  f.stock(f.cobble) == 8000 - routed[0]
                      && f.saved.registry().stock(f.childCell, f.cobble).total() == routed[0],
                  "Contending route quantities are unchanged by processing retries");
              f.owner.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
