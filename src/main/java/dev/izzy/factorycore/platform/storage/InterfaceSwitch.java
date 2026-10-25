package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * Switches a placed Interface to another mode (ADR 0059). A switch is exactly "break the old
 * device, retire its lease, place a fresh one", so it is refused unless nothing can be lost:
 *
 * <ul>
 *   <li>the player may use the Interface and holds EDIT at it;
 *   <li>no staged transfer is unresolved for its lease (nothing in transit);
 *   <li>a Buffer's nine slots are empty;
 *   <li>no Throughput or Overdrive upgrade is installed (upgrades cannot be removed, so switching
 *       would lose them).
 * </ul>
 *
 * Every check runs before anything changes; a refusal changes nothing. Settings, labels and presets
 * of the old mode are not carried over.
 */
public final class InterfaceSwitch {
  /** Button {@code BUTTON + kind ordinal} on an Interface screen asks for that mode. */
  public static final int BUTTON = 200;

  public enum Outcome {
    SWITCHED,
    SAME,
    DENIED,
    BUSY,
    NOT_EMPTY,
    UPGRADED,
    FAULT;

    /** Lower-case status suffix for screen text. */
    public String key() {
      return "switch_" + name().toLowerCase(Locale.ROOT);
    }
  }

  private InterfaceSwitch() {}

  /** The mode a menu button id asks for, or null when it is not a switch button. */
  public static InterfaceKind requested(int button) {
    int index = button - BUTTON;
    var kinds = InterfaceKind.values();
    return index >= 0 && index < kinds.length ? kinds[index] : null;
  }

  public static Outcome request(ServerPlayer player, BlockPos position, InterfaceKind target) {
    var level = player.serverLevel();
    var state = level.getBlockState(position);
    if (!(state.getBlock() instanceof InterfaceBlock)) return Outcome.FAULT;
    if (state.getValue(InterfaceBlock.KIND) == target) return Outcome.SAME;
    var entity = level.getBlockEntity(position);
    // C1: a Signal Interface has no lease, contents or upgrades; switching it only needs EDIT.
    if (entity instanceof dev.izzy.factorycore.platform.signal.SignalInterfaceEntity signal) {
      if (!signal.canUse(player)
          || !WorldNetworks.get(player.server)
              .allows(Actors.of(player), signal.origin(), NetworkPermissions.Permission.EDIT))
        return Outcome.DENIED;
      level.removeBlockEntity(position);
      level.setBlock(position, state.setValue(InterfaceBlock.KIND, target), Block.UPDATE_ALL);
      return placeFresh(player, level.getBlockEntity(position));
    }
    NodeRegistry.Handle lease;
    NodeRegistry.Position at;
    ThroughputTier tier;
    try {
      if (entity instanceof BufferedInterfaceEntity buffer) {
        if (!buffer.canUse(player)) return Outcome.DENIED;
        lease = buffer.handle();
        at = buffer.position();
        tier = buffer.tier();
        if (buffer.inventory().snapshot().slots().stream().anyMatch(slot -> slot.key() != null))
          return Outcome.NOT_EMPTY;
      } else if (entity instanceof ConnectorEntity port) {
        if (!port.canUse(player)) return Outcome.DENIED;
        lease = port.handle();
        at = port.position();
        tier = port.tier();
      } else return Outcome.FAULT;
    } catch (IllegalArgumentException | IllegalStateException unplaced) {
      return Outcome.FAULT;
    }
    if (!WorldNetworks.get(player.server)
        .allows(Actors.of(player), at, NetworkPermissions.Permission.EDIT)) return Outcome.DENIED;
    if (tier != ThroughputTier.BASE) return Outcome.UPGRADED;
    var saved = DeviceSavedData.get(player.server);
    if (saved.transfers().unresolvedDevice(lease.id()) != null) return Outcome.BUSY;
    // Break exactly as the block does, then retire every table keyed by the detached lease.
    NodeRegistry.Handle detached;
    if (entity instanceof BufferedInterfaceEntity buffer) {
      buffer.portableForBreak();
      detached = buffer.handle();
      if (!saved.buffers().retire(detached.id())) return Outcome.FAULT;
    } else {
      var port = (ConnectorEntity) entity;
      port.portableForBreak();
      detached = port.handle();
      if (port instanceof MachineInterfaceEntity) saved.machinePresets().forget(detached.id());
    }
    if (saved.nodes().retire(detached) != NodeRegistry.Failure.OK) return Outcome.FAULT;
    // Same block, new kind: drop the old entity so the chunk creates the new kind's entity.
    level.removeBlockEntity(position);
    level.setBlock(position, state.setValue(InterfaceBlock.KIND, target), Block.UPDATE_ALL);
    return placeFresh(player, level.getBlockEntity(position));
  }

  /** Places the new kind's device through its own placement path, owned by {@code player}. */
  private static Outcome placeFresh(
      ServerPlayer player, net.minecraft.world.level.block.entity.BlockEntity fresh) {
    var item = new ItemStack(InterfaceContent.ITEM.get());
    if (fresh instanceof BufferedInterfaceEntity buffer) buffer.place(item, player.getUUID());
    else if (fresh instanceof ConnectorEntity port) port.place(item);
    else if (fresh instanceof dev.izzy.factorycore.platform.signal.SignalInterfaceEntity signal)
      signal.place(player.getUUID());
    else return Outcome.FAULT;
    return Outcome.SWITCHED;
  }
}
