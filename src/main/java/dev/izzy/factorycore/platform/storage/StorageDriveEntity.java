package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.network.WiredMember;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/** Four physical lease items; stock and reservations are never copied into chunk NBT. */
public final class StorageDriveEntity extends BlockEntity implements WiredMember, CanonicalStorage {
  public static final String RECOVERY = "FactoryCoreDriveRecovery";
  private static final String REJECTED = "RejectedCellData";
  private final ItemStack[] cells = {
    ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY
  };
  private CompoundTag rejected;
  private String diagnostic = "";

  public StorageDriveEntity(BlockPos position, BlockState state) {
    super(StorageContent.DRIVE_ENTITY.get(), position, state);
  }

  /**
   * Mirrors bay occupancy to the block state for rendering, without neighbor or shape updates. A
   * drive being replaced or removed no longer has its own block at this position and is skipped.
   */
  @Override
  public void setChanged() {
    super.setChanged();
    syncBays();
  }

  private void syncBays() {
    if (level == null || level.isClientSide() || isRemoved()) return;
    var state = level.getBlockState(worldPosition);
    if (!(state.getBlock() instanceof StorageDriveBlock)) return;
    var next = state;
    for (int slot = 0; slot < cells.length; slot++)
      next = next.setValue(StorageDriveBlock.BAYS.get(slot), !cells[slot].isEmpty());
    if (next != state)
      level.setBlock(worldPosition, next, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
  }

  public String diagnostic() {
    return diagnostic;
  }

  public boolean needsDataRecovery() {
    return rejected != null;
  }

  public ItemStack cell(int slot) {
    checkSlot(slot);
    return cells[slot].copy();
  }

  // ServerLevel is borrowed from Minecraft; this device must never close the world.
  public DeviceRegistry.Location location(int slot) {
    checkSlot(slot);
    if (!(level instanceof ServerLevel server))
      throw new IllegalStateException("Drive mutation requires server level");
    return new DeviceRegistry.Location(
        server.dimension().location().toString(), worldPosition.asLong(), slot);
  }

  public int installCell(ItemStack input) {
    requireUsable();
    for (int slot = 0; slot < cells.length; slot++) {
      if (cells[slot].isEmpty()) {
        cells[slot] = CellOwnership.install(input, registry(), location(slot));
        setChanged();
        networkingChanged();
        return slot;
      }
    }
    throw new IllegalStateException("All four drive slots are occupied");
  }

  public ItemStack removeCell(int slot) {
    requireUsable();
    checkSlot(slot);
    if (cells[slot].isEmpty()) return ItemStack.EMPTY;
    var handle = CellHandleData.read(cells[slot]);
    if (handle == null) throw new IllegalStateException("Placed cell has no lease");
    var detached = registry().detach(handle, location(slot));
    if (detached.failure() != DeviceRegistry.Failure.OK)
      throw new IllegalStateException("Cell removal: " + detached.failure());
    ItemStack portable = cells[slot].copy();
    CellHandleData.write(portable, detached.handle());
    cells[slot] = ItemStack.EMPTY;
    setChanged();
    networkingChanged();
    return portable;
  }

  /** True when any bay holds a cell whose lease names {@code backing}. */
  private boolean holds(UUID backing) {
    for (var stack : cells) {
      var held = CellHandleData.read(stack);
      if (held != null && held.backing().equals(backing)) return true;
    }
    return false;
  }

  /** Operator recovery adapter. Never relocates a backing or resolves pending transfer evidence. */
  public Path repairCellConflict(DeviceRegistry.Handle expected) throws IOException {
    if (!(level instanceof ServerLevel server)
        || rejected != null
        || !LeaseRecovery.loadedHere(this))
      throw new IllegalStateException("Canonical drive must be loaded and readable");
    var saved = DeviceSavedData.get(server.getServer());
    var registry = saved.registry();
    var owner = registry.ownership(expected.backing());
    if (owner == null
        || !owner.handle().equals(expected)
        || !owner.conflict()
        || expected.generation() == Long.MAX_VALUE
        || saved.transfers().unresolved(expected.backing()) != null)
      throw new IllegalStateException("Expected conflict cannot be repaired");
    var retained = owner.definition().location();
    int slot = retained.slot();
    checkSlot(slot);
    var stack = cells[slot];
    if (!retained.equals(location(slot))
        || !expected.equals(CellHandleData.read(stack))
        || stack.getCount() != 1
        || !(stack.getItem() instanceof CellItem item)
        || item.tier().kind() != owner.definition().kind()
        || item.tier().capacity() != owner.definition().capacity()
        || item.tier().infinite() != owner.definition().infinite())
      throw new IllegalStateException("Exact canonical cell reference required");
    var replacement = stack.copy();
    CellHandleData.write(
        replacement,
        new DeviceRegistry.Handle(expected.world(), expected.backing(), expected.generation() + 1));
    var path =
        DeviceRecoveryAudit.preserve(
            server.getServer().getWorldPath(LevelResource.ROOT),
            expected.world(),
            registry.evidence(expected.backing()),
            stack.save(server.registryAccess()));
    var repaired = registry.repairConflict(expected, retained);
    if (repaired.failure() != DeviceRegistry.Failure.OK)
      throw new IllegalStateException("Conflict repair refused: " + repaired.failure());
    cells[slot] = replacement;
    setChanged();
    diagnostic = "";
    onLoad(); // Recheck every slot; other faulty references keep their diagnostic.
    return path;
  }

  /**
   * Independent-save reconciliation adapter: adopts the mismatched reference in {@code slot} of
   * this loaded drive as the canonical holder of {@code expected}. Refuses unless the old canonical
   * holder is loaded and holds no reference to the backing, the slot holds exactly one
   * matching-tier cell of the same world/backing with a different generation, and no transfer is
   * unresolved. The single ledger moves; quantities, claims and other copies are never touched.
   */
  public Path adoptCell(DeviceRegistry.Handle expected, int slot) throws IOException {
    checkSlot(slot);
    if (!(level instanceof ServerLevel server)
        || rejected != null
        || !LeaseRecovery.loadedHere(this))
      throw new IllegalStateException("Adopting drive must be loaded and readable");
    var saved = DeviceSavedData.get(server.getServer());
    var registry = saved.registry();
    var owner = registry.ownership(expected.backing());
    if (owner == null
        || !owner.handle().equals(expected)
        || owner.definition().location() == null
        || expected.generation() == Long.MAX_VALUE
        || saved.transfers().unresolved(expected.backing()) != null)
      throw new IllegalStateException("Canonical lease cannot be adopted");
    var stack = cells[slot];
    var physical = CellHandleData.read(stack);
    if (physical == null
        || !physical.world().equals(expected.world())
        || !physical.backing().equals(expected.backing())
        || physical.generation() == expected.generation()
        || stack.getCount() != 1
        || !(stack.getItem() instanceof CellItem item)
        || item.tier().kind() != owner.definition().kind()
        || item.tier().capacity() != owner.definition().capacity()
        || item.tier().infinite() != owner.definition().infinite())
      throw new IllegalStateException("Exact mismatched cell reference required");
    var old =
        LeaseRecovery.releasedOldHolder(
            server.getServer(),
            owner.definition().location(),
            entity ->
                entity instanceof StorageDriveEntity drive && drive.holds(expected.backing()));
    var path =
        DeviceRecoveryAudit.preserveAdoption(
            server.getServer().getWorldPath(LevelResource.ROOT),
            expected.world(),
            registry.evidence(expected.backing()),
            location(slot),
            stack.save(server.registryAccess()));
    var adopted = registry.adopt(expected, location(slot));
    if (adopted.failure() != DeviceRegistry.Failure.OK)
      throw new IllegalStateException("Adoption refused: " + adopted.failure());
    var replacement = stack.copy();
    CellHandleData.write(replacement, adopted.handle());
    cells[slot] = replacement;
    setChanged();
    diagnostic = "";
    old.changed();
    onLoad(); // Recheck every slot; other faulty references keep their diagnostic.
    return path;
  }

  public List<ItemStack> ejectForBreak() {
    if (!(level instanceof ServerLevel)) return List.of();
    var dropped = new ArrayList<ItemStack>(4);
    if (rejected != null) {
      dropped.add(recoveryStack(rejected));
      return dropped;
    }
    for (int slot = 0; slot < cells.length; slot++) {
      if (!cells[slot].isEmpty()) {
        try {
          dropped.add(removeCell(slot));
        } catch (IllegalArgumentException | IllegalStateException failure) {
          // Keep the exact reference as an inert item; canonical stock remains unavailable in the
          // registry.
          deactivate(slot);
          ItemStack retained = cells[slot].copy();
          retained.set(
              DataComponents.CUSTOM_NAME, Component.translatable("item.factorycore.cell_recovery"));
          dropped.add(retained);
          cells[slot] = ItemStack.EMPTY;
          diagnostic = failure.getMessage();
        }
      }
    }
    return List.copyOf(dropped);
  }

  private ItemStack recoveryStack(CompoundTag original) {
    ItemStack stack = new ItemStack(StorageContent.DRIVE_ITEM.get());
    stack.set(
        DataComponents.CUSTOM_NAME, Component.translatable("item.factorycore.drive_recovery"));
    CustomData.update(
        DataComponents.CUSTOM_DATA, stack, data -> data.put(RECOVERY, original.copy()));
    return stack;
  }

  public void restoreRecovery(ItemStack stack, HolderLookup.Provider registries) {
    var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    if (data.contains(RECOVERY)) {
      if (data.contains(RECOVERY, Tag.TAG_COMPOUND)) {
        loadAdditional(data.getCompound(RECOVERY), registries);
      } else {
        rejected = data.copy();
        diagnostic = "Malformed drive recovery envelope";
      }
      onLoad();
      setChanged();
      networkingChanged();
    }
  }

  @Override
  public void onLoad() {
    super.onLoad();
    networkingChanged();
    // Worlds saved before bay lamps existed: publish occupancy after, not during, chunk loading.
    if (level instanceof ServerLevel server)
      server.getServer().tell(new TickTask(server.getServer().getTickCount(), this::syncBays));
    if (!(level instanceof ServerLevel) || rejected != null) return;
    for (int slot = 0; slot < cells.length; slot++) {
      if (!cells[slot].isEmpty()) {
        try {
          CellOwnership.rebind(cells[slot], registry(), location(slot));
        } catch (IllegalArgumentException | IllegalStateException failure) {
          diagnostic = failure.getMessage();
        }
      }
    }
  }

  @Override
  public void setRemoved() {
    networkingChanged();
    if (level instanceof ServerLevel && rejected == null) {
      for (int slot = 0; slot < cells.length; slot++) {
        if (!cells[slot].isEmpty()) {
          deactivate(slot);
        }
      }
    }
    super.setRemoved();
  }

  private void networkingChanged() {
    WorldNetworks.changed(level, worldPosition);
  }

  private void deactivate(int slot) {
    try {
      var handle = CellHandleData.read(cells[slot]);
      if (handle != null) registry().availability(handle, location(slot), false);
    } catch (IllegalArgumentException | IllegalStateException failure) {
      diagnostic = failure.getMessage();
    }
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (rejected != null) {
      tag.put(REJECTED, rejected.copy());
      return;
    }
    tag.putInt("CellSchema", 1);
    var records = new ListTag();
    for (int slot = 0; slot < cells.length; slot++) {
      if (!cells[slot].isEmpty()) {
        var entry = new CompoundTag();
        entry.putInt("Slot", slot);
        entry.put("Stack", cells[slot].save(registries));
        records.add(entry);
      }
    }
    tag.put("CellSlots", records);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    rejected = null;
    diagnostic = "";
    Arrays.fill(cells, ItemStack.EMPTY);
    if (tag.contains(REJECTED)) {
      rejected =
          tag.contains(REJECTED, Tag.TAG_COMPOUND) ? tag.getCompound(REJECTED).copy() : tag.copy();
      diagnostic = "Preserved drive data needs recovery";
      return;
    }
    if (!tag.contains("CellSchema") && !tag.contains("CellSlots")) return;
    try {
      if (!tag.contains("CellSchema", Tag.TAG_INT)
          || tag.getInt("CellSchema") != 1
          || !tag.contains("CellSlots", Tag.TAG_LIST)) {
        throw new IllegalArgumentException("Unsupported drive cell schema");
      }
      var records = tag.getList("CellSlots", Tag.TAG_COMPOUND);
      if (records.size() != ((ListTag) tag.get("CellSlots")).size() || records.size() > 4)
        throw new IllegalArgumentException("Invalid drive slots");
      for (int i = 0; i < records.size(); i++) {
        var entry = records.getCompound(i);
        if (!entry.contains("Slot", Tag.TAG_INT) || !entry.contains("Stack", Tag.TAG_COMPOUND))
          throw new IllegalArgumentException("Malformed cell slot");
        int slot = entry.getInt("Slot");
        checkSlot(slot);
        if (!cells[slot].isEmpty()) throw new IllegalArgumentException("Duplicate cell slot");
        ItemStack cell =
            ItemStack.parse(registries, entry.getCompound("Stack"))
                .orElseThrow(() -> new IllegalArgumentException("Invalid saved cell stack"));
        if (!(cell.getItem() instanceof CellItem)
            || cell.getCount() != 1
            || CellHandleData.read(cell) == null)
          throw new IllegalArgumentException("Invalid placed cell");
        cells[slot] = cell;
      }
    } catch (IllegalArgumentException | IllegalStateException failure) {
      Arrays.fill(cells, ItemStack.EMPTY);
      rejected = tag.copy();
      diagnostic = failure.getMessage();
    }
  }

  private DeviceRegistry registry() {
    if (!(level instanceof ServerLevel server))
      throw new IllegalStateException("Drive requires server level");
    return DeviceSavedData.get(server.getServer()).registry();
  }

  private void requireUsable() {
    if (rejected != null || !diagnostic.isEmpty())
      throw new IllegalStateException("Drive unavailable: " + diagnostic);
    if (!(level instanceof ServerLevel))
      throw new IllegalStateException("Drive requires server level");
  }

  private static void checkSlot(int slot) {
    if (slot < 0 || slot >= 4) throw new IllegalArgumentException("Drive slot must be 0..3");
  }

  @Override
  public Vertex wireVertex() {
    return new Vertex(
        null, "wire", NetworkTopology.Role.STORAGE, !isRemoved() && diagnostic().isEmpty());
  }
}
