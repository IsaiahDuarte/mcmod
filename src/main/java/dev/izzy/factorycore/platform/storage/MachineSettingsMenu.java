package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.resource.ResourceKey;
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
import net.minecraft.world.inventory.MenuType;
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
  private MachineSettingsPackets.Status status = MachineSettingsPackets.Status.READY;
  private MachinePresets.Deployment presetBase;
  private long presetConfiguration, presetRevision = 1, presetGoal = 64;
  private MachinePresets.Mode presetMode = MachinePresets.Mode.PAUSED;
  private ResourceFilter presetFilter = ResourceFilter.all(ResourceKind.ITEM);
  private MachinePresetPackets.Status presetStatus = MachinePresetPackets.Status.READY;
  private final net.minecraft.world.inventory.DataSlot redstone;

  /** C2: the redstone control and input, decoded with {@link RedstoneMenu}. */
  public int redstoneState() {
    return redstone.get();
  }

  private boolean presetDirty, presetBusy;
  private UUID cleanupRequest;
  private MachinePresetPackets.View presetView;
  private DeviceStatus.View deviceStatus;

  /** The client side of an Importer or Exporter menu (H6), whose type selects its screen. */
  static MachineSettingsMenu client(
      MenuType<MachineSettingsMenu> type,
      int id,
      Inventory inventory,
      RegistryFriendlyByteBuf buffer) {
    return new MachineSettingsMenu(type, id, inventory, null, MenuSession.read(buffer));
  }

  MachineSettingsMenu(int id, Inventory inventory, MachineInterfaceEntity machine, UUID session) {
    this(
        machine.role() != null
            ? PortContent.menu(machine.role())
            : PortContent.CABLE_PORT_MENU.get(),
        id,
        inventory,
        machine,
        session);
  }

  private MachineSettingsMenu(
      MenuType<MachineSettingsMenu> type,
      int id,
      Inventory inventory,
      MachineInterfaceEntity machine,
      UUID session) {
    super(type, id);
    this.viewer = inventory.player;
    protocol = new MenuSession(this, viewer, session, machine != null, 5);
    this.machine = machine;
    lease = machine == null ? null : machine.handle();
    facing = machine == null ? null : machine.targetDirection();
    scope = machine == null ? null : runtime().membership(machine.position()).scope();
    if (machine != null && resetPreset()) presetStatus = MachinePresetPackets.Status.RESUMABLE;
    deviceStatus = DeviceStatus.View.pending(id, protocol.id());
    redstone = addDataSlot(RedstoneMenu.slot(machine));
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
            MachinePresetPackets.Status.READY,
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
            MachineSettingsPackets.Status.READY,
            false,
            "base",
            ThroughputTier.BASE.portRate(ResourceKind.ITEM),
            false,
            0,
            0);
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
    int[] size = machine.containerSize();
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
            ThroughputStatus.throttled(machine.serverLevel().getServer(), machine.position()),
            size[0],
            size[1]);
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
    if (view.generation() != 0 && view.generation() != machine.generation())
      status = MachineSettingsPackets.Status.STALE;
    if (!Objects.equals(presetBase, saved().machinePresets().get(lease.id()))
        || presetConfiguration != machine.generation()) {
      presetStatus =
          resetPreset() ? MachinePresetPackets.Status.RESUMABLE : MachinePresetPackets.Status.STALE;
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
                // A paused preset has no runtime reason worth showing; say it is paused. A cable
                // port has no preset of its own (programs drive it), so it shows no reason.
                machine.cablePort()
                    ? ""
                    : presetView.running() == MachinePresets.Mode.PAUSED
                        ? "status.factorycore.buffer_mode.paused"
                        : "status.factorycore.buffer_reason." + presetView.reason(),
                ""));
  }

  public void apply(ServerPlayer sender, MachineSettingsPackets.Apply packet) {
    if (!protocol.fromViewer(sender, packet.menu(), packet.session()) || !current() || presetBusy)
      return;
    if (packet.generation() != machine.generation()) {
      status = MachineSettingsPackets.Status.STALE;
      publish();
      return;
    }
    if (!editable()) {
      status = MachineSettingsPackets.Status.DENIED;
      publish();
      return;
    }
    try {
      if (!packet.form().settings().fits(machine.tier())) {
        status = MachineSettingsPackets.Status.UPGRADE;
        publish();
        return;
      }
      var before = machine.settings().kind();
      if (machine.applySettings(actor(), packet.form().settings(), packet.form().label())
          != NetworkAuthority.Decision.ALLOWED) {
        status = MachineSettingsPackets.Status.DENIED;
        publish();
        return;
      }
      // H6: an Importer switched to another resource keeps importing everything of it; without
      // Deploy or resource grants it stays paused for an explicit Apply.
      if (machine.role() == PortBlock.Role.IMPORTER && machine.settings().kind() != before)
        machine.importEverything(actor());
    } catch (IllegalArgumentException invalid) {
      status = MachineSettingsPackets.Status.INVALID;
      publish();
      return;
    } catch (ArithmeticException limit) {
      status = MachineSettingsPackets.Status.LIMIT;
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

  /**
   * Interface mode row (U11, ADR 0059): button {@link InterfaceSwitch#BUTTON} + kind asks to
   * switch. On success the screen closes and the player is told to reopen it; a refusal shows its
   * reason.
   */
  @Override
  public boolean clickMenuButton(Player player, int button) {
    if (RedstoneMenu.requested(button)) {
      if (machine == null
          || !current()
          || !(player instanceof ServerPlayer sender)
          || sender != viewer) return false;
      presetStatus =
          RedstoneMenu.click(sender, machine, button)
              ? MachinePresetPackets.Status.REDSTONE_SET
              : MachinePresetPackets.Status.REDSTONE_DENIED;
      presetRevision++;
      publishPreset();
      return true;
    }
    var target = InterfaceSwitch.requested(button);
    if (target == null
        || machine == null
        || !current()
        || presetBusy
        || !(player instanceof ServerPlayer sender)
        || sender != viewer) return false;
    var outcome = InterfaceSwitch.request(sender, machine.getBlockPos(), target);
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
    status = outcome.as(MachineSettingsPackets.Status.class);
    publish();
    return false;
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

  /** Q100: the last computed Exporter rule statuses, for which filter, and when. */
  private List<MachinePresetPackets.RuleStatus> ruleStatuses = List.of();

  private ResourceFilter statusesFor;
  private long statusesAt = Long.MIN_VALUE;

  /**
   * Q100: per Exporter rule, whether the network holds some, could craft it, or has none. Tag rules
   * stay UNKNOWN. Recomputed at most once a second, or when the filter changes; each rule is one
   * stock lookup through the viewer's own access, as a job preview does.
   */
  private List<MachinePresetPackets.RuleStatus> ruleStatuses(PlatformResourceCodec codec) {
    if (machine.role() != PortBlock.Role.EXPORTER || scope == null) return List.of();
    long now = player().serverLevel().getGameTime();
    if (presetFilter.equals(statusesFor) && now - statusesAt < 20) return ruleStatuses;
    var jobs = dev.izzy.factorycore.platform.crafting.WorldCraftingJobs.get(player().server);
    var index =
        dev.izzy.factorycore.platform.crafting.WorldCrafting.get(player().server).index(scope);
    var statuses = new ArrayList<MachinePresetPackets.RuleStatus>(presetFilter.terms().size());
    for (var term : presetFilter.terms()) {
      ResourceKey key =
          term instanceof ResourceFilter.Exact exact
              ? exact.key()
              : term instanceof ResourceFilter.Registry registry
                  ? registryKey(codec, presetFilter.kind(), registry.id())
                  : null;
      if (key == null) statuses.add(MachinePresetPackets.RuleStatus.UNKNOWN);
      else if (jobs.available(actor(), machine.position(), key) > 0)
        statuses.add(MachinePresetPackets.RuleStatus.STOCKED);
      else if (!index.producers(key).isEmpty())
        statuses.add(MachinePresetPackets.RuleStatus.CRAFTABLE);
      else statuses.add(MachinePresetPackets.RuleStatus.EMPTY);
    }
    ruleStatuses = List.copyOf(statuses);
    statusesFor = presetFilter;
    statusesAt = now;
    return ruleStatuses;
  }

  /** The plain key of a registry-ID rule, or null when the ID is not a known item or fluid. */
  private static ResourceKey registryKey(
      PlatformResourceCodec codec, ResourceKind kind, String id) {
    var location = net.minecraft.resources.ResourceLocation.tryParse(id);
    if (location == null) return null;
    return switch (kind) {
      case ITEM ->
          net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(location)
              ? codec.itemKey(
                  new net.minecraft.world.item.ItemStack(
                      net.minecraft.core.registries.BuiltInRegistries.ITEM.get(location)))
              : null;
      case FLUID ->
          net.minecraft.core.registries.BuiltInRegistries.FLUID.containsKey(location)
              ? codec.fluidKey(
                  new net.neoforged.neoforge.fluids.FluidStack(
                      net.minecraft.core.registries.BuiltInRegistries.FLUID.get(location), 1))
              : null;
      default -> null;
    };
  }

  private void publishPreset() {
    var codec = new PlatformResourceCodec(player().registryAccess());
    var rules = FilterEdits.rules(presetFilter, codec);
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
            staged != null && !uncertain,
            ruleStatuses(codec));
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
      presetStatus =
          resetPreset() ? MachinePresetPackets.Status.RESUMABLE : MachinePresetPackets.Status.STALE;
      publishPreset();
      return;
    }
    if (packet.revision() != presetRevision) {
      presetStatus = MachinePresetPackets.Status.STALE;
      publishPreset();
      return;
    }
    if (packet.action() == MachinePresetPackets.RETURN) {
      var value = ownedStaging();
      if (value == null) {
        presetStatus = MachinePresetPackets.Status.RESERVED;
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
                      presetStatus = MachinePresetPackets.Status.of(result.reason());
                      presetRevision++;
                      publishPreset();
                    }
                  },
                  () -> current() && player().containerMenu == this);
      if (submission.rejection() != OperationResult.Reason.OK) {
        presetBusy = false;
        cleanupRequest = null;
        presetStatus = MachinePresetPackets.Status.of(submission.rejection());
      }
      publishPreset();
      return;
    }
    if (!presetEditable()) {
      presetStatus = MachinePresetPackets.Status.DENIED;
      publishPreset();
      return;
    }
    try {
      var terms = new ArrayList<>(presetFilter.terms());
      var filterMode = presetFilter.mode();
      long goal = presetGoal;
      switch (packet.action()) {
        case MachinePresetPackets.MODE -> {
          var role = machine.role();
          do
            presetMode =
                MachinePresets.Mode.values()[
                    (presetMode.ordinal() + 1) % MachinePresets.Mode.values().length];
          while (role != null && !role.allows(presetMode));
        }
        case MachinePresetPackets.FILTER_MODE -> {
          if (packet.selection() >= ResourceFilter.Mode.values().length)
            throw new IllegalArgumentException("Filter mode");
          filterMode = ResourceFilter.Mode.values()[packet.selection()];
          if (filterMode == ResourceFilter.Mode.ALL) terms.clear();
          else if (terms.isEmpty()) throw new IllegalArgumentException("Empty filter");
        }
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
              presetStatus = MachinePresetPackets.Status.SAMPLE_EMPTY;
              publishPreset();
              return;
            }
            var codec = new PlatformResourceCodec(sender.registryAccess());
            if (!MessageDigest.isEqual(
                TerminalPackets.fingerprint(codec.itemKey(stack)), packet.fingerprint())) {
              presetStatus = MachinePresetPackets.Status.STALE;
              publishPreset();
              return;
            }
            if (presetFilter.kind() == ResourceKind.ITEM) key = codec.itemKey(stack);
            else {
              var contents = FluidUtil.getFluidContained(stack.copyWithCount(1));
              if (contents.isEmpty()) {
                presetStatus = MachinePresetPackets.Status.SAMPLE_EMPTY;
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
        case MachinePresetPackets.CARRIED -> {
          var term = FilterEdits.carried(presetFilter.kind(), getCarried());
          if (term == null) {
            presetStatus = MachinePresetPackets.Status.SAMPLE_EMPTY;
            publishPreset();
            return;
          }
          terms.add(term);
          if (filterMode == ResourceFilter.Mode.ALL) filterMode = ResourceFilter.Mode.INCLUDE;
        }
        case MachinePresetPackets.CYCLE -> {
          if (packet.selection() >= terms.size()) return;
          var next =
              FilterEdits.cycle(
                  presetFilter.kind(),
                  terms.get(packet.selection()),
                  packet.text(),
                  new PlatformResourceCodec(sender.registryAccess()));
          if (next == null) {
            presetStatus = MachinePresetPackets.Status.STALE;
            publishPreset();
            return;
          }
          terms.set(packet.selection(), next);
        }
        case MachinePresetPackets.CLEAR -> {
          terms.clear();
          filterMode = ResourceFilter.Mode.ALL;
        }
        case MachinePresetPackets.RESET -> {
          presetStatus =
              resetPreset()
                  ? MachinePresetPackets.Status.RESUMABLE
                  : MachinePresetPackets.Status.READY;
          publishPreset();
          return;
        }
        case MachinePresetPackets.DEPLOY, MachinePresetPackets.DEPLOY_AS -> {
          if (packet.action() == MachinePresetPackets.DEPLOY_AS) {
            if (packet.selection() >= MachinePresets.Mode.values().length)
              throw new IllegalArgumentException("Preset mode");
            presetMode = MachinePresets.Mode.values()[packet.selection()];
          }
          // H6: an Importer only imports; an Exporter exports or keeps a stock.
          if (machine.role() != null && !machine.role().allows(presetMode)) {
            presetStatus = MachinePresetPackets.Status.DENIED;
            publishPreset();
            return;
          }
          // An Exporter needs to be told what to send; it never empties all storage.
          if (presetMode != MachinePresets.Mode.PAUSED
              && presetMode != MachinePresets.Mode.IMPORT
              && machine.role() == PortBlock.Role.EXPORTER
              && presetFilter.mode() == ResourceFilter.Mode.ALL) {
            presetStatus = MachinePresetPackets.Status.CHOOSE;
            publishPreset();
            return;
          }
          if (!packet.text().isEmpty()) goal = Long.parseLong(packet.text());
          if (goal < 0) throw new IllegalArgumentException("Negative goal");
          if (presetMode != MachinePresets.Mode.PAUSED)
            for (var permission :
                List.of(
                    NetworkPermissions.Permission.WITHDRAW, NetworkPermissions.Permission.DEPOSIT))
              if (!runtime().allows(actor(), machine.position(), permission)) {
                presetStatus = MachinePresetPackets.Status.DENIED;
                publishPreset();
                return;
              }
          machine.deployPreset(actor(), presetMode, presetFilter, goal);
          resetPreset();
          presetStatus = MachinePresetPackets.Status.APPLIED;
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
      presetStatus = MachinePresetPackets.Status.DRAFT;
    } catch (IllegalArgumentException invalid) {
      presetStatus = MachinePresetPackets.Status.INVALID;
    } catch (ArithmeticException overflow) {
      presetStatus = MachinePresetPackets.Status.LIMIT;
    } catch (IllegalStateException limit) {
      presetStatus = MachinePresetPackets.Status.LIMIT;
    }
    publishPreset();
  }
}
