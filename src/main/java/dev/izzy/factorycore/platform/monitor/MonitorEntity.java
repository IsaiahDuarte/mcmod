package dev.izzy.factorycore.platform.monitor;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.WiredMember;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.WorldStorageRoutes;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Q28 (M28): a Storage Monitor shows how much of one item the network stores, on its face. Use it
 * with an item to choose what it shows (Edit); with an empty hand to take a stack (Withdraw); with
 * the shown item to store the held stack, or, used again within half a second, every matching stack
 * in the inventory (Deposit). A take that finds none requests a craft of one stack instead (Craft).
 * Sneak-use with an empty hand locks or unlocks what it shows (Edit). Every move goes through the
 * shared player-transfer routes, under the user's own grants. The count is read once a second by
 * {@link WorldMonitors} under the grants of whoever chose the item.
 */
public final class MonitorEntity extends BlockEntity implements WiredMember {
  static final String FIELD = "FactoryCoreMonitor";

  /** Ticks within which a second use with the shown item stores every matching stack. */
  public static final int DOUBLE_USE = 10;

  private UUID owner;
  private ResourceKey shown;
  private boolean locked;
  private long count = -1;
  private UUID lastUser;
  private long lastUse = Long.MIN_VALUE;

  public MonitorEntity(BlockPos position, BlockState state) {
    super(MonitorContent.ENTITY.get(), position, state);
  }

  public UUID owner() {
    return owner;
  }

  /** The item shown, or null. */
  public ResourceKey shown() {
    return shown;
  }

  public boolean locked() {
    return locked;
  }

  /** The stored amount last read, or -1 while unknown (unset, offline or denied). */
  public long count() {
    return count;
  }

  public NodeRegistry.Position origin() {
    return Positions.of((ServerLevel) level, worldPosition);
  }

  public boolean current() {
    if (isRemoved() || !(level instanceof ServerLevel server)) return false;
    var chunk = Loaded.chunk(server, worldPosition);
    return chunk != null
        && chunk.getBlockEntity(worldPosition, LevelChunk.EntityCreationType.CHECK) == this;
  }

  /** The placer may choose what it shows until someone else does. */
  public void place(UUID owner) {
    this.owner = owner;
    setChanged();
  }

  /** Called by {@link WorldMonitors} with a fresh reading; clients redraw only on a change. */
  void count(long value) {
    if (value == count) return;
    count = value;
    sync();
  }

  /** A use on the monitor's block; returns false to let the game handle the item instead. */
  public boolean use(ServerPlayer player, ItemStack held) {
    var networks = WorldNetworks.get(player.server);
    var origin = origin();
    if (networks.membership(origin).status() != NetworkTopology.Status.VALID) {
      tell(player, "offline");
      return true;
    }
    var actor = Actors.of(player);
    if (held.isEmpty() && player.isShiftKeyDown()) {
      if (!networks.allows(actor, origin, NetworkPermissions.Permission.EDIT)) {
        tell(player, "denied");
        return true;
      }
      locked = !locked;
      sync();
      tell(player, locked ? "locked" : "unlocked");
      return true;
    }
    if (held.isEmpty()) {
      if (shown == null) tell(player, "unset");
      else take(player, actor);
      return true;
    }
    ResourceKey key;
    try {
      key = new PlatformResourceCodec(player.registryAccess()).itemKey(held);
    } catch (IllegalArgumentException unsupported) {
      return false;
    }
    if (key.equals(shown)) {
      long now = player.serverLevel().getGameTime();
      boolean all = player.getUUID().equals(lastUser) && now - lastUse <= DOUBLE_USE;
      lastUser = player.getUUID();
      lastUse = now;
      deposit(player, actor, all);
      return true;
    }
    if (locked) {
      tell(player, "locked_other");
      return true;
    }
    if (!networks.allows(actor, origin, NetworkPermissions.Permission.EDIT)) {
      tell(player, "denied");
      return true;
    }
    shown = key;
    owner = player.getUUID();
    count = -1;
    sync();
    WorldMonitors.get(player.server).dueNow(this);
    return true;
  }

  /** A stack of the shown item into the selected hotbar slot if empty, else the first empty one. */
  private void take(ServerPlayer player, NetworkPermissions.Actor actor) {
    var inventory = player.getInventory();
    int slot =
        Inventory.isHotbarSlot(inventory.selected) && inventory.getSelected().isEmpty()
            ? inventory.selected
            : inventory.getFreeSlot();
    if (slot < 0) {
      tell(player, "inventory_full");
      return;
    }
    var codec = new PlatformResourceCodec(player.registryAccess());
    int stack = codec.itemStack(shown, 1).getMaxStackSize();
    var transfers = WorldNetworks.get(player.server).transfers();
    var admission =
        transfers
            .routes()
            .priority(
                UUID.randomUUID(),
                actor,
                origin(),
                transfers.inventory(player, slot),
                shown,
                stack,
                WorldStorageRoutes.Direction.FROM_STORAGE,
                result -> {
                  if (result.move().reason() != OperationResult.Reason.OK && !player.isRemoved())
                    refused(player, actor, result.move().reason(), stack);
                  WorldMonitors.get(player.server).dueNow(this);
                });
    if (admission.rejection() != OperationResult.Reason.OK)
      refused(player, actor, admission.rejection(), stack);
  }

