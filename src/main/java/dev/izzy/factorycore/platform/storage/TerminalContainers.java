package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.WirelessBudget;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Explicit terminal container actions on the player's own main inventory: fill/empty fluid
 * containers and charge/discharge energy items against authorized digital storage. Each action
 * proves fit first and runs on the server thread in one tick; storage changes only by the amount
 * the container actually accepted or released, and a container that disagrees with its own
 * simulation is reverted and reported. No fluid is ever handed out as a fictional item.
 */
final class TerminalContainers {
  /** Per-action FE limit for charge/discharge (R08 configured limit). */
  static final long ENERGY_PER_ACTION = 1_000;

  /** Largest fluid amount moved in one fill/empty action. */
  static final int FLUID_PER_ACTION = 16_000;

  private record Cell(DeviceRegistry.Handle handle) {}

  private final ServerPlayer player;
  private final NodeRegistry.Position origin;
  private final DeviceSavedData saved;
  private final WorldStorageTransfers transfers;
  private final PlatformResourceCodec codec;
  private final NetworkPermissions.Actor actor;

  TerminalContainers(ServerPlayer player, NodeRegistry.Position origin) {
    this.player = player;
    this.origin = origin;
    saved = DeviceSavedData.get(player.server);
    transfers = WorldNetworks.get(player.server).transfers();
    codec = new PlatformResourceCodec(player.registryAccess());
    actor = Actors.of(player);
  }

  /**
   * Placed, valid cells/banks of {@code key}'s kind allowed for {@code operation}, stable order.
   */
  private List<Cell> cells(ResourceKey key, NetworkPermissions.Permission operation) {
    var registry = saved.registry();
    var result = new ArrayList<Cell>();
    UUID after = null;
    do {
      var page = registry.handles(after, 16);
      for (var handle : page.handles()) {
        if (registry.validate(handle) != DeviceRegistry.Failure.OK
            || registry.definition(handle).location() == null
            || registry.definition(handle).kind() != key.kind()
            || !transfers.digitalAllowed(actor, origin, handle, key, operation)) continue;
        result.add(new Cell(handle));
      }
      after = page.next();
    } while (after != null);
    return result;
  }

  private static EnergyBankTier tier(DeviceRegistry registry, DeviceRegistry.Handle handle) {
    long capacity = registry.definition(handle).capacity();
    for (var tier : EnergyBankTier.values()) if (tier.capacity() == capacity) return tier;
    return null;
  }

  private record Leg(DeviceRegistry.Handle handle, long amount, WirelessBudget.Permit permit) {}

  /** All admission precedes owned mutations; compensation targets only these exact holders. */
  private final class Plan {
    final ResourceKey key;
    final boolean take;
    final List<Leg> legs = new ArrayList<>();
    long amount;
    TerminalPackets.Status failure;

    Plan(ResourceKey key, boolean take) {
      this.key = key;
      this.take = take;
      failure = take ? TerminalPackets.Status.SHORTAGE : TerminalPackets.Status.FULL;
    }

    void release() {
      for (var leg : legs) leg.permit().finish(0);
    }

    boolean commit() {
      var changed = new ArrayList<Long>();
      boolean complete = true;
      for (var leg : legs) {
        long actual = apply(leg.handle(), key, leg.amount(), take, false);
        changed.add(actual);
        if (actual != leg.amount()) {
          complete = false;
          break;
        }
      }
      if (!complete) {
        // No foreign handler or other tick can interleave. Restore exact owned holders, without
        // requesting a second wireless or bank-rate allowance for this compensation.
        for (int index = changed.size() - 1; index >= 0; index--) {
          long actual = changed.get(index);
          var handle = legs.get(index).handle();
          long restored =
              take
                  ? saved.registry().insert(handle, key, actual).amount()
                  : saved.registry().extract(handle, key, actual, null).amount();
          if (restored != actual)
            throw new IllegalStateException("Owned terminal compensation failed");
        }
        release();
        return false;
      }
      for (var leg : legs) leg.permit().finish(leg.amount());
      return true;
    }
  }

