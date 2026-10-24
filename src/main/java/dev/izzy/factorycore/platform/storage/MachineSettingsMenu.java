package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.session.DeviceStatus;
import dev.izzy.factorycore.platform.session.MenuSession;
import dev.izzy.factorycore.platform.upgrade.ThroughputStatus;
import java.security.MessageDigest;
import java.util.*;
import java.util.Locale;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.network.PacketDistributor;

/** Current authenticated physical editor. Apply validates the whole form before one publication. */
public final class MachineSettingsMenu extends AbstractContainerMenu
    implements DeviceStatus.Holder {
  private final MenuSession protocol;
  private final Player viewer;
  private final MachineInterfaceEntity machine;
  private final NodeRegistry.Handle lease;
  private final NetworkTopology.Scope scope;
  private final Direction facing;
  private MachineSettingsPackets.View view;
  private String status = "ready";
  private MachinePresets.Deployment presetBase;
  private long presetConfiguration, presetRevision = 1, presetGoal = 64;
  private MachinePresets.Mode presetMode = MachinePresets.Mode.PAUSED;
  private ResourceFilter presetFilter = ResourceFilter.all(ResourceKind.ITEM);
  private String presetStatus = "ready";
  private boolean presetDirty, presetBusy;
  private UUID cleanupRequest;
  private MachinePresetPackets.View presetView;
  private DeviceStatus.View deviceStatus;

  public MachineSettingsMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, null, MenuSession.read(buffer));
  }

  MachineSettingsMenu(int id, Inventory inventory, MachineInterfaceEntity machine, UUID session) {
    super(MachineInterfaceContent.MENU.get(), id);
    this.viewer = inventory.player;
    protocol = new MenuSession(this, viewer, session, machine != null, 5);
    this.machine = machine;
    lease = machine == null ? null : machine.handle();
    facing = machine == null ? null : machine.targetDirection();
    scope = machine == null ? null : runtime().membership(machine.position()).scope();
    if (machine != null && resetPreset()) presetStatus = "resumable";
    deviceStatus = DeviceStatus.View.pending(id, protocol.id());
    presetView =
        new MachinePresetPackets.View(
            id,
            protocol.id(),
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
            protocol.id(),
            0,
            new MachineSettingsPackets.Form(
                "", ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 64, 64),
            "north",
            "ready",
            false,
            "base",
            ThroughputTier.BASE.portRate(ResourceKind.ITEM),
            false);
  }

  public UUID session() {
    return protocol.id();
  }

  @Override
  public DeviceStatus.View deviceStatus() {
    return deviceStatus;
  }

  @Override
  public void acceptDeviceStatus(DeviceStatus.View packet) {
    if (machine == null)
      deviceStatus = DeviceStatus.accept(containerId, protocol.id(), deviceStatus, packet);
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
    return Actors.of(viewer);
  }

  private boolean current() {
    return protocol.active()
        && machine != null
        && lease.equals(machine.handle())
        && facing == machine.targetDirection()
        && machine.canUse(player())
        && scope.equals(runtime().membership(machine.position()).scope());
  }

  private boolean editable() {
    return runtime().allows(actor(), machine.position(), NetworkPermissions.Permission.EDIT);
  }

  public void accept(MachineSettingsPackets.View packet) {
    if (machine == null
        && protocol.owns(packet.menu(), packet.session())
        && packet.generation() >= view.generation()) view = packet;
  }

  private void publish() {
    var settings = machine.settings();
    var packet =
        new MachineSettingsPackets.View(
            containerId,
            protocol.id(),
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
            editable(),
            machine.tier().name().toLowerCase(Locale.ROOT),
            machine.tier().portRate(settings.kind()),
            ThroughputStatus.throttled(machine.serverLevel().getServer(), machine.position()));
    if (!packet.equals(view)) {
      view = packet;
      PacketDistributor.sendToPlayer(player(), packet);
    }
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    // Native initMenu broadcasts before assigning the new container to the player.
    if (!protocol.due(this::current)) return;
    if (view.generation() != 0 && view.generation() != machine.generation()) status = "stale";
    if (!Objects.equals(presetBase, saved().machinePresets().get(lease.id()))
        || presetConfiguration != machine.generation()) {
      presetStatus = resetPreset() ? "resumable" : "stale";
    }
    publish();
    publishPreset();
    deviceStatus =
        DeviceStatus.publish(
            player(),
            deviceStatus,
            DeviceStatus.of(
                player().server,
                containerId,
                protocol.id(),
                machine.position(),
                // A paused preset has no runtime reason worth showing; say it is paused.
                presetView.running() == MachinePresets.Mode.PAUSED
                    ? "status.factorycore.buffer_mode.paused"
                    : "status.factorycore.buffer_reason." + presetView.reason(),
                ""));
  }

  public void apply(ServerPlayer sender, MachineSettingsPackets.Apply packet) {
    if (!protocol.fromViewer(sender, packet.menu(), packet.session()) || !current() || presetBusy)
      return;
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
      if (!packet.form().settings().fits(machine.tier())) {
        status = "upgrade";
        publish();
        return;
      }
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
    sender.displayClientMessage(
        Component.translatable(
            machine.interruptedMode() == null
                ? "gui.factorycore.machine.saved"
                : "gui.factorycore.machine.saved_paused"),
        true);
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
    protocol.close();
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
        && protocol.owns(packet.menu(), packet.session())
        && packet.revision() >= presetView.revision()) presetView = packet;
  }

  /** Reloads the draft from the saved preset; true when it offers a paused preset to resume. */
  private boolean resetPreset() {
    presetBase = saved().machinePresets().get(lease.id());
    presetConfiguration = machine.generation();
    boolean compatible =
        presetBase != null && presetBase.filter().kind() == machine.settings().kind();
    presetMode = compatible ? presetBase.mode() : MachinePresets.Mode.PAUSED;
    // A preset paused by a binding change or upgrade is offered back as a one-click resume.
    var interrupted = compatible ? saved().machinePresets().interrupted(lease.id()) : null;
    if (interrupted != null) presetMode = interrupted;
    presetFilter = compatible ? presetBase.filter() : ResourceFilter.all(machine.settings().kind());
    presetGoal =
        compatible
            ? presetBase.target()
            : machine.settings().kind() == ResourceKind.ITEM ? 64 : 1000;
    presetDirty = interrupted != null;
    presetRevision++;
    return interrupted != null;
  }

  private boolean presetEditable() {
    return editable()
        && runtime().allows(actor(), machine.position(), NetworkPermissions.Permission.DEPLOY);
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
            protocol.id(),
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
    if (!protocol.fromViewer(sender, packet.menu(), packet.session())
        || !current()
        || presetBusy
        || !protocol.admitRate()) return;
    if (!Objects.equals(presetBase, saved().machinePresets().get(lease.id()))
        || presetConfiguration != machine.generation()) {
      presetStatus = resetPreset() ? "resumable" : "stale";
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
            if (!MessageDigest.isEqual(
                TerminalPackets.fingerprint(codec.itemKey(stack)), packet.fingerprint())) {
              presetStatus = "stale";
              publishPreset();
              return;
            }
            if (presetFilter.kind() == ResourceKind.ITEM) key = codec.itemKey(stack);
            else {
              var contents = FluidUtil.getFluidContained(stack.copyWithCount(1));
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
          presetStatus = resetPreset() ? "resumable" : "ready";
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
              if (!runtime().allows(actor(), machine.position(), permission)) {
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
