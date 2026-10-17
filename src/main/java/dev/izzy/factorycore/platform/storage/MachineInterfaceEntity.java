package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Local machine port; canonical leases and foreign adapter ownership are shared with connectors.
 */
public final class MachineInterfaceEntity extends ConnectorEntity {
  public MachineInterfaceEntity(BlockPos position, BlockState state) {
    super(MachineInterfaceContent.ENTITY.get(), position, state);
  }

  @Override
  protected NodeRegistry.Kind leaseKind() {
    return NodeRegistry.Kind.MACHINE_INTERFACE;
  }

  @Override
  protected net.minecraft.world.item.Item portableItem() {
    return MachineInterfaceContent.ITEM.get();
  }

  public NetworkAuthority.Decision relabel(NetworkPermissions.Actor actor, String label) {
    return configure(actor, settings(), label);
  }

  public boolean canUse(ServerPlayer player) {
    try {
      validatePlaced();
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      return false;
    }
    return player.isAlive()
        && player.serverLevel() == getLevel()
        && player.server.getPlayerList().getPlayer(player.getUUID()) == player
        && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(getBlockPos())) <= 64
        && WorldNetworks.get(player.server)
                .authorize(
                    new NetworkPermissions.Actor(player.getUUID(), player.hasPermissions(2)),
                    position(),
                    NetworkPermissions.Permission.VIEW)
            == NetworkAuthority.Decision.ALLOWED;
  }
}