  private long apply(
      DeviceRegistry.Handle handle, ResourceKey key, long amount, boolean take, boolean simulate) {
    if (key.kind() == ResourceKind.ENERGY) {
      var tier = tier(saved.registry(), handle);
      if (tier == null) return 0;
      return take
          ? saved.energyBanks().extract(handle, tier, amount, simulate).amount()
          : saved.energyBanks().receive(handle, tier, amount, simulate).amount();
    }
    if (take)
      return simulate
          ? Math.min(amount, saved.registry().stock(handle, key).available())
          : saved.registry().extract(handle, key, amount, null).amount();
    return simulate
        ? saved.registry().simulateInsert(handle, key, amount).amount()
        : saved.registry().insert(handle, key, amount).amount();
  }

  private Plan plan(ResourceKey key, long requested, boolean take) {
    var plan = new Plan(key, take);
    var runtime = WorldNetworks.get(player.server);
    var permission =
        take ? NetworkPermissions.Permission.WITHDRAW : NetworkPermissions.Permission.DEPOSIT;
    for (var cell : cells(key, permission)) {
      if (plan.amount == requested) break;
      long offer = apply(cell.handle(), key, requested - plan.amount, take, true);
      if (offer == 0) continue;
      var location = saved.registry().definition(cell.handle()).location();
      var position = new NodeRegistry.Position(location.dimension(), location.position());
      var permit = runtime.movement(take ? position : origin, take ? origin : position, key, offer);
      if (permit.amount() < offer) plan.failure = TerminalPackets.Status.of(permit.reason());
      if (permit.amount() == 0) {
        permit.finish(0);
        continue;
      }
      plan.legs.add(new Leg(cell.handle(), permit.amount(), permit));
      plan.amount += permit.amount();
    }
    return plan;
  }

  /** Puts {@code result} where one unit of the stack in {@code slot} was; false if no room. */
  private boolean replaceOne(int slot, ItemStack result, boolean simulate) {
    var inventory = player.getInventory();
    var stack = inventory.getItem(slot);
    if (stack.getCount() == 1) {
      if (!simulate) inventory.setItem(slot, result);
      return true;
    }
    boolean room =
        result.isEmpty()
            || inventory.getFreeSlot() >= 0
            || inventory.getSlotWithRemainingSpace(result) >= 0;
    if (!simulate && room) {
      stack.shrink(1);
      if (!result.isEmpty()) inventory.add(result);
    }
    return room;
  }

  /** Fills the first main-inventory container that accepts {@code key}. */
  TerminalPackets.Status fill(ResourceKey key) {
    if (key.kind() != ResourceKind.FLUID) return TerminalPackets.Status.WRONG_RESOURCE;
    var inventory = player.getInventory();
    var fluid = codec.fluidStack(key, 1);
    long have = 0;
    for (var cell : cells(key, NetworkPermissions.Permission.WITHDRAW)) {
      have +=
          Math.min(FLUID_PER_ACTION - have, saved.registry().stock(cell.handle(), key).available());
      if (have == FLUID_PER_ACTION) break;
    }
    if (have == 0) return TerminalPackets.Status.SHORTAGE;
    for (int slot = 0; slot < 36; slot++) {
      var stack = inventory.getItem(slot);
      if (stack.isEmpty()) continue;
      var single = stack.copyWithCount(1);
      var handler = single.getCapability(Capabilities.FluidHandler.ITEM);
      if (handler == null) continue;
      int offer = (int) Math.min(have, FLUID_PER_ACTION);
      int accept = handler.fill(fluid.copyWithAmount(offer), IFluidHandler.FluidAction.SIMULATE);
      if (accept <= 0) continue;
      if (accept > offer) return TerminalPackets.Status.HANDLER_FAULT;
      // Simulate on a disposable copy to learn the resulting container before moving fluid.
      var trial = stack.copyWithCount(1).getCapability(Capabilities.FluidHandler.ITEM);
      if (trial == null
          || trial.fill(fluid.copyWithAmount(accept), IFluidHandler.FluidAction.EXECUTE) != accept)
        return TerminalPackets.Status.HANDLER_FAULT;
      if (!replaceOne(slot, trial.getContainer(), true)) return TerminalPackets.Status.FULL;
      var plan = plan(key, accept, true);
      if (plan.amount != accept) {
        plan.release();
        return plan.failure;
      }
      if (!plan.commit()) return TerminalPackets.Status.HANDLER_FAULT;
      replaceOne(slot, trial.getContainer(), false);
      inventory.setChanged();
      return TerminalPackets.Status.FILLED;
    }
    return TerminalPackets.Status.NO_CONTAINER;
  }