  /** A take that found none asks the network to craft one stack; other refusals are only told. */
  private void refused(
      ServerPlayer player, NetworkPermissions.Actor actor, OperationResult.Reason why, int stack) {
    if (why != OperationResult.Reason.SHORTAGE || shown == null || !current()) {
      tell(player, reason(why));
      return;
    }
    var submission = WorldCraftingJobs.get(player.server).submit(actor, origin(), shown, stack);
    if (submission.job() != null)
      player.displayClientMessage(
          Component.translatable("message.factorycore.monitor.crafting", stack), true);
    else
      player.displayClientMessage(
          Component.translatable(
              "message.factorycore.monitor.not_crafted",
              Component.translatable(
                  "gui.factorycore.terminal.job.status." + submission.reason().key())),
          true);
  }

  /** The held stack, or with {@code all} every main-inventory stack of the shown item. */
  private void deposit(ServerPlayer player, NetworkPermissions.Actor actor, boolean all) {
    var inventory = player.getInventory();
    var codec = new PlatformResourceCodec(player.registryAccess());
    var transfers = WorldNetworks.get(player.server).transfers();
    for (int slot = 0; slot < inventory.items.size(); slot++) {
      if (!all && slot != inventory.selected) continue;
      var stack = inventory.items.get(slot);
      if (stack.isEmpty()) continue;
      ResourceKey key;
      try {
        key = codec.itemKey(stack);
      } catch (IllegalArgumentException unsupported) {
        continue;
      }
      if (!key.equals(shown)) continue;
      var admission =
          transfers
              .routes()
              .priority(
                  UUID.randomUUID(),
                  actor,
                  origin(),
                  transfers.inventory(player, slot),
                  key,
                  stack.getCount(),
                  WorldStorageRoutes.Direction.TO_STORAGE,
                  result -> {
                    if (result.move().reason() != OperationResult.Reason.OK && !player.isRemoved())
                      tell(player, reason(result.move().reason()));
                    WorldMonitors.get(player.server).dueNow(this);
                  });
      if (admission.rejection() != OperationResult.Reason.OK) {
        tell(player, reason(admission.rejection()));
        return;
      }
    }
  }

  private static String reason(OperationResult.Reason reason) {
    return switch (reason) {
      case DENIED -> "denied";
      case FULL, CATALOG_LIMIT -> "full";
      case SHORTAGE -> "none";
      case OFFLINE -> "offline";
      default -> "busy";
    };
  }

  private static void tell(ServerPlayer player, String key) {
    player.displayClientMessage(Component.translatable("message.factorycore.monitor." + key), true);
  }

  private void sync() {
    setChanged();
    if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel server) {
      WorldNetworks.changed(level, worldPosition);
      WorldMonitors.get(server.getServer()).loaded(this);
    }
  }

  @Override
  public void setRemoved() {
    if (level instanceof ServerLevel server) {
      WorldNetworks.changed(level, worldPosition);
      WorldMonitors.get(server.getServer()).removed(this);
    }
    super.setRemoved();
  }

  @Override
  public Vertex wireVertex() {
    return new Vertex(
        null,
        "storage_monitor",
        dev.izzy.factorycore.core.network.NetworkTopology.Role.TERMINAL,
        current());
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    tag.put(FIELD, settings());
  }

  private CompoundTag settings() {
    var data = new CompoundTag();
    data.putInt("Schema", 1);
    if (owner != null) data.putUUID("Owner", owner);
    if (shown != null) {
      var key = new CompoundTag();
      key.putString("Id", shown.registryId());
      key.putByteArray("Components", shown.components());
      data.put("Shown", key);
    }
    data.putBoolean("Locked", locked);
    return data;
  }

  /** Malformed or future data shows nothing rather than guessing; a monitor holds no resources. */
  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    owner = null;
    shown = null;
    locked = false;
    if (!tag.contains(FIELD, Tag.TAG_COMPOUND)) return;
    var data = tag.getCompound(FIELD);
    if (data.getInt("Schema") != 1) return;
    try {
      owner = data.hasUUID("Owner") ? data.getUUID("Owner") : null;
      if (data.contains("Shown", Tag.TAG_COMPOUND)) {
        var key = data.getCompound("Shown");
        shown =
            new ResourceKey(ResourceKind.ITEM, key.getString("Id"), key.getByteArray("Components"));
      }
      locked = data.getBoolean("Locked");
      // Only the update packet carries a reading; a saved world reads it again.
      count = data.contains("Count", Tag.TAG_LONG) ? data.getLong("Count") : -1;
    } catch (IllegalArgumentException malformed) {
      owner = null;
      shown = null;
      locked = false;
    }
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    var tag = new CompoundTag();
    var data = settings();
    data.putLong("Count", count);
    tag.put(FIELD, data);
    return tag;
  }

  @Override
  public Packet<ClientGamePacketListener> getUpdatePacket() {
    return ClientboundBlockEntityDataPacket.create(this);
  }
}
