package dev.izzy.factorycore.platform.wireless;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.TerminalAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Handheld session through one access point: same level, within {@link AccessPointEntity#RANGE}
 * blocks, the bound handheld still in hand, View at the access point, 16 FE per open tick from the
 * access point and 64 FE per action from the handheld.
 */
final class WirelessAccess implements TerminalAccess {
  static final long ACTION_COST = 64;
  private final AccessPointEntity point;
  private final InteractionHand hand;

  WirelessAccess(AccessPointEntity point, InteractionHand hand) {
    this.point = point;
    this.hand = hand;
  }

  @Override
  public boolean canUse(ServerPlayer player) {
    var held = player.getItemInHand(hand);
    var binding = held.get(WirelessContent.BINDING.get());
    double range = (double) AccessPointEntity.RANGE * AccessPointEntity.RANGE;
    return player.isAlive()
        && point.current()
        && player.level() == point.getLevel()
        && player.server.getPlayerList().getPlayer(player.getUUID()) == player
        && held.is(WirelessContent.HANDHELD.get())
        && binding != null
        && binding.position() == point.getBlockPos().asLong()
        && binding.dimension().equals(point.origin().dimension())
        && player.distanceToSqr(Vec3.atCenterOf(point.getBlockPos())) <= range
        && WorldNetworks.get(player.server)
                .authorize(
                    new NetworkPermissions.Actor(player.getUUID(), player.hasPermissions(2)),
                    point.origin(),
                    NetworkPermissions.Permission.VIEW)
            == NetworkAuthority.Decision.ALLOWED;
  }

  @Override
  public NodeRegistry.Position origin() {
    return point.origin();
  }

  @Override
  public BlockPos getBlockPos() {
    return point.getBlockPos();
  }

  @Override
  public boolean upkeep(ServerPlayer player) {
    return point.spend(AccessPointEntity.TICK_COST);
  }

  @Override
  public boolean spendAction(ServerPlayer player) {
    var energy = player.getItemInHand(hand).getCapability(Capabilities.EnergyStorage.ITEM);
    if (energy == null || energy.extractEnergy((int) ACTION_COST, true) < ACTION_COST) return false;
    energy.extractEnergy((int) ACTION_COST, false);
    return true;
  }

  @Override
  public double reachSquared() {
    return (double) AccessPointEntity.RANGE * AccessPointEntity.RANGE;
  }
}
