package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Local machine port; canonical leases and foreign adapter ownership are shared with connectors.
 */
public final class MachineInterfaceEntity extends ConnectorEntity
    implements MenuProvider, RedstoneControlled {
  private UUID opening;
  private final dev.izzy.factorycore.core.storage.RedstoneGate redstone =
      new dev.izzy.factorycore.core.storage.RedstoneGate();

  @Override
  public dev.izzy.factorycore.core.storage.RedstoneGate redstone() {
    return redstone;
  }

  @Override
  public void redstoneWake() {
    setChanged();
    if (level instanceof net.minecraft.server.level.ServerLevel server)
      WorldNetworks.get(server.getServer()).machinePresets().wake(this);
  }

  @Override
  protected void saveAdditional(
      net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    RedstoneControlled.save(redstone, tag);
  }

  @Override
  protected void loadAdditional(
      net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    RedstoneControlled.load(redstone, tag);
  }

  UUID sessionForOpen() {
    return opening;
  }

  /** Importer or Exporter (H6), or null for a Machine-mode Interface or a cable port. */
  public PortBlock.Role role() {
    return PortBlock.role(getBlockState());
  }

  /** True for a cable port (H5): a cable that reaches the machine beside it. */
  public boolean cablePort() {
    return getBlockState().getBlock() instanceof CablePortBlock;
  }

  /** A label (and its owner) a Label Tool asked for while the network rebuilt; not saved. */
  private UUID pendingLabeller;

  private String pendingLabel;
  private int pendingLabelAttempts;

  /** Asks to bind and label this port under {@code player} once its network is valid again. */
  public void requestLabel(UUID player, String label) {
    pendingLabeller = player;
    pendingLabel = label;
    pendingLabelAttempts = 0;
  }

  /**
   * H5: binds the resource the machine offers on the touched face and applies the requested label
   * under the live player who asked, with that player's current EDIT. True while the network is
   * still settling and the caller should try again.
   */
  public boolean applyPendingLabel() {
    if (pendingLabeller == null || handle() == null) return false;
    var server = serverLevel().getServer();
    var player = server.getPlayerList().getPlayer(pendingLabeller);
    if (player == null || ++pendingLabelAttempts > RESUME_ATTEMPTS) {
      pendingLabeller = null;
      return false;
    }
    if (WorldNetworks.get(server).membership(position()).status() != NetworkTopology.Status.VALID)
      return true;
    var label = pendingLabel;
    pendingLabeller = null;
    pendingLabel = null;
    try {
      var kind = offeredKind();
      var settings = kind == settings().kind() ? settings() : ConnectorSettings.defaults(kind);
      if (applySettings(dev.izzy.factorycore.platform.Actors.of(player), settings, label)
          != NetworkAuthority.Decision.ALLOWED)
        player.displayClientMessage(
            Component.translatable("message.factorycore.label_tool.denied"), true);
    } catch (IllegalArgumentException | IllegalStateException refused) {
      player.displayClientMessage(
          Component.translatable("message.factorycore.label_tool.invalid"), true);
    }
    return false;
  }

  /**
   * Breaking a cable port (H5). With nothing in transit its lease, presets and binding are retired
   * exactly as an Interface mode switch retires them (ADR 0059) and a plain cable drops. With work
   * in transit, or a lease that cannot be retired, the port item drops carrying the lease, so
   * Transfer Recovery can still finish it.
   */
  public ItemStack cablePortBreak() {
    var saved = DeviceSavedData.get(serverLevel().getServer());
    var lease = handle();
    boolean busy =
        lease == null
            || !diagnostic().isEmpty()
            || saved.transfers().unresolvedDevice(lease.id()) != null
            || tier() != ThroughputTier.BASE;
    var portable = portableForBreak();
    if (busy) return portable;
    var detached = handle();
    saved.machinePresets().forget(detached.id());
    if (saved.nodes().retire(detached) != NodeRegistry.Failure.OK) return portable;
    return new ItemStack(dev.izzy.factorycore.platform.network.NetworkContent.CABLE_ITEM.get());
  }

  @Override
  public Component getDisplayName() {
    var role = role();
    return Component.translatable(
        cablePort()
            ? "block.factorycore.cable_port"
            : role == null
                ? "gui.factorycore.interface.title.machine"
                : "block.factorycore." + role.name().toLowerCase(java.util.Locale.ROOT));
  }

  /** A placer waiting for this port's default preset (H6); not saved. */
  private UUID pendingDefault;

  private int pendingDefaultAttempts;

  /**
   * Asks for the role's default preset once the network is valid (see {@link #applyDefaultPreset}).
   */
  void requestDefaultPreset(UUID placer) {
    if (role() == null) return;
    pendingDefault = placer;
    pendingDefaultAttempts = 0;
  }

  /**
   * H6, like a Buffer's default Import (U6): a freshly placed port binds the resource its machine
   * offers on the touching face (items, else fluids, else energy) and an Importer starts importing
   * everything, under the authenticated live player who placed it and with that player's grants. An
   * Exporter waits for a filter. Missing grants or a departed placer leave it paused. True while
   * the network is still settling and the caller should try again.
   */
  public boolean applyDefaultPreset() {
    if (pendingDefault == null || handle() == null) return false;
    var server = serverLevel().getServer();
    var player = server.getPlayerList().getPlayer(pendingDefault);
    if (player == null || ++pendingDefaultAttempts > RESUME_ATTEMPTS) {
      pendingDefault = null;
      return false;
    }
    if (WorldNetworks.get(server).membership(position()).status() != NetworkTopology.Status.VALID)
      return true;
    pendingDefault = null;
    var saved = DeviceSavedData.get(server);
    if (saved.machinePresets().get(handle().id()) != null) return false;
    var actor = dev.izzy.factorycore.platform.Actors.of(player);
    try {
      var kind = offeredKind();
      if (kind != settings().kind()
          && applySettings(actor, ConnectorSettings.defaults(kind), label())
              != NetworkAuthority.Decision.ALLOWED) return false;
    } catch (IllegalArgumentException | IllegalStateException denied) {
      // Missing grants or an unavailable network leave the port paused for an explicit Apply.
      return false;
    }
    if (role() == PortBlock.Role.IMPORTER) importEverything(actor);
    return false;
  }

  /**
   * Starts an Importer importing everything of its bound resource under {@code actor}, with the
   * same checks as an explicit Apply. False, leaving it paused, when the actor lacks a grant.
   */
  public boolean importEverything(NetworkPermissions.Actor actor) {
    try {
      deployPreset(actor, MachinePresets.Mode.IMPORT, ResourceFilter.all(settings().kind()), 0);
      return true;
    } catch (IllegalArgumentException | IllegalStateException denied) {
      return false;
    }
  }

  /**
   * Items if the touched face offers them, else fluids, else energy (items when it offers none).
   */
  private ResourceKind offeredKind() {
    var level = serverLevel();
    var target = getBlockPos().relative(targetDirection());
    var side = targetDirection().getOpposite();
    if (level.getCapability(Capabilities.ItemHandler.BLOCK, target, side) != null)
      return ResourceKind.ITEM;
    if (level.getCapability(Capabilities.FluidHandler.BLOCK, target, side) != null)
      return ResourceKind.FLUID;
    if (level.getCapability(Capabilities.EnergyStorage.BLOCK, target, side) != null)
      return ResourceKind.ENERGY;
    return ResourceKind.ITEM;
  }

  @Override
  public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
    if (!(player instanceof ServerPlayer server) || !canUse(server)) return null;
    opening = UUID.randomUUID();
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
    // H6: an Importer only imports and an Exporter only exports or keeps a stock.
    if (role() != null && !role().allows(mode))
      throw new IllegalArgumentException("Preset mode not allowed for " + role());
    var runtime = WorldNetworks.get(serverLevel().getServer());
    for (var permission :
        List.of(
            NetworkPermissions.Permission.VIEW,
            NetworkPermissions.Permission.EDIT,
            NetworkPermissions.Permission.DEPLOY))
      if (!runtime.allows(actor, position(), permission))
        throw new IllegalStateException("Machine preset permission denied");
    if (mode != MachinePresets.Mode.PAUSED)
      for (var permission :
          List.of(NetworkPermissions.Permission.WITHDRAW, NetworkPermissions.Permission.DEPOSIT))
        if (!runtime.allows(actor, position(), permission))
          throw new IllegalStateException("Machine preset resource permission denied");
    var scope = runtime.membership(position()).scope();
    var saved = DeviceSavedData.get(serverLevel().getServer());
    var next =
        saved
            .machinePresets()
            .prepare(
                handle(),
                mode == MachinePresets.Mode.PAUSED
                    ? Math.max(generation(), saved.machinePresets().configuration(handle().id()))
                    : saved.machinePresets().nextConfiguration(handle(), generation()),
                MachinePresets.Face.valueOf(targetDirection().name()),
                new MachinePresets.Authority(
                    actor.id(),
                    scope.network(),
                    scope.segment(),
                    saved.network(scope.network()).generation()),
                mode,
                filter,
                target);
    if (mode != MachinePresets.Mode.PAUSED
        && super.configure(actor, settings(), label(), next.configuration())
            != NetworkAuthority.Decision.ALLOWED)
      throw new IllegalStateException("Machine preset edit denied");
    saved.machinePresets().commit(handle(), next);
    runtime.machinePresets().register(this);
    return next;
  }

  /** The active mode a configuration change paused, or null when nothing can be resumed. */
  public MachinePresets.Mode interruptedMode() {
    validatePlaced();
    return DeviceSavedData.get(serverLevel().getServer())
        .machinePresets()
        .interrupted(handle().id());
  }

  /**
   * Resumes a preset that a configuration change paused, in {@code actor}'s name, through the
   * ordinary {@link #deployPreset} checks. False when nothing is resumable or the resource kind
   * changed; throws when the actor lacks the deployment or resource grants.
   */
  public boolean resumePreset(NetworkPermissions.Actor actor) {
    var presets = DeviceSavedData.get(serverLevel().getServer()).machinePresets();
    var mode = presets.interrupted(handle().id());
    var paused = presets.get(handle().id());
    if (mode == null || paused.filter().kind() != settings().kind()) return false;
    deployPreset(actor, mode, paused.filter(), paused.target());
    return true;
  }

  /** A deployer's resume waiting for the network to rebuild after an upgrade; not saved. */
  private NetworkPermissions.Actor pendingResume;

  private int pendingResumeAttempts;

  public static final int RESUME_ATTEMPTS = 20, RESUME_TICKS = 5;

  private static boolean deployedBy(
      NetworkPermissions.Actor actor, MachinePresets.Deployment before) {
    return before != null
        && before.mode() != MachinePresets.Mode.PAUSED
        && before.authority().principal().equals(actor.id());
  }

  /** Runs a deferred deployer resume; true while it should retry after the network settles. */
  public boolean applyPendingResume() {
    if (pendingResume == null) return false;
    if (++pendingResumeAttempts > RESUME_ATTEMPTS) {
      pendingResume = null;
      return false;
    }
    var membership = WorldNetworks.get(serverLevel().getServer()).membership(position());
    if (membership.status() != NetworkTopology.Status.VALID) return true;
    var actor = pendingResume;
    pendingResume = null;
    resumeIfDeployer(actor);
    return false;
  }

  /**
   * U6: a change by the preset's own deployer keeps it running, since no other player's authority
   * is involved. Any other editor leaves it paused for an explicit resume (ADR 0054).
   */
  private void resumeIfDeployer(NetworkPermissions.Actor actor) {
    try {
      resumePreset(actor);
    } catch (IllegalArgumentException | IllegalStateException denied) {
      // Revoked grants leave the preset paused, as an explicit resume would be refused.
      return;
    }
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
          List.of(
              NetworkPermissions.Permission.VIEW,
              NetworkPermissions.Permission.EDIT,
              NetworkPermissions.Permission.DEPLOY,
              NetworkPermissions.Permission.WITHDRAW,
              NetworkPermissions.Permission.DEPOSIT))
        if (!runtime.allows(actor, position(), permission)) return false;
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
    var before = presets.get(handle().id());
    long next = presets.nextConfiguration(handle(), generation());
    var result = super.configure(actor, settings, label, next);
    if (result == NetworkAuthority.Decision.ALLOWED) {
      presets.observeConfiguration(handle(), generation());
      presets.invalidate(handle().id());
      if (deployedBy(actor, before)) resumeIfDeployer(actor);
    }
    return result;
  }

  /**
   * An upgrade is a configuration change: it advances the fence and pauses the preset, which keeps
   * running only when the installer is its deployer.
   */
  @Override
  protected NetworkAuthority.Decision configureTier(
      NetworkPermissions.Actor actor, ConnectorSettings settings, ThroughputTier next) {
    validatePlaced();
    var presets = DeviceSavedData.get(serverLevel().getServer()).machinePresets();
    presets.prepareInvalidation(handle().id());
    var before = presets.get(handle().id());
    long generation = presets.nextConfiguration(handle(), generation());
    var result = commitTier(actor, settings, next, generation);
    if (result == NetworkAuthority.Decision.ALLOWED) {
      presets.observeConfiguration(handle(), generation());
      presets.invalidate(handle().id());
      // The tier change rebuilds the network, so the resume waits for it (see ConnectorBlock).
      if (deployedBy(actor, before)) {
        pendingResume = actor;
        pendingResumeAttempts = 0;
        level.scheduleTick(getBlockPos(), getBlockState().getBlock(), RESUME_TICKS);
      }
    }
    return result;
  }

  public MachineInterfaceEntity(BlockPos position, BlockState state) {
    super(MachineInterfaceContent.ENTITY.get(), position, state);
  }

  @Override
  public void validatePlaced() {
    super.validatePlaced();
    // A current native reference may be newer than a legacy deployment-only save.
    DeviceSavedData.get(serverLevel().getServer())
        .machinePresets()
        .observeConfiguration(handle(), generation());
  }

  @Override
  protected NodeRegistry.Kind leaseKind() {
    return NodeRegistry.Kind.MACHINE_INTERFACE;
  }

  /** The Interface, Importer or Exporter item this port was placed from. */
  @Override
  protected Item portableItem() {
    return getBlockState().getBlock().asItem();
  }

  public NetworkAuthority.Decision relabel(NetworkPermissions.Actor actor, String label) {
    return configure(actor, settings(), label);
  }

  @Override
  public void place(ItemStack stack) {
    super.place(stack);
    registerPreset();
  }

  private void registerPreset() {
    if (handle() == null || !diagnostic().isEmpty()) return;
    var saved = DeviceSavedData.get(serverLevel().getServer());
    validatePlaced();
    saved.machinePresets().observeConfiguration(handle(), generation());
    if (saved.machinePresets().get(handle().id()) != null)
      WorldNetworks.get(serverLevel().getServer()).machinePresets().register(this);
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (getLevel() instanceof ServerLevel) registerPreset();
  }

  @Override
  public void setRemoved() {
    WorldNetworks.machineInvalidated(getLevel(), this);
    super.setRemoved();
  }
}
