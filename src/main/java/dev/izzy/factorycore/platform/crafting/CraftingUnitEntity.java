package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Pattern holder for its wired segment. It owns no network resources and has no ownership lease:
 * copying the block copies only ordinary pattern items, and duplicate pattern IDs are deduplicated
 * by the pattern index.
 */
@SuppressWarnings("PMD.CloseResource") // Minecraft owns every borrowed ServerLevel.
public final class CraftingUnitEntity extends BlockEntity {
  public static final int SLOTS = 9;
  static final int SCHEMA = 1;
  private CompoundTag unsupported;

  private final ItemStackHandler patterns =
      new ItemStackHandler(SLOTS) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
          return unsupported == null
              && stack.is(CraftingContent.CRAFTING_PATTERN.get())
              && stack.get(CraftingContent.PATTERN.get()) != null;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
          return unsupported == null ? super.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
          return 1;
        }

        @Override
        protected void onContentsChanged(int slot) {
          setChanged();
        }
      };

  public CraftingUnitEntity(BlockPos position, BlockState state) {
    super(CraftingContent.UNIT_ENTITY.get(), position, state);
  }

  /** Nonempty when saved data is from an unsupported schema and is being retained unchanged. */
  public String diagnostic() {
    return unsupported == null ? "" : "Unsupported crafting unit data; retained unchanged";
  }

  public ItemStackHandler patterns() {
    return patterns;
  }

  public int patternCount() {
    int count = 0;
    for (int slot = 0; slot < SLOTS; slot++) if (!patterns.getStackInSlot(slot).isEmpty()) count++;
    return count;
  }

  public NodeRegistry.Position origin() {
    return new NodeRegistry.Position(
        level.dimension().location().toString(), worldPosition.asLong());
  }

  /** True while this exact holder is the loaded block entity at its position. */
  public boolean current() {
    if (isRemoved() || !(level instanceof ServerLevel server)) return false;
    var chunk =
        server.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
    return chunk != null
        && chunk.getBlockEntity(worldPosition, LevelChunk.EntityCreationType.CHECK) == this;
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel server) WorldCrafting.get(server.getServer()).loaded(this);
    WorldNetworks.changed(level, worldPosition);
  }

  @Override
  public void setRemoved() {
    if (level instanceof ServerLevel server && WorldCrafting.exists(server.getServer()))
      WorldCrafting.get(server.getServer()).removed(this);
    WorldNetworks.changed(level, worldPosition);
    super.setRemoved();
  }

  @Override
  public void onChunkUnloaded() {
    if (level instanceof ServerLevel server && WorldCrafting.exists(server.getServer()))
      WorldCrafting.get(server.getServer()).removed(this);
    super.onChunkUnloaded();
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (unsupported != null) {
      for (var key : unsupported.getAllKeys()) tag.put(key, unsupported.get(key).copy());
      return;
    }
    tag.putInt("Schema", SCHEMA);
    tag.put("Patterns", patterns.serializeNBT(registries));
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    unsupported = null;
    if (!tag.contains("Schema")) return;
    var saved = tag.getCompound("Patterns");
    if (tag.getInt("Schema") != SCHEMA || saved.getInt("Size") != SLOTS) {
      unsupported = tag.copy();
      for (var key : java.util.List.of("id", "x", "y", "z", "components")) unsupported.remove(key);
      return;
    }
    patterns.deserializeNBT(registries, saved);
  }

  @Override
  protected void applyImplicitComponents(DataComponentInput input) {
    super.applyImplicitComponents(input);
    var retained = input.get(CraftingContent.RETAINED.get());
    if (retained != null) unsupported = retained.copyTag();
  }

  @Override
  protected void collectImplicitComponents(
      net.minecraft.core.component.DataComponentMap.Builder components) {
    super.collectImplicitComponents(components);
    if (unsupported != null)
      components.set(
          CraftingContent.RETAINED.get(),
          net.minecraft.world.item.component.CustomData.of(unsupported));
  }
}
