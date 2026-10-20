package dev.izzy.factorycore.testing.wireless;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.EnergyBankContent;
import dev.izzy.factorycore.platform.storage.EnergyBankEntity;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.WorldStorageTransfers;
import dev.izzy.factorycore.platform.wireless.LinkEndpointBlock;
import dev.izzy.factorycore.platform.wireless.LinkEndpointEntity;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Physical storage routes across real paired endpoints; all quantities are actual ledger changes.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WirelessThroughputGameTests {
  private static final BlockPos ROOT = new BlockPos(0, 1, 1),
      ROOT_DRIVE = new BlockPos(0, 1, 0),
      CHILD = new BlockPos(6, 1, 1),
      NESTED = new BlockPos(12, 1, 1),
      SIBLING = new BlockPos(0, 1, 7),
      ROOT_BANK = new BlockPos(0, 2, 0),
      CHILD_BANK = new BlockPos(6, 2, 1);
  private static final ResourceKey IRON =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);
  private static final ResourceKey WATER =
      new ResourceKey(ResourceKind.FLUID, "minecraft:water", new byte[0]);

  private record Fixture(
      ServerPlayer player,
      WorldNetworks networks,
      DeviceSavedData saved,
      List<LinkEndpointEntity> ends,
      DeviceRegistry.Handle[] root,
      DeviceRegistry.Handle[] child,
      DeviceRegistry.Handle[] nested,
      DeviceRegistry.Handle[] sibling,
      DeviceRegistry.Handle rootBank,
      DeviceRegistry.Handle childBank) {
    NetworkPermissions.Actor actor() {
      return new NetworkPermissions.Actor(player.getUUID(), false);
    }

    long stock(DeviceRegistry.Handle handle, ResourceKey key) {
      return saved.registry().stock(handle, key).total();
    }

    void power() {
      for (var end : ends) end.energyStorage().receiveEnergy(1000, false);
    }
  }

  private WirelessThroughputGameTests() {}

  private static NodeRegistry.Position position(GameTestHelper h, BlockPos relative) {
    return Positions.of(h.getLevel(), h.absolutePos(relative));
  }

  private static DeviceRegistry.Handle[] drive(GameTestHelper h, BlockPos position) {
    h.setBlock(position, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(position);
    var handles = new DeviceRegistry.Handle[4];
    for (int slot = 0; slot < 4; slot++) {
      drive.installCell(
          new ItemStack(
              StorageContent.cell(slot < 2 ? CellTier.STARTER_ITEM : CellTier.STARTER_FLUID)));
      handles[slot] = CellHandleData.read(drive.cell(slot));
    }
    return handles;
  }

  private static DeviceRegistry.Handle bank(GameTestHelper h, BlockPos position) {
    h.setBlock(position, EnergyBankContent.block(EnergyBankTier.ADVANCED));
    EnergyBankEntity bank = h.getBlockEntity(position);
    bank.place(new ItemStack(EnergyBankContent.item(EnergyBankTier.ADVANCED)));
    return bank.handle();
  }

  private static void gate(GameTestHelper h, BlockPos position, Direction facing) {
    h.setBlock(
        position,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, facing));
    NetworkNodeEntity entity = h.getBlockEntity(position);
    entity.commission(null);
  }

  private static void endpoint(
      GameTestHelper h,
      ServerPlayer owner,
      BlockPos position,
      Direction facing,
      List<LinkEndpointEntity> ends) {
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
    ends.add(h.getBlockEntity(position));
  }

  private static Fixture setup(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner.getUUID());
    var ends = new ArrayList<LinkEndpointEntity>();
    for (int x : new int[] {1, 7}) {
      gate(h, new BlockPos(x, 1, 1), Direction.EAST);
      endpoint(h, owner, new BlockPos(x + 1, 1, 1), Direction.EAST, ends);
      endpoint(h, owner, new BlockPos(x + 3, 1, 1), Direction.WEST, ends);
      gate(h, new BlockPos(x + 4, 1, 1), Direction.EAST);
    }
    gate(h, new BlockPos(0, 1, 2), Direction.SOUTH);
    endpoint(h, owner, new BlockPos(0, 1, 3), Direction.SOUTH, ends);
    endpoint(h, owner, new BlockPos(0, 1, 5), Direction.NORTH, ends);
    gate(h, new BlockPos(0, 1, 6), Direction.SOUTH);
    return new Fixture(
        owner,
        WorldNetworks.get(h.getLevel().getServer()),
        DeviceSavedData.get(h.getLevel().getServer()),
        ends,
        drive(h, ROOT_DRIVE),
        drive(h, CHILD),
        drive(h, NESTED),
        drive(h, SIBLING),
        bank(h, ROOT_BANK),
        bank(h, CHILD_BANK));
  }

  private static void pair(GameTestHelper h, Fixture f) {
    for (int index = 0; index < f.ends.size(); index += 2)
      h.assertTrue(
          LinkEndpointBlock.pair(
                  f.player, f.ends.get(index).getBlockPos(), f.ends.get(index + 1).getBlockPos())
              .equals("paired"),
          "Pair authorized");
    f.power();
  }

  private static void ready(GameTestHelper h, Fixture f) {
    f.power();
    for (var location : List.of(ROOT_DRIVE, CHILD, NESTED, SIBLING, ROOT_BANK, CHILD_BANK))
      h.assertTrue(
          f.networks.membership(position(h, location)).status() == NetworkTopology.Status.VALID,
          "All physical routes validated: "
              + location
              + " "
              + f.networks.membership(position(h, location))
              + " "
              + f.networks.diagnostic());
  }

  /** Drain only accepted work while the Minecraft tick remains fixed, preserving shared quotas. */
  private static WorldStorageTransfers.Result submitOne(
      GameTestHelper h,
      Fixture f,
      BlockPos origin,
      DeviceRegistry.Handle from,
      DeviceRegistry.Handle to,
      ResourceKey key,
      long amount) {
    WorldStorageTransfers.Result[] result = {null};
    var admission =
        f.networks
            .transfers()
            .submit(
                UUID.randomUUID(),
                f.actor(),
                position(h, origin),
                from,
                to,
                key,
                amount,
                value -> result[0] = value);
    h.assertTrue(
        admission.admission() == FairScheduler.Admission.ACCEPTED,
        "Transfer admitted: " + admission);
    for (int attempt = 0; attempt < 100 && result[0] == null; attempt++)
      f.networks.scheduler().tick();
    h.assertTrue(result[0] != null, "Scheduled transfer completed in the same server tick");
    return result[0];
  }

  private static long delivered(WorldStorageTransfers.Result result) {
    return result.transfer() == null ? 0 : result.transfer().delivered();
  }

  @GameTest(template = "empty", batch = "wireless_overdrive", timeoutTicks = 600)
  public static void bothPaidEndsRaiseSharedResourceCapsWithoutRaisingBankRates(GameTestHelper h) {
    var f = setup(h);
    var extraRoot = bank(h, ROOT_BANK.above());
    var extraChild = bank(h, CHILD_BANK.above());
    h.startSequence()
        .thenIdle(3)
        .thenExecute(() -> pair(h, f))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> f.ends.getFirst().install(ThroughputTier.ENDGAME))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.ends.getFirst().pairScale() == 1,
                  "One paid endpoint cannot raise the pair's limit");
              f.ends.get(1).install(ThroughputTier.ENDGAME);
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.ends.getFirst().pairScale() == 4 && f.ends.get(1).pairScale() == 4,
                  "Both paid ends publish the upgraded pair scale");
              f.saved.registry().insert(f.root[0], IRON, 2000);
              f.saved.registry().insert(f.root[2], WATER, 16000);
              f.saved.registry().insert(f.rootBank, ResourceKey.ENERGY, 16000);
              f.saved.registry().insert(extraRoot, ResourceKey.ENERGY, 16000);
              var denied = submitOne(h, f, ROOT, f.root[0], f.child[0], IRON, 128);
              h.assertTrue(
                  denied.reason() == OperationResult.Reason.DENIED
                      && f.stock(f.root[0], IRON) == 2000,
                  "Upgraded routing still denies a root request into child-private storage");
              long available =
                  f.networks.movementAvailable(position(h, ROOT), position(h, CHILD), IRON, 2000);
              long actualItems = move(h, f, CHILD, f.root[0], f.child[0], IRON, 1500);
              h.assertTrue(
                  actualItems == 1024,
                  "Actual item callers share the 1024-item upgraded cap; available="
                      + available
                      + " actual="
                      + actualItems
                      + " localScale="
                      + f.ends.getFirst().pairScale());
              h.assertTrue(
                  move(h, f, CHILD, f.root[2], f.child[2], WATER, 16000) == 16000
                      && move(h, f, CHILD, f.child[2], f.root[2], WATER, 1) == 0,
                  "Fluid callers share 16000 mB in both directions");
              h.assertTrue(
                  move(h, f, CHILD, f.rootBank, f.childBank, ResourceKey.ENERGY, 16000) == 16000
                      && move(h, f, CHILD, extraRoot, extraChild, ResourceKey.ENERGY, 16000)
                          == 16000
                      && move(h, f, CHILD, f.childBank, f.rootBank, ResourceKey.ENERGY, 1) == 0,
                  "Two finite banks use the shared 32000 FE cap without raising either bank's rate");
              h.assertTrue(
                  f.stock(f.root[0], IRON) + f.stock(f.child[0], IRON) == 2000
                      && f.stock(f.root[2], WATER) + f.stock(f.child[2], WATER) == 16000
                      && f.stock(f.rootBank, ResourceKey.ENERGY)
                              + f.stock(f.childBank, ResourceKey.ENERGY)
                              + f.stock(extraRoot, ResourceKey.ENERGY)
                              + f.stock(extraChild, ResourceKey.ENERGY)
                          == 32000,
                  "All upgraded resource movements conserve exact ownership");
              f.player.connection.disconnect(Component.literal("Overdrive pair fixture complete"));
            })
        .thenSucceed();
  }

  /** Moves up to {@code amount} as successive transfer quanta inside one fixed server tick. */
  private static long move(
      GameTestHelper h,
      Fixture f,
      BlockPos origin,
      DeviceRegistry.Handle from,
      DeviceRegistry.Handle to,
      ResourceKey key,
      long amount) {
    long quantum = key.kind() == ResourceKind.ITEM ? 128 : 1000;
    long total = 0;
    while (total < amount) {
      long chunk = Math.min(quantum, amount - total);
      long moved = delivered(submitOne(h, f, origin, from, to, key, chunk));
      total += moved;
      if (moved < chunk) break;
    }
    return total;
  }

  @GameTest(template = "empty", batch = "wireless_throughput", timeoutTicks = 600)
  public static void
      bidirectionalCellsFluidAndEnergySharePairCapsWithoutBlockingWiredOrSiblingRoutes(
          GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenIdle(3)
        .thenExecute(() -> pair(h, f))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.root[0], IRON, 1000);
              f.saved.registry().insert(f.child[1], IRON, 1000);
              f.saved.registry().insert(f.root[2], WATER, 10000);
              f.saved.registry().insert(f.child[3], WATER, 10000);
              f.saved.registry().insert(f.rootBank, ResourceKey.ENERGY, 50000);
              f.saved.registry().insert(f.childBank, ResourceKey.ENERGY, 50000);
              h.assertTrue(
                  move(h, f, CHILD, f.root[0], f.child[0], IRON, 200) == 200,
                  "First direction moves200 items");
              h.assertTrue(
                  move(h, f, CHILD, f.child[1], f.root[1], IRON, 200) == 56,
                  "Reverse direction and different cells share the remaining56 item units");
              h.assertTrue(
                  move(h, f, CHILD, f.root[0], f.child[0], IRON, 1) == 0,
                  "Exhausted pair refuses another item");
              h.assertTrue(
                  move(h, f, CHILD, f.root[2], f.child[2], WATER, 3000) == 3000,
                  "Independent fluid budget moves3000mB");
              h.assertTrue(
                  move(h, f, CHILD, f.child[3], f.root[3], WATER, 3000) == 1000,
                  "Reverse tanks share4000mB aggregate");
              h.assertTrue(
                  move(h, f, CHILD, f.rootBank, f.childBank, ResourceKey.ENERGY, 6000) == 6000,
                  "Energy moves6000FE");
              h.assertTrue(
                  move(h, f, CHILD, f.childBank, f.rootBank, ResourceKey.ENERGY, 6000) == 2000,
                  "Reverse banks share8000FE aggregate");
              h.assertTrue(
                  move(h, f, ROOT, f.root[0], f.root[1], IRON, 400) == 400,
                  "Unrelated wired route progresses despite exhausted pair");
              h.assertTrue(
                  move(h, f, SIBLING, f.root[0], f.sibling[0], IRON, 256) == 256,
                  "Sibling pair retains its independent256 item capacity");
              h.assertTrue(
                  f.stock(f.root[0], IRON)
                          + f.stock(f.root[1], IRON)
                          + f.stock(f.child[0], IRON)
                          + f.stock(f.child[1], IRON)
                          + f.stock(f.sibling[0], IRON)
                      == 2000,
                  "Item conservation across all routes");
              h.assertTrue(
                  f.stock(f.root[2], WATER)
                          + f.stock(f.root[3], WATER)
                          + f.stock(f.child[2], WATER)
                          + f.stock(f.child[3], WATER)
                      == 20000,
                  "Fluid conservation");
              h.assertTrue(
                  f.stock(f.rootBank, ResourceKey.ENERGY) + f.stock(f.childBank, ResourceKey.ENERGY)
                      == 100000,
                  "Transferred FE conserved independently of endpoint operating FE");
              f.player.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }

  // The nested chain spans 13 blocks, wider than the batch spacing, so it runs alone.
  @GameTest(template = "empty", batch = "wireless_throughput_nested", timeoutTicks = 600)
  public static void nestedRoutesUseTightestHopAndNeverChargeTheCommonSegmentsUpstreamPair(
      GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenIdle(3)
        .thenExecute(() -> pair(h, f))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.root[0], IRON, 1000);
              f.saved.registry().insert(f.child[1], IRON, 1000);
              h.assertTrue(
                  move(h, f, CHILD, f.root[0], f.child[0], IRON, 200) == 200,
                  "Ancestor pair used200");
              long twoHop = move(h, f, NESTED, f.root[0], f.nested[0], IRON, 100);
              h.assertTrue(
                  twoHop == 56, "Two-hop route uses tightest remaining56 units: " + twoHop);
              h.assertTrue(
                  move(h, f, NESTED, f.child[1], f.nested[1], IRON, 256) == 200,
                  "Child-local to nested crosses only its own remaining200 pair units");
              h.assertTrue(
                  f.stock(f.root[0], IRON) == 744
                      && f.stock(f.child[0], IRON) == 200
                      && f.stock(f.nested[0], IRON) == 56
                      && f.stock(f.child[1], IRON) == 800
                      && f.stock(f.nested[1], IRON) == 200,
                  "Nested partial moves preserve every item");
              // Re-pairing the same physical ends must preserve physical ownership and quantities.
              pair(h, f);
              h.assertTrue(
                  f.stock(f.root[0], IRON)
                          + f.stock(f.child[0], IRON)
                          + f.stock(f.child[1], IRON)
                          + f.stock(f.nested[0], IRON)
                          + f.stock(f.nested[1], IRON)
                      == 2000,
                  "Re-pairing preserves all owners and contents");
              f.player.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