  /** Empties the fluid container in {@code slot} into storage. */
  TerminalPackets.Status empty(int slot) {
    var inventory = player.getInventory();
    var stack = inventory.getItem(slot);
    if (stack.isEmpty()) return TerminalPackets.Status.NO_CONTAINER;
    var handler = stack.copyWithCount(1).getCapability(Capabilities.FluidHandler.ITEM);
    if (handler == null) return TerminalPackets.Status.NO_CONTAINER;
    var simulated = handler.drain(FLUID_PER_ACTION, IFluidHandler.FluidAction.SIMULATE);
    if (simulated.isEmpty()) return TerminalPackets.Status.EMPTY;
    if (simulated.getAmount() > FLUID_PER_ACTION) return TerminalPackets.Status.HANDLER_FAULT;
    var key = codec.fluidKey(simulated);
    var drained = handler.drain(simulated.copy(), IFluidHandler.FluidAction.EXECUTE);
    if (drained.getAmount() != simulated.getAmount() || !codec.fluidKey(drained).equals(key))
      return TerminalPackets.Status.HANDLER_FAULT;
    if (!replaceOne(slot, handler.getContainer(), true)) return TerminalPackets.Status.FULL;
    var plan = plan(key, drained.getAmount(), false);
    if (plan.amount != drained.getAmount()) {
      plan.release();
      return plan.failure;
    }
    if (!plan.commit()) return TerminalPackets.Status.HANDLER_FAULT;
    replaceOne(slot, handler.getContainer(), false);
    inventory.setChanged();
    return TerminalPackets.Status.EMPTIED;
  }

  /** Moves FE between storage banks and the single energy item in {@code slot}. */
  TerminalPackets.Status energy(int slot, boolean charge) {
    var inventory = player.getInventory();
    var stack = inventory.getItem(slot);
    if (stack.isEmpty() || stack.getCount() != 1) return TerminalPackets.Status.NO_ENERGY_ITEM;
    var changed = stack.copy();
    var item = changed.getCapability(Capabilities.EnergyStorage.ITEM);
    if (item == null) return TerminalPackets.Status.NO_ENERGY_ITEM;
    var key = ResourceKey.ENERGY;
    if (charge) {
      if (!item.canReceive()) return TerminalPackets.Status.NO_ENERGY_ITEM;
      int want = item.receiveEnergy((int) ENERGY_PER_ACTION, true);
      if (want <= 0) return TerminalPackets.Status.ITEM_FULL;
      if (want > ENERGY_PER_ACTION) return TerminalPackets.Status.HANDLER_FAULT;
      var plan = plan(key, want, true);
      if (plan.amount == 0) {
        plan.release();
        return plan.failure;
      }
      int received = item.receiveEnergy((int) plan.amount, false);
      if (received != plan.amount) {
        plan.release();
        return TerminalPackets.Status.HANDLER_FAULT;
      }
      if (!plan.commit()) return TerminalPackets.Status.HANDLER_FAULT;
      inventory.setItem(slot, changed);
      inventory.setChanged();
      return TerminalPackets.Status.CHARGED;
    }
    if (!item.canExtract()) return TerminalPackets.Status.NO_ENERGY_ITEM;
    int offer = item.extractEnergy((int) ENERGY_PER_ACTION, true);
    if (offer <= 0) return TerminalPackets.Status.ITEM_EMPTY;
    if (offer > ENERGY_PER_ACTION) return TerminalPackets.Status.HANDLER_FAULT;
    var plan = plan(key, offer, false);
    if (plan.amount == 0) {
      plan.release();
      return plan.failure;
    }
    int extracted = item.extractEnergy((int) plan.amount, false);
    if (extracted != plan.amount) {
      plan.release();
      return TerminalPackets.Status.HANDLER_FAULT;
    }
    if (!plan.commit()) return TerminalPackets.Status.HANDLER_FAULT;
    inventory.setItem(slot, changed);
    inventory.setChanged();
    return TerminalPackets.Status.DISCHARGED;
  }
}
