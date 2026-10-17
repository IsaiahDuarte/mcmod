package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** Current authenticated physical editor. Apply validates the whole form before one publication. */
public final class MachineSettingsMenu extends AbstractContainerMenu {
  private final Player viewer;
  private final MachineInterfaceEntity machine;
  private final UUID session;
  private final NodeRegistry.Handle lease;
  private final NetworkTopology.Scope scope;
  private final Direction facing;
  private MachineSettingsPackets.View view;
  private int lastPoll = Integer.MIN_VALUE;
  private boolean active = true;
  private String status = "ready";

  public MachineSettingsMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, null, buffer.readUUID());
  }

  MachineSettingsMenu(int id, Inventory inventory, MachineInterfaceEntity machine, UUID session) {
    super(MachineInterfaceContent.MENU.get(), id);
    this.viewer = inventory.player;
    this.machine = machine;
    this.session = session;
    lease = machine == null ? null : machine.handle();
    facing = machine == null ? null : machine.targetDirection();
    scope = machine == null ? null : runtime().membership(machine.position()).scope();
    view =
        new MachineSettingsPackets.View(
            id,
            session,
            0,
            new MachineSettingsPackets.Form(
                "", ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 64, 64),
            "north",
            "ready",
            false);
  }

  public UUID session() {
    return session;
  }

  public MachineSettingsPackets.View view() {
    return view;
  }

  private ServerPlayer player() {
    return (ServerPlayer) viewer;
  }

  private WorldNetworks runtime() {
    return WorldNetworks.get(player().server);
  }

  private NetworkPermissions.Actor actor() {
    return new NetworkPermissions.Actor(viewer.getUUID(), player().hasPermissions(2));
  }

  private boolean current() {
    return active
        && machine != null
        && lease.equals(machine.handle())
        && facing == machine.targetDirection()
        && machine.canUse(player())
        && scope.equals(runtime().membership(machine.position()).scope());
  }

  private boolean editable() {
    return runtime().authorize(actor(), machine.position(), NetworkPermissions.Permission.EDIT)
        == NetworkAuthority.Decision.ALLOWED;
  }

  public void accept(MachineSettingsPackets.View packet) {
    if (machine == null
        && packet.menu() == containerId
        && packet.session().equals(session)
        && packet.generation() >= view.generation()) view = packet;
  }

  private void publish() {
    var settings = machine.settings();
    var packet =
        new MachineSettingsPackets.View(
            containerId,
            session,
            machine.generation(),
            new MachineSettingsPackets.Form(
                machine.label(),
                settings.kind(),
                settings.access(),
                settings.priority(),
                settings.firstSlot(),
                settings.slotCount(),
                settings.rate()),
            machine.targetDirection().getOpposite().getName(),
            status,
            editable());
    if (!packet.equals(view)) {
      view = packet;
      PacketDistributor.sendToPlayer(player(), packet);
    }
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    // Native initMenu broadcasts before assigning the new container to the player.
    if (machine == null || !active || player().containerMenu != this) return;
    if (!current()) {
      player().closeContainer();
      return;
    }
    int tick = player().server.getTickCount();
    if (lastPoll != Integer.MIN_VALUE && tick - lastPoll < 5) return;
    lastPoll = tick;
    if (view.generation() != 0 && view.generation() != machine.generation()) status = "stale";
    publish();
  }

  public void apply(ServerPlayer sender, MachineSettingsPackets.Apply packet) {
    if (sender != viewer
        || sender.containerMenu != this
        || packet.menu() != containerId
        || !packet.session().equals(session)
        || !current()) return;
    if (packet.generation() != machine.generation()) {
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
      if (machine.applySettings(actor(), packet.form().settings(), packet.form().label())
          != NetworkAuthority.Decision.ALLOWED) {
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
    sender.displayClientMessage(Component.translatable("gui.factorycore.machine.saved"), true);
  }

  @Override
  public boolean stillValid(Player player) {
    return player == viewer && (machine == null || current());
  }

  @Override
  public ItemStack quickMoveStack(Player player, int slot) {
    return ItemStack.EMPTY;
  }

  @Override
  public void removed(Player player) {
    active = false;
    super.removed(player);
  }
}
