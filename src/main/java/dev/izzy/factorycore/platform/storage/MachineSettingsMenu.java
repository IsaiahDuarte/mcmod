package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.*;
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
  private MachinePresets.Deployment presetBase;
  private long presetConfiguration, presetRevision = 1, presetGoal = 64;
  private MachinePresets.Mode presetMode = MachinePresets.Mode.PAUSED;
  private ResourceFilter presetFilter = ResourceFilter.all(ResourceKind.ITEM);
  private String presetStatus = "ready";
  private boolean presetDirty, presetBusy;
  private int lastPresetCommand = Integer.MIN_VALUE;
  private UUID cleanupRequest;
  private MachinePresetPackets.View presetView;

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
    if (machine != null) resetPreset();
    presetView =
        new MachinePresetPackets.View(
            id,
            session,
            presetRevision,
            ResourceKind.ITEM,
            MachinePresets.Mode.PAUSED,
            ResourceFilter.Mode.ALL,
            64,
            List.of(),
            "ready",
            MachinePresets.Mode.PAUSED,
            "ok",
            false,
            false,
            false,
            0,
            false);
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
    if (!Objects.equals(presetBase, saved().machinePresets().get(lease.id()))
        || presetConfiguration != machine.generation()) {
      resetPreset();
      presetStatus = "stale";
    }
    publish();
    publishPreset();
  }

  public void apply(ServerPlayer sender, MachineSettingsPackets.Apply packet) {
    if (sender != viewer
        || sender.containerMenu != this
        || packet.menu() != containerId
        || !packet.session().equals(session)
        || !current()
        || presetBusy) return;
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
    if (cleanupRequest != null && !runtime().transfers().inExecution())
      runtime().transfers().cancelQueued(cleanupRequest, actor());
    super.removed(player);
  }

  private DeviceSavedData saved() {
    return DeviceSavedData.get(player().server);
  }

  public MachinePresetPackets.View presetView() {
    return presetView;
  }

  public void acceptPreset(MachinePresetPackets.View packet) {
    if (machine == null
        && packet.menu() == containerId
        && packet.session().equals(session)
        && packet.revision() >= presetView.revision()) presetView = packet;
  }

  private void resetPreset() {
    presetBase = saved().machinePresets().get(lease.id());
    presetConfiguration = machine.generation();
    boolean compatible =
        presetBase != null && presetBase.filter().kind() == machine.settings().kind();
    presetMode = compatible ? presetBase.mode() : MachinePresets.Mode.PAUSED;
    presetFilter = compatible ? presetBase.filter() : ResourceFilter.all(machine.settings().kind());
    presetGoal =
        compatible
            ? presetBase.target()
            : machine.settings().kind() == ResourceKind.ITEM ? 64 : 1000;
    presetDirty = false;
    presetRevision++;
  }

  private boolean presetEditable() {
    return editable()
        && runtime().authorize(actor(), machine.position(), NetworkPermissions.Permission.DEPLOY)
            == NetworkAuthority.Decision.ALLOWED;
  }

  private TransferRegistry.Stored ownedStaging() {
    var value = saved().transfers().unresolvedDevice(lease.id());
    if (value == null
        || !value.transfer().owner().equals(viewer.getUUID())
        || value.context() == null
        || !value.context().origin().equals(machine.position())
        || !value.context().network().equals(scope.network())
        || !value.context().segment().equals(scope.segment())) return null;
    return value;
  }

  private void publishPreset() {
    var codec = new PlatformResourceCodec(player().registryAccess());
    var rules = new ArrayList<String>();
    for (var term : presetFilter.terms()) {
      String text;
      if (term instanceof ResourceFilter.Registry registry) text = "ID " + registry.id();
      else if (term instanceof ResourceFilter.Tag tag) text = "#" + tag.id();
      else {
        var key = ((ResourceFilter.Exact) term).key();
        String name = key.registryId();
        try {
          if (key.kind() == ResourceKind.ITEM)
            name = codec.itemStack(key, 1).getHoverName().getString();
          else if (key.kind() == ResourceKind.FLUID)
            name = codec.fluidStack(key, 1).getHoverName().getString();
        } catch (IllegalArgumentException unsupported) {
          name = key.registryId();
        }
        text = "= " + key.registryId() + " · " + name;
      }
      text = text.replaceAll("[\\p{Cntrl}§]", "");
      rules.add(text.length() > 256 ? text.substring(0, 256) : text);
    }
    var runtimeStatus = runtime().machinePresets().status(machine);
    var staged = ownedStaging();
    boolean uncertain =
        staged != null
            && (staged.transfer().pending() != StagedTransfer.PendingCall.NONE
                || staged.transfer().state() == StagedTransfer.State.QUARANTINED);
    var packet =
        new MachinePresetPackets.View(
            containerId,
            session,
            presetRevision,
            machine.settings().kind(),
            presetMode,
            presetFilter.mode(),
            presetGoal,
            rules,
            presetStatus,
            runtimeStatus.mode(),
            runtimeStatus.reason().name().toLowerCase(Locale.ROOT),
            presetEditable(),
            presetBusy,
            presetDirty,
            staged == null ? 0 : staged.transfer().staged(),
            staged != null && !uncertain);
    if (!packet.equals(presetView)) {
      presetView = packet;
      PacketDistributor.sendToPlayer(player(), packet);
    }
  }

  public void presetCommand(ServerPlayer sender, MachinePresetPackets.Command packet) {
    if (sender != viewer
        || sender.containerMenu != this
        || packet.menu() != containerId
        || !packet.session().equals(session)
        || !current()
        || presetBusy) return;
    int tick = sender.server.getTickCount();
    if (lastPresetCommand != Integer.MIN_VALUE && tick - lastPresetCommand < 2) return;
    lastPresetCommand = tick;
    if (!Objects.equals(presetBase, saved().machinePresets().get(lease.id()))
        || presetConfiguration != machine.generation()) {
      resetPreset();
      presetStatus = "stale";
      publishPreset();
      return;
    }
    if (packet.revision() != presetRevision) {
      presetStatus = "stale";
      publishPreset();
      return;
    }
    if (packet.action() == MachinePresetPackets.RETURN) {
      var value = ownedStaging();
      if (value == null) {
        presetStatus = "reserved";
        publishPreset();
        return;
      }
      cleanupRequest = value.id();
      presetBusy = true;
      var submission =
          runtime()
              .transfers()
              .progressGuarded(
                  value.id(),
                  actor(),
                  true,
                  result -> {
                    presetBusy = false;
                    cleanupRequest = null;
                    if (result.transfer() != null
                        && result.transfer().state() == StagedTransfer.State.COMPLETE
                        && result.transfer().pending() == StagedTransfer.PendingCall.NONE
                        && result.transfer().staged() == 0
                        && saved().transfers().contains(result.id()))
                      saved().transfers().retire(result.id());
                    if (current() && player().containerMenu == this) {
                      presetStatus = result.reason().name().toLowerCase(Locale.ROOT);
                      presetRevision++;
                      publishPreset();
                    }
                  },
                  () -> current() && player().containerMenu == this);
      if (submission.rejection() != OperationResult.Reason.OK) {
        presetBusy = false;
        cleanupRequest = null;
        presetStatus = submission.rejection().name().toLowerCase(Locale.ROOT);
      }
      publishPreset();
      return;
    }
    if (!presetEditable()) {
      presetStatus = "denied";
      publishPreset();
      return;
    }
    try {
      var terms = new ArrayList<>(presetFilter.terms());
      var filterMode = presetFilter.mode();
      long goal = presetGoal;
      switch (packet.action()) {
        case MachinePresetPackets.MODE ->
            presetMode =
                MachinePresets.Mode.values()[
                    (presetMode.ordinal() + 1) % MachinePresets.Mode.values().length];
        case MachinePresetPackets.FILTER -> {
          filterMode =
              ResourceFilter.Mode.values()[
                  (filterMode.ordinal() + 1) % ResourceFilter.Mode.values().length];
          if (filterMode == ResourceFilter.Mode.ALL) terms.clear();
        }
        case MachinePresetPackets.GOAL -> goal = Long.parseLong(packet.text());
        case MachinePresetPackets.REGISTRY -> {
          terms.add(new ResourceFilter.Registry(packet.text()));
          if (filterMode == ResourceFilter.Mode.ALL) filterMode = ResourceFilter.Mode.INCLUDE;
        }
        case MachinePresetPackets.TAG -> {
          terms.add(
              new ResourceFilter.Tag(
                  packet.text().startsWith("#") ? packet.text().substring(1) : packet.text()));
          if (filterMode == ResourceFilter.Mode.ALL) filterMode = ResourceFilter.Mode.INCLUDE;
        }
        case MachinePresetPackets.SAMPLE -> {
          ResourceKey key;
          if (presetFilter.kind() == ResourceKind.ENERGY) key = ResourceKey.ENERGY;
          else {
            var stack = sender.getInventory().getItem(packet.selection());
            if (stack.isEmpty()) {
              presetStatus = "sample_empty";
              publishPreset();
              return;
            }
            var codec = new PlatformResourceCodec(sender.registryAccess());
            if (!java.security.MessageDigest.isEqual(
                TerminalPackets.fingerprint(codec.itemKey(stack)), packet.fingerprint())) {
              presetStatus = "stale";
              publishPreset();
              return;
            }
            if (presetFilter.kind() == ResourceKind.ITEM) key = codec.itemKey(stack);
            else {
              var contents =
                  net.neoforged.neoforge.fluids.FluidUtil.getFluidContained(stack.copyWithCount(1));
              if (contents.isEmpty()) {
                presetStatus = "sample_empty";
                publishPreset();
                return;
              }
              key = codec.fluidKey(contents.get());
            }
          }
          terms.add(new ResourceFilter.Exact(key));
          if (filterMode == ResourceFilter.Mode.ALL) filterMode = ResourceFilter.Mode.INCLUDE;
        }
        case MachinePresetPackets.REMOVE -> {
          if (packet.selection() >= terms.size()) return;
          terms.remove(packet.selection());
        }
        case MachinePresetPackets.CLEAR -> {
          terms.clear();
          filterMode = ResourceFilter.Mode.ALL;
        }
        case MachinePresetPackets.RESET -> {
          resetPreset();
          presetStatus = "ready";
          publishPreset();
          return;
        }
        case MachinePresetPackets.DEPLOY -> {
          if (!packet.text().isEmpty()) goal = Long.parseLong(packet.text());
          if (goal < 0) throw new IllegalArgumentException("Negative goal");
          if (presetMode != MachinePresets.Mode.PAUSED)
            for (var permission :
                List.of(
                    NetworkPermissions.Permission.WITHDRAW, NetworkPermissions.Permission.DEPOSIT))
              if (runtime().authorize(actor(), machine.position(), permission)
                  != NetworkAuthority.Decision.ALLOWED) {
                presetStatus = "denied";
                publishPreset();
                return;
              }
          machine.deployPreset(actor(), presetMode, presetFilter, goal);
          resetPreset();
          presetStatus = "applied";
          publishPreset();
          return;
        }
        default -> throw new IllegalArgumentException("Preset action");
      }
      if (goal < 0) throw new IllegalArgumentException("Negative goal");
      presetFilter = new ResourceFilter(machine.settings().kind(), filterMode, terms);
      presetGoal = goal;
      presetDirty = true;
      presetRevision++;
      presetStatus = "draft";
    } catch (IllegalArgumentException invalid) {
      presetStatus = "invalid";
    } catch (ArithmeticException overflow) {
      presetStatus = "limit";
    } catch (IllegalStateException limit) {
      presetStatus = "limit";
    }
    publishPreset();
  }
}
