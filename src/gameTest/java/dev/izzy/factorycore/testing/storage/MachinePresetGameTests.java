package dev.izzy.factorycore.testing.storage;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.platform.storage.MachinePresetPackets;
import dev.izzy.factorycore.testing.ConnectorFixture;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class MachinePresetGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      MACHINE = new BlockPos(2, 1, 1),
      PROVIDER = new BlockPos(3, 1, 1),
      DRIVE = new BlockPos(1, 1, 2);

  private record Fixture(
      NetworkPermissions.Actor actor,
      MachineInterfaceEntity machine,
      WorldNetworks runtime,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      ResourceKey key) {}

  private MachinePresetGameTests() {}

  private static Fixture setup(GameTestHelper h, ResourceKind kind) {
    return setup(
        h, kind, kind == ResourceKind.ITEM ? CellTier.STARTER_ITEM : CellTier.STARTER_FLUID);
  }

  private static Fixture setup(GameTestHelper h, ResourceKind kind, CellTier tier) {
    var actor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    NetworkNodeEntity root = Net.in(h, actor.id()).controller(ROOT);
    MachineInterfaceEntity machine = Net.in(h).machine(MACHINE, Direction.EAST);
    h.setBlock(PROVIDER, kind == ResourceKind.ITEM ? Blocks.CHEST : ConnectorFixture.BLOCK.get());
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    var key =
        kind == ResourceKind.ITEM
            ? codec.itemKey(new ItemStack(Items.IRON_INGOT))
            : kind == ResourceKind.FLUID
                ? codec.fluidKey(new FluidStack(Fluids.WATER, 1))
                : ResourceKey.ENERGY;
    DeviceRegistry.Handle cell;
    if (kind == ResourceKind.ENERGY) {
      EnergyBankEntity bank = Net.in(h).bank(DRIVE, EnergyBankTier.STARTER);
      cell = bank.handle();
    } else {
      h.setBlock(DRIVE, StorageContent.DRIVE.get());
      StorageDriveEntity drive = h.getBlockEntity(DRIVE);
      drive.installCell(new ItemStack(StorageContent.cell(tier)));
      cell = CellHandleData.read(drive.cell(0));
    }
    return new Fixture(
        actor,
        machine,
        WorldNetworks.get(h.getLevel().getServer()),
        DeviceSavedData.get(h.getLevel().getServer()),
        cell,
        key);
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        f.runtime.membership(f.machine.position()).status() == NetworkTopology.Status.VALID,
        "Machine network ready");
  }

  private static ResourceFilter exact(Fixture f) {
    return new ResourceFilter(
        f.key.kind(), ResourceFilter.Mode.INCLUDE, List.of(new ResourceFilter.Exact(f.key)));
  }

  /** Items each tier imports: eight chest loads, so tier rates dominate the fixed overhead. */
  public static final int THROUGHPUT_ITEMS = 8 * 27 * 64;

  /**
   * M144: each tier imports {@link #THROUGHPUT_ITEMS} from a chest refilled whenever a slot
   * empties. At 1,728 items fixed overhead left 1–3 ticks between tiers; at this total the tier
   * rates set the time.
   */
  public static void paidTiersIncreaseAutomaticChestImport(GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM, CellTier.EXPANDED_ITEM);
    int[] elapsed = new int[3];
    int[] started = new int[1];
    int[] supplied = new int[1];
    var sequence = h.startSequence().thenWaitUntil(() -> ready(h, f));
    for (var tier : ThroughputTier.values()) {
      sequence.thenExecute(
          () -> {
            if (tier == ThroughputTier.BASE)
              f.machine.configure(f.actor, ConnectorSettings.defaults(ResourceKind.ITEM));
            else
              h.assertTrue(
                  f.machine.installThroughput(f.actor, tier) == NetworkAuthority.Decision.ALLOWED,
                  "Paid tier installed");
          });
      sequence.thenWaitUntil(() -> ready(h, f));
      sequence.thenExecute(
          () -> {
            supplied[0] = 0;
            refill(h, supplied);
            started[0] = h.getLevel().getServer().getTickCount();
            f.machine.deployPreset(f.actor, MachinePresets.Mode.IMPORT, exact(f), 0);
          });
      sequence.thenWaitUntil(
          () -> {
            refill(h, supplied);
            h.assertTrue(
                supplied[0] == THROUGHPUT_ITEMS && resident(h, ResourceKind.ITEM) == 0,
                "Every chest load imported");
          });
      sequence.thenExecute(
          () -> {
            elapsed[tier.ordinal()] = h.getLevel().getServer().getTickCount() - started[0];
            h.assertTrue(
                f.saved.registry().stock(f.cell, f.key).total()
                    == (tier.ordinal() + 1L) * THROUGHPUT_ITEMS,
                "Every imported item remains conserved across tier changes");
            f.machine.deployPreset(f.actor, MachinePresets.Mode.PAUSED, exact(f), 0);
          });
    }
    sequence
        .thenExecute(
            () -> {
              System.out.println(
                  "FACTORYCORE_AUTOMATIC_THROUGHPUT ticks="
                      + Arrays.toString(elapsed)
                      + " itemsPerTier="
                      + THROUGHPUT_ITEMS
                      + " source=vanilla_chest_refilled");
              h.assertTrue(
                  elapsed[1] < elapsed[0],
                  "Advanced import must beat base: " + Arrays.toString(elapsed));
              h.assertTrue(
                  elapsed[2] < elapsed[1],
                  "Endgame import must beat Advanced: " + Arrays.toString(elapsed));
            })
        .thenSucceed();
  }

  /**
   * Fills each empty chest slot with a stack until {@link #THROUGHPUT_ITEMS} have been supplied.
   */
  private static void refill(GameTestHelper h, int[] supplied) {
    ChestBlockEntity chest = h.getBlockEntity(PROVIDER);
    for (int slot = 0; slot < 27 && supplied[0] < THROUGHPUT_ITEMS; slot++)
      if (chest.getItem(slot).isEmpty()) {
        chest.setItem(slot, new ItemStack(Items.IRON_INGOT, 64));
        supplied[0] += 64;
      }
  }

  private static void seed(GameTestHelper h, ResourceKind kind, int amount) {
    if (kind == ResourceKind.ITEM) {
      ChestBlockEntity chest = h.getBlockEntity(PROVIDER);
      chest.setItem(0, new ItemStack(Items.IRON_INGOT, amount));
      chest.setItem(1, new ItemStack(Items.GOLD_INGOT, 7));
    } else {
      ConnectorFixture.ProviderEntity provider = h.getBlockEntity(PROVIDER);
      if (kind == ResourceKind.FLUID)
        provider.fluids.fill(new FluidStack(Fluids.WATER, amount), FluidAction.EXECUTE);
      else provider.energy.receiveEnergy(amount, false);
    }
  }

  private static int resident(GameTestHelper h, ResourceKind kind) {
    if (kind == ResourceKind.ITEM) {
      ChestBlockEntity chest = h.getBlockEntity(PROVIDER);
      int amount = 0;
      for (int i = 0; i < chest.getContainerSize(); i++)
        if (chest.getItem(i).is(Items.IRON_INGOT)) amount += chest.getItem(i).getCount();
      return amount;
    }
    ConnectorFixture.ProviderEntity provider = h.getBlockEntity(PROVIDER);
    return kind == ResourceKind.FLUID
        ? provider.fluids.getFluidAmount()
        : provider.energy.getEnergyStored();
  }

  private static void automatic(GameTestHelper h, ResourceKind kind, MachinePresets.Mode mode) {
    var f = setup(h, kind);
    int amount = kind == ResourceKind.ITEM ? 23 : 1000;
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.machine.configure(f.actor, ConnectorSettings.defaults(kind));
              if (mode == MachinePresets.Mode.IMPORT) seed(h, kind, amount);
              else f.saved.registry().insert(f.cell, f.key, amount);
              f.machine.deployPreset(f.actor, mode, exact(f), amount);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    mode == MachinePresets.Mode.IMPORT
                        ? f.saved.registry().stock(f.cell, f.key).total() == amount
                            && resident(h, kind) == 0
                        : resident(h, kind) == amount
                            && f.saved.registry().stock(f.cell, f.key).total() == 0,
                    "Automatic preset transfers exact selected resource"))
        .thenExecute(
            () -> {
              if (kind == ResourceKind.ITEM) {
                ChestBlockEntity chest = h.getBlockEntity(PROVIDER);
                h.assertTrue(
                    mode != MachinePresets.Mode.IMPORT || chest.getItem(1).getCount() == 7,
                    "Excluded gold remains physically owned");
              }
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void automaticItemImportUsesSelectedIdentity(GameTestHelper h) {
    automatic(h, ResourceKind.ITEM, MachinePresets.Mode.IMPORT);
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void automaticItemExportUsesPriorityRoutes(GameTestHelper h) {
    automatic(h, ResourceKind.ITEM, MachinePresets.Mode.EXPORT);
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void automaticFluidImportPreservesMillibuckets(GameTestHelper h) {
    automatic(h, ResourceKind.FLUID, MachinePresets.Mode.IMPORT);
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void automaticFluidExportPreservesMillibuckets(GameTestHelper h) {
    automatic(h, ResourceKind.FLUID, MachinePresets.Mode.EXPORT);
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void automaticEnergyImportPreservesForgeEnergy(GameTestHelper h) {
    automatic(h, ResourceKind.ENERGY, MachinePresets.Mode.IMPORT);
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void automaticEnergyExportPreservesForgeEnergy(GameTestHelper h) {
    automatic(h, ResourceKind.ENERGY, MachinePresets.Mode.EXPORT);
  }

  private static void stock(GameTestHelper h, ResourceKind kind) {
    var f = setup(h, kind);
    int initial = kind == ResourceKind.ITEM ? 32 : 1000,
        goal = kind == ResourceKind.ITEM ? 8 : 400,
        used = kind == ResourceKind.ITEM ? 3 : 100;
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.machine.configure(f.actor, ConnectorSettings.defaults(kind));
              f.saved.registry().insert(f.cell, f.key, initial);
              f.machine.deployPreset(f.actor, MachinePresets.Mode.STOCK, exact(f), goal);
            })
        .thenWaitUntil(
            () -> h.assertTrue(resident(h, kind) == goal, "Machine Stock reaches exact goal"))
        .thenIdle(30)
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.saved.registry().stock(f.cell, f.key).total() == initial - goal,
                  "Goal stops additional extraction");
              if (kind == ResourceKind.ITEM) {
                ChestBlockEntity chest = h.getBlockEntity(PROVIDER);
                chest.removeItem(0, used);
              } else {
                ConnectorFixture.ProviderEntity provider = h.getBlockEntity(PROVIDER);
                if (kind == ResourceKind.FLUID) provider.fluids.drain(used, FluidAction.EXECUTE);
                else provider.energy.extractEnergy(used, false);
              }
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    resident(h, kind) == goal
                        && f.saved.registry().stock(f.cell, f.key).total() == initial - goal - used,
                    "Physical consumption replenishes without exceeding goal"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void itemStockStopsAndReplenishes(GameTestHelper h) {
    stock(h, ResourceKind.ITEM);
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void fluidStockStopsAndReplenishes(GameTestHelper h) {
    stock(h, ResourceKind.FLUID);
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void energyStockStopsAndReplenishes(GameTestHelper h) {
    stock(h, ResourceKind.ENERGY);
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void fullStorageBackpressureAndPauseRetainSource(GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 8192);
              seed(h, ResourceKind.ITEM, 23);
              f.machine.deployPreset(f.actor, MachinePresets.Mode.IMPORT, exact(f), 64);
            })
        .thenIdle(40)
        .thenExecute(
            () -> {
              h.assertTrue(
                  resident(h, ResourceKind.ITEM) == 23
                      && f.saved.registry().stock(f.cell, f.key).total() == 8192,
                  "Full storage does not extract or void machine source");
              f.saved.registry().extract(f.cell, f.key, 8, null);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    resident(h, ResourceKind.ITEM) == 15
                        && f.saved.registry().stock(f.cell, f.key).total() == 8192,
                    "Vacancy resumes exactly eight actual items"))
        .thenExecute(
            () -> {
              f.machine.deployPreset(f.actor, MachinePresets.Mode.PAUSED, exact(f), 64);
              f.saved.registry().extract(f.cell, f.key, 8, null);
            })
        .thenIdle(40)
        .thenExecute(
            () ->
                h.assertTrue(
                    resident(h, ResourceKind.ITEM) == 15
                        && f.saved.registry().stock(f.cell, f.key).total() == 8184,
                    "Paused deployment retains remaining physical source"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void explicitMatchingInsertionCapsToLiveMachineGoal(GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    WorldStorageTransfers.Result[] result = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 32);
              f.machine.deployPreset(f.actor, MachinePresets.Mode.STOCK, exact(f), 8);
              f.runtime.machinePresets().unregister(f.machine);
              ChestBlockEntity chest = h.getBlockEntity(PROVIDER);
              chest.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
              var handle = f.runtime.connectors().bind(f.machine).handle();
              f.runtime
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      f.actor,
                      f.machine.position(),
                      new WorldStorageTransfers.Digital(f.cell),
                      new WorldStorageTransfers.External(handle),
                      f.key,
                      16,
                      r -> result[0] = r);
            })
        .thenWaitUntil(() -> h.assertTrue(result[0] != null, "Explicit stock insertion completed"))
        .thenExecute(
            () ->
                h.assertTrue(
                    resident(h, ResourceKind.ITEM) == 8
                        && f.saved.registry().stock(f.cell, f.key).total() == 29
                        && result[0].transfer().delivered() == 3,
                    "Shared insertion caps matching keys to live three-item deficit before extraction"))
        .thenSucceed();
  }

  private static ResourcePort digital(Fixture f) {
    return new ResourcePort() {
      public UUID backingId() {
        return f.cell.backing();
      }

      public long simulateInsert(ResourceKey k, long n) {
        return f.saved.registry().simulateInsert(f.cell, k, n).amount();
      }

      public long insert(ResourceKey k, long n) {
        return f.saved.registry().insert(f.cell, k, n).amount();
      }

      public long extract(ResourceKey k, long n) {
        return f.saved.registry().extract(f.cell, k, n, null).amount();
      }
    };
  }

  private static ResourcePort rejecting(UUID backing) {
    return new ResourcePort() {
      public UUID backingId() {
        return backing;
      }

      public long simulateInsert(ResourceKey k, long n) {
        return n;
      }

      public long insert(ResourceKey k, long n) {
        return 0;
      }

      public long extract(ResourceKey k, long n) {
        throw new AssertionError("Rejecting target cannot extract");
      }
    };
  }

  private static TransferContext context(
      Fixture f, WorldConnectors.Handle handle, boolean importing) {
    var scope = f.runtime.membership(f.machine.position()).scope();
    var external =
        new TransferContext.Endpoint(
            TransferContext.Type.EXTERNAL,
            f.machine.handle().id(),
            handle.backing(),
            f.machine.handle().generation(),
            f.machine.generation());
    var digital =
        new TransferContext.Endpoint(
            TransferContext.Type.DIGITAL,
            f.cell.backing(),
            f.cell.backing(),
            f.cell.generation(),
            0);
    return new TransferContext(
        f.saved.registry().world(),
        scope.network(),
        scope.segment(),
        f.machine.position(),
        importing ? external : digital,
        importing ? digital : external);
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void knownInboundCompletesWithoutReextractingRefilledStorage(GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 15);
              f.machine.deployPreset(f.actor, MachinePresets.Mode.STOCK, exact(f), 12);
              var handle = f.runtime.connectors().bind(f.machine).handle();
              id[0] = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(
                      id[0],
                      f.actor.id(),
                      f.key,
                      digital(f),
                      rejecting(handle.backing()),
                      12,
                      12,
                      () -> true,
                      context(f, handle, false));
              f.saved.transfers().unbind(id[0]);
              f.saved.registry().insert(f.cell, f.key, 20);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    resident(h, ResourceKind.ITEM) == 12, "Known inbound staging delivered"))
        .thenIdle(30)
        .thenExecute(
            () ->
                h.assertTrue(
                    f.saved.registry().stock(f.cell, f.key).total() == 23
                        && !f.saved.transfers().contains(id[0]),
                    "Known stock delivery retires staging and never re-extracts refill;35 items conserved"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void ownedReturnBypassesOnlyGoalAndPreservesBothRefills(GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    UUID[] id = {null};
    WorldStorageTransfers.Result[] result = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.machine.deployPreset(f.actor, MachinePresets.Mode.STOCK, exact(f), 8);
              f.runtime.machinePresets().unregister(f.machine);
              seed(h, ResourceKind.ITEM, 8);
              var handle = f.runtime.connectors().bind(f.machine).handle();
              id[0] = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(
                      id[0],
                      f.actor.id(),
                      f.key,
                      f.runtime.connectors().port(handle),
                      rejecting(f.cell.backing()),
                      8,
                      8,
                      () -> true,
                      context(f, handle, true));
              f.saved.transfers().unbind(id[0]);
              seed(h, ResourceKind.ITEM, 8);
              h.assertTrue(
                  f.runtime.connectors().port(handle).simulateInsert(f.key, 8) == 0,
                  "Fresh matching insertion is capped at the reached goal");
              f.runtime.transfers().progress(id[0], f.actor, true, r -> result[0] = r);
            })
        .thenWaitUntil(() -> h.assertTrue(result[0] != null, "Owned return completed"))
        .thenExecute(
            () ->
                h.assertTrue(
                    result[0].transfer().returned() == 8
                        && result[0].transfer().staged() == 0
                        && resident(h, ResourceKind.ITEM) == 16,
                    "Known eight-item return preserves eight-item refill beyond best-effort goal without extracting again"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void queuedEditAndRevocationPreventSourceMutation(GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    MachinePresets.Deployment[] original = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              seed(h, ResourceKind.ITEM, 23);
              f.machine.deployPreset(f.actor, MachinePresets.Mode.IMPORT, exact(f), 64);
              f.runtime.machinePresets().tick();
              // A non-deployer editor, so the edit pauses the preset (a deployer's edit resumes).
              var editor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
              f.saved
                  .network(f.runtime.membership(f.machine.position()).scope().network())
                  .setGrants(
                      f.actor,
                      editor.id(),
                      Set.of(
                          NetworkPermissions.Permission.VIEW, NetworkPermissions.Permission.EDIT));
              f.machine.configure(
                  editor,
                  new ConnectorSettings(
                      ResourceKind.ITEM, ConnectorSettings.Access.READ_ONLY, 0, 0, 64, 8));
            })
        .thenIdle(30)
        .thenExecute(
            () -> {
              h.assertTrue(
                  resident(h, ResourceKind.ITEM) == 23
                      && f.saved.registry().stock(f.cell, f.key).total() == 0,
                  "Queued preparation cannot extract after configuration invalidation");
              var scope = f.runtime.membership(f.machine.position()).scope();
              var grants = f.saved.network(scope.network());
              grants.setGrants(
                  f.actor,
                  guest.id(),
                  Set.of(
                      NetworkPermissions.Permission.VIEW,
                      NetworkPermissions.Permission.EDIT,
                      NetworkPermissions.Permission.DEPLOY,
                      NetworkPermissions.Permission.WITHDRAW,
                      NetworkPermissions.Permission.DEPOSIT));
              original[0] = f.machine.deployPreset(guest, MachinePresets.Mode.IMPORT, exact(f), 64);
              f.runtime.machinePresets().tick();
              grants.setGrants(f.actor, guest.id(), Set.of());
            })
        .thenIdle(30)
        .thenExecute(
            () ->
                h.assertTrue(
                    resident(h, ResourceKind.ITEM) == 23
                        && f.saved.registry().stock(f.cell, f.key).total() == 0
                        && !f.machine.presetAuthorized(original[0]),
                    "Queued revoked principal cannot mutate or switch to the root owner"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void samePrincipalReplacementDoesNotAdoptOldStaging(GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 15);
              f.machine.deployPreset(f.actor, MachinePresets.Mode.STOCK, exact(f), 12);
              var handle = f.runtime.connectors().bind(f.machine).handle();
              id[0] = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(
                      id[0],
                      f.actor.id(),
                      f.key,
                      digital(f),
                      rejecting(handle.backing()),
                      12,
                      12,
                      () -> true,
                      context(f, handle, false));
              f.saved.transfers().unbind(id[0]);
              f.machine.deployPreset(f.actor, MachinePresets.Mode.STOCK, exact(f), 12);
              f.saved.registry().insert(f.cell, f.key, 20);
            })
        .thenIdle(50)
        .thenExecute(
            () ->
                h.assertTrue(
                    resident(h, ResourceKind.ITEM) == 0
                        && f.saved.registry().stock(f.cell, f.key).total() == 23
                        && f.saved.transfers().get(id[0]).staged() == 12
                        && f.runtime.machinePresets().status(f.machine).reason()
                            == OperationResult.Reason.RESERVED,
                    "Replacement has a new port generation and cannot adopt an earlier request even for the same principal"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void uncertainExtractionFreezesTheRefillWithoutReplay(GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    h.setBlock(PROVIDER, ConnectorFixture.BLOCK.get());
    ConnectorFixture.ProviderEntity provider = h.getBlockEntity(PROVIDER);
    UUID[] backing = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              provider.items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 23));
              provider.failAfterExtraction = true;
              f.machine.deployPreset(f.actor, MachinePresets.Mode.IMPORT, exact(f), 64);
              backing[0] = f.runtime.connectors().bind(f.machine).handle().backing();
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.saved.transfers().unresolved(backing[0]) != null
                        && f.saved.transfers().unresolved(backing[0]).transfer().state()
                            == StagedTransfer.State.QUARANTINED,
                    "Unknown extraction is canonically quarantined"))
        .thenExecute(() -> provider.items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 23)))
        .thenIdle(50)
        .thenExecute(
            () ->
                h.assertTrue(
                    provider.items.getStackInSlot(0).getCount() == 23
                        && f.saved.registry().stock(f.cell, f.key).total() == 0
                        && f.saved.transfers().snapshot().stream()
                                .filter(
                                    r ->
                                        r.transfer().source().equals(backing[0])
                                            || r.transfer().destination().equals(backing[0]))
                                .count()
                            == 1
                        && f.runtime.machinePresets().status(f.machine).reason()
                            == OperationResult.Reason.UNCERTAIN,
                    "Unknown original extraction freezes refilled source and reports uncertainty without replay/refund"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void metadataAdmissionFailureDoesNotAdvancePhysicalConfiguration(GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var scope = f.runtime.membership(f.machine.position()).scope();
              var auth =
                  new MachinePresets.Authority(
                      f.actor.id(),
                      scope.network(),
                      scope.segment(),
                      f.saved.network(scope.network()).generation());
              var added = new ArrayList<NodeRegistry.Handle>();
              int remaining =
                  MachinePresets.MAX_DEPLOYMENTS - f.saved.machinePresets().snapshot().size();
              try {
                for (int i = 0; i < remaining; i++) {
                  var node =
                      f.saved
                          .nodes()
                          .create(
                              UUID.randomUUID(),
                              NodeRegistry.Kind.MACHINE_INTERFACE,
                              new NodeRegistry.Position("factorycore_tests:quota", i))
                          .handle();
                  added.add(node);
                  f.saved
                      .machinePresets()
                      .deploy(
                          node,
                          1,
                          MachinePresets.Face.NORTH,
                          auth,
                          MachinePresets.Mode.IMPORT,
                          ResourceFilter.all(ResourceKind.ITEM),
                          64);
                }
                long config = f.machine.generation();
                var previous = f.machine.settings();
                f.saved.setDirty(false);
                boolean rejected = false;
                try {
                  f.machine.deployPreset(f.actor, MachinePresets.Mode.IMPORT, exact(f), 64);
                } catch (IllegalStateException full) {
                  rejected = true;
                }
                h.assertTrue(
                    rejected
                        && f.machine.generation() == config
                        && f.machine.settings().equals(previous)
                        && f.saved.machinePresets().get(f.machine.handle().id()) == null
                        && !f.saved.isDirty(),
                    "Preset metadata rejection precedes port/reference/authority mutation");
              } finally {
                for (var node : added) f.saved.machinePresets().remove(node);
              }
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void pausingKeepsKnownStagingAvailableForOriginalOwnerCleanup(GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    UUID[] id = {null};
    WorldStorageTransfers.Result[] result = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 15);
              f.machine.deployPreset(f.actor, MachinePresets.Mode.STOCK, exact(f), 12);
              var handle = f.runtime.connectors().bind(f.machine).handle();
              id[0] = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(
                      id[0],
                      f.actor.id(),
                      f.key,
                      digital(f),
                      rejecting(handle.backing()),
                      12,
                      12,
                      () -> true,
                      context(f, handle, false));
              f.saved.transfers().unbind(id[0]);
              long configuration = f.machine.generation();
              var paused =
                  f.machine.deployPreset(f.actor, MachinePresets.Mode.PAUSED, exact(f), 12);
              h.assertTrue(
                  f.machine.generation() == configuration
                      && paused.configuration() == configuration,
                  "Pause invalidates intent without invalidating known endpoint cleanup");
              f.runtime.transfers().progress(id[0], f.actor, true, r -> result[0] = r);
            })
        .thenWaitUntil(() -> h.assertTrue(result[0] != null, "Original owner cleanup completed"))
        .thenIdle(30)
        .thenExecute(
            () ->
                h.assertTrue(
                    result[0].transfer().returned() == 12
                        && result[0].transfer().staged() == 0
                        && f.saved.registry().stock(f.cell, f.key).total() == 15
                        && resident(h, ResourceKind.ITEM) == 0,
                    "Paused original-owner return restores fifteen exact units without restarting automatic extraction"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void removedPresetAndOldChunkCannotReuseStagingGeneration(GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    UUID[] id = {null};
    long[] oldConfiguration = {0};
    WorldStorageTransfers.Result[] cleanup = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var oldChunk = f.machine.saveWithFullMetadata(h.getLevel().registryAccess());
              f.saved.registry().insert(f.cell, f.key, 15);
              f.machine.deployPreset(f.actor, MachinePresets.Mode.STOCK, exact(f), 12);
              oldConfiguration[0] = f.machine.generation();
              var handle = f.runtime.connectors().bind(f.machine).handle();
              id[0] = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(
                      id[0],
                      f.actor.id(),
                      f.key,
                      digital(f),
                      rejecting(handle.backing()),
                      12,
                      12,
                      () -> true,
                      context(f, handle, false));
              f.saved.transfers().unbind(id[0]);
              f.saved.registry().insert(f.cell, f.key, 20);
              f.saved.machinePresets().remove(f.machine.handle());
              f.machine.loadWithComponents(oldChunk, h.getLevel().registryAccess());
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var replacement =
                  f.machine.deployPreset(f.actor, MachinePresets.Mode.STOCK, exact(f), 12);
              h.assertTrue(
                  replacement.revision() == 1 && f.machine.generation() > oldConfiguration[0],
                  "Removed metadata and an old chunk cannot rewind the canonical configuration fence");
            })
        .thenIdle(40)
        .thenExecute(
            () -> {
              h.assertTrue(
                  resident(h, ResourceKind.ITEM) == 0
                      && f.saved.registry().stock(f.cell, f.key).total() == 23
                      && f.saved.transfers().get(id[0]).staged() == 12,
                  "Old request remains owned and is not adopted after revision counter reset");
              f.runtime
                  .transfers()
                  .progress(
                      id[0],
                      f.actor,
                      true,
                      r -> {
                        cleanup[0] = r;
                        if (r.transfer() != null
                            && r.transfer().state() == StagedTransfer.State.COMPLETE)
                          f.saved.transfers().retire(r.id());
                      });
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    cleanup[0] != null
                        && resident(h, ResourceKind.ITEM) == 12
                        && f.saved.registry().stock(f.cell, f.key).total() == 23,
                    "Original-owner return releases staging before replacement extracts fresh stock"))
        .thenExecute(
            () ->
                h.assertTrue(
                    cleanup[0].transfer().returned() == 12 && !f.saved.transfers().contains(id[0]),
                    "Thirty-five units remain conserved after explicit cleanup and resumed automation"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void authenticatedMenuReturnsOnlyOriginalOwnersKnownStaging(GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    var actor = new NetworkPermissions.Actor(player.getUUID(), false);
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    var grants = f.saved.network(root.handle().id());
    grants.setGrants(f.actor, player.getUUID(), EnumSet.allOf(NetworkPermissions.Permission.class));
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 15);
              f.machine.deployPreset(actor, MachinePresets.Mode.STOCK, exact(f), 12);
              var handle = f.runtime.connectors().bind(f.machine).handle();
              id[0] = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(
                      id[0],
                      actor.id(),
                      f.key,
                      digital(f),
                      rejecting(handle.backing()),
                      12,
                      12,
                      () -> true,
                      context(f, handle, false));
              f.saved.transfers().unbind(id[0]);
              f.machine.deployPreset(actor, MachinePresets.Mode.PAUSED, exact(f), 12);
              grants.setGrants(
                  f.actor, player.getUUID(), Set.of(NetworkPermissions.Permission.VIEW));
              h.useBlock(MACHINE, player);
              h.assertTrue(
                  player.containerMenu instanceof MachineSettingsMenu,
                  "Actual original owner opens physical menu");
              var menu = (MachineSettingsMenu) player.containerMenu;
              menu.broadcastChanges();
              h.assertTrue(
                  menu.presetView().cleanup()
                      && menu.presetView().staged() == 12
                      && !menu.presetView().editable(),
                  "VIEW-only original owner can recover known staging without DEPLOY");
              menu.presetCommand(
                  player,
                  new MachinePresetPackets.Command(
                      menu.containerId,
                      menu.session(),
                      menu.presetView().revision(),
                      MachinePresetPackets.RETURN,
                      0,
                      "",
                      new byte[32]));
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    !f.saved.transfers().contains(id[0]),
                    "Menu cleanup retires only completed known operation"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.saved.registry().stock(f.cell, f.key).total() == 15
                      && resident(h, ResourceKind.ITEM) == 0,
                  "Menu return restores all original stock without fresh extraction");
              player.closeContainer();
              player.connection.disconnect(Component.literal("Preset cleanup fixture complete"));
            })
        .thenSucceed();
  }

  private static int command(CommandSourceStack source, String command) {
    try {
      return source.getServer().getCommands().getDispatcher().execute(command, source);
    } catch (CommandSyntaxException denied) {
      return -1;
    }
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void recoveryCommandsPreserveEvidenceAndOnlyOriginalPlayerCanReturn(
      GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    var actor = new NetworkPermissions.Actor(player.getUUID(), false);
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    f.saved
        .network(root.handle().id())
        .setGrants(f.actor, player.getUUID(), EnumSet.allOf(NetworkPermissions.Permission.class));
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 15);
              var handle = f.runtime.connectors().bind(f.machine).handle();
              id[0] = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(
                      id[0],
                      actor.id(),
                      f.key,
                      digital(f),
                      rejecting(handle.backing()),
                      12,
                      12,
                      () -> true,
                      context(f, handle, false));
              f.saved.transfers().unbind(id[0]);
              var ordinary = player.createCommandSourceStack().withPermission(0);
              var operator = player.createCommandSourceStack().withPermission(2);
              h.assertTrue(
                  command(ordinary, "factorycore recovery show " + id[0]) < 1
                      && command(operator, "factorycore recovery show " + id[0]) == 1,
                  "Operator inspection is gated while actual command syntax resolves");
              h.assertTrue(
                  command(ordinary, "factorycore recovery list") < 1
                      && command(operator, "factorycore recovery list") > 0,
                  "Only operator source can page world recovery metadata");
              var console = player.server.createCommandSourceStack();
              h.assertTrue(
                  command(console, "factorycore recovery return " + id[0]) < 1,
                  "Console cannot impersonate an original player for cleanup");
              h.assertTrue(
                  command(console.withEntity(player), "factorycore recovery return " + id[0]) < 1,
                  "execute-as cannot turn console authority into the original player");
              h.assertTrue(
                  command(ordinary, "factorycore recovery return " + id[0]) == 1,
                  "Authenticated original owner queues known return");
              var folder =
                  player.server.getWorldPath(LevelResource.ROOT).resolve("factorycore-recovery");
              boolean evidence = false;
              try (var paths = Files.list(folder)) {
                evidence =
                    paths.anyMatch(path -> path.getFileName().toString().startsWith(id[0] + "-"));
              } catch (IOException failure) {
                throw new IllegalStateException(failure);
              }
              h.assertTrue(
                  evidence && f.saved.transfers().get(id[0]).staged() == 12,
                  "Before-state evidence exists before scheduled resource mutation");
              h.assertTrue(
                  command(operator, "factorycore recovery retire " + id[0]) == 0,
                  "Live staging cannot retire");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.saved.transfers().get(id[0]).state() == StagedTransfer.State.COMPLETE,
                    "Known command cleanup completes"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.saved.registry().stock(f.cell, f.key).total() == 15
                      && f.saved.transfers().contains(id[0])
                      && f.saved.transfers().get(id[0]).returned() == 12,
                  "Cleanup conserves stock and retains completed record as evidence");
              h.assertTrue(
                  command(
                              player.createCommandSourceStack().withPermission(2),
                              "factorycore recovery retire " + id[0])
                          == 1
                      && !f.saved.transfers().contains(id[0])
                      && f.saved.registry().stock(f.cell, f.key).total() == 15,
                  "Operator explicitly retires only empty completion after preserving its evidence");
              player.connection.disconnect(Component.literal("Recovery command fixture complete"));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void recoveryCommandDeniesOtherOwnersAndUncertaintyWithoutAuditMutation(
      GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    var owner = TestPlayers.mock(h);
    var other = TestPlayers.mock(h);
    TestPlayers.connect(owner);
    TestPlayers.connect(other);
    var actor = new NetworkPermissions.Actor(owner.getUUID(), false);
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    f.saved
        .network(root.handle().id())
        .setGrants(f.actor, owner.getUUID(), EnumSet.allOf(NetworkPermissions.Permission.class));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 15);
              var handle = f.runtime.connectors().bind(f.machine).handle();
              var id = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(
                      id,
                      actor.id(),
                      f.key,
                      digital(f),
                      new ResourcePort() {
                        public UUID backingId() {
                          return handle.backing();
                        }

                        public long simulateInsert(ResourceKey key, long n) {
                          return n;
                        }

                        public long insert(ResourceKey key, long n) {
                          throw new IllegalStateException("Injected command uncertainty");
                        }

                        public long extract(ResourceKey key, long n) {
                          throw new AssertionError("Cannot extract target");
                        }
                      },
                      8,
                      8,
                      () -> true,
                      context(f, handle, false));
              var before = f.saved.transfers().get(id);
              h.assertTrue(
                  command(
                          other.createCommandSourceStack().withPermission(2),
                          "factorycore recovery return " + id)
                      == 0,
                  "An operator cannot adopt another principal's transfer");
              h.assertTrue(
                  command(owner.createCommandSourceStack(), "factorycore recovery return " + id)
                          == 0
                      && before.equals(f.saved.transfers().get(id))
                      && before.pending() == StagedTransfer.PendingCall.DELIVER
                      && f.saved.registry().stock(f.cell, f.key).total() == 7,
                  "Original owner cannot replay/refund uncertainty or change accepted accounting");
              var folder =
                  owner.server.getWorldPath(LevelResource.ROOT).resolve("factorycore-recovery");
              if (Files.exists(folder))
                try (var paths = Files.list(folder)) {
                  h.assertTrue(
                      paths.noneMatch(path -> path.getFileName().toString().startsWith(id + "-")),
                      "Denied uncertainty writes no cleanup audit");
                } catch (IOException failure) {
                  throw new IllegalStateException(failure);
                }
              owner.connection.disconnect(Component.literal("Recovery denial complete"));
              other.connection.disconnect(Component.literal("Recovery denial complete"));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", batch = "machine_recovery", timeoutTicks = 500)
  public static void auditedMachineRepairKeepsFenceAndRequiresAuthenticatedRedeployment(
      GameTestHelper h) {
    var f = setup(h, ResourceKind.ITEM);
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.getInventory().clearContent();
    player.moveTo(Vec3.atCenterOf(h.absolutePos(MACHINE)));
    h.assertTrue(
        !player.hasPermissions(Actors.OPERATOR_LEVEL),
        "Recovered machine deployer is an ordinary registered player");
    var deployer = new NetworkPermissions.Actor(player.getUUID(), false);
    var old = f.machine.handle();
    var settings =
        new ConnectorSettings(ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 7, 0, 1, 3);
    MachinePresets.Deployment[] accepted = {null};
    long[] fence = {0};
    WorldConnectors.Handle[] registration = {null};
    CompoundTag[] original = {null};
    MachineInterfaceEntity[] clone = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved
                  .network(f.runtime.membership(f.machine.position()).scope().network())
                  .setGrants(
                      f.actor, deployer.id(), EnumSet.allOf(NetworkPermissions.Permission.class));
              h.assertTrue(
                  f.machine.applySettings(f.actor, settings, "repaired_furnace")
                      == NetworkAuthority.Decision.ALLOWED,
                  "Configure labeled selected machine window");
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenIdle(20)
        .thenExecute(
            () -> {
              seed(h, ResourceKind.ITEM, 19);
              accepted[0] =
                  f.machine.deployPreset(deployer, MachinePresets.Mode.IMPORT, exact(f), 19);
              fence[0] = f.saved.machinePresets().configuration(old.id());
              f.runtime.machinePresets().tick();
              registration[0] = f.runtime.connectors().bind(f.machine).handle();
              h.assertTrue(
                  registration[0] != null, "Remember actual provider registration before conflict");
              var stalePort = f.runtime.connectors().port(registration[0]);
              ChestBlockEntity chest = h.getBlockEntity(PROVIDER);
              var provider = chest.saveWithFullMetadata(h.getLevel().registryAccess());
              original[0] = f.machine.saveWithFullMetadata(h.getLevel().registryAccess());
              clone[0] = copyMachineForRecovery(h, f.machine, original[0]);
              h.assertTrue(
                  f.saved.nodes().validate(old) == NodeRegistry.Failure.CONFLICT
                      && registration[0].equals(
                          f.runtime.connectors().recoveryRegistration(f.machine)),
                  "Current conflicted machine retains metadata witness");
              var console = h.getLevel().getServer().createCommandSourceStack();
              var repair = "factorycore recovery port repair " + old.id() + " " + old.generation();
              h.assertTrue(
                  command(console.withPermission(0), repair) < 1, "Operator permission required");
              h.assertTrue(
                  command(console, "factorycore recovery port show " + old.id()) == 1,
                  "Inspect original accepted principal/fence");
              h.assertTrue(
                  command(console, repair) == 1,
                  "Actual command repairs original Machine Interface");
              h.assertTrue(
                  f.machine.handle().generation() == old.generation() + 1
                      && f.machine.settings().equals(settings)
                      && f.machine.label().equals("repaired_furnace")
                      && f.machine.targetDirection() == Direction.EAST
                      && f.machine.generation() == fence[0],
                  "Lease rotates without changing local settings/configuration/label/face");
              h.assertTrue(
                  accepted[0].equals(f.saved.machinePresets().get(old.id()))
                      && fence[0] == f.saved.machinePresets().configuration(old.id())
                      && !f.machine.presetAuthorized(accepted[0]),
                  "Accepted deployment/fence unchanged; old lease cannot run");
              h.assertTrue(
                  provider.equals(chest.saveWithFullMetadata(h.getLevel().registryAccess()))
                      && stalePort.extract(f.key, 1) == 0
                      && stalePort.insert(f.key, 1) == 0,
                  "Repair never mutates provider; old mod-owned port rejects mutation");
              clone[0].loadWithComponents(original[0], h.getLevel().registryAccess());
              clone[0].onLoad();
              h.assertTrue(
                  f.saved.nodes().validate(f.machine.handle()) == NodeRegistry.Failure.OK
                      && !clone[0].diagnostic().isEmpty(),
                  "Old copied machine cannot requarantine new lease");
            })
        .thenIdle(30)
        .thenExecute(
            () -> {
              ready(h, f);
              h.assertTrue(
                  resident(h, ResourceKind.ITEM) == 19
                      && f.saved.registry().stock(f.cell, f.key).total() == 0
                      && accepted[0].equals(f.saved.machinePresets().get(old.id())),
                  "Queued/retained old automation never extracts after repair");
              var rebound = f.runtime.connectors().bind(f.machine).handle();
              h.assertTrue(
                  rebound != null && rebound.backing().equals(registration[0].backing()),
                  "Normal provider reacquisition keeps backing identity");
              var grants = EnumSet.allOf(NetworkPermissions.Permission.class);
              grants.remove(NetworkPermissions.Permission.DEPLOY);
              var scope = f.runtime.membership(f.machine.position()).scope();
              f.saved.network(scope.network()).setGrants(f.actor, deployer.id(), grants);
              h.useBlock(MACHINE, player);
              h.assertTrue(
                  player.containerMenu instanceof MachineSettingsMenu,
                  "Physical use opens recovered machine settings");
              var menu = (MachineSettingsMenu) player.containerMenu;
              menu.broadcastChanges();
              recoveryDeploy(menu, player);
              h.assertTrue(
                  menu.presetView().status() == MachinePresetPackets.Status.DENIED
                      && accepted[0].equals(f.saved.machinePresets().get(old.id())),
                  "Native redeploy cannot bypass revoked current DEPLOY grant");
              f.saved
                  .network(scope.network())
                  .setGrants(
                      f.actor, deployer.id(), EnumSet.allOf(NetworkPermissions.Permission.class));
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var menu = (MachineSettingsMenu) player.containerMenu;
              menu.broadcastChanges();
              recoveryDeploy(menu, player);
              var next = f.saved.machinePresets().get(old.id());
              h.assertTrue(
                  menu.presetView().status() == MachinePresetPackets.Status.APPLIED
                      && next.authority().principal().equals(deployer.id())
                      && next.leaseGeneration() == f.machine.handle().generation()
                      && next.filter().equals(accepted[0].filter())
                      && next.target() == 19
                      && next.revision() == accepted[0].revision() + 1
                      && next.configuration() == fence[0] + 1,
                  "Native explicit deployment accepts current lease under original authorized principal/policy");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    resident(h, ResourceKind.ITEM) == 0
                        && f.saved.registry().stock(f.cell, f.key).total() == 19,
                    "Authorized redeployment resumes shared Import with19 conserved items"))
        .thenExecute(
            () -> {
              player.closeContainer();
              ChestBlockEntity chest = h.getBlockEntity(PROVIDER);
              h.assertTrue(
                  chest.getItem(1).is(Items.GOLD_INGOT) && chest.getItem(1).getCount() == 7,
                  "Selected window and exact filter preserve excluded native stock");
              var current = f.machine.handle();
              var repaired = f.machine.saveWithFullMetadata(h.getLevel().registryAccess());
              var deployment = f.saved.machinePresets().get(old.id());
              f.machine.loadWithComponents(original[0], h.getLevel().registryAccess());
              f.machine.onLoad();
              rejectMachineRepair(h, f.machine, current);
              h.assertTrue(
                  f.saved.nodes().validate(current) == NodeRegistry.Failure.OK
                      && deployment.equals(f.saved.machinePresets().get(old.id())),
                  "Old original snapshot stays inert without changing accepted current deployment");
              f.machine.loadWithComponents(repaired, h.getLevel().registryAccess());
              f.machine.onLoad();
              f.saved.machinePresets().observeConfiguration(current, f.machine.generation() + 1);
              var ahead = f.saved.machinePresets().configuration(old.id());
              copyMachineForRecovery(h, f.machine, repaired);
              var conflicted = f.saved.nodes().evidence(old.id());
              rejectMachineRepair(h, f.machine, current);
              h.assertTrue(
                  ahead == f.saved.machinePresets().configuration(old.id())
                      && conflicted.equals(f.saved.nodes().evidence(old.id()))
                      && deployment.equals(f.saved.machinePresets().get(old.id()))
                      && f.saved.registry().stock(f.cell, f.key).total() == 19,
                  "Mismatched physical/canonical fence refuses without adopting stale configuration or losing stock");
              player.connection.disconnect(Component.literal("Machine recovery fixture complete"));
            })
        .thenIdle(20)
        .thenSucceed();
  }

  private static MachineInterfaceEntity copyMachineForRecovery(
      GameTestHelper h, MachineInterfaceEntity original, CompoundTag raw) {
    var position = new BlockPos(3, 1, 3);
    h.setBlock(position, original.getBlockState());
    MachineInterfaceEntity copy = h.getBlockEntity(position);
    copy.loadWithComponents(raw, h.getLevel().registryAccess());
    copy.onLoad();
    return copy;
  }

  private static void rejectMachineRepair(
      GameTestHelper h, MachineInterfaceEntity machine, NodeRegistry.Handle expected) {
    boolean refused = false;
    try {
      machine.repairConflict(expected);
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      refused = true;
    } catch (IOException failure) {
      throw new AssertionError(failure);
    }
    h.assertTrue(refused, "Unsupported machine recovery must refuse");
  }

  private static void recoveryDeploy(MachineSettingsMenu menu, ServerPlayer player) {
    menu.presetCommand(
        player,
        new MachinePresetPackets.Command(
            menu.containerId,
            menu.session(),
            menu.presetView().revision(),
            MachinePresetPackets.DEPLOY,
            0,
            "",
            new byte[32]));
  }
}
