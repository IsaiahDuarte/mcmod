package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Q32 (QD8): reaches a cell held in a Portable Storage without a network (ADR 0005 amendment).
 *
 * <p>A move attaches the cell's lease to a per-player portable location, makes it available, runs
 * the move, and detaches it again, all in one server-thread call. So between ticks (and in every
 * save) the cell is detached, exactly as a cell in an inventory, and nothing can see the portable
 * location but the move itself. The routing predicate ({@code WorldNetworks}) admits a portable
 * location only while such a move holds it.
 */
public final class PortableCells {
  /** The pseudo-dimension of portable locations; no level has it. */
  public static final String DIMENSION = "factorycore:portable";

  private static final Set<DeviceRegistry.Location> OPEN = new HashSet<>();

  private PortableCells() {}

  public static boolean portable(DeviceRegistry.Location location) {
    return DIMENSION.equals(location.dimension());
  }

  /** True only while a move holds {@code location}. */
  public static boolean open(DeviceRegistry.Location location) {
    return OPEN.contains(location);
  }

  static DeviceRegistry.Location location(UUID player) {
    return new DeviceRegistry.Location(
        DIMENSION, player.getMostSignificantBits() ^ player.getLeastSignificantBits(), 0);
  }

  /** The result of a move and the cell as it must be stored afterwards (new lease generation). */
  public record Moved<T>(T result, ItemStack cell) {}

  /**
   * Runs {@code move} with {@code cell} reachable. Throws (and changes nothing) when the cell is
   * placed elsewhere, its tier does not match its backing, a transfer for it is unresolved, or the
   * registry is full; a failure inside {@code move} still detaches it.
   */
  public static <T> Moved<T> with(
      ServerPlayer player, ItemStack cell, Function<DeviceRegistry.Handle, T> move) {
    var saved = DeviceSavedData.get(player.server);
    var registry = saved.registry();
    var known = CellHandleData.read(cell);
    if (known != null && saved.transfers().unresolved(known.backing()) != null)
      throw new IllegalStateException("A transfer for this cell is unresolved");
    var location = location(player.getUUID());
    var installed = CellOwnership.install(cell, registry, location);
    var handle = CellHandleData.read(installed);
    T result;
    OPEN.add(location);
    try {
      registry.availability(handle, location, true);
      result = move.apply(handle);
    } finally {
      OPEN.remove(location);
      var summary = CellSummary.of(registry, handle);
      var detached = registry.detach(handle, location);
      if (detached.failure() != DeviceRegistry.Failure.OK)
        throw new IllegalStateException("Portable cell detach: " + detached.failure());
      CellHandleData.write(installed, detached.handle());
      if (summary != null) installed.set(StorageContent.SUMMARY.get(), summary);
    }
    return new Moved<>(result, installed);
  }
}
