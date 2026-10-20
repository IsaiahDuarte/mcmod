package dev.izzy.factorycore.platform.power;

import dev.izzy.factorycore.core.storage.FuelGenerator;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Server-thread Fuel Generator holder. Slot 0 accepts furnace fuel from every face except down;
 * slot 1 holds container remainders and is extractable only from below. A future schema is kept
 * byte-for-byte and the device stays inert rather than resetting.
 */
public final class FuelGeneratorEntity extends BlockEntity {
  static final int SCHEMA = 1;
  public static final int FUEL = 0;
  public static final int REMAINDER = 1;

  private FuelGenerator generator = new FuelGenerator();
  private CompoundTag unsupported;
  private final EnumMap<Direction, BlockCapabilityCache<IEnergyStorage, Direction>> receivers =
      new EnumMap<>(Direction.class);

  private final ItemStackHandler slots =
      new ItemStackHandler(2) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
          return slot == FUEL && burnTime(stack) > 0;
        }

        @Override
        protected void onContentsChanged(int slot) {
          setChanged();
        }
      };

  private final IEnergyStorage energy =
      new IEnergyStorage() {
        @Override
        public int receiveEnergy(int amount, boolean simulate) {
          return 0;
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
          if (amount <= 0 || !active()) return 0;
          long moved = generator.extract(amount, simulate, level.getGameTime());
          if (!simulate && moved > 0) setChanged();
          return (int) moved;
        }

        @Override
        public int getEnergyStored() {
          return active() ? (int) generator.energy() : 0;
        }

        @Override
        public int getMaxEnergyStored() {
          return (int) FuelGenerator.CAPACITY;
        }

        @Override
        public boolean canExtract() {
          return active();
        }

        @Override
        public boolean canReceive() {
          return false;
        }
      };

  public FuelGeneratorEntity(BlockPos position, BlockState state) {
    super(FuelGeneratorContent.ENTITY.get(), position, state);
  }

  private static int burnTime(ItemStack stack) {
    return stack.isEmpty() ? 0 : stack.getBurnTime(RecipeType.SMELTING);
  }

  private boolean active() {
    return unsupported == null && level != null && !level.isClientSide() && !isRemoved();
  }

  public FuelGenerator generator() {
    return generator;
  }

  /** Nonempty when saved data is from an unsupported schema and is being retained unchanged. */
  public String diagnostic() {
    return unsupported == null ? "" : "Unsupported fuel generator schema; data retained";
  }

  public IEnergyStorage energy() {
    return energy;
  }

  /** Face-restricted view: down extracts remainders; other faces insert fuel only. */
  public IItemHandler items(Direction face) {
    return new IItemHandler() {
      private int slot() {
        return face == Direction.DOWN ? REMAINDER : FUEL;
      }

      @Override
      public int getSlots() {
        return 1;
      }

      @Override
      public ItemStack getStackInSlot(int index) {
        return index == 0 ? slots.getStackInSlot(slot()) : ItemStack.EMPTY;
      }

      @Override
      public ItemStack insertItem(int index, ItemStack stack, boolean simulate) {
        if (index != 0 || slot() != FUEL || unsupported != null) return stack;
        return slots.insertItem(FUEL, stack, simulate);
      }

      @Override
      public ItemStack extractItem(int index, int amount, boolean simulate) {
        if (index != 0 || slot() != REMAINDER || unsupported != null) return ItemStack.EMPTY;
        return slots.extractItem(REMAINDER, amount, simulate);
      }

      @Override
      public int getSlotLimit(int index) {
        return slots.getSlotLimit(slot());
      }

      @Override
      public boolean isItemValid(int index, ItemStack stack) {
        return index == 0 && slot() == FUEL && slots.isItemValid(FUEL, stack);
      }
    };
  }

  /** Unrestricted owner view for player interaction, drops and tests. */
  public ItemStackHandler slots() {
    return slots;
  }

  /** Why no FE is being produced, including a full remainder slot. */
  public String blockedReason() {
    if (unsupported != null) return "unsupported";
    return switch (generator.status()) {
      case BURNING -> "";
      case FULL -> "full";
      case NO_FUEL -> {
        if (!generator.needsFuel()) yield "full";
        var fuel = slots.getStackInSlot(FUEL);
        if (fuel.isEmpty()) yield "no_fuel";
        yield remainderFits(fuel) ? "" : "remainder_full";
      }
    };
  }

  private boolean remainderFits(ItemStack fuel) {
    if (!fuel.hasCraftingRemainingItem()) return true;
    var remainder = fuel.getCraftingRemainingItem();
    var held = slots.getStackInSlot(REMAINDER);
    if (held.isEmpty()) return true;
    return ItemStack.isSameItemSameComponents(held, remainder)
        && held.getCount() + remainder.getCount()
            <= Math.min(held.getMaxStackSize(), slots.getSlotLimit(REMAINDER));
  }

  /** Consumes at most one fuel item, burns one tick, then pushes at most 80 FE to neighbors. */
  void serverTick() {
    if (!active()) return;
    boolean changed = false;
    var fuel = slots.getStackInSlot(FUEL);
    int burn = burnTime(fuel);
    if (generator.needsFuel() && burn > 0 && remainderFits(fuel)) {
      var remainder = fuel.hasCraftingRemainingItem() ? fuel.getCraftingRemainingItem() : null;
      generator.ignite(burn);
      slots.extractItem(FUEL, 1, false);
      if (remainder != null && !remainder.isEmpty()) {
        var held = slots.getStackInSlot(REMAINDER);
        if (held.isEmpty()) slots.setStackInSlot(REMAINDER, remainder.copy());
        else held.grow(remainder.getCount());
      }
      changed = true;
    }
    if (generator.burn() > 0) changed = true;
    if (push()) changed = true;
    boolean lit = generator.status() == FuelGenerator.Status.BURNING;
    var state = getBlockState();
    if (state.getValue(FuelGeneratorBlock.LIT) != lit)
      level.setBlock(worldPosition, state.setValue(FuelGeneratorBlock.LIT, lit), 3);
    if (changed) setChanged();
  }

  private boolean push() {
    if (generator.energy() == 0 || !(level instanceof ServerLevel server)) return false;
    boolean moved = false;
    long tick = level.getGameTime();
    for (var face : Direction.values()) {
      long offer = generator.extract(FuelGenerator.MAX_OUTPUT, true, tick);
      if (offer == 0) break;
      var cache =
          receivers.computeIfAbsent(
              face,
              f ->
                  BlockCapabilityCache.create(
                      Capabilities.EnergyStorage.BLOCK,
                      server,
                      worldPosition.relative(f),
                      f.getOpposite()));
      var receiver = cache.getCapability();
      if (receiver == null || !receiver.canReceive()) continue;
      int accepted = receiver.receiveEnergy((int) offer, false);
      // A handler reporting more than offered cannot have received more than was offered.
      long actual = Math.max(0, Math.min(accepted, offer));
      if (actual > 0) {
        generator.extract(actual, false, tick);
        moved = true;
      }
    }
    return moved;
  }

  @Override
  public void setRemoved() {
    receivers.clear();
    super.setRemoved();
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (unsupported != null) {
      for (var key : unsupported.getAllKeys()) tag.put(key, unsupported.get(key).copy());
      return;
    }
    var state = generator.snapshot();
    tag.putInt("Schema", SCHEMA);
    tag.putLong("Energy", state.energy());
    tag.putLong("Burn", state.burnRemaining());
    tag.putLong("BurnTotal", state.burnTotal());
    tag.put("Items", slots.serializeNBT(registries));
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    unsupported = null;
    if (!tag.contains("Schema")) return;
    int schema = tag.getInt("Schema");
    try {
      if (schema != SCHEMA) throw new IllegalArgumentException("Unsupported schema " + schema);
      var restored =
          new FuelGenerator(
              new FuelGenerator.Snapshot(
                  tag.getLong("Energy"), tag.getLong("Burn"), tag.getLong("BurnTotal")));
      var items = tag.getCompound("Items");
      if (items.getInt("Size") != 2) throw new IllegalArgumentException("Slot count");
      slots.deserializeNBT(registries, items);
      generator = restored;
    } catch (IllegalArgumentException invalid) {
      unsupported = tag.copy();
      for (var key : List.of("id", "x", "y", "z", "components")) unsupported.remove(key);
    }
  }

  @Override
  protected void applyImplicitComponents(DataComponentInput input) {
    super.applyImplicitComponents(input);
    var retained = input.get(FuelGeneratorContent.RETAINED.get());
    if (retained != null) {
      unsupported = retained.copyTag();
      generator = new FuelGenerator();
      return;
    }
    var saved = input.get(FuelGeneratorContent.STATE.get());
    if (saved != null && unsupported == null) generator = new FuelGenerator(saved);
  }

  @Override
  protected void collectImplicitComponents(DataComponentMap.Builder components) {
    super.collectImplicitComponents(components);
    if (unsupported != null) {
      // Breaking must not discard retained future data; placement retains it again unchanged.
      components.set(FuelGeneratorContent.RETAINED.get(), CustomData.of(unsupported));
      return;
    }
    var state = generator.snapshot();
    if (state.energy() > 0 || state.burnRemaining() > 0)
      components.set(FuelGeneratorContent.STATE.get(), state);
  }
}
