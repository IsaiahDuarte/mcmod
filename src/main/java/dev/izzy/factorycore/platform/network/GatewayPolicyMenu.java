package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.session.DeviceStatus;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * Gateway policy editor. Button {@code n < GatewayPolicy.BITS} toggles one policy bit; the server
 * re-checks Manage at the gateway on every click and rewrites the canonical node policy, then
 * re-discovers the gateway so ancestors and published patterns update. Data slots carry the mask
 * (low/high 16 bits) and whether the viewer may edit; no grants are synced.
 */
public final class GatewayPolicyMenu extends AbstractContainerMenu {
  private final NetworkNodeEntity gateway;
  private final Player viewer;
  private final ContainerData data;

  /** Client constructor. */
  public GatewayPolicyMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    super(NetworkContent.POLICY_MENU.get(), id);
    buffer.readBlockPos();
    gateway = null;
    viewer = inventory.player;
    data = new SimpleContainerData(4);
    addDataSlots(data);
  }

  GatewayPolicyMenu(int id, Inventory inventory, NetworkNodeEntity gateway) {
    super(NetworkContent.POLICY_MENU.get(), id);
    this.gateway = gateway;
    viewer = inventory.player;
    data =
        new ContainerData() {
          @Override
          public int get(int index) {
            int mask = policy().mask();
            return switch (index) {
              case 0 -> mask & 0xffff;
              case 1 -> mask >>> 16;
              case 2 -> viewer instanceof ServerPlayer player && mayManage(player) ? 1 : 0;
              // Network state ordinal + 1 for the status strip; 0 means not yet synced.
              default ->
                  gateway.handle() == null
                      ? 0
                      : WorldNetworks.get(gateway.getLevel().getServer())
                              .membership(gateway.position())
                              .status()
                              .ordinal()
                          + 1;
            };
          }

          @Override
          public void set(int index, int value) {}

          @Override
          public int getCount() {
            return 4;
          }
        };
    addDataSlots(data);
  }

  /** Client view of the synchronized mask. */
  public GatewayPolicy policy() {
    if (gateway == null) return GatewayPolicy.fromMask(data.get(0) & 0xffff | data.get(1) << 16);
    var saved = DeviceSavedData.get(gateway.getLevel().getServer());
    return saved.nodes().definition(gateway.handle()).policy();
  }

  public boolean editable() {
    return data.get(2) == 1;
  }

  /** The status strip (U7), from the synchronized network state; the gateway has no reason. */
  public DeviceStatus.View deviceStatus() {
    int state = data.get(3);
    var statuses = NetworkTopology.Status.values();
    return new DeviceStatus.View(
        containerId,
        new UUID(0, 0),
        state < 1 || state > statuses.length
            ? ""
            : statuses[state - 1].name().toLowerCase(Locale.ROOT),
        "",
        "");
  }

  private boolean mayManage(ServerPlayer player) {
    return gateway.handle() != null
        && WorldNetworks.get(player.server)
            .allows(Actors.of(player), gateway.position(), NetworkPermissions.Permission.MANAGE);
  }

  /** Button id that reopens this gateway as a Network Access session (ADR 0055). */
  public static final int ACCESS_BUTTON = 64;

  @Override
  public boolean clickMenuButton(Player player, int button) {
    if (button == ACCESS_BUTTON
        && gateway != null
        && player instanceof ServerPlayer server
        && stillValid(player)) {
      NetworkAccessMenu.open(server, gateway);
      return true;
    }
    if (gateway == null
        || !(player instanceof ServerPlayer server)
        || button < 0
        || button >= GatewayPolicy.BITS
        || !stillValid(player)
        || !mayManage(server)) return false;
    var saved = DeviceSavedData.get(server.server);
    var next = policy().toggled(button);
    if (saved.nodes().setPolicy(gateway.handle(), next) != NodeRegistry.Failure.OK) return false;
    WorldNetworks.changed(gateway.getLevel(), gateway.getBlockPos());
    broadcastChanges();
    return true;
  }

  @Override
  public ItemStack quickMoveStack(Player player, int slot) {
    return ItemStack.EMPTY;
  }

  @Override
  public boolean stillValid(Player player) {
    if (gateway == null) return true;
    BlockPos position = gateway.getBlockPos();
    return !gateway.isRemoved()
        && gateway.handle() != null
        && player.level() == gateway.getLevel()
        && player.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5)
            <= 64;
  }
}
