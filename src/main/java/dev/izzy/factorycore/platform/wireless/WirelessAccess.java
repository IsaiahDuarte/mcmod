package dev.izzy.factorycore.platform.wireless;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.TerminalAccess;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Handheld session through one access point: same level, within the point's {@link
 * AccessPointEntity#range()} (64 blocks, more with upgrades), the bound handheld still where it was
 * opened from (the hand, or for the open key (Q14) its inventory slot), View at the access point,
 * its {@link AccessPointEntity#tickCost()} (16 FE or more) per open tick and 64 FE per action from
 * the handheld.
 */
final class WirelessAccess implements TerminalAccess {
  static final long ACTION_COST = 64;
  private final AccessPointEntity point;
  private final Function<ServerPlayer, ItemStack> handheld;

  WirelessAccess(AccessPointEntity point, InteractionHand hand) {
    this(point, player -> player.getItemInHand(hand));
  }

  /** Q14: {@code handheld} finds the handheld where it was opened from. */
  WirelessAccess(AccessPointEntity point, Function<ServerPlayer, ItemStack> handheld) {
    this.point = point;
    this.handheld = handheld;
  }

  @Override
  public boolean canUse(ServerPlayer player) {
    var held = handheld.apply(player);
    var binding = held.get(WirelessContent.BINDING.get());
    double range = (double) point.range() * point.range();
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
            .allows(Actors.of(player), point.origin(), NetworkPermissions.Permission.VIEW);
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
    return point.spend(point.tickCost());
  }

  @Override
  public boolean spendAction(ServerPlayer player) {
    var energy = handheld.apply(player).getCapability(Capabilities.EnergyStorage.ITEM);
    if (energy == null || energy.extractEnergy((int) ACTION_COST, true) < ACTION_COST) return false;
    energy.extractEnergy((int) ACTION_COST, false);
    return true;
  }

  @Override
  public double reachSquared() {
    return (double) point.range() * point.range();
  }
}
