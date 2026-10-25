package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.session.DeviceStatus;
import dev.izzy.factorycore.platform.session.MenuSession;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-owned draft and exact inventory selections. Clients never submit root/actor/component
 * authority.
 */
public final class BufferSettingsMenu extends AbstractContainerMenu implements DeviceStatus.Holder {
  private final MenuSession protocol;
  private DeviceStatus.View deviceStatus;
  private final Player viewer;
  private final BufferedInterfaceEntity holder;
  private final BlockPos position;
  private final NodeRegistry.Handle lease;
  private final NetworkTopology.Scope scope;
  private BufferPolicy draft = BufferPolicy.DEFAULT;
  private BufferRegistry.Mode mode = BufferRegistry.Mode.PAUSED;
  private long configuration, revision = 1;
  private List<ItemBuffer.Slot> selections = List.of();
  private boolean busy, dirty, inventoryTab;
  private UUID ticket;
  private long moved;
  private int requested;
  private boolean returning;
  private String status = "ready";
  private BufferSettingsPackets.View view;

  public BufferSettingsMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, null, buffer.readBlockPos(), MenuSession.read(buffer));
  }

  BufferSettingsMenu(int id, Inventory inventory, BufferedInterfaceEntity holder, UUID nonce) {
    this(id, inventory, holder, holder.getBlockPos(), nonce);
  }

  private BufferSettingsMenu(
      int id, Inventory inventory, BufferedInterfaceEntity holder, BlockPos position, UUID nonce) {
    super(BufferedInterfaceContent.MENU.get(), id);
    viewer = inventory.player;
    protocol = new MenuSession(this, viewer, nonce, holder != null, 5);
    this.holder = holder;
    this.position = position;
    lease = holder == null ? null : holder.handle();
    scope =
        holder == null
            ? null
            : WorldNetworks.get(player().server).membership(holder.position()).scope();
    for (int row = 0; row < 3; row++)
      for (int column = 0; column < 9; column++)
        addSlot(
            new Slot(inventory, 9 + row * 9 + column, 79 + 18 * column, 103 + 18 * row) {
              @Override
              public boolean isActive() {
                return !viewer.level().isClientSide() || inventoryTab;
              }
            });
    for (int column = 0; column < 9; column++)
      addSlot(
          new Slot(inventory, column, 79 + 18 * column, 165) {
            @Override
            public boolean isActive() {
              return !viewer.level().isClientSide() || inventoryTab;
            }
          });
    if (holder != null) reset();
    view =
        new BufferSettingsPackets.View(
            id, nonce, revision, "PAUSED", "ALL", 64, 64, List.of(), List.of(), status, false,
            false, false, 0, 0, 0);
    deviceStatus = DeviceStatus.View.pending(id, nonce);
  }

  @Override
  public DeviceStatus.View deviceStatus() {
    return deviceStatus;
  }

  @Override
  public void acceptDeviceStatus(DeviceStatus.View packet) {
    if (holder == null)
      deviceStatus = DeviceStatus.accept(containerId, protocol.id(), deviceStatus, packet);
  }

  public UUID session() {
    return protocol.id();
  }

  public BufferSettingsPackets.View view() {
    return view;
  }

  public boolean inventoryTab() {
    return inventoryTab;
  }

  public void inventoryTab(boolean value) {
    inventoryTab = value;
  }

  public void accept(BufferSettingsPackets.View packet) {
    if (holder == null
        && protocol.owns(packet.menu(), packet.session())
        && packet.revision() >= view.revision()) view = packet;
  }

  private ServerPlayer player() {
    return (ServerPlayer) viewer;
  }

  private DeviceSavedData saved() {
    return DeviceSavedData.get(player().server);
  }

  private WorldStorageTransfers transfers() {
    return WorldNetworks.get(player().server).transfers();
  }

  private NetworkPermissions.Actor actor() {
    return Actors.of(viewer);
  }

  private boolean current() {
    return protocol.active()
        && holder != null
        && lease.equals(holder.handle())
        && holder.canUse(player())
        && scope.equals(WorldNetworks.get(player().server).membership(holder.position()).scope());
  }

  private boolean authorized() {
    return current() && player().containerMenu == this;
  }

  private boolean editable() {
    for (var permission :
        new NetworkPermissions.Permission[] {
          NetworkPermissions.Permission.EDIT, NetworkPermissions.Permission.DEPLOY
        })
      if (!WorldNetworks.get(player().server).allows(actor(), holder.position(), permission))
        return false;
    return true;
  }

  /**
   * Interface mode row (U11, ADR 0059): button {@link InterfaceSwitch#BUTTON} + kind asks to
   * switch. On success the screen closes and the player is told to reopen it; a refusal shows its
   * reason.
   */
  @Override
  public boolean clickMenuButton(Player player, int button) {
    var target = InterfaceSwitch.requested(button);
    if (target == null
        || holder == null
        || !current()
        || busy
        || !(player instanceof ServerPlayer sender)
        || sender != viewer) return false;
    var outcome = InterfaceSwitch.request(sender, position, target);
    if (outcome == InterfaceSwitch.Outcome.SWITCHED) {
      sender.closeContainer();
      sender.displayClientMessage(
          Component.translatable(
              "message.factorycore.interface_switched",
              Component.translatable(
                  "gui.factorycore.interface.mode." + target.getSerializedName())),
          true);
      return true;
    }
    status = outcome.key();
    publish();
    return false;
  }

  @Override
  public boolean stillValid(Player player) {
    return player == viewer && (holder == null || current());
  }

  @Override
  public ItemStack quickMoveStack(Player player, int slot) {
    return ItemStack.EMPTY;
  }

  private void reset() {
    var deployment = saved().buffers().deployment(lease.id());
    configuration = holder.reference().configuration();
    draft = deployment == null ? BufferPolicy.DEFAULT : deployment.policy();
    mode = deployment == null ? BufferRegistry.Mode.PAUSED : deployment.mode();
    dirty = false;
    moved = 0;
    requested = 0;
    revision++;
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (!protocol.due(this::current)) return;
    if (holder.reference().configuration() != configuration) {
      reset();
      status = "stale";
    }
    if (!busy) {
      var actual = holder.inventory().snapshot().slots();
      if (!actual.equals(selections)) {
        selections = actual;
        revision++;
      }
    }
    publish();
    var running = WorldNetworks.get(player().server).imports().status(holder);
    deviceStatus =
        DeviceStatus.publish(
            player(),
            deviceStatus,
            DeviceStatus.of(
                player().server,
                containerId,
                protocol.id(),
                holder.position(),
                "status.factorycore.buffer_reason."
                    + running.reason().name().toLowerCase(java.util.Locale.ROOT),
                ""));
  }

  public void command(ServerPlayer sender, BufferSettingsPackets.Command packet) {
    if (!protocol.fromViewer(sender, packet.menu(), packet.session())
        || !authorized()
        || busy
        || !protocol.admitRate()) return;
    if (holder.reference().configuration() != configuration) {
      reset();
      status = "stale";
      publish();
      return;
    }
    if (packet.revision() != revision) {
      status = "stale";
      publish();
      return;
    }
    if (packet.action() >= BufferSettingsPackets.TAKE
        && packet.action() <= BufferSettingsPackets.RETURN) {
      inventory(packet);
      return;
    }
    if (!editable()) {
      status = "denied";
      publish();
      return;
    }
    moved = 0;
    requested = 0;
    try {
      var terms = new ArrayList<>(draft.filter().terms());
      var filterMode = draft.filter().mode();
      int rate = draft.rate(), target = draft.target();
      switch (packet.action()) {
        case BufferSettingsPackets.MODE ->
            mode =
                switch (mode) {
                  case PAUSED -> BufferRegistry.Mode.IMPORT;
                  case IMPORT -> BufferRegistry.Mode.EXPORT;
                  case EXPORT -> BufferRegistry.Mode.STOCK;
                  case STOCK -> BufferRegistry.Mode.PAUSED;
                };
        case BufferSettingsPackets.FILTER -> {
          filterMode =
              switch (filterMode) {
                case ALL -> ResourceFilter.Mode.INCLUDE;
                case INCLUDE -> ResourceFilter.Mode.EXCLUDE;
                case EXCLUDE -> ResourceFilter.Mode.ALL;
              };
          if (filterMode == ResourceFilter.Mode.ALL) terms.clear();
        }
        case BufferSettingsPackets.RATE -> {
          if (packet.value() < 1 || packet.value() > holder.tier().bufferRate())
            throw new IllegalArgumentException("Rate");
          rate = packet.value();
        }
        case BufferSettingsPackets.TARGET -> target = packet.value();
        case BufferSettingsPackets.REGISTRY -> {
          terms.add(new ResourceFilter.Registry(packet.text()));
          if (filterMode == ResourceFilter.Mode.ALL) filterMode = ResourceFilter.Mode.INCLUDE;
        }
        case BufferSettingsPackets.TAG -> {
          String text = packet.text();
          terms.add(new ResourceFilter.Tag(text.startsWith("#") ? text.substring(1) : text));
          if (filterMode == ResourceFilter.Mode.ALL) filterMode = ResourceFilter.Mode.INCLUDE;
        }
        case BufferSettingsPackets.EXACT -> {
          var stack = sender.getInventory().getItem(packet.selection());
          if (stack.isEmpty()) return;
          var key = new PlatformResourceCodec(sender.registryAccess()).itemKey(stack);
          if (!MessageDigest.isEqual(TerminalPackets.fingerprint(key), packet.fingerprint())) {
            status = "stale";
            publish();
            return;
          }
          terms.add(new ResourceFilter.Exact(key));
          if (filterMode == ResourceFilter.Mode.ALL) filterMode = ResourceFilter.Mode.INCLUDE;
        }
        case BufferSettingsPackets.REMOVE -> {
          if (packet.selection() >= terms.size()) return;
          terms.remove(packet.selection());
        }
        case BufferSettingsPackets.CARRIED -> {
          var term = FilterEdits.carried(ResourceKind.ITEM, getCarried());
          if (term == null) return;
          terms.add(term);
          if (filterMode == ResourceFilter.Mode.ALL) filterMode = ResourceFilter.Mode.INCLUDE;
        }
        case BufferSettingsPackets.CYCLE -> {
          if (packet.selection() >= terms.size()) return;
          var next =
              FilterEdits.cycle(
                  ResourceKind.ITEM,
                  terms.get(packet.selection()),
                  packet.text(),
                  new PlatformResourceCodec(sender.registryAccess()));
          if (next == null) {
            status = "stale";
            publish();
            return;
          }
          terms.set(packet.selection(), next);
        }
        case BufferSettingsPackets.CLEAR -> {
          terms.clear();
          filterMode = ResourceFilter.Mode.ALL;
        }
        case BufferSettingsPackets.APPLY -> {
          holder.configure(actor(), mode, draft);
          reset();
          status = "applied";
          publish();
          return;
        }
        case BufferSettingsPackets.RESET -> {
          reset();
          status = "ready";
          publish();
          return;
        }
        default -> throw new IllegalArgumentException("Action");
      }
      draft =
          new BufferPolicy(new ResourceFilter(ResourceKind.ITEM, filterMode, terms), rate, target);
      dirty = true;
      revision++;
      status = "draft";
    } catch (IllegalArgumentException invalid) {
      status = "invalid";
    } catch (IllegalStateException denied) {
      status = "denied";
    } catch (ArithmeticException overflow) {
      status = "technical_limit";
    }
    publish();
  }

  private UUID unresolved() {
    var operation = saved().transfers().unresolved(lease.id());
    if (operation == null)
      operation =
          saved()
              .transfers()
              .unresolved(
                  TransferContext.playerBacking(saved().registry().world(), viewer.getUUID()));
    return operation == null ? null : operation.id();
  }

  private void inventory(BufferSettingsPackets.Command packet) {
    if (packet.action() == BufferSettingsPackets.RETURN) {
      var operation = unresolved();
      if (operation == null) return;
      if (!saved().transfers().get(operation).owner().equals(viewer.getUUID())) {
        status = "denied";
        publish();
        return;
      }
      busy = true;
      returning = true;
      moved = 0;
      requested = 0;
      ticket = operation;
      var admission =
          transfers().progressGuarded(operation, actor(), true, this::finish, this::authorized);
      if (admission.rejection() != OperationResult.Reason.OK) {
        busy = false;
        ticket = null;
        status = admission.rejection().name().toLowerCase(Locale.ROOT);
      }
      publish();
      return;
    }
    if (packet.value() < 1 || packet.value() > 64) return;
    if (unresolved() != null) {
      status = "reserved";
      publish();
      return;
    }
    WorldStorageTransfers.Endpoint source, destination;
    ResourceKey key;
    long amount = packet.value();
    var buffer = new WorldStorageTransfers.Buffered(lease, configuration);
    if (packet.action() == BufferSettingsPackets.TAKE) {
      if (packet.selection() >= selections.size()) return;
      key = selections.get(packet.selection()).key();
      if (key == null) return;
      source = buffer;
      destination = transfers().inventory(player(), -1);
    } else if (packet.action() == BufferSettingsPackets.DEPOSIT) {
      var stack = player().getInventory().getItem(packet.selection());
      if (stack.isEmpty()) return;
      try {
        key = new PlatformResourceCodec(player().registryAccess()).itemKey(stack);
      } catch (IllegalArgumentException invalid) {
        status = "unsupported";
        publish();
        return;
      }
      if (!MessageDigest.isEqual(TerminalPackets.fingerprint(key), packet.fingerprint())) {
        status = "stale";
        publish();
        return;
      }
      amount = Math.min(amount, stack.getCount());
      source = transfers().inventory(player(), packet.selection());
      destination = buffer;
    } else return;
    busy = true;
    returning = false;
    moved = 0;
    requested = (int) amount;
    ticket = UUID.randomUUID();
    long expected = configuration;
    var admission =
        transfers()
            .submitGuarded(
                ticket,
                actor(),
                holder.position(),
                source,
                destination,
                key,
                amount,
                this::finish,
                () -> authorized() && holder.reference().configuration() == expected,
                () ->
                    saved().transfers().unresolved(lease.id()) == null
                        && saved()
                                .transfers()
                                .unresolved(
                                    TransferContext.playerBacking(
                                        saved().registry().world(), viewer.getUUID()))
                            == null);
    if (admission.rejection() != OperationResult.Reason.OK) {
      busy = false;
      ticket = null;
      status = admission.rejection().name().toLowerCase(Locale.ROOT);
    }
    publish();
  }

  private void finish(WorldStorageTransfers.Result result) {
    busy = false;
    ticket = null;
    if (result.transfer() != null
        && result.transfer().state() == StagedTransfer.State.COMPLETE
        && result.transfer().pending() == StagedTransfer.PendingCall.NONE
        && result.transfer().staged() == 0
        && saved().transfers().contains(result.id())) saved().transfers().retire(result.id());
    if (authorized()) {
      selections = holder.inventory().snapshot().slots();
      moved =
          result.transfer() == null
              ? 0
              : returning ? result.transfer().returned() : result.transfer().delivered();
      status =
          result.reason() == OperationResult.Reason.OK && requested > moved
              ? "partial"
              : result.reason().name().toLowerCase(Locale.ROOT);
      revision++;
      publish();
    }
  }

  private void publish() {
    var codec = new PlatformResourceCodec(player().registryAccess());
    var rules = FilterEdits.rules(draft.filter(), codec);
    var rows = new ArrayList<BufferSettingsPackets.Row>();
    for (int i = 0; i < selections.size(); i++) {
      var slot = selections.get(i);
      rows.add(
          new BufferSettingsPackets.Row(
              i,
              slot.key() == null ? "minecraft:air" : slot.key().registryId(),
              slot.key() == null ? "" : name(codec, slot.key()),
              slot.count()));
    }
    var operation = unresolved();
    long staged = operation == null ? 0 : saved().transfers().get(operation).staged();
    String displayStatus =
        operation != null
                && saved().transfers().get(operation).state() == StagedTransfer.State.QUARANTINED
            ? "uncertain"
            : status;
    var packet =
        new BufferSettingsPackets.View(
            containerId,
            protocol.id(),
            revision,
            mode.name(),
            draft.filter().mode().name(),
            draft.rate(),
            draft.target(),
            rules,
            rows,
            displayStatus,
            editable(),
            busy,
            dirty,
            staged,
            moved,
            requested);
    if (!packet.equals(view)) {
      view = packet;
      PacketDistributor.sendToPlayer(player(), packet);
    }
  }

  private static String name(PlatformResourceCodec codec, ResourceKey key) {
    String text;
    try {
      text = codec.itemStack(key, 1).getHoverName().getString().replaceAll("[\\p{Cntrl}§]", "");
    } catch (IllegalArgumentException invalid) {
      text = key.registryId();
    }
    return text.length() > 96 ? text.substring(0, 96) : text;
  }

  @Override
  public void removed(Player player) {
    if (holder != null && protocol.active()) {
      protocol.close();
      if (ticket != null && !transfers().inExecution()) transfers().cancelQueued(ticket, actor());
      selections = List.of();
    }
    super.removed(player);
  }
}
