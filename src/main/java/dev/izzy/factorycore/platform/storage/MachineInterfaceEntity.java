package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Local machine port; canonical leases and foreign adapter ownership are shared with connectors.
 */
public final class MachineInterfaceEntity extends ConnectorEntity
    implements net.minecraft.world.MenuProvider {
  private java.util.UUID opening;

  java.util.UUID sessionForOpen() {
    return opening;
  }

  @Override
  public net.minecraft.network.chat.Component getDisplayName() {
    return net.minecraft.network.chat.Component.translatable("block.factorycore.machine_interface");
  }

  @Override
  public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
      int id,
      net.minecraft.world.entity.player.Inventory inventory,
      net.minecraft.world.entity.player.Player player) {
    if (!(player instanceof ServerPlayer server) || !canUse(server)) return null;
    opening = java.util.UUID.randomUUID();
    return new MachineSettingsMenu(id, inventory, this, opening);
  }

  public NetworkAuthority.Decision applySettings(
      NetworkPermissions.Actor actor, ConnectorSettings settings, String label) {
    return configure(actor, settings, label);
  }

  public MachinePresets.Deployment deployPreset(
      NetworkPermissions.Actor actor,
      MachinePresets.Mode mode,
      ResourceFilter filter,
      long target) {
    validatePlaced();
    if (filter.kind() != settings().kind())
      throw new IllegalArgumentException("Preset/port resource mismatch");
    var runtime = WorldNetworks.get(serverLevel().getServer());
    for (var permission :
        java.util.List.of(
            NetworkPermissions.Permission.VIEW,
            NetworkPermissions.Permission.EDIT,
            NetworkPermissions.Permission.DEPLOY))
      if (runtime.authorize(actor, position(), permission) != NetworkAuthority.Decision.ALLOWED)
        throw new IllegalStateException("Machine preset permission denied");
    if (mode != MachinePresets.Mode.PAUSED)
      for (var permission :
          java.util.List.of(
              NetworkPermissions.Permission.WITHDRAW, NetworkPermissions.Permission.DEPOSIT))
        if (runtime.authorize(actor, position(), permission) != NetworkAuthority.Decision.ALLOWED)
          throw new IllegalStateException("Machine preset resource permission denied");
    var scope = runtime.membership(position()).scope();
    var saved = DeviceSavedData.get(serverLevel().getServer());
    return saved
        .machinePresets()
        .deploy(
            handle(),
            generation(),
            MachinePresets.Face.valueOf(targetDirection().name()),
            new MachinePresets.Authority(
                actor.id(),
                scope.network(),
                scope.segment(),
                saved.network(scope.network()).generation()),
            mode,
            filter,
            target);
  }

  /** Freshness plus current permissions, not permission delegated by a saved record. */
  public boolean presetAuthorized(MachinePresets.Deployment expected) {
    if (expected == null) return false;
    try {
      validatePlaced();
      var runtime = WorldNetworks.get(serverLevel().getServer());
      var membership = runtime.membership(position());
      if (membership.status() != NetworkTopology.Status.VALID
          || expected.filter().kind() != settings().kind()) return false;
      var scope = membership.scope();
      var saved = DeviceSavedData.get(serverLevel().getServer());
      if (!expected.equals(saved.machinePresets().get(handle().id()))) return false;
      var authority =
          new MachinePresets.Authority(
              expected.authority().principal(),
              scope.network(),
              scope.segment(),
              saved.network(scope.network()).generation());
      if (!saved
          .machinePresets()
          .matches(
              handle(),
              generation(),
              MachinePresets.Face.valueOf(targetDirection().name()),
              authority,
              expected.revision())) return false;
      var actor = new NetworkPermissions.Actor(authority.principal(), false);
      for (var permission :
          java.util.List.of(
              NetworkPermissions.Permission.VIEW,
              NetworkPermissions.Permission.EDIT,
              NetworkPermissions.Permission.DEPLOY,
              NetworkPermissions.Permission.WITHDRAW,
              NetworkPermissions.Permission.DEPOSIT))
        if (runtime.authorize(actor, position(), permission) != NetworkAuthority.Decision.ALLOWED)
          return false;
      return true;
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      return false;
    }
  }

  @Override
  protected NetworkAuthority.Decision configure(
      NetworkPermissions.Actor actor, ConnectorSettings settings, String label) {
    validatePlaced();
    var presets = DeviceSavedData.get(serverLevel().getServer()).machinePresets();
    presets.prepareInvalidation(handle().id());
    var result = super.configure(actor, settings, label);
    if (result == NetworkAuthority.Decision.ALLOWED) presets.invalidate(handle().id());
    return result;
  }

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
