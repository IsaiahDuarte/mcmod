package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WiredMember;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Wired holder of up to nine pattern items for its segment. It owns no network resources and has no
 * ownership lease: copying the block copies only ordinary pattern items, and duplicate pattern IDs
 * are deduplicated by the pattern index. Unsupported saved data is retained unchanged.
 */
public abstract class PatternHolderEntity extends BlockEntity implements WiredMember {
  public static final int SLOTS = 9;
  static final int SCHEMA = 1;

  /** Longest label, as for Signal and Machine Interfaces. */
  public static final int MAX_LABEL = 64;

  private boolean hidden;

  private CompoundTag unsupported;
  private String label = "";

  private final ItemStackHandler patterns =
      new ItemStackHandler(SLOTS) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
          return unsupported == null && accepts(stack);
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

  protected PatternHolderEntity(BlockEntityType<?> type, BlockPos position, BlockState state) {
    super(type, position, state);
  }

  /** Which encoded pattern items this holder accepts. */
  protected abstract boolean accepts(ItemStack stack);

  /** Nonempty when saved data is from an unsupported schema and is being retained unchanged. */
  public String diagnostic() {
    return unsupported == null ? "" : "Unsupported pattern holder data; retained unchanged";
  }

  public ItemStackHandler patterns() {
    return patterns;
  }

  /**
   * Whether {@code player} may take or insert patterns here: EDIT where this holder is. The Crafter
   * screen (Q2) and the terminal's Patterns view (Q3) both ask this before each change.
   */
  public boolean editableBy(ServerPlayer player) {
    return WorldNetworks.get(player.server)
        .allows(Actors.of(player), origin(), NetworkPermissions.Permission.EDIT);
  }

  /** Q107: the Label Tool's name for this holder, or "" (then screens name it by position). */
  public String label() {
    return label;
  }

  /** Sets the label (EDIT checked by the caller); control characters are removed. */
  public void label(String value) {
    var clean = clean(value);
    if (clean.length() > MAX_LABEL) throw new IllegalArgumentException("Label longer than 64");
    label = clean;
    setChanged();
  }

  private static String clean(String value) {
    return value.replaceAll("[\\p{Cntrl}§]", "").strip();
  }

  public int patternCount() {
    int count = 0;
    for (int slot = 0; slot < SLOTS; slot++) if (!patterns.getStackInSlot(slot).isEmpty()) count++;
    return count;
  }

  public NodeRegistry.Position origin() {
    return Positions.of(level, worldPosition);
  }

  /** True while this exact holder is the loaded block entity at its position. */
  public boolean current() {
    if (isRemoved() || !(level instanceof ServerLevel server)) return false;
    var chunk = Loaded.chunk(server, worldPosition);
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
    if (!label.isEmpty()) tag.putString("Label", label);
    if (hidden) tag.putBoolean("Hidden", true);
  }

  /**
   * Q100: hidden from the terminal's Patterns view unless its filter is All. Saved with the
   * Crafter; it does not change what jobs may use.
   */
  public boolean hidden() {
    return hidden;
  }

  public void hidden(boolean value) {
    hidden = value;
    setChanged();
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    unsupported = null;
    label = "";
    hidden = false;
    if (!tag.contains("Schema")) return;
    var saved = tag.getCompound("Patterns");
    if (tag.getInt("Schema") != SCHEMA || saved.getInt("Size") != SLOTS) {
      unsupported = tag.copy();
      for (var key : List.of("id", "x", "y", "z", "components")) unsupported.remove(key);
      return;
    }
    patterns.deserializeNBT(registries, saved);
    var savedLabel = clean(tag.getString("Label"));
    label = savedLabel.length() > MAX_LABEL ? "" : savedLabel;
    hidden = tag.getBoolean("Hidden");
  }

  @Override
  protected void applyImplicitComponents(DataComponentInput input) {
    super.applyImplicitComponents(input);
    var retained = input.get(CraftingContent.RETAINED.get());
    if (retained != null) unsupported = retained.copyTag();
    // Q107: the label travels as the item's name, so an anvil-named Crafter is labelled too.
    var name = input.get(DataComponents.CUSTOM_NAME);
    if (name != null && unsupported == null) {
      var named = clean(name.getString());
      label = named.length() > MAX_LABEL ? named.substring(0, MAX_LABEL) : named;
    }
  }

  @Override
  protected void collectImplicitComponents(DataComponentMap.Builder components) {
    super.collectImplicitComponents(components);
    if (unsupported != null)
      components.set(CraftingContent.RETAINED.get(), CustomData.of(unsupported));
    else if (!label.isEmpty()) components.set(DataComponents.CUSTOM_NAME, Component.literal(label));
  }

  @Override
  public Vertex wireVertex() {
    return new Vertex(
        null, "crafter", NetworkTopology.Role.CRAFTER, current() && diagnostic().isEmpty());
  }
}
