package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.testing.ConnectorFixture;
import dev.izzy.factorycore.testing.Net;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class StorageSearchGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos CONNECTOR = new BlockPos(2, 1, 1);
  private static final BlockPos PROVIDER = new BlockPos(3, 1, 1);
  private static final BlockPos DRIVE = new BlockPos(1, 1, 2);

  private record Fixture(
      NetworkPermissions.Actor actor,
      NetworkNodeEntity root,
      ConnectorEntity connector,
      ConnectorFixture.ProviderEntity provider,
      DeviceRegistry.Handle cell,
      DeviceSavedData saved,
      WorldNetworks runtime) {}

  private StorageSearchGameTests() {}

  private static Fixture setup(GameTestHelper helper, boolean privateChild) {
    var actor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    NetworkNodeEntity root = Net.in(helper, actor.id()).controller(ROOT);
    var connectorPosition = privateChild ? new BlockPos(3, 1, 1) : CONNECTOR;
    var providerPosition = privateChild ? new BlockPos(4, 1, 1) : PROVIDER;
    if (privateChild) {
      helper.setBlock(
          CONNECTOR,
          NetworkContent.GATEWAY
              .get()
              .defaultBlockState()
              .setValue(BlockStateProperties.FACING, Direction.EAST));
      NetworkNodeEntity gate = helper.getBlockEntity(CONNECTOR);
      gate.commission(null);
    }
    helper.setBlock(
        connectorPosition,
        ConnectorContent.BLOCK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    ConnectorEntity connector = helper.getBlockEntity(connectorPosition);
    connector.place(new ItemStack(ConnectorContent.ITEM.get()));
    helper.setBlock(providerPosition, ConnectorFixture.BLOCK.get());
    ConnectorFixture.ProviderEntity provider = helper.getBlockEntity(providerPosition);
    StorageDriveEntity drive = Net.in(helper).drive(DRIVE, CellTier.STARTER_ITEM);
    return new Fixture(
        actor,
        root,
        connector,
        provider,
        CellHandleData.read(drive.cell(0)),
        DeviceSavedData.get(helper.getLevel().getServer()),
        WorldNetworks.get(helper.getLevel().getServer()));
  }

  private static NodeRegistry.Position origin(GameTestHelper helper) {
    return Positions.of(helper.getLevel(), helper.absolutePos(ROOT));
  }

  private static void ready(GameTestHelper helper, Fixture fixture) {
    helper.assertTrue(
        fixture.runtime().membership(origin(helper)).status() == NetworkTopology.Status.VALID
            && fixture.runtime().membership(fixture.connector().position()).status()
                == NetworkTopology.Status.VALID,
        "Search and provider scopes ready");
  }

  private static ItemStack named(int index, int count) {
    var stack = new ItemStack(Items.IRON_INGOT, count);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Exact catalog variant " + index));
    return stack;
  }

  private static ResourceKey key(GameTestHelper helper, ItemStack stack) {
    return new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(stack);
  }

  private static UUID start(GameTestHelper helper, Fixture fixture, String query) {
    var id = UUID.randomUUID();
    helper.assertTrue(
        fixture
                .runtime()
                .transfers()
                .search()
                .start(id, fixture.actor(), origin(helper), ResourceKind.ITEM, query)
                .rejection()
            == OperationResult.Reason.OK,
        "Search admitted");
    return id;
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void allKeySearchPagesExactVariantsAndAppliesForeignHoldsOnceAcrossSlotPages(
      GameTestHelper helper) {
    var fixture = setup(helper, false);
    fixture.provider().items.setSize(32);
    fixture.provider().items.setStackInSlot(0, named(0, 10));
    fixture.provider().items.setStackInSlot(17, named(0, 20));
    fixture.provider().items.setStackInSlot(31, new ItemStack(Items.DIAMOND, 7));
    UUID[] query = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              for (int index = 0; index < 40; index++)
                fixture
                    .saved()
                    .registry()
                    .insert(fixture.cell(), key(helper, named(index, 1)), index + 1);
              fixture
                  .saved()
                  .registry()
                  .reserve(fixture.cell(), UUID.randomUUID(), key(helper, named(0, 1)), 1);
              var registration = fixture.runtime().connectors().bind(fixture.connector());
              helper.assertTrue(registration.handle() != null, "Canonical provider identity bound");
              fixture
                  .saved()
                  .externalClaims()
                  .reserve(
                      registration.handle().backing(),
                      fixture.actor().id(),
                      key(helper, named(0, 1)),
                      12,
                      30);
              query[0] = start(helper, fixture, "");
            })
        .thenWaitUntil(
            () -> {
              var result =
                  fixture.runtime().transfers().search().result(query[0], fixture.actor(), null, 5);
              helper.assertTrue(
                  result.reason() == OperationResult.Reason.OK && result.complete(),
                  "Complete bounded all-key scan");
            })
        .thenExecute(
            () -> {
              var rows = new ArrayList<CatalogCounts.Row>();
              ResourceKey cursor = null;
              int pages = 0;
              do {
                var page =
                    fixture
                        .runtime()
                        .transfers()
                        .search()
                        .result(query[0], fixture.actor(), cursor, 5);
                helper.assertTrue(
                    page.rows().size() <= 5 && ++pages <= 9, "Bounded sorted result pages");
                rows.addAll(page.rows());
                cursor = page.next();
              } while (cursor != null);
              helper.assertTrue(
                  rows.size() == 41
                      && rows.stream().map(CatalogCounts.Row::key).distinct().count() == 41,
                  "All component variants plus diamond, no duplicates");
              var iron =
                  rows.stream()
                      .filter(row -> row.key().equals(key(helper, named(0, 1))))
                      .findFirst()
                      .orElseThrow();
              helper.assertTrue(
                  iron.total().equals(BigInteger.valueOf(31))
                      && iron.reserved().equals(BigInteger.valueOf(13))
                      && iron.available().equals(BigInteger.valueOf(18)),
                  "Digital and multi-page foreign holds are applied once per backing");
              helper.assertTrue(
                  fixture.provider().items.getStackInSlot(0).getCount() == 10
                      && fixture.provider().items.getStackInSlot(17).getCount() == 20,
                  "Search performs no resource mutation");
              helper.assertTrue(
                  fixture.runtime().transfers().search().close(query[0], fixture.actor()),
                  "Session releases retained metadata");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void filteredSearchKeepsExactIdentityAndExcludesOwnedMachineBuffer(
      GameTestHelper helper) {
    var fixture = setup(helper, false);
    var bufferPosition = new BlockPos(3, 1, 2);
    helper.setBlock(new BlockPos(2, 1, 2), NetworkContent.CABLE.get());
    BufferedInterfaceEntity buffer = Net.in(helper, fixture.actor().id()).buffer(bufferPosition);
    fixture.provider().items.setStackInSlot(0, new ItemStack(Items.DIAMOND, 5));
    UUID[] query = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture
                  .saved()
                  .registry()
                  .insert(fixture.cell(), key(helper, new ItemStack(Items.DIAMOND)), 4);
              fixture.saved().registry().insert(fixture.cell(), key(helper, named(0, 1)), 8);
              buffer.capability().insertItem(0, new ItemStack(Items.DIAMOND, 64), false);
              query[0] = start(helper, fixture, "DIAMOND");
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    fixture
                        .runtime()
                        .transfers()
                        .search()
                        .result(query[0], fixture.actor(), null, 32)
                        .complete(),
                    "Filtered scan ready"))
        .thenExecute(
            () -> {
              var rows =
                  fixture
                      .runtime()
                      .transfers()
                      .search()
                      .result(query[0], fixture.actor(), null, 32)
                      .rows();
              helper.assertTrue(
                  rows.size() == 1
                      && rows.getFirst().total().equals(BigInteger.valueOf(9))
                      && buffer.inventory().total(key(helper, new ItemStack(Items.DIAMOND))) == 64,
                  "Registry filter includes permitted storage and excludes real local buffers");
              fixture.runtime().transfers().search().close(query[0], fixture.actor());
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void parentSearchCannotReadPrivateProviderEvenWithOperatorFlag(
      GameTestHelper helper) {
    var fixture = setup(helper, true);
    fixture.provider().items.setStackInSlot(0, named(0, 30));
    fixture.provider().failSecondStockRead = true;
    var actor = new NetworkPermissions.Actor(fixture.actor().id(), true);
    var id = UUID.randomUUID();
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture
                  .runtime()
                  .transfers()
                  .search()
                  .start(id, actor, origin(helper), ResourceKind.ITEM, "");
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    fixture.runtime().transfers().search().result(id, actor, null, 32).complete(),
                    "Private scope scan ready"))
        .thenExecute(
            () -> {
              var result = fixture.runtime().transfers().search().result(id, actor, null, 32);
              helper.assertTrue(
                  result.reason() == OperationResult.Reason.OK
                      && result.rows().isEmpty()
                      && fixture.connector().diagnostic().isEmpty()
                      && fixture.provider().getPersistentData().isEmpty(),
                  "Private provider was neither probed, marked nor read; operator does not widen scope");
              fixture.runtime().transfers().search().close(id, actor);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void queuedSearchRevocationRejectsWithoutReadingProvider(GameTestHelper helper) {
    var fixture = setup(helper, false);
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    fixture.provider().failSecondStockRead = true;
    var id = UUID.randomUUID();
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              var grants = fixture.saved().network(fixture.root().handle().id());
              grants.setGrants(
                  fixture.actor(), guest.id(), Set.of(NetworkPermissions.Permission.VIEW));
              helper.assertTrue(
                  fixture
                          .runtime()
                          .transfers()
                          .search()
                          .start(id, guest, origin(helper), ResourceKind.ITEM, "")
                          .rejection()
                      == OperationResult.Reason.OK,
                  "Authenticated view-only search queued");
              grants.setGrants(fixture.actor(), guest.id(), Set.of());
            })
        .thenIdle(20)
        .thenExecute(
            () -> {
              var result = fixture.runtime().transfers().search().result(id, guest, null, 32);
              helper.assertTrue(
                  result.reason() == OperationResult.Reason.DENIED
                      && result.rows().isEmpty()
                      && fixture.connector().diagnostic().isEmpty()
                      && fixture.provider().getPersistentData().isEmpty(),
                  "Revocation precedes any private provider read or publication");
              fixture.runtime().transfers().search().close(id, guest);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void readySearchRejectsOtherActorRevocationAndSameRootRebuild(
      GameTestHelper helper) {
    var fixture = setup(helper, false);
    fixture.provider().items.setStackInSlot(0, named(0, 5));
    UUID[] query = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(() -> query[0] = start(helper, fixture, ""))
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    fixture
                        .runtime()
                        .transfers()
                        .search()
                        .result(query[0], fixture.actor(), null, 32)
                        .complete(),
                    "Session ready"))
        .thenExecute(
            () -> {
              var service = fixture.runtime().transfers().search();
              var other = new NetworkPermissions.Actor(UUID.randomUUID(), true);
              helper.assertTrue(
                  service.result(query[0], other, null, 32).reason()
                          == OperationResult.Reason.DENIED
                      && !service.close(query[0], other),
                  "Session ID is not a reusable actor credential");
              fixture
                  .saved()
                  .network(fixture.root().handle().id())
                  .setGrants(
                      fixture.actor(), other.id(), Set.of(NetworkPermissions.Permission.VIEW));
              helper.assertTrue(
                  service.result(query[0], fixture.actor(), null, 32).reason()
                      == OperationResult.Reason.DENIED,
                  "Grant generation invalidates retained observations even when owner rights remain");
              query[0] = start(helper, fixture, "");
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    fixture
                        .runtime()
                        .transfers()
                        .search()
                        .result(query[0], fixture.actor(), null, 32)
                        .complete(),
                    "Replacement scan ready"))
        .thenExecute(() -> fixture.runtime().changed(origin(helper)))
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () ->
                helper.assertTrue(
                    fixture
                            .runtime()
                            .transfers()
                            .search()
                            .result(query[0], fixture.actor(), null, 32)
                            .reason()
                        == OperationResult.Reason.DENIED,
                    "Rebuilt topology does not inherit an old ready search session"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void providerFaultReturnsTypedFailureWithNoPartialSearchRows(
      GameTestHelper helper) {
    var fixture = setup(helper, false);
    fixture.provider().items.setStackInSlot(0, named(0, 30));
    fixture.provider().failSecondStockRead = true;
    UUID[] query = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.saved().registry().insert(fixture.cell(), key(helper, named(1, 1)), 40);
              query[0] = start(helper, fixture, "");
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    fixture
                        .runtime()
                        .transfers()
                        .search()
                        .result(query[0], fixture.actor(), null, 32)
                        .complete(),
                    "Faulting query terminates"))
        .thenExecute(
            () -> {
              var result =
                  fixture
                      .runtime()
                      .transfers()
                      .search()
                      .result(query[0], fixture.actor(), null, 32);
              helper.assertTrue(
                  result.reason() == OperationResult.Reason.HANDLER_FAULT
                      && result.rows().isEmpty()
                      && fixture
                              .saved()
                              .registry()
                              .stock(fixture.cell(), key(helper, named(1, 1)))
                              .total()
                          == 40,
                  "Failed search discards partial advisory data and retains all owned resources");
              fixture.runtime().transfers().search().close(query[0], fixture.actor());
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void sessionLimitReclaimsAdmissionOnCloseAndIdleExpiry(GameTestHelper helper) {
    var fixture = setup(helper, false);
    var admitted = new ArrayList<UUID>();
    UUID[] expired = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              var service = fixture.runtime().transfers().search();
              for (int index = 0; index < WorldStorageSearch.MAX_SEARCHES + 1; index++) {
                var id = UUID.randomUUID();
                var admission =
                    service.start(id, fixture.actor(), origin(helper), ResourceKind.ITEM, "");
                if (admission.rejection() == OperationResult.Reason.OK) admitted.add(id);
                else
                  helper.assertTrue(
                      admission.rejection() == OperationResult.Reason.TECHNICAL_LIMIT,
                      "Shared search count is bounded");
              }
              helper.assertTrue(
                  !admitted.isEmpty() && admitted.size() <= WorldStorageSearch.MAX_SEARCHES,
                  "At least one bounded search admitted");
              service.close(admitted.removeFirst(), fixture.actor());
              var replacement = start(helper, fixture, "");
              for (var id : admitted) service.close(id, fixture.actor());
              expired[0] = replacement;
            })
        .thenIdle(WorldStorageSearch.IDLE_TICKS + 100)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  fixture
                          .runtime()
                          .transfers()
                          .search()
                          .result(expired[0], fixture.actor(), null, 32)
                          .reason()
                      == OperationResult.Reason.DENIED,
                  "Unused transient sessions expire and release retained metadata");
              var replacement = start(helper, fixture, "");
              fixture.runtime().transfers().search().close(replacement, fixture.actor());
            })
        .thenSucceed();
  }

  private static void utilitySearch(GameTestHelper helper, boolean energy) {
    var fixture = setup(helper, false);
    DeviceRegistry.Handle[] cell = {null};
    ResourceKey[] resource = {null};
    UUID[] query = {null};
    var kind = energy ? ResourceKind.ENERGY : ResourceKind.FLUID;
    if (energy) {
      var bankPosition = new BlockPos(2, 1, 2);
      var tier = EnergyBankTier.STARTER;
      EnergyBankEntity bank = Net.in(helper).bank(bankPosition, tier);
      cell[0] = bank.handle();
      resource[0] = ResourceKey.ENERGY;
      fixture.provider().energy.receiveEnergy(800, false);
    } else {
      StorageDriveEntity drive = helper.getBlockEntity(DRIVE);
      drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_FLUID)));
      cell[0] = CellHandleData.read(drive.cell(1));
      var water = new FluidStack(Fluids.WATER, 1000);
      fixture.provider().fluids.fill(water, IFluidHandler.FluidAction.EXECUTE);
      resource[0] = new PlatformResourceCodec(helper.getLevel().registryAccess()).fluidKey(water);
    }
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture
                  .connector()
                  .configure(
                      fixture.actor(),
                      new ConnectorSettings(
                          kind, ConnectorSettings.Access.READ_WRITE, 0, 0, 64, 1000));
            })
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.saved().registry().insert(cell[0], resource[0], energy ? 3000 : 500);
              fixture
                  .saved()
                  .registry()
                  .reserve(cell[0], UUID.randomUUID(), resource[0], energy ? 500 : 100);
              var registration = fixture.runtime().connectors().bind(fixture.connector());
              helper.assertTrue(registration.handle() != null, "Typed utility provider registered");
              fixture
                  .saved()
                  .externalClaims()
                  .reserve(
                      registration.handle().backing(),
                      fixture.actor().id(),
                      resource[0],
                      energy ? 100 : 200,
                      energy ? 800 : 1000);
              query[0] = UUID.randomUUID();
              helper.assertTrue(
                  fixture
                          .runtime()
                          .transfers()
                          .search()
                          .start(query[0], fixture.actor(), origin(helper), kind, "")
                          .rejection()
                      == OperationResult.Reason.OK,
                  "Typed utility search admitted");
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    fixture
                        .runtime()
                        .transfers()
                        .search()
                        .result(query[0], fixture.actor(), null, 32)
                        .complete(),
                    "Typed utility search ready"))
        .thenExecute(
            () -> {
              var result =
                  fixture
                      .runtime()
                      .transfers()
                      .search()
                      .result(query[0], fixture.actor(), null, 32);
              helper.assertTrue(
                  result.reason() == OperationResult.Reason.OK && result.rows().size() == 1,
                  "One exact utility identity, no item rows");
              var row = result.rows().getFirst();
              helper.assertTrue(
                  row.key().equals(resource[0])
                      && row.total().equals(BigInteger.valueOf(energy ? 3800 : 1500))
                      && row.reserved().equals(BigInteger.valueOf(energy ? 600 : 300))
                      && row.available().equals(BigInteger.valueOf(energy ? 3200 : 1200)),
                  "Correct observable FE/mB units and unique backing claims");
              if (energy)
                helper.assertTrue(
                    row.capacity().equals(BigInteger.valueOf(102000)),
                    "Unique bank/provider observable FE capacity");
              else
                helper.assertTrue(row.capacity() == null, "No invented fluid-capacity attribution");
              fixture.runtime().transfers().search().close(query[0], fixture.actor());
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void fluidSearchReportsExactMillibucketsAcrossCellAndProvider(
      GameTestHelper helper) {
    utilitySearch(helper, false);
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void energySearchReportsUniqueObservableStoredReservedAndCapacity(
      GameTestHelper helper) {
    utilitySearch(helper, true);
  }

  @GameTest(template = "empty", timeoutTicks = 800)
  public static void sharedMetadataQuotaReturnsNoPartialRowsAndCloseReclaimsRealAdmission(
      GameTestHelper helper) {
    var fixture = setup(helper, false);
    var ids = new ArrayList<UUID>();
    UUID[] replacement = {null};
    ResourceKey[] first = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              for (int index = 0; index < 100; index++) {
                var stack = named(index, 1);
                CustomData.update(
                    DataComponents.CUSTOM_DATA,
                    stack,
                    tag -> tag.putByteArray("Metadata", new byte[60000]));
                var identity = key(helper, stack);
                if (index == 0) first[0] = identity;
                helper.assertTrue(
                    fixture.saved().registry().insert(fixture.cell(), identity, 1).amount() == 1,
                    "Fixture admits large exact keys within owned storage quotas");
              }
              for (int index = 0; index < 3; index++) ids.add(start(helper, fixture, ""));
            })
        .thenWaitUntil(
            () -> {
              for (var id : ids)
                helper.assertTrue(
                    fixture
                        .runtime()
                        .transfers()
                        .search()
                        .result(id, fixture.actor(), null, 32)
                        .complete(),
                    "Shared bounded query completes or reports admission failure");
            })
        .thenExecute(
            () -> {
              var successes = new ArrayList<UUID>();
              int failures = 0;
              for (var id : ids) {
                var result =
                    fixture.runtime().transfers().search().result(id, fixture.actor(), null, 32);
                if (result.reason() == OperationResult.Reason.OK) successes.add(id);
                else {
                  helper.assertTrue(
                      result.reason() == OperationResult.Reason.TECHNICAL_LIMIT
                          && result.rows().isEmpty(),
                      "Shared metadata excess reports a typed failure without partial stock");
                  failures++;
                }
              }
              helper.assertTrue(
                  !successes.isEmpty() && failures > 0,
                  "Shared sixteen-MiB budget bounds simultaneous heavy searches");
              for (var id : ids) fixture.runtime().transfers().search().close(id, fixture.actor());
              replacement[0] = start(helper, fixture, "");
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    fixture
                        .runtime()
                        .transfers()
                        .search()
                        .result(replacement[0], fixture.actor(), null, 32)
                        .complete(),
                    "Replacement heavy search completes after releasing sessions"))
        .thenExecute(
            () -> {
              var result =
                  fixture
                      .runtime()
                      .transfers()
                      .search()
                      .result(replacement[0], fixture.actor(), null, 32);
              helper.assertTrue(
                  result.reason() == OperationResult.Reason.OK
                      && result.rows().size() <= 2
                      && fixture.saved().registry().stock(fixture.cell(), first[0]).total() == 1,
                  "Closing heavy sessions reclaims metadata; result payload remains bounded and stock unchanged");
              fixture.runtime().transfers().search().close(replacement[0], fixture.actor());
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void inactiveLedgerCannotAppearAsAvailableLoadedSearchStock(GameTestHelper helper) {
    var fixture = setup(helper, false);
    UUID[] query = {null};
    var identity = key(helper, named(0, 1));
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.saved().registry().insert(fixture.cell(), identity, 15);
              var location =
                  new DeviceRegistry.Location(
                      helper.getLevel().dimension().location().toString(),
                      helper.absolutePos(DRIVE).asLong(),
                      0);
              helper.assertTrue(
                  fixture.saved().registry().availability(fixture.cell(), location, false)
                      == DeviceRegistry.Failure.OK,
                  "Fixture retains a physical valid lease with inactive ledger availability");
              query[0] = start(helper, fixture, "");
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    fixture
                        .runtime()
                        .transfers()
                        .search()
                        .result(query[0], fixture.actor(), null, 32)
                        .complete(),
                    "Inactive ledger query completes"))
        .thenExecute(
            () -> {
              var result =
                  fixture
                      .runtime()
                      .transfers()
                      .search()
                      .result(query[0], fixture.actor(), null, 32);
              helper.assertTrue(
                  result.reason() == OperationResult.Reason.OK
                      && result.rows().isEmpty()
                      && fixture.saved().registry().stock(fixture.cell(), identity).total() == 15
                      && !fixture.saved().registry().stock(fixture.cell(), identity).loaded(),
                  "Loaded-only search cannot advertise inactive owned stock as available or erase it");
              fixture.runtime().transfers().search().close(query[0], fixture.actor());
            })
        .thenSucceed();
  }
}
