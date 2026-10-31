package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.BufferPolicy;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.signal.SignalInterfaceEntity;
import dev.izzy.factorycore.platform.upgrade.ThroughputUpgradeItem;
import dev.izzy.factorycore.platform.upgrade.ThroughputUpgradeable;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Q13: Label Tool settings copy and paste for the devices beyond cable ports, Importers, Exporters
 * and Storage Interfaces (those keep their {@code Mode} copies in {@link LabelToolItem}). A copy is
 * a {@link #DEVICE}-tagged compound on the tool; reading it needs VIEW where the device is. A paste
 * goes through the same authority as the device's own screen: EDIT (and DEPLOY for a Buffer), or
 * MANAGE for a Gateway. As AE2's Memory Card does, a paste first installs the copied throughput
 * upgrades from the player's inventory, and a Crafter paste fills empty pattern slots with the
 * copied patterns, each using one Blank Pattern from the inventory; anything missing is reported.
 * The upgrade tier of a Crafter is not copied. Server thread only.
 */
public final class DeviceCopy {
  static final String DEVICE = "Device";

  /** The copyable device kinds; the name is the copy's {@link #DEVICE} value in lower case. */
  enum Kind {
    BUFFER,
    SIGNAL,
    DRIVE,
    GATEWAY,
    PROGRAM,
    CRAFTER;

    String key() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** A paste result: a {@code message.factorycore.label_tool.*} key and its arguments. */
  public record Outcome(String key, Object... arguments) {}

  private DeviceCopy() {}

  /** The kind this device is copied as, or null when it is not one of these devices. */
  static Kind kind(BlockEntity entity) {
    if (entity instanceof BufferedInterfaceEntity) return Kind.BUFFER;
    if (entity instanceof SignalInterfaceEntity) return Kind.SIGNAL;
    if (entity instanceof StorageDriveEntity) return Kind.DRIVE;
    if (entity instanceof NetworkNodeEntity node && node.kind() == NodeRegistry.Kind.GATEWAY)
      return Kind.GATEWAY;
    if (entity instanceof ProgramControllerEntity) return Kind.PROGRAM;
    if (entity instanceof CrafterEntity) return Kind.CRAFTER;
    return null;
  }

  /** The kind a copy holds, or null for a port copy or none. */
  static Kind kind(CompoundTag copy) {
    if (!copy.contains(DEVICE, Tag.TAG_STRING)) return null;
    for (var kind : Kind.values()) if (kind.key().equals(copy.getString(DEVICE))) return kind;
    return null;
  }

  private static NodeRegistry.Position where(BlockEntity entity) {
    return Positions.of(entity.getLevel(), entity.getBlockPos());
  }

  private static boolean allows(
      ServerPlayer player, BlockEntity entity, NetworkPermissions.Permission permission) {
    return WorldNetworks.get(player.server).allows(Actors.of(player), where(entity), permission);
  }

  /** The device's settings as a copy, without a permission check (also the paste fingerprint). */
  static CompoundTag settings(BlockEntity entity) {
    var kind = kind(entity);
    if (kind == null) throw new IllegalArgumentException("Not a copyable device");
    var copy = new CompoundTag();
    copy.putInt("Schema", 1);
    copy.putString(DEVICE, kind.key());
    switch (kind) {
      case BUFFER -> {
        var buffer = (BufferedInterfaceEntity) entity;
        var saved = DeviceSavedData.get(buffer.getLevel().getServer());
        var deployment =
            buffer.handle() == null ? null : saved.buffers().deployment(buffer.handle().id());
        copy.putString(
            "Mode", (deployment == null ? BufferRegistry.Mode.PAUSED : deployment.mode()).name());
        copy.put(
            "Policy",
            BufferPolicyCodec.encode(
                deployment == null ? BufferPolicy.DEFAULT : deployment.policy()));
        copy.putString("Tier", buffer.tier().name());
      }
      case SIGNAL -> {
        var signal = (SignalInterfaceEntity) entity;
        var rule = signal.rule();
        copy.putString("Condition", rule.condition().name());
        copy.putString("Compare", rule.compare().name());
        copy.putLong("Threshold", rule.threshold());
        copy.putString("Output", rule.output().name());
        if (signal.watch() != null) copy.put("Watch", FilterCodec.encode(signal.watch()));
        copy.putString("Label", signal.label());
      }
      case DRIVE -> copy.putInt("Priority", ((StorageDriveEntity) entity).priority());
      case GATEWAY -> {
        var gateway = (NetworkNodeEntity) entity;
        var saved = DeviceSavedData.get(gateway.getLevel().getServer());
        copy.putInt("Policy", saved.nodes().definition(gateway.handle()).policy().mask());
      }
      case PROGRAM -> copy.putString("Draft", ((ProgramControllerEntity) entity).draft());
      case CRAFTER -> {
        var crafter = (CrafterEntity) entity;
        var registries = crafter.getLevel().registryAccess();
        var patterns = new ListTag();
        for (int slot = 0; slot < crafter.patterns().getSlots(); slot++) {
          var pattern = crafter.patterns().getStackInSlot(slot);
          if (!pattern.isEmpty()) patterns.add(pattern.save(registries));
        }
        copy.put("Patterns", patterns);
      }
    }
    return copy;
  }

  /** A copy for {@code player}, or null without VIEW where the device is. */
  public static CompoundTag copy(ServerPlayer player, BlockEntity entity) {
    return allows(player, entity, NetworkPermissions.Permission.VIEW) ? settings(entity) : null;
  }

  /** A change fingerprint for the confirm-once check: the device's settings as copied. */
  static long fingerprint(BlockEntity entity) {
    return settings(entity).hashCode();
  }

  /**
   * Installs throughput upgrades from {@code player}'s inventory, one step at a time (each step is
   * the next tier's item), until {@code device} reaches {@code wanted}. Returns how many steps were
   * left for want of an item or permission.
   */
  static int installUpgrades(
      ServerPlayer player, ThroughputUpgradeable device, ThroughputTier wanted) {
    while (device.tier().ordinal() < wanted.ordinal()) {
      var step = ThroughputTier.values()[device.tier().ordinal() + 1];
      var inventory = player.getInventory();
      ItemStack item = ItemStack.EMPTY;
      for (int slot = 0; slot < inventory.getContainerSize() && item.isEmpty(); slot++) {
        var stack = inventory.getItem(slot);
        if (stack.getItem() instanceof ThroughputUpgradeItem upgrade && upgrade.tier() == step)
          item = stack;
      }
      if (item.isEmpty() || !device.upgrade(Actors.of(player), step))
        return wanted.ordinal() - device.tier().ordinal();
      if (!player.getAbilities().instabuild) item.shrink(1);
    }
    return 0;
  }

  /** Pastes {@code copy} onto {@code entity}; the kinds must match. */
  public static Outcome paste(ServerPlayer player, CompoundTag copy, BlockEntity entity) {
    var kind = kind(entity);
    if (kind == null || kind != kind(copy)) return new Outcome("paste_mismatch");
    if (copy.getInt("Schema") != 1) return new Outcome("invalid");
    var permission =
        kind == Kind.GATEWAY
            ? NetworkPermissions.Permission.MANAGE
            : NetworkPermissions.Permission.EDIT;
    if (!allows(player, entity, permission)) return new Outcome("denied");
    var actor = Actors.of(player);
    switch (kind) {
      case BUFFER -> {
        var buffer = (BufferedInterfaceEntity) entity;
        int missing =
            installUpgrades(player, buffer, ThroughputTier.valueOf(copy.getString("Tier")));
        var copied = BufferPolicyCodec.decode(copy.getCompound("Policy"));
        var policy = copied;
        if (!policy.fits(buffer.tier()))
          policy =
              new BufferPolicy(
                  copied.filter(),
                  Math.min(copied.rate(), buffer.tier().bufferRate()),
                  copied.target());
        try {
          buffer.configure(actor, BufferRegistry.Mode.valueOf(copy.getString("Mode")), policy);
        } catch (BufferedInterfaceEntity.PermissionDenied denied) {
          return new Outcome("denied");
        }
        if (missing > 0) return new Outcome("pasted_upgrades_missing");
        return new Outcome(policy.rate() < copied.rate() ? "pasted_rate" : "pasted");
      }
      case SIGNAL -> {
        var signal = (SignalInterfaceEntity) entity;
        var rule =
            new SignalRule(
                SignalRule.Condition.valueOf(copy.getString("Condition")),
                SignalRule.Compare.valueOf(copy.getString("Compare")),
                copy.getLong("Threshold"),
                SignalRule.Output.valueOf(copy.getString("Output")));
        if (copy.contains("Watch", Tag.TAG_COMPOUND))
          signal.configure(
              player.getUUID(), rule, FilterCodec.decode(copy.getCompound("Watch"), "Signal"));
        else if (copy.contains("Resource", Tag.TAG_COMPOUND)) {
          // A label copied before Q24 names one exact resource.
          var key = copy.getCompound("Resource");
          signal.configure(
              player.getUUID(),
              rule,
              new ResourceKey(
                  ResourceKind.valueOf(key.getString("Kind")),
                  key.getString("Id"),
                  key.getByteArray("Components")));
        } else signal.configure(player.getUUID(), rule, (ResourceKey) null);
        signal.label(copy.getString("Label"));
        return new Outcome("pasted");
      }
      case DRIVE -> {
        ((StorageDriveEntity) entity).setPriority(copy.getInt("Priority"));
        return new Outcome("pasted");
      }
      case GATEWAY -> {
        var gateway = (NetworkNodeEntity) entity;
        var saved = DeviceSavedData.get(player.server);
        if (saved.nodes().setPolicy(gateway.handle(), GatewayPolicy.fromMask(copy.getInt("Policy")))
            != NodeRegistry.Failure.OK) return new Outcome("invalid");
        WorldNetworks.changed(gateway.getLevel(), gateway.getBlockPos());
        return new Outcome("pasted");
      }
      case PROGRAM -> {
        ((ProgramControllerEntity) entity).draft(copy.getString("Draft"));
        return new Outcome("pasted_draft");
      }
      case CRAFTER -> {
        return pastePatterns(
            player, (CrafterEntity) entity, copy.getList("Patterns", Tag.TAG_COMPOUND));
      }
    }
    return new Outcome("invalid");
  }

  /**
   * Fills empty pattern slots with copies of the patterns the target lacks, each using one Blank
   * Pattern from the inventory. Counts patterns left out for want of a blank.
   */
  private static Outcome pastePatterns(
      ServerPlayer player, CrafterEntity crafter, ListTag patterns) {
    var registries = player.serverLevel().registryAccess();
    var slots = crafter.patterns();
    int placed = 0, missing = 0;
    for (var tag : patterns) {
      var pattern = ItemStack.parse(registries, tag).orElse(ItemStack.EMPTY);
      if (pattern.isEmpty()) continue;
      boolean present = false;
      int empty = -1;
      for (int slot = 0; slot < slots.getSlots(); slot++) {
        var held = slots.getStackInSlot(slot);
        if (ItemStack.isSameItemSameComponents(held, pattern)) present = true;
        if (held.isEmpty() && empty < 0) empty = slot;
      }
      if (present || empty < 0) continue;
      if (!player.getAbilities().instabuild && !takeBlank(player)) {
        missing++;
        continue;
      }
      slots.setStackInSlot(empty, pattern.copyWithCount(1));
      placed++;
    }
    return missing > 0
        ? new Outcome("pasted_patterns_missing", placed, missing)
        : new Outcome("pasted_patterns", placed);
  }

  private static boolean takeBlank(ServerPlayer player) {
    var inventory = player.getInventory();
    for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
      var stack = inventory.getItem(slot);
      if (stack.is(CraftingContent.BLANK_PATTERN.get())) {
        stack.shrink(1);
        return true;
      }
    }
    return false;
  }
}
