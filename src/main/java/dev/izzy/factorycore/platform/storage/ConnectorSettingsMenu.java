package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.session.DeviceStatus;
import dev.izzy.factorycore.platform.session.MenuSession;
import dev.izzy.factorycore.platform.upgrade.ThroughputStatus;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Storage Connector settings: the screen equivalent of the offhand controls (resource, access,
 * priority) plus the item window and rate. Apply validates the whole form under current EDIT
 * authority in one configuration change, exactly like {@link ConnectorEntity#configure}. The view
 * polls the container registration so the status line shows why the connector is not ready.
 */
public final class ConnectorSettingsMenu extends AbstractContainerMenu
    implements DeviceStatus.Holder {
  /** The registration probe touches the foreign container, so it is polled sparingly. */
  private static final int POLL_TICKS = 10;

  private final MenuSession protocol;
  private final Player viewer;
  private final ConnectorEntity connector;
  private final NodeRegistry.Handle lease;
  private final NetworkTopology.Scope scope;
  private final Direction facing;
  private ConnectorSettingsPackets.View view;
  private String status = "ready";
  private DeviceStatus.View deviceStatus;

  public ConnectorSettingsMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, null, MenuSession.read(buffer));
  }

  ConnectorSettingsMenu(int id, Inventory inventory, ConnectorEntity connector, UUID session) {
    super(ConnectorContent.MENU.get(), id);
    viewer = inventory.player;
    protocol = new MenuSession(this, viewer, session, connector != null, POLL_TICKS);
    this.connector = connector;
    lease = connector == null ? null : connector.handle();
    facing = connector == null ? null : connector.targetDirection();
    scope = connector == null ? null : runtime().membership(connector.position()).scope();
    deviceStatus = DeviceStatus.View.pending(id, protocol.id());
    view =
        new ConnectorSettingsPackets.View(
            id,
            protocol.id(),
            0,
            new MachineSettingsPackets.Form(
                "", ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 64, 64),
            "north",
            "ok",
            "ready",
            false,
            "base",
            ThroughputTier.BASE.portRate(ResourceKind.ITEM),
            false);
  }

  public UUID session() {
    return protocol.id();
  }

  public ConnectorSettingsPackets.View view() {
    return view;
  }

  private ServerPlayer player() {
    return (ServerPlayer) viewer;
  }

  private WorldNetworks runtime() {
    return WorldNetworks.get(player().server);
  }

  private NetworkPermissions.Actor actor() {
    return Actors.of(viewer);
  }

  private boolean current() {
    return protocol.active()
        && connector != null
        && lease.equals(connector.handle())
        && facing == connector.targetDirection()
        && connector.canUse(player())
        && scope.equals(runtime().membership(connector.position()).scope());
  }

  private boolean editable() {
    return runtime().allows(actor(), connector.position(), NetworkPermissions.Permission.EDIT);
  }

  @Override
  public DeviceStatus.View deviceStatus() {
    return deviceStatus;
  }

  @Override
  public void acceptDeviceStatus(DeviceStatus.View packet) {
    if (connector == null)
      deviceStatus = DeviceStatus.accept(containerId, protocol.id(), deviceStatus, packet);
  }

  public void accept(ConnectorSettingsPackets.View packet) {
    if (connector == null
        && protocol.owns(packet.menu(), packet.session())
        && packet.generation() >= view.generation()) view = packet;
  }

  private void publish() {
    var settings = connector.settings();
    var binding = runtime().connectors().bind(connector).reason();
    deviceStatus =
        DeviceStatus.publish(
            player(),
            deviceStatus,
            DeviceStatus.of(
                player().server,
                containerId,
                protocol.id(),
                connector.position(),
                "reason.factorycore." + binding.name().toLowerCase(Locale.ROOT),
                ""));
    view =
        protocol.publish(
            view,
            new ConnectorSettingsPackets.View(
                containerId,
                protocol.id(),
                connector.generation(),
                new MachineSettingsPackets.Form(
                    "",
                    settings.kind(),
                    settings.access(),
                    settings.priority(),
                    settings.firstSlot(),
                    settings.slotCount(),
                    settings.rate()),
                connector.targetDirection().getOpposite().getName(),
                binding.name().toLowerCase(Locale.ROOT),
                status,
                editable(),
                connector.tier().name().toLowerCase(Locale.ROOT),
                connector.tier().portRate(settings.kind()),
                ThroughputStatus.throttled(
                    connector.serverLevel().getServer(), connector.position())));
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    // Native initMenu broadcasts before assigning the new container to the player.
    if (!protocol.due(this::current)) return;
    if (view.generation() != 0 && view.generation() != connector.generation()) status = "stale";
    publish();
  }

  public void apply(ServerPlayer sender, ConnectorSettingsPackets.Apply packet) {
    if (!protocol.fromViewer(sender, packet.menu(), packet.session()) || !current()) return;
    if (packet.generation() != connector.generation()) {
      status = "stale";
      publish();
      return;
    }
    if (!editable()) {
      status = "denied";
      publish();
      return;
    }
    try {
      var settings = packet.form().settings();
      if (!settings.fits(connector.tier())) {
        status = "upgrade";
        publish();
        return;
      }
      if (connector.configure(actor(), settings) != NetworkAuthority.Decision.ALLOWED) {
        status = "denied";
        publish();
        return;
      }
    } catch (IllegalArgumentException invalid) {
      status = "invalid";
      publish();
      return;
    } catch (ArithmeticException limit) {
      status = "limit";
      publish();
      return;
    }
    sender.closeContainer();
    sender.displayClientMessage(Component.translatable("gui.factorycore.connector.saved"), true);
  }

  @Override
  public boolean stillValid(Player player) {
    return player == viewer && (connector == null || current());
  }

  @Override
  public ItemStack quickMoveStack(Player player, int slot) {
    return ItemStack.EMPTY;
  }

  @Override
  public void removed(Player player) {
    protocol.close();
    super.removed(player);
  }
}
