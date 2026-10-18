package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.ArrayList;
import java.util.List;
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

  private record Cell(DeviceRegistry.Handle handle, long available) {}

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
    actor = new NetworkPermissions.Actor(player.getUUID(), player.hasPermissions(2));
  }

  /**
   * Placed, valid cells/banks of {@code key}'s kind allowed for {@code operation}, stable order.
   */
  private List<Cell> cells(ResourceKey key, NetworkPermissions.Permission operation) {
    var registry = saved.registry();
    var result = new ArrayList<Cell>();
    java.util.UUID after = null;
    do {
      var page = registry.handles(after, 16);
      for (var handle : page.handles()) {
        if (registry.validate(handle) != DeviceRegistry.Failure.OK
            || registry.definition(handle).location() == null
            || registry.definition(handle).kind() != key.kind()
            || !transfers.digitalAllowed(actor, origin, handle, key, operation)) continue;
        result.add(new Cell(handle, registry.stock(handle, key).available()));
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

  /** Removes up to {@code amount} unreserved units from storage; returns what was removed. */
  private long take(ResourceKey key, long amount) {
    long taken = 0;
    for (var cell : cells(key, NetworkPermissions.Permission.WITHDRAW)) {
      if (taken == amount) break;
      long want = Math.min(amount - taken, cell.available());
      if (want <= 0) continue;
      if (key.kind() == ResourceKind.ENERGY) {
        var tier = tier(saved.registry(), cell.handle());
        if (tier == null) continue;
        taken += saved.energyBanks().extract(cell.handle(), tier, want, false).amount();
      } else taken += saved.registry().extract(cell.handle(), key, want, null).amount();
    }
    return taken;
  }

  /** Inserts up to {@code amount} units into storage; returns what was accepted. */
  private long put(ResourceKey key, long amount, boolean simulate) {
    long placed = 0;
    for (var cell : cells(key, NetworkPermissions.Permission.DEPOSIT)) {
      if (placed == amount) break;
      long remaining = amount - placed;
      if (key.kind() == ResourceKind.ENERGY) {
        var tier = tier(saved.registry(), cell.handle());
        if (tier == null) continue;
        placed += saved.energyBanks().receive(cell.handle(), tier, remaining, simulate).amount();
      } else
        placed +=
            simulate
                ? saved.registry().simulateInsert(cell.handle(), key, remaining).amount()
                : saved.registry().insert(cell.handle(), key, remaining).amount();
    }
    return placed;
  }

  private long available(ResourceKey key) {
    long total = 0;
    for (var cell : cells(key, NetworkPermissions.Permission.WITHDRAW))
      total = Math.addExact(total, cell.available());
    return total;
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
  String fill(ResourceKey key) {
    if (key.kind() != ResourceKind.FLUID) return "wrong_resource";
    var inventory = player.getInventory();
    var fluid = codec.fluidStack(key, 1);
    long have = available(key);
    if (have == 0) return "shortage";
    for (int slot = 0; slot < 36; slot++) {
      var stack = inventory.getItem(slot);
      if (stack.isEmpty()) continue;
      var single = stack.copyWithCount(1);
      var handler = single.getCapability(Capabilities.FluidHandler.ITEM);
      if (handler == null) continue;
      int offer = (int) Math.min(have, FLUID_PER_ACTION);
      int accept = handler.fill(fluid.copyWithAmount(offer), IFluidHandler.FluidAction.SIMULATE);
      if (accept <= 0) continue;
      // Simulate on a disposable copy to learn the resulting container before moving fluid.
      var trial = stack.copyWithCount(1).getCapability(Capabilities.FluidHandler.ITEM);
      if (trial == null
          || trial.fill(fluid.copyWithAmount(accept), IFluidHandler.FluidAction.EXECUTE) != accept)
        return "handler_fault";
      if (!replaceOne(slot, trial.getContainer(), true)) return "full";
      long taken = take(key, accept);
      if (taken != accept) {
        put(key, taken, false);
        return "shortage";
      }
      int filled = handler.fill(fluid.copyWithAmount(accept), IFluidHandler.FluidAction.EXECUTE);
      if (filled != accept) {
        put(key, taken, false);
        return "handler_fault";
      }
      replaceOne(slot, handler.getContainer(), false);
      inventory.setChanged();
      return "filled";
    }
    return "no_container";
  }

  /** Empties the fluid container in {@code slot} into storage. */
  String empty(int slot) {
    var inventory = player.getInventory();
    var stack = inventory.getItem(slot);
    if (stack.isEmpty()) return "no_container";
    var handler = stack.copyWithCount(1).getCapability(Capabilities.FluidHandler.ITEM);
    if (handler == null) return "no_container";
    var simulated = handler.drain(FLUID_PER_ACTION, IFluidHandler.FluidAction.SIMULATE);
    if (simulated.isEmpty()) return "empty";
    var key = codec.fluidKey(simulated);
    long room = put(key, simulated.getAmount(), true);
    if (room < simulated.getAmount()) return "full";
    var drained = handler.drain(simulated.copy(), IFluidHandler.FluidAction.EXECUTE);
    if (drained.getAmount() != simulated.getAmount() || !codec.fluidKey(drained).equals(key))
      return "handler_fault";
    if (!replaceOne(slot, handler.getContainer(), true)) return "full";
    long placed = put(key, drained.getAmount(), false);
    if (placed != drained.getAmount()) {
      // Should not happen after the room check; keep what could not be stored in the container.
      take(key, placed);
      return "full";
    }
    replaceOne(slot, handler.getContainer(), false);
    inventory.setChanged();
    return "emptied";
  }

  /** Moves FE between storage banks and the single energy item in {@code slot}. */
  String energy(int slot, boolean charge) {
    var inventory = player.getInventory();
    var stack = inventory.getItem(slot);
    if (stack.isEmpty() || stack.getCount() != 1) return "no_energy_item";
    var item = stack.getCapability(Capabilities.EnergyStorage.ITEM);
    if (item == null) return "no_energy_item";
    var key = ResourceKey.ENERGY;
    if (charge) {
      if (!item.canReceive()) return "no_energy_item";
      int want = item.receiveEnergy((int) ENERGY_PER_ACTION, true);
      if (want <= 0) return "item_full";
      long taken = take(key, want);
      if (taken == 0) return "shortage";
      int received = Math.max(0, Math.min((int) taken, item.receiveEnergy((int) taken, false)));
      if (received < taken) put(key, taken - received, false);
      inventory.setChanged();
      return "charged";
    }
    if (!item.canExtract()) return "no_energy_item";
    int offer = item.extractEnergy((int) ENERGY_PER_ACTION, true);
    if (offer <= 0) return "item_empty";
    long room = put(key, offer, true);
    if (room == 0) return "full";
    int extracted = Math.max(0, Math.min((int) room, item.extractEnergy((int) room, false)));
    long placed = put(key, extracted, false);
    if (placed < extracted) item.receiveEnergy((int) (extracted - placed), false);
    inventory.setChanged();
    return "discharged";
  }
}
