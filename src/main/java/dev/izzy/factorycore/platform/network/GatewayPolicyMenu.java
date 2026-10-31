package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.session.DeviceStatus;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.wireless.LinkModule;
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

  /** Q19: the link's upgrade slot; no slot while the gateway has no link. */
  private final net.minecraft.world.inventory.DataSlot upgrade;

  /** Client constructor. */
  public GatewayPolicyMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    super(NetworkContent.POLICY_MENU.get(), id);
    buffer.readBlockPos();
    gateway = null;
    viewer = inventory.player;
    data = new SimpleContainerData(5);
    addDataSlots(data);
    upgrade = addDataSlot(dev.izzy.factorycore.platform.upgrade.UpgradeSlots.state(() -> null));
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
              case 4 -> linkState(gateway.link());
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
            return 5;
          }
        };
    addDataSlots(data);
    upgrade =
        addDataSlot(
            dev.izzy.factorycore.platform.upgrade.UpgradeSlots.state(
                () -> gateway.link() == null ? null : gateway));
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

  /** True once the server's data slots have arrived (the network state slot is never 0 then). */
  public boolean synced() {
    return gateway != null || data.get(3) != 0;
  }

  /** Link states for data slot 4: 0 is no link; otherwise an index into {@link #LINK_STATES}. */
  private static final String[] LINK_STATES = {
    "", "active", "unpaired", "out_of_range", "no_power", "unknown_data"
  };

  private static int linkState(LinkModule link) {
    if (link == null) return 0;
    String diagnostic = link.diagnostic();
    if (diagnostic.isEmpty()) return 1;
    for (int i = 2; i < LINK_STATES.length; i++) if (LINK_STATES[i].equals(diagnostic)) return i;
    return LINK_STATES.length - 1;
  }

  /** The installed link's state key suffix, or an empty string without a link (ADR 0061). */
  public String linkState() {
    int state = data.get(4);
    return state > 0 && state < LINK_STATES.length ? LINK_STATES[state] : "";
  }

  /** The status strip (U7), from the synchronized network state; the reason is the link state. */
  public DeviceStatus.View deviceStatus() {
    int state = data.get(3);
    var statuses = NetworkTopology.Status.values();
    return new DeviceStatus.View(
        containerId,
        new UUID(0, 0),
        state < 1 || state > statuses.length ? null : statuses[state - 1],
        linkState().isEmpty() ? "" : "status.factorycore.link." + linkState(),
        "");
  }

  private boolean mayManage(ServerPlayer player) {
    return gateway.handle() != null
        && WorldNetworks.get(player.server)
            .allows(Actors.of(player), gateway.position(), NetworkPermissions.Permission.MANAGE);
  }

  /** Button id that reopens this gateway as a Network Access session (ADR 0055). */
  public static final int ACCESS_BUTTON = 64;

  /** Button {@code PRESET_BUTTON + ordinal} applies one {@link GatewayPolicy.Preset} (U8). */
  public static final int PRESET_BUTTON = 32;

  /** Q19: the synced upgrade slot state. */
  public int upgradeState() {
    return upgrade.get();
  }

  @Override
  public boolean clickMenuButton(Player player, int button) {
    if (button == dev.izzy.factorycore.platform.upgrade.UpgradeSlots.BUTTON)
      return gateway != null
          && gateway.link() != null
          && player instanceof ServerPlayer upgrader
          && stillValid(upgrader)
          && dev.izzy.factorycore.platform.upgrade.UpgradeSlots.click(upgrader, this, gateway);
    if (button == ACCESS_BUTTON
        && gateway != null
        && player instanceof ServerPlayer server
        && stillValid(player)) {
      NetworkAccessMenu.open(server, gateway);
      return true;
    }
    int presets = GatewayPolicy.Preset.values().length;
    boolean preset = button >= PRESET_BUTTON && button < PRESET_BUTTON + presets;
    if (gateway == null
        || !(player instanceof ServerPlayer server)
        || button < 0
        || button >= GatewayPolicy.BITS && !preset
        || !stillValid(player)
        || !mayManage(server)) return false;
    var saved = DeviceSavedData.get(server.server);
    var next =
        preset
            ? GatewayPolicy.Preset.values()[button - PRESET_BUTTON].policy()
            : policy().toggled(button);
    if (next.equals(policy())) return true;
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
