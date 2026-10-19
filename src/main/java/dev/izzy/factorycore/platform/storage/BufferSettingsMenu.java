package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-owned draft and exact inventory selections. Clients never submit root/actor/component
 * authority.
 */
public final class BufferSettingsMenu extends AbstractContainerMenu {
  private final Player viewer;
  private final BufferedInterfaceEntity holder;
  private final UUID session;
  private final BlockPos position;
  private final NodeRegistry.Handle lease;
  private final NetworkTopology.Scope scope;
  private BufferPolicy draft = BufferPolicy.DEFAULT;
  private BufferRegistry.Mode mode = BufferRegistry.Mode.PAUSED;
  private long configuration, revision = 1;
  private List<ItemBuffer.Slot> selections = List.of();
  private boolean active = true, busy, dirty, inventoryTab;
  private int lastCommand = Integer.MIN_VALUE, lastPoll = Integer.MIN_VALUE;
  private UUID ticket;
  private long moved;
  private int requested;
  private boolean returning;
  private String status = "ready";
  private BufferSettingsPackets.View view;

  public BufferSettingsMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, null, buffer.readBlockPos(), buffer.readUUID());
  }

  BufferSettingsMenu(int id, Inventory inventory, BufferedInterfaceEntity holder, UUID nonce) {
    this(id, inventory, holder, holder.getBlockPos(), nonce);
  }

  private BufferSettingsMenu(
      int id, Inventory inventory, BufferedInterfaceEntity holder, BlockPos position, UUID nonce) {
    super(BufferedInterfaceContent.MENU.get(), id);
    viewer = inventory.player;
    this.holder = holder;
    this.position = position;
    session = nonce;
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
  }

  public UUID session() {
    return session;
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
        && packet.menu() == containerId
        && packet.session().equals(session)
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
    return new NetworkPermissions.Actor(viewer.getUUID(), player().hasPermissions(2));
  }

  private boolean current() {
    return active
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
      if (WorldNetworks.get(player().server).authorize(actor(), holder.position(), permission)
          != NetworkAuthority.Decision.ALLOWED) return false;
    return true;
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
    if (holder == null || !active || player().containerMenu != this) return;
    if (!current()) {
      player().closeContainer();
      return;
    }
    int tick = player().server.getTickCount();
    if (lastPoll != Integer.MIN_VALUE && tick - lastPoll < 5) return;
    lastPoll = tick;
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
  }

  public void command(ServerPlayer sender, BufferSettingsPackets.Command packet) {
    if (sender != viewer
        || packet.menu() != containerId
        || !packet.session().equals(session)
        || !authorized()
        || busy) return;
    int tick = sender.server.getTickCount();
    if (lastCommand != Integer.MIN_VALUE && tick - lastCommand < 2) return;
    lastCommand = tick;
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
    if (packet.action() >= BufferSettingsPackets.TAKE) {
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
    var rules = new ArrayList<String>();
    for (var term : draft.filter().terms()) {
      String label =
          term instanceof ResourceFilter.Registry registry
              ? "ID " + registry.id()
              : term instanceof ResourceFilter.Tag tag
                  ? "#" + tag.id()
                  : "= "
                      + ((ResourceFilter.Exact) term).key().registryId()
                      + " · "
                      + name(codec, ((ResourceFilter.Exact) term).key());
      rules.add(label.length() > 128 ? label.substring(0, 128) : label);
    }
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
            session,
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
    if (holder != null && active) {
      active = false;
      if (ticket != null && !transfers().inExecution()) transfers().cancelQueued(ticket, actor());
      selections = List.of();
    }
    super.removed(player);
  }
}
