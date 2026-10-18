package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourcePort;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import dev.izzy.factorycore.core.resource.TransferContext;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.testing.ConnectorFixture;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class ConnectorGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos CABLE = new BlockPos(2, 1, 1);
  private static final BlockPos CONNECTOR = new BlockPos(3, 1, 1);
  private static final BlockPos PROVIDER = new BlockPos(4, 1, 1);
  private static final BlockPos STORAGE = new BlockPos(2, 1, 2);

  private ConnectorGameTests() {}

  private record Setup(
      NetworkPermissions.Actor actor,
      NetworkNodeEntity root,
      ConnectorEntity connector,
      WorldNetworks runtime,
      DeviceSavedData saved) {}

  private static NodeRegistry.Position position(GameTestHelper helper, BlockPos pos) {
    return new NodeRegistry.Position(
        helper.getLevel().dimension().location().toString(), helper.absolutePos(pos).asLong());
  }

  private static void ready(GameTestHelper helper, WorldNetworks runtime, BlockPos pos) {
    helper.assertTrue(
        runtime.membership(position(helper, pos)).status() == NetworkTopology.Status.VALID,
        "Wired endpoint ready");
  }

  private static ConnectorEntity connector(GameTestHelper helper, BlockPos pos, Direction facing) {
    helper.setBlock(
        pos,
        ConnectorContent.BLOCK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, facing));
    ConnectorEntity entity = helper.getBlockEntity(pos);
    entity.place(new ItemStack(ConnectorContent.ITEM.get()));
    return entity;
  }

  private static Setup setup(GameTestHelper helper) {
    var actor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(actor.id());
    helper.setBlock(CABLE, NetworkContent.CABLE.get());
    var entity = connector(helper, CONNECTOR, Direction.EAST);
    return new Setup(
        actor,
        root,
        entity,
        WorldNetworks.get(helper.getLevel().getServer()),
        DeviceSavedData.get(helper.getLevel().getServer()));
  }

  private static ItemStack marked(int amount) {
    var stack = new ItemStack(Items.IRON_INGOT, amount);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Exact connector identity"));
    return stack;
  }

  private static void transferScenario(GameTestHelper helper, int mode) {
    var setup = setup(helper);
    var kind = mode == 4 ? ResourceKind.FLUID : mode == 5 ? ResourceKind.ENERGY : ResourceKind.ITEM;
    boolean fixture = mode >= 3;
    helper.setBlock(PROVIDER, fixture ? ConnectorFixture.BLOCK.get() : Blocks.CHEST);
    var codec = new PlatformResourceCodec(helper.getLevel().registryAccess());
    var key =
        kind == ResourceKind.ENERGY
            ? ResourceKey.ENERGY
            : kind == ResourceKind.FLUID
                ? codec.fluidKey(new FluidStack(Fluids.WATER, 1000))
                : codec.itemKey(marked(64));
    dev.izzy.factorycore.core.storage.DeviceRegistry.Handle digital;
    if (kind == ResourceKind.ENERGY) {
      helper.setBlock(STORAGE, EnergyBankContent.block(EnergyBankTier.STARTER));
      EnergyBankEntity bank = helper.getBlockEntity(STORAGE);
      bank.place(new ItemStack(EnergyBankContent.item(EnergyBankTier.STARTER)));
      digital = bank.handle();
    } else {
      helper.setBlock(STORAGE, StorageContent.DRIVE.get());
      StorageDriveEntity drive = helper.getBlockEntity(STORAGE);
      drive.installCell(
          new ItemStack(
              StorageContent.cell(
                  kind == ResourceKind.FLUID ? CellTier.STARTER_FLUID : CellTier.STARTER_ITEM)));
      digital = CellHandleData.read(drive.cell(0));
    }
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var id = UUID.randomUUID();
    WorldStorageTransfers.Result[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, setup.runtime(), CONNECTOR);
              ready(helper, setup.runtime(), STORAGE);
            })
        .thenExecute(
            () -> {
              setup.connector().configure(setup.actor(), ConnectorSettings.defaults(kind));
              long remainder =
                  kind == ResourceKind.FLUID ? 123 : kind == ResourceKind.ENERGY ? 300 : 3;
              long capacity =
                  kind == ResourceKind.FLUID ? 16000 : kind == ResourceKind.ENERGY ? 100000 : 8192;
              if (mode == 1) setup.saved().registry().insert(digital, key, 100);
              else if (mode != 3)
                setup.saved().registry().insert(digital, key, capacity - remainder);
              if (fixture) {
                ConnectorFixture.ProviderEntity provider = helper.getBlockEntity(PROVIDER);
                if (kind == ResourceKind.FLUID)
                  provider.fluids.fill(new FluidStack(Fluids.WATER, 2000), FluidAction.EXECUTE);
                else if (kind == ResourceKind.ENERGY) {
                  provider.energy.receiveEnergy(1000, false);
                  provider.energy.receiveEnergy(1000, false);
                } else {
                  provider.items.setStackInSlot(0, marked(64));
                  provider.failAfterExtraction = true;
                }
              } else {
                ChestBlockEntity chest = helper.getBlockEntity(PROVIDER);
                chest.setItem(0, marked(mode == 1 ? 60 : 64));
                if (mode == 1)
                  for (int slot = 1; slot < 27; slot++)
                    chest.setItem(slot, new ItemStack(Items.GOLD_INGOT, 64));
              }
              if (mode == 1) setup.saved().registry().reserve(digital, UUID.randomUUID(), key, 60);
              var registration = setup.runtime().connectors().bind(setup.connector());
              helper.assertTrue(
                  registration.reason() == OperationResult.Reason.OK, "Sided provider registered");
              var external = new WorldStorageTransfers.External(registration.handle());
              var owned = new WorldStorageTransfers.Digital(digital);
              if (mode == 2)
                setup
                    .saved()
                    .network(setup.root().handle().id())
                    .setGrants(
                        setup.actor(),
                        guest.id(),
                        Set.of(
                            NetworkPermissions.Permission.WITHDRAW,
                            NetworkPermissions.Permission.DEPOSIT));
              setup
                  .runtime()
                  .transfers()
                  .submit(
                      id,
                      mode == 2 ? guest : setup.actor(),
                      position(helper, ROOT),
                      mode == 1 ? owned : external,
                      mode == 1 ? external : owned,
                      key,
                      kind == ResourceKind.ITEM ? 64 : 1000,
                      value -> result[0] = value);
              if (mode == 2)
                setup
                    .saved()
                    .network(setup.root().handle().id())
                    .setGrants(setup.actor(), guest.id(), Set.of());
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Transfer completed"))
        .thenExecute(
            () -> {
              if (mode == 2) {
                helper.assertTrue(
                    result[0].reason() == OperationResult.Reason.DENIED
                        && !setup.saved().transfers().contains(id),
                    "Revoked foreign transfer denied before recording");
                ChestBlockEntity chest = helper.getBlockEntity(PROVIDER);
                helper.assertTrue(
                    chest.getItem(0).getCount() == 64, "Revocation preserved foreign contents");
              } else if (mode == 3) {
                helper.assertTrue(
                    result[0].reason() == OperationResult.Reason.UNCERTAIN
                        && result[0].transfer().state() == StagedTransfer.State.QUARANTINED
                        && result[0].transfer().staged() == 0,
                    "Post-mutation exception preserved uncertainty, never fabricated staging");
                helper.assertTrue(
                    !setup.connector().diagnostic().isEmpty()
                        && setup.saved().transfers().cancel(id).reason()
                            == OperationResult.Reason.UNCERTAIN,
                    "Provider and transfer quarantined without refund");
                ConnectorFixture.ProviderEntity provider = helper.getBlockEntity(PROVIDER);
                helper.assertTrue(
                    provider.items.getStackInSlot(0).isEmpty()
                        && setup.saved().registry().stock(digital, key).total() == 0,
                    "Unknown extraction not replayed or credited");
              } else {
                long delivered =
                    mode == 1
                        ? 4
                        : kind == ResourceKind.FLUID ? 123 : kind == ResourceKind.ENERGY ? 300 : 3;
                helper.assertTrue(
                    result[0].transfer().delivered() == delivered
                        && result[0].transfer().staged() == 0,
                    "Actual partial quantity accounted exactly");
                if (kind == ResourceKind.FLUID || kind == ResourceKind.ENERGY) {
                  ConnectorFixture.ProviderEntity provider = helper.getBlockEntity(PROVIDER);
                  long left =
                      kind == ResourceKind.FLUID
                          ? provider.fluids.getFluidAmount()
                          : provider.energy.getEnergyStored();
                  long capacity = kind == ResourceKind.FLUID ? 16000 : 100000;
                  helper.assertTrue(
                      left == 2000 - delivered
                          && setup.saved().registry().stock(digital, key).total() == capacity,
                      "Provider and owned resource units conserved");
                } else {
                  ChestBlockEntity chest = helper.getBlockEntity(PROVIDER);
                  helper.assertTrue(
                      chest.getItem(0).getCount() == (mode == 1 ? 64 : 61),
                      "Foreign remainder preserved exact identity");
                  helper.assertTrue(
                      setup.saved().registry().stock(digital, key).total()
                          == (mode == 1 ? 96 : 8192),
                      "Digital count conserved");
                  if (mode == 1)
                    helper.assertTrue(
                        setup.saved().registry().stock(digital, key).reserved() == 60,
                        "Export preserves other reservation");
                }
              }
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void partialChestImportPreservesComponents(GameTestHelper helper) {
    transferScenario(helper, 0);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void partialChestExportHonorsReservation(GameTestHelper helper) {
    transferScenario(helper, 1);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void queuedForeignTransferRechecksRevocation(GameTestHelper helper) {
    transferScenario(helper, 2);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void faultyProviderQuarantinesUnknownExtraction(GameTestHelper helper) {
    transferScenario(helper, 3);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void realSidedTankMovesExactMillibuckets(GameTestHelper helper) {
    transferScenario(helper, 4);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void realSidedProviderMovesExactFE(GameTestHelper helper) {
    transferScenario(helper, 5);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void ratesPolicyAndCurrentProviderAreRechecked(GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, ConnectorFixture.BLOCK.get());
    helper.setBlock(STORAGE, NetworkContent.CABLE.get());
    var codec = new PlatformResourceCodec(helper.getLevel().registryAccess());
    var key = codec.itemKey(marked(64));
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              var registration = setup.runtime().connectors().bind(setup.connector());
              helper.assertTrue(
                  registration.reason() == OperationResult.Reason.OK, "Provider ready");
              var port = setup.runtime().connectors().port(registration.handle());
              helper.assertTrue(
                  port.simulateInsert(key, 64) == 64
                      && port.simulateInsert(key, 64) == 64
                      && port.insert(key, 50) == 50
                      && port.insert(key, 20) == 14
                      && port.insert(key, 1) == 0,
                  "Simulations preserve shared per-tick budget; actual writes consume it");
              helper.assertTrue(
                  setup.runtime().connectors().limit(registration.handle(), true, 1).reason()
                      == OperationResult.Reason.RATE_LIMIT,
                  "Rate rejection is typed");
              var denied =
                  setup
                      .connector()
                      .configure(
                          new NetworkPermissions.Actor(UUID.randomUUID(), false),
                          ConnectorSettings.defaults(ResourceKind.ENERGY));
              helper.assertTrue(
                  denied == NetworkAuthority.Decision.DENIED
                      && setup.connector().settings().kind() == ResourceKind.ITEM,
                  "Unprivileged configuration leaves policy intact");
              setup
                  .connector()
                  .configure(
                      setup.actor(),
                      new ConnectorSettings(
                          ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 10, 0, 64, 64));
              helper.assertTrue(
                  !setup
                      .runtime()
                      .connectors()
                      .allowed(
                          setup.actor(),
                          position(helper, ROOT),
                          registration.handle(),
                          key,
                          NetworkPermissions.Permission.DEPOSIT),
                  "Policy change invalidates old handle");
              var current = setup.runtime().connectors().bind(setup.connector());
              helper.assertTrue(
                  setup.runtime().connectors().port(current.handle()).insert(key, 1) == 0,
                  "Reconfiguration cannot reset backing rate budget");
              setup
                  .connector()
                  .configure(
                      setup.actor(),
                      new ConnectorSettings(
                          ResourceKind.ITEM, ConnectorSettings.Access.READ_ONLY, 10, 0, 64, 64));
              current = setup.runtime().connectors().bind(setup.connector());
              helper.assertTrue(
                  !setup
                      .runtime()
                      .connectors()
                      .allowed(
                          setup.actor(),
                          position(helper, ROOT),
                          current.handle(),
                          key,
                          NetworkPermissions.Permission.DEPOSIT),
                  "Read-only denies deposits");
              helper.setBlock(PROVIDER, Blocks.AIR);
              helper.setBlock(PROVIDER, ConnectorFixture.BLOCK.get());
              helper.assertTrue(
                  !setup
                      .runtime()
                      .connectors()
                      .allowed(
                          setup.actor(),
                          position(helper, ROOT),
                          current.handle(),
                          key,
                          NetworkPermissions.Permission.WITHDRAW),
                  "Replacement provider cannot use old handle");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void movementPreservesPolicyAndConsumedLeaseCannotClone(GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, Blocks.CHEST);
    var movedPos = new BlockPos(2, 1, 2);
    var old = setup.connector().handle();
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              setup
                  .connector()
                  .configure(
                      setup.actor(),
                      new ConnectorSettings(
                          ResourceKind.ITEM, ConnectorSettings.Access.WRITE_ONLY, -10, 2, 3, 32));
              var portable = setup.connector().portableForBreak();
              helper.setBlock(CONNECTOR, Blocks.AIR);
              helper.setBlock(
                  movedPos,
                  ConnectorContent.BLOCK
                      .get()
                      .defaultBlockState()
                      .setValue(BlockStateProperties.FACING, Direction.EAST));
              ConnectorEntity moved = helper.getBlockEntity(movedPos);
              moved.place(portable);
              helper.assertTrue(
                  moved.handle().id().equals(old.id())
                      && moved.handle().generation() == old.generation() + 2
                      && moved.settings().access() == ConnectorSettings.Access.WRITE_ONLY
                      && moved.settings().priority() == -10
                      && moved.settings().firstSlot() == 2,
                  "Portable transition retains one identity and selected policy");
              helper.setBlock(CONNECTOR, ConnectorContent.BLOCK.get());
              ConnectorEntity copy = helper.getBlockEntity(CONNECTOR);
              copy.place(portable.copy());
              helper.assertTrue(
                  !copy.diagnostic().isEmpty(), "Consumed portable lease rejects copy");
              moved.validatePlaced();
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void sidedAvailabilityMalformedKeysAndOpaqueBackingReject(GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, ConnectorFixture.BLOCK.get());
    var wrongSide = new BlockPos(4, 1, 0);
    helper.setBlock(new BlockPos(3, 1, 0), NetworkContent.CABLE.get());
    var wrong = connector(helper, wrongSide, Direction.SOUTH);
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, setup.runtime(), CONNECTOR);
              ready(helper, setup.runtime(), wrongSide);
            })
        .thenExecute(
            () -> {
              helper.assertTrue(
                  setup.runtime().connectors().bind(wrong).reason()
                      == OperationResult.Reason.OFFLINE,
                  "Provider denies unsupported side");
              var good = setup.runtime().connectors().bind(setup.connector());
              var malformed =
                  new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {1, 2});
              var endpoint = new WorldStorageTransfers.External(good.handle());
              var submission =
                  setup
                      .runtime()
                      .transfers()
                      .submit(
                          UUID.randomUUID(),
                          setup.actor(),
                          position(helper, ROOT),
                          endpoint,
                          endpoint,
                          malformed,
                          1,
                          value -> {
                            throw new IllegalStateException("Invalid request must not queue");
                          });
              helper.assertTrue(
                  submission.rejection() == OperationResult.Reason.WRONG_RESOURCE
                      && setup.connector().diagnostic().isEmpty(),
                  "Malformed identity rejected without blaming provider");
              helper.setBlock(PROVIDER, ConnectorFixture.OPAQUE.get());
              helper.assertTrue(
                  !setup
                      .runtime()
                      .connectors()
                      .allowed(
                          setup.actor(),
                          position(helper, ROOT),
                          good.handle(),
                          malformed,
                          NetworkPermissions.Permission.WITHDRAW),
                  "Replacement invalidates old registration immediately");
            })
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () ->
                helper.assertTrue(
                    setup.runtime().connectors().bind(setup.connector()).reason()
                        == OperationResult.Reason.UNSUPPORTED,
                    "Opaque alias ownership rejected explicitly after topology revalidation"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void rotationDropsOldCapabilityAndRequiresRevalidation(GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, Blocks.CHEST);
    var south = new BlockPos(3, 1, 2);
    helper.setBlock(south, Blocks.CHEST);
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(marked(64));
    WorldConnectors.Handle[] old = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              old[0] = setup.runtime().connectors().bind(setup.connector()).handle();
              helper.assertTrue(
                  setup.runtime().connectors().port(old[0]).insert(key, 64) == 64,
                  "First provider receives known amount");
              helper.setBlock(
                  CONNECTOR,
                  ConnectorContent.BLOCK
                      .get()
                      .defaultBlockState()
                      .setValue(BlockStateProperties.FACING, Direction.SOUTH));
              helper.assertTrue(
                  !setup
                      .runtime()
                      .connectors()
                      .allowed(
                          setup.actor(),
                          position(helper, ROOT),
                          old[0],
                          key,
                          NetworkPermissions.Permission.DEPOSIT),
                  "Rotation suspends old handle immediately");
            })
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              var rotated = setup.runtime().connectors().bind(setup.connector());
              helper.assertTrue(
                  rotated.reason() == OperationResult.Reason.OK
                      && !old[0].backing().equals(rotated.handle().backing()),
                  "Revalidated rotation acquires new provider");
              helper.assertTrue(
                  setup.runtime().connectors().port(rotated.handle()).insert(key, 1) == 1,
                  "New tick and provider accept one actual item");
              ChestBlockEntity first = helper.getBlockEntity(PROVIDER);
              ChestBlockEntity second = helper.getBlockEntity(south);
              helper.assertTrue(
                  first.getItem(0).getCount() == 64 && second.getItem(0).getCount() == 1,
                  "Old cached capability cannot mutate after rotation");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void physicalControlsRequireEditAndDoNotConsumeMaterials(GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, Blocks.CHEST);
    var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              setup
                  .saved()
                  .network(setup.root().handle().id())
                  .setGrants(
                      setup.actor(), player.getUUID(), Set.of(NetworkPermissions.Permission.EDIT));
              player.setItemInHand(
                  net.minecraft.world.InteractionHand.OFF_HAND, new ItemStack(Items.REDSTONE, 1));
              helper.useBlock(CONNECTOR, player);
              helper.assertTrue(
                  setup.connector().settings().access() == ConnectorSettings.Access.READ_ONLY
                      && player.getOffhandItem().getCount() == 1,
                  "Offhand redstone configures access without consumption");
              player.setItemInHand(
                  net.minecraft.world.InteractionHand.OFF_HAND, new ItemStack(Items.GOLD_INGOT, 7));
              helper.useBlock(CONNECTOR, player);
              helper.assertTrue(
                  setup.connector().settings().priority() == -7,
                  "Offhand gold selects negative stack-size priority");
              player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
              player.setShiftKeyDown(true);
              helper.useBlock(CONNECTOR, player);
              helper.assertTrue(
                  setup.connector().settings().kind() == ResourceKind.FLUID,
                  "Empty-hand crouch action changes resource type");
              setup
                  .saved()
                  .network(setup.root().handle().id())
                  .setGrants(setup.actor(), player.getUUID(), Set.of());
              helper.useBlock(CONNECTOR, player);
              helper.assertTrue(
                  setup.connector().settings().kind() == ResourceKind.FLUID,
                  "Revocation prevents physical policy mutation");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void futureConnectorDataPreservesEvidenceAndReleasesOldRegistration(
      GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, Blocks.CHEST);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  setup.runtime().connectors().bind(setup.connector()).reason()
                      == OperationResult.Reason.OK,
                  "Live registration before malformed reload");
              var saved = setup.connector().saveWithoutMetadata(helper.getLevel().registryAccess());
              var future = saved.getCompound("FactoryCoreConnector");
              future.putInt("Schema", 2);
              future.putString("FutureEvidence", "retain exactly");
              setup.connector().loadWithComponents(saved, helper.getLevel().registryAccess());
              helper.assertTrue(
                  !setup.connector().diagnostic().isEmpty()
                      && setup
                          .connector()
                          .saveWithoutMetadata(helper.getLevel().registryAccess())
                          .getCompound("FactoryCoreConnector")
                          .equals(future),
                  "Unknown schema retains full recovery payload");
              var portable = setup.connector().portableForBreak();
              helper.assertTrue(
                  portable
                      .getOrDefault(
                          DataComponents.CUSTOM_DATA,
                          net.minecraft.world.item.component.CustomData.EMPTY)
                      .copyTag()
                      .getCompound("FactoryCoreConnector")
                      .equals(future),
                  "Recovery casing preserves evidence");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void recursiveProviderMutationQuarantinesBeforeUnboundedReentry(
      GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, ConnectorFixture.BLOCK.get());
    helper.setBlock(STORAGE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(STORAGE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var handle = CellHandleData.read(drive.cell(0));
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(marked(64));
    WorldStorageTransfers.Result[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, setup.runtime(), CONNECTOR);
              ready(helper, setup.runtime(), STORAGE);
            })
        .thenExecute(
            () -> {
              setup.saved().registry().insert(handle, key, 64);
              var external = setup.runtime().connectors().bind(setup.connector()).handle();
              var port = setup.runtime().connectors().port(external);
              ConnectorFixture.ProviderEntity provider = helper.getBlockEntity(PROVIDER);
              provider.afterInsertion = () -> port.insert(key, 1);
              setup
                  .runtime()
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      setup.actor(),
                      position(helper, ROOT),
                      new WorldStorageTransfers.Digital(handle),
                      new WorldStorageTransfers.External(external),
                      key,
                      64,
                      value -> result[0] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Failure reported"))
        .thenExecute(
            () -> {
              ConnectorFixture.ProviderEntity provider = helper.getBlockEntity(PROVIDER);
              helper.assertTrue(
                  result[0].reason() == OperationResult.Reason.UNCERTAIN
                      && result[0].transfer().pending() == StagedTransfer.PendingCall.DELIVER
                      && provider.items.getStackInSlot(0).getCount() == 64,
                  "Nested mutation rejected once and uncertain delivery retained");
              helper.assertTrue(
                  setup.saved().registry().stock(handle, key).total() == 0
                      && !setup.connector().diagnostic().isEmpty(),
                  "No fabricated rollback after real destination mutation");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void providerDescriptionChangeCannotResetConnectorBudget(GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, ConnectorFixture.BLOCK.get());
    helper.setBlock(PROVIDER.north(), ConnectorFixture.BLOCK.get());
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(marked(64));
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              var old = setup.runtime().connectors().bind(setup.connector()).handle();
              helper.assertTrue(
                  setup.runtime().connectors().port(old).insert(key, 64) == 64,
                  "Initial budget consumed");
              ConnectorFixture.ProviderEntity provider = helper.getBlockEntity(PROVIDER);
              provider.extendedBacking = true;
              var current = setup.runtime().connectors().bind(setup.connector()).handle();
              helper.assertTrue(
                  !old.backing().equals(current.backing()),
                  "Backing description change invalidates old binding");
              helper.assertTrue(
                  setup.runtime().connectors().port(current).insert(key, 1) == 0
                      && provider.items.getStackInSlot(0).getCount() == 64
                      && provider.items.getStackInSlot(1).isEmpty(),
                  "New provider budget cannot bypass canonical connector budget in same tick");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void parentCannotTransferPrivateChildProviderStock(GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(
        CABLE,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gateway = helper.getBlockEntity(CABLE);
    gateway.commission(null);
    helper.setBlock(PROVIDER, Blocks.CHEST);
    ChestBlockEntity chest = helper.getBlockEntity(PROVIDER);
    chest.setItem(0, marked(64));
    var parentStorage = new BlockPos(1, 1, 2);
    helper.setBlock(parentStorage, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(parentStorage);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var cell = CellHandleData.read(drive.cell(0));
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(marked(64));
    var id = UUID.randomUUID();
    WorldStorageTransfers.Result[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, setup.runtime(), CONNECTOR);
              ready(helper, setup.runtime(), parentStorage);
            })
        .thenExecute(
            () -> {
              var external = setup.runtime().connectors().bind(setup.connector()).handle();
              setup
                  .runtime()
                  .transfers()
                  .submit(
                      id,
                      setup.actor(),
                      position(helper, ROOT),
                      new WorldStorageTransfers.External(external),
                      new WorldStorageTransfers.Digital(cell),
                      key,
                      64,
                      value -> result[0] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Denied result returned"))
        .thenExecute(
            () ->
                helper.assertTrue(
                    result[0].reason() == OperationResult.Reason.DENIED
                        && result[0].transfer() == null
                        && !setup.saved().transfers().contains(id)
                        && chest.getItem(0).getCount() == 64
                        && setup.saved().registry().stock(cell, key).total() == 0,
                    "Ownership never widens parent scope to child-private provider"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void slotWindowAndSixSocketOrientationsAreExplicit(GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, ConnectorFixture.BLOCK.get());
    var codec = new PlatformResourceCodec(helper.getLevel().registryAccess());
    var iron = codec.itemKey(marked(64));
    var gold = codec.itemKey(new ItemStack(Items.GOLD_INGOT));
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              ConnectorFixture.ProviderEntity provider = helper.getBlockEntity(PROVIDER);
              provider.items.setStackInSlot(0, marked(16));
              provider.items.setStackInSlot(1, new ItemStack(Items.GOLD_INGOT, 32));
              setup
                  .connector()
                  .configure(
                      setup.actor(),
                      new ConnectorSettings(
                          ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 1, 1, 64));
              var port =
                  setup
                      .runtime()
                      .connectors()
                      .port(setup.runtime().connectors().bind(setup.connector()).handle());
              helper.assertTrue(
                  port.extract(iron, 16) == 0
                      && port.extract(gold, 32) == 32
                      && provider.items.getStackInSlot(0).getCount() == 16
                      && provider.items.getStackInSlot(1).isEmpty(),
                  "Only selected slot participates");
              for (var direction : Direction.values()) {
                var state =
                    ConnectorContent.BLOCK
                        .get()
                        .defaultBlockState()
                        .setValue(BlockStateProperties.FACING, direction);
                for (var face : Direction.values())
                  helper.assertTrue(
                      NetworkContent.connects(state, face) == (face != direction),
                      "Socket excludes only provider face from cabling");
                double x =
                    direction == Direction.EAST ? 15.5 : direction == Direction.WEST ? 0.5 : 8;
                double y = direction == Direction.UP ? 15.5 : direction == Direction.DOWN ? 0.5 : 8;
                double z =
                    direction == Direction.SOUTH ? 15.5 : direction == Direction.NORTH ? 0.5 : 8;
                var point = new net.minecraft.world.phys.Vec3(x / 16, y / 16, z / 16);
                helper.assertTrue(
                    state
                        .getShape(helper.getLevel(), helper.absolutePos(CONNECTOR))
                        .toAabbs()
                        .stream()
                        .anyMatch(box -> box.contains(point)),
                    "Socket geometry follows six-way facing");
              }
              helper.assertTrue(
                  helper
                      .getLevel()
                      .getRecipeManager()
                      .byKey(
                          net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                              "factorycore", "storage_connector"))
                      .isPresent(),
                  "Survival recipe loaded");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void selfTransfersRejectWithoutConsumingRecordQuota(GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, Blocks.CHEST);
    ChestBlockEntity chest = helper.getBlockEntity(PROVIDER);
    chest.setItem(0, marked(64));
    helper.setBlock(STORAGE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(STORAGE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var cell = CellHandleData.read(drive.cell(0));
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(marked(64));
    UUID[] ids = {UUID.randomUUID(), UUID.randomUUID()};
    WorldStorageTransfers.Result[] results = new WorldStorageTransfers.Result[2];
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, setup.runtime(), CONNECTOR);
              ready(helper, setup.runtime(), STORAGE);
            })
        .thenExecute(
            () -> {
              setup.saved().registry().insert(cell, key, 64);
              var foreign =
                  new WorldStorageTransfers.External(
                      setup.runtime().connectors().bind(setup.connector()).handle());
              var owned = new WorldStorageTransfers.Digital(cell);
              setup
                  .runtime()
                  .transfers()
                  .submit(
                      ids[0],
                      setup.actor(),
                      position(helper, ROOT),
                      foreign,
                      foreign,
                      key,
                      16,
                      value -> results[0] = value);
              setup
                  .runtime()
                  .transfers()
                  .submit(
                      ids[1],
                      setup.actor(),
                      position(helper, ROOT),
                      owned,
                      owned,
                      key,
                      16,
                      value -> results[1] = value);
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    results[0] != null && results[1] != null, "Both rejections completed"))
        .thenExecute(
            () -> {
              for (int index = 0; index < 2; index++)
                helper.assertTrue(
                    results[index].reason() == OperationResult.Reason.DENIED
                        && results[index].transfer() == null
                        && !setup.saved().transfers().contains(ids[index]),
                    "Self-move never owns a transfer record");
              helper.assertTrue(
                  chest.getItem(0).getCount() == 64
                      && setup.saved().registry().stock(cell, key).total() == 64,
                  "Self-moves preserve both stocks");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void providerMarkersSurviveRealContainerNBTAndRejectReplacement(
      GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, Blocks.CHEST);
    ChestBlockEntity chest = helper.getBlockEntity(PROVIDER);
    chest.setItem(0, marked(64));
    WorldConnectors.Handle[] initial = {null};
    net.minecraft.nbt.CompoundTag[] disk = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              initial[0] = setup.runtime().connectors().bind(setup.connector()).handle();
              disk[0] = chest.saveWithFullMetadata(helper.getLevel().registryAccess());
              var file =
                  helper
                      .getLevel()
                      .getServer()
                      .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                      .resolve("provider-identity-" + UUID.randomUUID() + ".dat");
              try {
                net.minecraft.nbt.NbtIo.writeCompressed(disk[0], file);
                disk[0] =
                    net.minecraft.nbt.NbtIo.readCompressed(
                        file, net.minecraft.nbt.NbtAccounter.create(1_048_576));
              } catch (java.io.IOException failure) {
                throw new IllegalStateException("Provider fixture disk round trip failed", failure);
              }
              helper.assertTrue(
                  disk[0].getCompound("NeoForgeData").contains("factorycore:external_identity"),
                  "Identity uses real persistent block-entity storage");
              var refreshed = setup.runtime().connectors().bind(setup.connector());
              helper.assertTrue(
                  initial[0].backing().equals(refreshed.handle().backing()),
                  "Ordinary rebind preserves backing");
              setup.runtime().connectors().invalidate(setup.connector());
              helper.setBlock(PROVIDER, Blocks.AIR);
              helper.setBlock(PROVIDER, Blocks.CHEST);
              ChestBlockEntity restored = helper.getBlockEntity(PROVIDER);
              restored.loadWithComponents(disk[0], helper.getLevel().registryAccess());
            })
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              var restored = setup.runtime().connectors().bind(setup.connector());
              ChestBlockEntity current = helper.getBlockEntity(PROVIDER);
              helper.assertTrue(
                  restored.handle().backing().equals(initial[0].backing())
                      && current.getItem(0).getCount() == 64,
                  "NBT reload preserves identity and actual items");
              helper.assertTrue(
                  !restored.handle().equals(initial[0]),
                  "Runtime token still invalidates old handle");
              helper.setBlock(PROVIDER, Blocks.AIR);
              helper.setBlock(PROVIDER, Blocks.CHEST);
            })
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () ->
                helper.assertTrue(
                    !initial[0]
                        .backing()
                        .equals(
                            setup
                                .runtime()
                                .connectors()
                                .bind(setup.connector())
                                .handle()
                                .backing()),
                    "Replacement without marker has a distinct backing"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void savedExternalStagingRebindsReloadedProviderWithoutExtraction(
      GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, Blocks.CHEST);
    ChestBlockEntity chest = helper.getBlockEntity(PROVIDER);
    chest.setItem(0, marked(64));
    helper.setBlock(STORAGE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(STORAGE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var cell = CellHandleData.read(drive.cell(0));
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(marked(64));
    var id = UUID.randomUUID();
    WorldStorageTransfers.Result[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, setup.runtime(), CONNECTOR);
              ready(helper, setup.runtime(), STORAGE);
            })
        .thenExecute(
            () -> {
              var endpoint = setup.runtime().connectors().bind(setup.connector()).handle();
              var scope = setup.runtime().membership(position(helper, ROOT)).scope();
              var context =
                  new dev.izzy.factorycore.core.resource.TransferContext(
                      setup.saved().registry().world(),
                      scope.network(),
                      scope.segment(),
                      position(helper, ROOT),
                      new dev.izzy.factorycore.core.resource.TransferContext.Endpoint(
                          dev.izzy.factorycore.core.resource.TransferContext.Type.EXTERNAL,
                          endpoint.connector().id(),
                          endpoint.backing(),
                          endpoint.connector().generation(),
                          endpoint.generation()),
                      new dev.izzy.factorycore.core.resource.TransferContext.Endpoint(
                          dev.izzy.factorycore.core.resource.TransferContext.Type.DIGITAL,
                          cell.backing(),
                          cell.backing(),
                          cell.generation(),
                          0));
              var blocked =
                  new dev.izzy.factorycore.core.resource.ResourcePort() {
                    @Override
                    public UUID backingId() {
                      return cell.backing();
                    }

                    @Override
                    public long simulateInsert(ResourceKey resource, long amount) {
                      return amount;
                    }

                    @Override
                    public long insert(ResourceKey resource, long amount) {
                      return 0;
                    }

                    @Override
                    public long extract(ResourceKey resource, long amount) {
                      throw new IllegalStateException("Destination cannot extract");
                    }
                  };
              var staged =
                  setup
                      .saved()
                      .transfers()
                      .start(
                          id,
                          setup.actor().id(),
                          key,
                          setup.runtime().connectors().port(endpoint),
                          blocked,
                          64,
                          64,
                          () -> true,
                          context);
              helper.assertTrue(
                  staged.staged() == 64 && chest.getItem(0).isEmpty(),
                  "Extraction owns exact staging before reload");
              var nbt = chest.saveWithFullMetadata(helper.getLevel().registryAccess());
              setup.runtime().connectors().invalidate(setup.connector());
              helper.setBlock(PROVIDER, Blocks.AIR);
              helper.setBlock(PROVIDER, Blocks.CHEST);
              ChestBlockEntity restored = helper.getBlockEntity(PROVIDER);
              restored.loadWithComponents(nbt, helper.getLevel().registryAccess());
            })
        .thenWaitUntil(
            () -> {
              ready(helper, setup.runtime(), CONNECTOR);
              ready(helper, setup.runtime(), STORAGE);
            })
        .thenExecute(
            () ->
                setup
                    .runtime()
                    .transfers()
                    .progress(id, setup.actor(), false, value -> result[0] = value))
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Recovery completed"))
        .thenExecute(
            () -> {
              ChestBlockEntity restored = helper.getBlockEntity(PROVIDER);
              helper.assertTrue(
                  result[0].transfer().delivered() == 64
                      && result[0].transfer().staged() == 0
                      && restored.getItem(0).isEmpty()
                      && setup.saved().registry().stock(cell, key).total() == 64,
                  "Reloaded stable provider delivers owned staging without replaying empty source");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void futureProviderMarkerFailsClosedWithoutRewritingInventory(
      GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, Blocks.CHEST);
    ChestBlockEntity chest = helper.getBlockEntity(PROVIDER);
    chest.setItem(0, marked(64));
    var marker = new net.minecraft.nbt.CompoundTag();
    marker.putInt("Schema", 2);
    marker.putString("FutureEvidence", "retain exactly");
    chest.getPersistentData().put("factorycore:external_identity", marker.copy());
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  setup.runtime().connectors().bind(setup.connector()).reason()
                      == OperationResult.Reason.DENIED,
                  "Future marker blocks registration");
              helper.assertTrue(
                  chest
                          .getPersistentData()
                          .getCompound("factorycore:external_identity")
                          .equals(marker)
                      && chest.getItem(0).getCount() == 64,
                  "No reset or speculative stock change");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void copiedProviderIdentityCannotCreateTwoRegisteredBackings(
      GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, Blocks.CHEST);
    helper.setBlock(STORAGE, NetworkContent.CABLE.get());
    helper.setBlock(new BlockPos(3, 1, 2), NetworkContent.CABLE.get());
    var secondConnectorPos = new BlockPos(4, 1, 2);
    var secondProviderPos = new BlockPos(4, 1, 3);
    var second = connector(helper, secondConnectorPos, Direction.SOUTH);
    helper.setBlock(secondProviderPos, Blocks.CHEST);
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, setup.runtime(), CONNECTOR);
              ready(helper, setup.runtime(), secondConnectorPos);
            })
        .thenExecute(
            () -> {
              var first = setup.runtime().connectors().bind(setup.connector());
              helper.assertTrue(
                  first.reason() == OperationResult.Reason.OK, "First backing registered");
              ChestBlockEntity original = helper.getBlockEntity(PROVIDER);
              ChestBlockEntity copy = helper.getBlockEntity(secondProviderPos);
              copy.getPersistentData()
                  .put(
                      "factorycore:external_identity",
                      original.getPersistentData().get("factorycore:external_identity").copy());
              helper.assertTrue(
                  setup.runtime().connectors().bind(second).reason()
                      == OperationResult.Reason.OVERLAP,
                  "Copied marker at another coordinate cannot double stock");
            })
        .thenSucceed();
  }

  private static void overlap(GameTestHelper helper, boolean doubleChest) {
    var setup = setup(helper);
    helper.setBlock(STORAGE, NetworkContent.CABLE.get());
    helper.setBlock(new BlockPos(3, 1, 2), NetworkContent.CABLE.get());
    var secondPos = new BlockPos(doubleChest ? 5 : 4, 1, 2);
    helper.setBlock(new BlockPos(4, 1, 2), NetworkContent.CABLE.get());
    helper.setBlock(PROVIDER, Blocks.CHEST);
    if (doubleChest) {
      helper.setBlock(
          new BlockPos(5, 1, 1),
          Blocks.CHEST
              .defaultBlockState()
              .setValue(ChestBlock.FACING, Direction.NORTH)
              .setValue(ChestBlock.TYPE, ChestType.RIGHT));
      helper.setBlock(
          PROVIDER,
          Blocks.CHEST
              .defaultBlockState()
              .setValue(ChestBlock.FACING, Direction.NORTH)
              .setValue(ChestBlock.TYPE, ChestType.LEFT));
    }
    var second = connector(helper, secondPos, Direction.NORTH);
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, setup.runtime(), CONNECTOR);
              ready(helper, setup.runtime(), secondPos);
            })
        .thenExecute(
            () -> {
              if (doubleChest)
                helper.assertTrue(
                    helper.getBlockState(PROVIDER).getValue(ChestBlock.TYPE) != ChestType.SINGLE,
                    "Actual double chest connected");
              helper.assertTrue(
                  setup.runtime().connectors().bind(setup.connector()).reason()
                      == OperationResult.Reason.OK,
                  "First registration owns provider view");
              helper.assertTrue(
                  setup.runtime().connectors().bind(second).reason()
                      == OperationResult.Reason.OVERLAP,
                  "Overlapping provider registration rejected");
              helper.setBlock(CONNECTOR, Blocks.AIR);
            })
        .thenWaitUntil(() -> ready(helper, setup.runtime(), secondPos))
        .thenExecute(
            () ->
                helper.assertTrue(
                    setup.runtime().connectors().bind(second).reason() == OperationResult.Reason.OK,
                    "Removal releases registration without inventory mutation"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void twoFacesCannotRegisterOneChestTwice(GameTestHelper helper) {
    overlap(helper, false);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void doubleChestHalvesCannotDoubleCountBacking(GameTestHelper helper) {
    overlap(helper, true);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void scheduledForeignObservationRechecksViewGrantAndPreservesStock(
      GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, Blocks.CHEST);
    ChestBlockEntity chest = helper.getBlockEntity(PROVIDER);
    chest.setItem(0, marked(37));
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var id = UUID.randomUUID();
    WorldConnectors.Observation[] results = {null, null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              var handle = setup.runtime().connectors().bind(setup.connector()).handle();
              setup
                  .saved()
                  .network(setup.root().handle().id())
                  .setGrants(setup.actor(), guest.id(), Set.of(NetworkPermissions.Permission.VIEW));
              setup
                  .runtime()
                  .transfers()
                  .observe(
                      id, guest, position(helper, ROOT), handle, 0, value -> results[0] = value);
              setup
                  .saved()
                  .network(setup.root().handle().id())
                  .setGrants(setup.actor(), guest.id(), Set.of());
            })
        .thenWaitUntil(() -> helper.assertTrue(results[0] != null, "Revoked observation callback"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  results[0].reason() == OperationResult.Reason.DENIED && results[0].page() == null,
                  "Revocation hides provider counts");
              var handle = setup.runtime().connectors().bind(setup.connector()).handle();
              setup
                  .runtime()
                  .transfers()
                  .observe(
                      UUID.randomUUID(),
                      setup.actor(),
                      position(helper, ROOT),
                      handle,
                      0,
                      value -> results[1] = value);
            })
        .thenWaitUntil(
            () -> helper.assertTrue(results[1] != null, "Authorized observation callback"))
        .thenExecute(
            () -> {
              var page = results[1].page();
              helper.assertTrue(
                  results[1].reason() == OperationResult.Reason.OK
                      && page.entries().size() == 1
                      && page.entries().getFirst().stored() == 37
                      && page.entries()
                          .getFirst()
                          .key()
                          .equals(
                              new PlatformResourceCodec(helper.getLevel().registryAccess())
                                  .itemKey(marked(1)))
                      && chest.getItem(0).getCount() == 37,
                  "Authorized exact stock read preserves inventory");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void foreignObservationFaultDiscardsPartialPageAndQuarantines(
      GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, ConnectorFixture.BLOCK.get());
    ConnectorFixture.ProviderEntity provider = helper.getBlockEntity(PROVIDER);
    provider.items.setStackInSlot(0, marked(29));
    provider.failSecondStockRead = true;
    WorldConnectors.Observation[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              var handle = setup.runtime().connectors().bind(setup.connector()).handle();
              setup
                  .runtime()
                  .transfers()
                  .observe(
                      UUID.randomUUID(),
                      setup.actor(),
                      position(helper, ROOT),
                      handle,
                      0,
                      value -> result[0] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Fault callback"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  result[0].reason() == OperationResult.Reason.HANDLER_FAULT
                      && result[0].page() == null,
                  "Partially observed rows never escape fault");
              helper.assertTrue(
                  !setup.connector().diagnostic().isEmpty()
                      && provider.items.getStackInSlot(0).getCount() == 29,
                  "Read fault quarantines without mutating stock");
            })
        .thenSucceed();
  }

  private static void utilityClaims(GameTestHelper helper, ResourceKind kind) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, ConnectorFixture.BLOCK.get());
    ConnectorFixture.ProviderEntity provider = helper.getBlockEntity(PROVIDER);
    var key =
        kind == ResourceKind.ENERGY
            ? ResourceKey.ENERGY
            : new PlatformResourceCodec(helper.getLevel().registryAccess())
                .fluidKey(new FluidStack(Fluids.WATER, 1000));
    dev.izzy.factorycore.core.storage.DeviceRegistry.Handle digital;
    if (kind == ResourceKind.ENERGY) {
      provider.energy.receiveEnergy(1000, false);
      helper.setBlock(STORAGE, EnergyBankContent.block(EnergyBankTier.STARTER));
      EnergyBankEntity bank = helper.getBlockEntity(STORAGE);
      bank.place(new ItemStack(EnergyBankContent.item(EnergyBankTier.STARTER)));
      digital = bank.handle();
    } else {
      provider.fluids.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE);
      helper.setBlock(STORAGE, StorageContent.DRIVE.get());
      StorageDriveEntity drive = helper.getBlockEntity(STORAGE);
      drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_FLUID)));
      digital = CellHandleData.read(drive.cell(0));
    }
    WorldConnectors.Handle[] endpoint = {null};
    OperationResult[] claim = {null};
    WorldStorageTransfers.Result[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, setup.runtime(), CONNECTOR);
              ready(helper, setup.runtime(), STORAGE);
            })
        .thenExecute(
            () -> {
              setup.connector().configure(setup.actor(), ConnectorSettings.defaults(kind));
              endpoint[0] = setup.runtime().connectors().bind(setup.connector()).handle();
              setup
                  .runtime()
                  .transfers()
                  .claim(
                      UUID.randomUUID(),
                      setup.actor(),
                      position(helper, ROOT),
                      endpoint[0],
                      key,
                      600,
                      false,
                      value -> claim[0] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(claim[0] != null, "Utility claim callback"))
        .thenExecute(
            () -> {
              helper.assertTrue(claim[0].amount() == 600, "Exact utility units held");
              setup
                  .runtime()
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      setup.actor(),
                      position(helper, ROOT),
                      new WorldStorageTransfers.External(endpoint[0]),
                      new WorldStorageTransfers.Digital(digital),
                      key,
                      1000,
                      value -> result[0] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Background utility callback"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  result[0].transfer().delivered() == 400
                      && setup
                              .saved()
                              .externalClaims()
                              .held(endpoint[0].backing(), setup.actor().id(), key)
                          == 600,
                  "Background utility transfer protects held mB or FE");
              result[0] = null;
              setup
                  .runtime()
                  .transfers()
                  .submitClaimed(
                      UUID.randomUUID(),
                      setup.actor(),
                      position(helper, ROOT),
                      endpoint[0],
                      new WorldStorageTransfers.Digital(digital),
                      key,
                      1000,
                      value -> result[0] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Claimed utility callback"))
        .thenExecute(
            () ->
                helper.assertTrue(
                    result[0].transfer().delivered() == 600
                        && setup
                                .saved()
                                .externalClaims()
                                .held(endpoint[0].backing(), setup.actor().id(), key)
                            == 0
                        && setup.saved().registry().stock(digital, key).total() == 1000
                        && (kind == ResourceKind.ENERGY
                                ? provider.energy.getEnergyStored()
                                : provider.fluids.getFluidAmount())
                            == 0,
                    "Claimed actual units conserved and exact holds consumed"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void foreignFluidClaimsProtectAndConsumeExactMillibuckets(GameTestHelper helper) {
    utilityClaims(helper, ResourceKind.FLUID);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void foreignEnergyClaimsProtectAndConsumeExactFE(GameTestHelper helper) {
    utilityClaims(helper, ResourceKind.ENERGY);
  }

  private static void catalogPages(
      GameTestHelper helper,
      Setup setup,
      ResourceKey key,
      WorldStorageTransfers.CatalogCursor cursor,
      java.util.List<WorldStorageTransfers.CatalogRow> rows,
      boolean[] complete,
      int[] pages) {
    helper.assertTrue(++pages[0] <= 640, "Catalog steps remain bounded by registry quotas");
    setup
        .runtime()
        .transfers()
        .catalog(
            UUID.randomUUID(),
            setup.actor(),
            position(helper, ROOT),
            key,
            cursor,
            page -> {
              helper.assertTrue(
                  page.reason() == OperationResult.Reason.OK && page.rows().size() <= 8,
                  "Authorized bounded catalog page");
              rows.addAll(page.rows());
              if (page.next() == null) complete[0] = true;
              else catalogPages(helper, setup, key, page.next(), rows, complete, pages);
            });
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void catalogDiscoversDigitalAndForeignCountsWithoutWideningOtherRoots(
      GameTestHelper helper) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, ConnectorFixture.BLOCK.get());
    ConnectorFixture.ProviderEntity provider = helper.getBlockEntity(PROVIDER);
    provider.items.setStackInSlot(0, marked(10));
    provider.items.setStackInSlot(1, marked(20));
    helper.setBlock(STORAGE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(STORAGE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var cell = CellHandleData.read(drive.cell(0));
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(marked(1));
    WorldConnectors.Handle[] endpoint = {null};
    OperationResult[] claim = {null};
    boolean[] complete = {false};
    var rows = new java.util.ArrayList<WorldStorageTransfers.CatalogRow>();
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, setup.runtime(), CONNECTOR);
              ready(helper, setup.runtime(), STORAGE);
            })
        .thenExecute(
            () -> {
              setup.saved().registry().insert(cell, key, 40);
              setup.saved().registry().reserve(cell, UUID.randomUUID(), key, 5);
              setup
                  .connector()
                  .configure(
                      setup.actor(),
                      new ConnectorSettings(
                          ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 7, 0, 64, 64));
              endpoint[0] = setup.runtime().connectors().bind(setup.connector()).handle();
              setup
                  .runtime()
                  .transfers()
                  .claim(
                      UUID.randomUUID(),
                      setup.actor(),
                      position(helper, ROOT),
                      endpoint[0],
                      key,
                      12,
                      false,
                      value -> claim[0] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(claim[0] != null, "Saved foreign hold"))
        .thenExecute(
            () -> {
              helper.assertTrue(claim[0].amount() == 12, "Foreign hold admitted");
              setup.runtime().connectors().invalidate(setup.connector());
              catalogPages(helper, setup, key, null, rows, complete, new int[] {0});
            })
        .thenWaitUntil(() -> helper.assertTrue(complete[0], "All metadata pages complete"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  rows.size() == 2, "Only this root's two unique backings are visible");
              var digital =
                  rows.stream()
                      .filter(row -> row.backing().equals(cell.backing()))
                      .findFirst()
                      .orElseThrow();
              var foreign =
                  rows.stream()
                      .filter(row -> row.backing().equals(endpoint[0].backing()))
                      .findFirst()
                      .orElseThrow();
              helper.assertTrue(
                  digital.total() == 40
                      && digital.reserved() == 5
                      && digital.available() == 35
                      && digital.withdrawable(),
                  "Exact digital totals and holds");
              helper.assertTrue(
                  foreign.total() == 30
                      && foreign.reserved() == 12
                      && foreign.available() == 18
                      && foreign.priority() == 7
                      && foreign.withdrawable(),
                  "Reacquired foreign totals/holds/priority");
              helper.assertTrue(
                  provider.items.getStackInSlot(0).getCount() == 10
                      && provider.items.getStackInSlot(1).getCount() == 20,
                  "Enumeration never mutates foreign stock");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void parentCatalogDoesNotProbeOrEnumeratePrivateChildProvider(
      GameTestHelper helper) {
    var base = setup(helper);
    var setup =
        new Setup(
            new NetworkPermissions.Actor(base.actor().id(), true),
            base.root(),
            base.connector(),
            base.runtime(),
            base.saved());
    helper.setBlock(
        CABLE,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gateway = helper.getBlockEntity(CABLE);
    gateway.commission(null);
    helper.setBlock(PROVIDER, Blocks.CHEST);
    ChestBlockEntity chest = helper.getBlockEntity(PROVIDER);
    chest.setItem(0, marked(64));
    var parentStorage = new BlockPos(1, 1, 2);
    helper.setBlock(parentStorage, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(parentStorage);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var cell = CellHandleData.read(drive.cell(0));
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(marked(1));
    var rows = new java.util.ArrayList<WorldStorageTransfers.CatalogRow>();
    boolean[] complete = {false};
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, setup.runtime(), CONNECTOR);
              ready(helper, setup.runtime(), parentStorage);
            })
        .thenExecute(
            () -> {
              setup.saved().registry().insert(cell, key, 20);
              catalogPages(helper, setup, key, null, rows, complete, new int[] {0});
            })
        .thenWaitUntil(() -> helper.assertTrue(complete[0], "Private-scope enumeration finished"))
        .thenExecute(
            () ->
                helper.assertTrue(
                    rows.size() == 1
                        && rows.getFirst().backing().equals(cell.backing())
                        && rows.getFirst().total() == 20
                        && chest.getItem(0).getCount() == 64
                        && !chest.getPersistentData().contains("factorycore:external_identity"),
                    "Owner/operator sees parent storage without probing private child provider"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void queuedCatalogRevocationReturnsNoRows(GameTestHelper helper) {
    var setup = setup(helper);
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(marked(1));
    WorldStorageTransfers.CatalogPage[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () -> {
              setup
                  .saved()
                  .network(setup.root().handle().id())
                  .setGrants(setup.actor(), guest.id(), Set.of(NetworkPermissions.Permission.VIEW));
              setup
                  .runtime()
                  .transfers()
                  .catalog(
                      UUID.randomUUID(),
                      guest,
                      position(helper, ROOT),
                      key,
                      null,
                      value -> result[0] = value);
              setup
                  .saved()
                  .network(setup.root().handle().id())
                  .setGrants(setup.actor(), guest.id(), Set.of());
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Catalog denial callback"))
        .thenExecute(
            () ->
                helper.assertTrue(
                    result[0].reason() == OperationResult.Reason.DENIED
                        && result[0].rows().isEmpty()
                        && result[0].next() == null,
                    "Revoked queued query publishes no rows or cursor"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void catalogCursorRejectsOtherActorKeyAndReplacementRoot(GameTestHelper helper) {
    var setup = setup(helper);
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(marked(1));
    WorldStorageTransfers.CatalogCursor[] cursor = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () ->
                setup
                    .runtime()
                    .transfers()
                    .catalog(
                        UUID.randomUUID(),
                        setup.actor(),
                        position(helper, ROOT),
                        key,
                        null,
                        page -> cursor[0] = page.next()))
        .thenWaitUntil(() -> helper.assertTrue(cursor[0] != null, "Server cursor issued"))
        .thenExecute(
            () -> {
              setup
                  .saved()
                  .network(setup.root().handle().id())
                  .setGrants(setup.actor(), guest.id(), Set.of(NetworkPermissions.Permission.VIEW));
              helper.assertTrue(
                  setup
                          .runtime()
                          .transfers()
                          .catalog(
                              UUID.randomUUID(),
                              guest,
                              position(helper, ROOT),
                              key,
                              cursor[0],
                              page -> {})
                          .rejection()
                      == OperationResult.Reason.DENIED,
                  "Other authenticated principal cannot reuse cursor");
              helper.assertTrue(
                  setup
                          .runtime()
                          .transfers()
                          .catalog(
                              UUID.randomUUID(),
                              setup.actor(),
                              position(helper, ROOT),
                              ResourceKey.ENERGY,
                              cursor[0],
                              page -> {})
                          .rejection()
                      == OperationResult.Reason.DENIED,
                  "Cursor cannot mix resource filters");
              helper.setBlock(ROOT, Blocks.AIR);
              helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
              NetworkNodeEntity replacement = helper.getBlockEntity(ROOT);
              replacement.commission(setup.actor().id());
            })
        .thenWaitUntil(() -> ready(helper, setup.runtime(), CONNECTOR))
        .thenExecute(
            () ->
                helper.assertTrue(
                    setup
                            .runtime()
                            .transfers()
                            .catalog(
                                UUID.randomUUID(),
                                setup.actor(),
                                position(helper, ROOT),
                                key,
                                cursor[0],
                                page -> {})
                            .rejection()
                        == OperationResult.Reason.DENIED,
                    "Owned replacement root cannot inherit old catalog cursor"))
        .thenSucceed();
  }

  private static void priorityRoute(GameTestHelper helper, int mode) {
    var setup = setup(helper);
    helper.setBlock(PROVIDER, ConnectorFixture.BLOCK.get());
    ConnectorFixture.ProviderEntity fixed = helper.getBlockEntity(PROVIDER);
    var highPos = new BlockPos(2, 1, 3);
    var highProviderPos = new BlockPos(3, 1, 3);
    var high = connector(helper, highPos, Direction.EAST);
    helper.setBlock(highProviderPos, ConnectorFixture.BLOCK.get());
    ConnectorFixture.ProviderEntity preferred = helper.getBlockEntity(highProviderPos);
    helper.setBlock(STORAGE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(STORAGE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var cell = CellHandleData.read(drive.cell(0));
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(marked(1));
    WorldStorageRoutes.Result[] result = {null};
    var request = UUID.randomUUID();
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var routeActor = mode == 5 ? guest : setup.actor();
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, setup.runtime(), CONNECTOR);
              ready(helper, setup.runtime(), highPos);
              ready(helper, setup.runtime(), STORAGE);
            })
        .thenExecute(
            () -> {
              high.configure(
                  setup.actor(),
                  new ConnectorSettings(
                      ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 10, 0, 64, 64));
              if (mode == 1) {
                preferred.items.setStackInSlot(0, marked(32));
                setup.saved().registry().insert(cell, key, 64);
              } else {
                fixed.items.setStackInSlot(0, marked(64));
                if (mode == 2 || mode == 7) {
                  preferred.items.setStackInSlot(0, marked(64));
                  preferred.items.setStackInSlot(1, marked(64));
                }
              }
              if (mode == 3) fixed.failAfterExtraction = true;
              if (mode == 5)
                setup
                    .saved()
                    .network(setup.root().handle().id())
                    .setGrants(
                        setup.actor(),
                        guest.id(),
                        Set.of(
                            NetworkPermissions.Permission.VIEW,
                            NetworkPermissions.Permission.WITHDRAW,
                            NetworkPermissions.Permission.DEPOSIT));
              var endpoint = setup.runtime().connectors().bind(setup.connector()).handle();
              var scope = setup.runtime().membership(position(helper, ROOT)).scope();
              var rotationScope =
                  new dev.izzy.factorycore.core.storage.RouteCursors.Scope(
                      scope.network(),
                      scope.segment(),
                      routeActor.id(),
                      request,
                      endpoint.backing(),
                      ResourceKind.ITEM,
                      true);
              if (mode == 7) setup.saved().routeCursors().advance(rotationScope, cell.backing());
              var admission =
                  mode >= 6
                      ? setup
                          .runtime()
                          .transfers()
                          .routes()
                          .roundRobin(
                              request,
                              request,
                              routeActor,
                              position(helper, ROOT),
                              new WorldStorageTransfers.External(endpoint),
                              key,
                              mode == 6 ? 32 : 64,
                              WorldStorageRoutes.Direction.TO_STORAGE,
                              value -> result[0] = value)
                      : setup
                          .runtime()
                          .transfers()
                          .routes()
                          .priority(
                              request,
                              routeActor,
                              position(helper, ROOT),
                              new WorldStorageTransfers.External(endpoint),
                              key,
                              64,
                              mode == 1
                                  ? WorldStorageRoutes.Direction.FROM_STORAGE
                                  : WorldStorageRoutes.Direction.TO_STORAGE,
                              value -> result[0] = value);
              helper.assertTrue(
                  admission.rejection() == OperationResult.Reason.OK, "Priority route admitted");
              if (mode == 4) {
                var routes = setup.runtime().transfers().routes();
                helper.assertTrue(
                    !routes.cancel(request, guest), "Other principal cannot cancel route");
                helper.assertTrue(
                    routes.cancel(request, setup.actor()), "Owner cancels queued route");
                helper.assertTrue(
                    !routes.cancel(request, setup.actor()), "Cancellation is single use");
              }
              if (mode == 5)
                setup
                    .saved()
                    .network(setup.root().handle().id())
                    .setGrants(setup.actor(), guest.id(), Set.of());
            })
        .thenIdle(3)
        .thenWaitUntil(
            () -> helper.assertTrue(mode == 4 || result[0] != null, "Priority route callback"))
        .thenExecute(
            () -> {
              if (mode == 4 || mode == 5) {
                helper.assertTrue(
                    mode == 4
                        ? result[0] == null
                        : result[0].move().reason() == OperationResult.Reason.DENIED
                            && result[0].move().transfer() == null,
                    "Cancelled/revoked route never mutates");
                helper.assertTrue(
                    fixed.items.getStackInSlot(0).getCount() == 64
                        && preferred.items.getStackInSlot(0).isEmpty()
                        && setup.saved().registry().stock(cell, key).total() == 0,
                    "Cancellation/revocation preserves both destinations and source");
                return;
              }
              helper.assertTrue(
                  result[0].request().equals(request)
                      && result[0].move().transfer() != null
                      && setup.saved().transfers().contains(result[0].move().id()),
                  "Route returns durable actual move identity");
              if (mode == 3)
                helper.assertTrue(
                    result[0].move().reason() == OperationResult.Reason.UNCERTAIN
                        && result[0].move().transfer().state() == StagedTransfer.State.QUARANTINED
                        && fixed.items.getStackInSlot(0).isEmpty()
                        && preferred.items.getStackInSlot(0).isEmpty()
                        && setup.saved().registry().stock(cell, key).total() == 0,
                    "Uncertain extraction retains actual record and never falls back or refunds");
              if (mode == 0)
                helper.assertTrue(
                    result[0].move().transfer().delivered() == 64
                        && preferred.items.getStackInSlot(0).getCount() == 64
                        && fixed.items.getStackInSlot(0).isEmpty()
                        && setup.saved().registry().stock(cell, key).total() == 0,
                    "Global high priority target wins over digital default regardless of page order");
              if (mode == 1)
                helper.assertTrue(
                    result[0].move().transfer().delivered() == 32
                        && preferred.items.getStackInSlot(0).isEmpty()
                        && fixed.items.getStackInSlot(0).getCount() == 32
                        && setup.saved().registry().stock(cell, key).total() == 64,
                    "High priority source supplies exact partial units; lower source is untouched");
              if (mode == 2 || mode == 7)
                helper.assertTrue(
                    result[0].move().transfer().delivered() == 64
                        && preferred.items.getStackInSlot(0).getCount() == 64
                        && preferred.items.getStackInSlot(1).getCount() == 64
                        && fixed.items.getStackInSlot(0).isEmpty()
                        && setup.saved().registry().stock(cell, key).total() == 64,
                    "Full preferred target is skipped before extraction and lower target conserves units");
              if (mode == 6 || mode == 7) {
                var member = setup.runtime().membership(position(helper, ROOT)).scope();
                var fixedEndpoint = setup.runtime().connectors().bind(setup.connector()).handle();
                var rotationScope =
                    new dev.izzy.factorycore.core.storage.RouteCursors.Scope(
                        member.network(),
                        member.segment(),
                        setup.actor().id(),
                        request,
                        fixedEndpoint.backing(),
                        ResourceKind.ITEM,
                        true);
                helper.assertTrue(
                    setup
                        .saved()
                        .routeCursors()
                        .last(rotationScope)
                        .equals(result[0].move().transfer().destination()),
                    "Rotation records selected stable backing");
                var restored =
                    DeviceSavedData.load(
                        setup
                            .saved()
                            .save(
                                new net.minecraft.nbt.CompoundTag(),
                                helper.getLevel().registryAccess()),
                        helper.getLevel().registryAccess());
                helper.assertTrue(
                    restored
                        .routeCursors()
                        .last(rotationScope)
                        .equals(setup.saved().routeCursors().last(rotationScope)),
                    "Canonical reload retains rotation");
                if (mode == 6) {
                  helper.assertTrue(
                      result[0].move().transfer().delivered() == 32,
                      "First rotating quantum delivers actual requested units");
                  result[0] = null;
                  var admission =
                      setup
                          .runtime()
                          .transfers()
                          .routes()
                          .roundRobin(
                              UUID.randomUUID(),
                              request,
                              setup.actor(),
                              position(helper, ROOT),
                              new WorldStorageTransfers.External(fixedEndpoint),
                              key,
                              32,
                              WorldStorageRoutes.Direction.TO_STORAGE,
                              value -> result[0] = value);
                  helper.assertTrue(
                      admission.rejection() == OperationResult.Reason.OK,
                      "Second rotating request admitted for same deployment");
                }
              }
            })
        .thenWaitUntil(
            () -> helper.assertTrue(result[0] != null || mode == 4, "Final route callback"))
        .thenExecute(
            () -> {
              if (mode == 6)
                helper.assertTrue(
                    result[0].move().transfer().delivered() == 32
                        && preferred.items.getStackInSlot(0).getCount() == 32
                        && setup.saved().registry().stock(cell, key).total() == 32
                        && fixed.items.getStackInSlot(0).isEmpty(),
                    "Repeated deployment rotates across stable targets independently of priority");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void priorityRouteChoosesGlobalPreferredStorageTarget(GameTestHelper helper) {
    priorityRoute(helper, 0);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void priorityRouteWithdrawsPreferredPartialStockExactly(GameTestHelper helper) {
    priorityRoute(helper, 1);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void priorityRouteSkipsFullPreferredTargetWithoutExtraction(GameTestHelper helper) {
    priorityRoute(helper, 2);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void priorityRouteRetainsUncertainExtractionWithoutFallback(GameTestHelper helper) {
    priorityRoute(helper, 3);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void priorityRouteCancellationIsOwnedAndNonmutating(GameTestHelper helper) {
    priorityRoute(helper, 4);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void priorityRouteRechecksRevokedDiscoveryPermission(GameTestHelper helper) {
    priorityRoute(helper, 5);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void roundRobinRotatesStableTargetsAndPersistsSelection(GameTestHelper helper) {
    priorityRoute(helper, 6);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void roundRobinSkipsFullTargetAndAdvancesPersistedCursor(GameTestHelper helper) {
    priorityRoute(helper, 7);
  }

  @GameTest(template = "empty", batch = "connector_recovery", timeoutTicks = 200)
  public static void auditedConnectorRepairPreservesProviderHoldsSettingsAndRate(GameTestHelper h) {
    var f = setup(h);
    h.setBlock(PROVIDER, Blocks.CHEST);
    ChestBlockEntity chest = h.getBlockEntity(PROVIDER);
    chest.setItem(0, marked(23));
    var key = new PlatformResourceCodec(h.getLevel().registryAccess()).itemKey(marked(1));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f.runtime, CONNECTOR))
        .thenIdle(20)
        .thenExecute(
            () -> {
              var settings =
                  new ConnectorSettings(
                      ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 7, 0, 1, 8);
              h.assertTrue(
                  f.connector.configure(f.actor, settings) == NetworkAuthority.Decision.ALLOWED,
                  "Configure exact port policy");
              var registration = f.runtime.connectors().bind(f.connector).handle();
              h.assertTrue(registration != null, "Bind actual provider before conflict");
              var old = f.connector.handle();
              var token = f.connector.providerToken();
              var configuration = f.connector.generation();
              var port = f.runtime.connectors().port(registration);
              var claim = UUID.randomUUID();
              f.saved.externalClaims().reserve(registration.backing(), claim, key, 5, 23);
              h.assertTrue(port.extract(key, 3) == 3, "Test-owned receiver takes3 of23 items");
              var physical = f.connector.saveWithFullMetadata(h.getLevel().registryAccess());
              var provider = chest.saveWithFullMetadata(h.getLevel().registryAccess());
              var clone = copyPort(h, f.connector, physical);
              h.assertTrue(
                  f.saved.nodes().validate(old) == NodeRegistry.Failure.CONFLICT
                      && registration.equals(
                          f.runtime.connectors().recoveryRegistration(f.connector)),
                  "Conflicted original retains exact metadata witness");
              rejectPortRepair(h, clone, old);
              var console = h.getLevel().getServer().createCommandSourceStack();
              var repair = "factorycore recovery port repair " + old.id() + " " + old.generation();
              h.assertTrue(
                  portCommand(console.withPermission(0), repair) < 1, "Only operator may repair");
              h.assertTrue(
                  portCommand(console, "factorycore recovery port show " + old.id()) == 1,
                  "Metadata inspection works");
              h.assertTrue(
                  portCommand(console, "factorycore recovery port list") >= 0,
                  "Bounded port page executes");
              h.assertTrue(
                  portCommand(console, "factorycore recovery port show " + f.root.handle().id())
                      == 0,
                  "Root cannot be reinterpreted as port");
              h.assertTrue(
                  portCommand(console, repair + "0") == 0, "Wrong expected generation rejects");
              h.assertTrue(
                  portCommand(console, repair) == 1,
                  "Real command repairs exact original connector");
              var current = f.connector.handle();
              h.assertTrue(
                  current.generation() == old.generation() + 1
                      && f.saved.nodes().validate(old) == NodeRegistry.Failure.STALE
                      && f.connector.settings().equals(settings)
                      && f.connector.generation() == configuration
                      && f.connector.targetDirection() == Direction.EAST
                      && !f.connector.providerToken().equals(token),
                  "Only ownership/provider lifetime changes; policy/configuration/face retained");
              h.assertTrue(
                  provider.equals(chest.saveWithFullMetadata(h.getLevel().registryAccess()))
                      && f.saved.externalClaims().held(registration.backing(), claim, key) == 5,
                  "Repair makes no foreign contents/identity/claim change");
              h.assertTrue(
                  port.extract(key, 1) == 0
                      && port.insert(key, 1) == 0
                      && port.simulateInsert(key, 1) == 0,
                  "Old mod-owned ResourcePort is inert");
              for (int attempt = 0;
                  attempt < 128
                      && f.runtime.membership(f.connector.position()).status()
                          != NetworkTopology.Status.VALID;
                  attempt++) f.runtime.tick();
              var rebound = f.runtime.connectors().bind(f.connector).handle();
              h.assertTrue(
                  rebound != null && rebound.backing().equals(registration.backing()),
                  "Normal revalidation reacquires same provider identity");
              h.assertTrue(
                  f.runtime.connectors().port(rebound).extract(key, 20) == 5
                      && chest.getItem(0).getCount() == 15
                      && f.saved.externalClaims().reserved(rebound.backing(), key) == 5,
                  "Same tick retains3 of8 spent;15 provider plus8 test-owned items conserve23 with5 held");
              clone.loadWithComponents(physical, h.getLevel().registryAccess());
              clone.onLoad();
              h.assertTrue(
                  f.saved.nodes().validate(current) == NodeRegistry.Failure.OK
                      && !clone.diagnostic().isEmpty(),
                  "Old copy cannot requarantine current lease");
              var repaired = f.connector.saveWithFullMetadata(h.getLevel().registryAccess());
              f.connector.loadWithComponents(repaired, h.getLevel().registryAccess());
              f.connector.onLoad();
              h.assertTrue(
                  current.equals(f.connector.handle()) && f.connector.settings().equals(settings),
                  "Repaired local reference reloads");
              f.connector.loadWithComponents(physical, h.getLevel().registryAccess());
              f.connector.onLoad();
              rejectPortRepair(h, f.connector, current);
              h.assertTrue(
                  f.saved.nodes().validate(current) == NodeRegistry.Failure.OK
                      && chest.getItem(0).getCount() == 15,
                  "Old original snapshot stays inert without resetting foreign stock");
            })
        .thenIdle(20)
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void failedPortAuditAndPersistedProviderFaultPreserveEvidence(GameTestHelper h) {
    var f = setup(h);
    h.setBlock(PROVIDER, Blocks.CHEST);
    ChestBlockEntity chest = h.getBlockEntity(PROVIDER);
    chest.setItem(0, marked(19));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f.runtime, CONNECTOR))
        .thenExecute(
            () -> {
              var registration = f.runtime.connectors().bind(f.connector).handle();
              h.assertTrue(registration != null, "Remember a normal loaded provider");
              var old = f.connector.handle();
              var physical = f.connector.saveWithFullMetadata(h.getLevel().registryAccess());
              copyPort(h, f.connector, physical);
              var owner = f.saved.nodes().evidence(old.id());
              var provider = chest.saveWithFullMetadata(h.getLevel().registryAccess());
              var token = f.connector.providerToken();
              var folder =
                  h.getLevel()
                      .getServer()
                      .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                      .resolve("factorycore-device-recovery");
              try {
                java.nio.file.Files.createDirectories(folder);
                var blocker =
                    java.nio.file.Files.createDirectory(
                        folder.resolve("unexpected-port-" + UUID.randomUUID()));
                boolean refused = false;
                try {
                  f.connector.repairConflict(old);
                } catch (java.io.IOException expected) {
                  refused = true;
                } finally {
                  java.nio.file.Files.delete(blocker);
                }
                h.assertTrue(refused, "Audit IO failure prevents repair");
              } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
              }
              h.assertTrue(
                  owner.equals(f.saved.nodes().evidence(old.id()))
                      && physical.equals(
                          f.connector.saveWithFullMetadata(h.getLevel().registryAccess()))
                      && token.equals(f.connector.providerToken())
                      && registration.equals(
                          f.runtime.connectors().recoveryRegistration(f.connector))
                      && provider.equals(chest.saveWithFullMetadata(h.getLevel().registryAccess())),
                  "Failed audit changes no reference/binding/provider data");
              f.connector.quarantine(
                  new IllegalStateException("Persisted uncertain provider diagnostic"));
              var faulted = f.connector.saveWithFullMetadata(h.getLevel().registryAccess());
              rejectPortRepair(h, f.connector, old);
              h.assertTrue(
                  owner.equals(f.saved.nodes().evidence(old.id()))
                      && faulted.equals(
                          f.connector.saveWithFullMetadata(h.getLevel().registryAccess()))
                      && f.connector.diagnostic().contains("uncertain"),
                  "Ownership recovery never clears a persisted provider fault");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void acceptedPortStagingBlocksRepairWithoutForeignReplay(GameTestHelper h) {
    var f = setup(h);
    h.setBlock(PROVIDER, Blocks.CHEST);
    ChestBlockEntity chest = h.getBlockEntity(PROVIDER);
    chest.setItem(0, marked(19));
    h.setBlock(STORAGE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(STORAGE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var cell = CellHandleData.read(drive.cell(0));
    var key = new PlatformResourceCodec(h.getLevel().registryAccess()).itemKey(marked(1));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f.runtime, CONNECTOR))
        .thenExecute(
            () -> {
              var registration = f.runtime.connectors().bind(f.connector).handle();
              var old = f.connector.handle();
              var scope = f.runtime.membership(f.connector.position()).scope();
              var context =
                  new TransferContext(
                      f.saved.registry().world(),
                      scope.network(),
                      scope.segment(),
                      f.connector.position(),
                      new TransferContext.Endpoint(
                          TransferContext.Type.EXTERNAL,
                          old.id(),
                          registration.backing(),
                          old.generation(),
                          f.connector.generation()),
                      new TransferContext.Endpoint(
                          TransferContext.Type.DIGITAL,
                          cell.backing(),
                          cell.backing(),
                          cell.generation(),
                          0));
              var destination =
                  new ResourcePort() {
                    public UUID backingId() {
                      return cell.backing();
                    }

                    public long simulateInsert(ResourceKey value, long amount) {
                      return amount;
                    }

                    public long extract(ResourceKey value, long amount) {
                      return 0;
                    }

                    public long insert(ResourceKey value, long amount) {
                      return 0;
                    }
                  };
              var id = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(
                      id,
                      f.actor.id(),
                      key,
                      f.runtime.connectors().port(registration),
                      destination,
                      3,
                      3,
                      () -> true,
                      context);
              h.assertTrue(
                  chest.getItem(0).getCount() == 16 && f.saved.transfers().get(id).staged() == 3,
                  "Real provider16 plus owned staging3 conserve19");
              copyPort(
                  h, f.connector, f.connector.saveWithFullMetadata(h.getLevel().registryAccess()));
              var owner = f.saved.nodes().evidence(old.id());
              var record = f.saved.transfers().get(id);
              rejectPortRepair(h, f.connector, old);
              h.assertTrue(
                  owner.equals(f.saved.nodes().evidence(old.id()))
                      && record.equals(f.saved.transfers().get(id))
                      && chest.getItem(0).getCount() == 16,
                  "Accepted endpoint identity blocks rotation with no provider replay");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void unboundPortWithUnscopedLegacyStagingCannotGuessBacking(GameTestHelper h) {
    var f = setup(h);
    h.setBlock(PROVIDER, Blocks.CHEST);
    ChestBlockEntity chest = h.getBlockEntity(PROVIDER);
    chest.setItem(0, marked(19));
    h.setBlock(STORAGE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(STORAGE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var cell = CellHandleData.read(drive.cell(0));
    var key = new PlatformResourceCodec(h.getLevel().registryAccess()).itemKey(marked(1));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f.runtime, CONNECTOR))
        .thenWaitUntil(() -> ready(h, f.runtime, STORAGE))
        .thenExecute(
            () -> {
              f.saved.registry().insert(cell, key, 3);
              var source =
                  new ResourcePort() {
                    public UUID backingId() {
                      return cell.backing();
                    }

                    public long simulateInsert(ResourceKey value, long amount) {
                      return f.saved.registry().simulateInsert(cell, value, amount).amount();
                    }

                    public long extract(ResourceKey value, long amount) {
                      return f.saved.registry().extract(cell, value, amount, null).amount();
                    }

                    public long insert(ResourceKey value, long amount) {
                      return f.saved.registry().insert(cell, value, amount).amount();
                    }
                  };
              var destination =
                  new ResourcePort() {
                    private final UUID id = UUID.randomUUID();

                    public UUID backingId() {
                      return id;
                    }

                    public long simulateInsert(ResourceKey value, long amount) {
                      return amount;
                    }

                    public long extract(ResourceKey value, long amount) {
                      return 0;
                    }

                    public long insert(ResourceKey value, long amount) {
                      return 0;
                    }
                  };
              var id = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(id, f.actor.id(), key, source, destination, 3, 3, () -> true);
              h.assertTrue(
                  f.saved.transfers().hasUnscopedUnresolved()
                      && f.runtime.connectors().recoveryRegistration(f.connector) == null,
                  "Legacy record lacks device association and port has no remembered binding");
              var old = f.connector.handle();
              copyPort(
                  h, f.connector, f.connector.saveWithFullMetadata(h.getLevel().registryAccess()));
              var before = f.saved.nodes().evidence(old.id());
              var provider = chest.saveWithFullMetadata(h.getLevel().registryAccess());
              rejectPortRepair(h, f.connector, old);
              h.assertTrue(
                  before.equals(f.saved.nodes().evidence(old.id()))
                      && provider.equals(chest.saveWithFullMetadata(h.getLevel().registryAccess()))
                      && f.saved.transfers().get(id).staged() == 3,
                  "Unscoped ambiguity neither probes nor guesses provider ownership");
            })
        .thenSucceed();
  }

  private static ConnectorEntity copyPort(
      GameTestHelper h, ConnectorEntity original, net.minecraft.nbt.CompoundTag raw) {
    var position = new BlockPos(3, 1, 3);
    h.setBlock(position, original.getBlockState());
    ConnectorEntity clone = h.getBlockEntity(position);
    clone.loadWithComponents(raw, h.getLevel().registryAccess());
    clone.onLoad();
    return clone;
  }

  private static void rejectPortRepair(
      GameTestHelper h, ConnectorEntity port, NodeRegistry.Handle expected) {
    boolean refused = false;
    try {
      port.repairConflict(expected);
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      refused = true;
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
    h.assertTrue(refused, "Unsupported port repair rejects");
  }

  private static int portCommand(net.minecraft.commands.CommandSourceStack source, String command) {
    try {
      return source.getServer().getCommands().getDispatcher().execute(command, source);
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException rejected) {
      return -1;
    }
  }
}
