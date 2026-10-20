package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongUnaryOperator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Item and fluid I/O against a leased processing machine for {@link WorldCraftingJobs}: inserts
 * exactly what the machine accepts from job staging and collects only expected outputs. Every call
 * is wireless-metered between the job's origin and the machine, and journaled pending before /
 * settled after (ADR 0057). Job scheduling and state stay in WorldCraftingJobs.
 */
final class MachineIo {
  private final MinecraftServer server;
  private final Function<CraftingJob, NodeRegistry.Position> origin;

  /** {@code origin} gives the job's request segment origin, or null when it is not resolvable. */
  MachineIo(MinecraftServer server, Function<CraftingJob, NodeRegistry.Position> origin) {
    this.server = server;
    this.origin = origin;
  }

  /** Pending-before / settled-after journal marks around each real machine call (ADR 0057). */
  static final class Journal {
    private final UUID world;
    private final CraftingJob job;
    private final BlockEntity machine;

    Journal(UUID world, CraftingJob job, BlockEntity machine) {
      this.world = world;
      this.job = job;
      this.machine = machine;
    }

    void begin() {
      ProcessingJournal.write(machine, world, job.id(), job.dispatch(), true);
    }

    void settle() {
      ProcessingJournal.write(machine, world, job.id(), job.dispatch(), false);
    }
  }

  static boolean containsFluid(IFluidHandler tanks, ResourceKey key) {
    for (int tank = 0; tank < tanks.getTanks(); tank++) {
      var present = tanks.getFluidInTank(tank);
      if (!present.isEmpty()
          && key.registryId().equals(BuiltInRegistries.FLUID.getKey(present.getFluid()).toString()))
        return true;
    }
    return false;
  }

  /** Fills exactly what the machine accepts of each staged fluid input; the rest stays staged. */
  void insertFluids(
      CraftingJob job,
      Map<ResourceKey, Long> inputs,
      IFluidHandler tanks,
      PlatformResourceCodec codec,
      Journal journal) {
    tanks = meteredFluids(job, tanks, codec);
    if (tanks == null) return;
    for (var input : inputs.entrySet()) {
      if (input.getKey().kind() != ResourceKind.FLUID) continue;
      long remaining =
          Math.min(
              input.getValue() - job.dispatch().inserted().getOrDefault(input.getKey(), 0L),
              job.stagedAmount(input.getKey()));
      if (remaining <= 0) continue;
      var offer = codec.fluidStack(input.getKey(), (int) Math.min(remaining, Integer.MAX_VALUE));
      journal.begin();
      int accepted = tanks.fill(offer, IFluidHandler.FluidAction.EXECUTE);
      if (accepted > remaining)
        throw new IllegalStateException("Machine accepted more fluid than offered");
      if (accepted > 0) job.inserted(input.getKey(), accepted);
      journal.settle();
    }
  }

  /** Drains only the expected fluid outputs from the leased machine into job staging. */
  void collectFluids(
      CraftingJob job,
      Map<ResourceKey, Long> outputs,
      IFluidHandler tanks,
      PlatformResourceCodec codec,
      Journal journal) {
    tanks = meteredFluids(job, tanks, codec);
    if (tanks == null) return;
    for (var output : outputs.entrySet()) {
      if (output.getKey().kind() != ResourceKind.FLUID) continue;
      long remaining =
          output.getValue() - job.dispatch().collected().getOrDefault(output.getKey(), 0L);
      if (remaining <= 0) continue;
      var want = codec.fluidStack(output.getKey(), (int) Math.min(remaining, Integer.MAX_VALUE));
      if (!containsFluid(tanks, output.getKey())) continue;
      journal.begin();
      var got = tanks.drain(want, IFluidHandler.FluidAction.EXECUTE);
      if (!got.isEmpty()) {
        if (!codec.fluidKey(got).equals(output.getKey()) || got.getAmount() > remaining)
          throw new IllegalStateException("Machine drained an unexpected fluid");
        job.collected(output.getKey(), got.getAmount());
      }
      journal.settle();
    }
  }

  void insert(
      CraftingJob job,
      Map<ResourceKey, Long> inputs,
      IItemHandler machine,
      PlatformResourceCodec codec,
      Journal journal) {
    machine = meteredItems(job, machine, codec);
    if (machine == null) return;
    for (var input : inputs.entrySet()) {
      if (input.getKey().kind() != ResourceKind.ITEM) continue;
      long remaining =
          Math.min(
              input.getValue() - job.dispatch().inserted().getOrDefault(input.getKey(), 0L),
              job.stagedAmount(input.getKey()));
      while (remaining > 0) {
        var stack = codec.itemStack(input.getKey(), 1);
        int size = (int) Math.min(remaining, stack.getMaxStackSize());
        stack.setCount(size);
        journal.begin();
        var rest = ItemHandlerHelper.insertItem(machine, stack, false);
        int accepted = size - rest.getCount();
        if (accepted > 0) job.inserted(input.getKey(), accepted);
        journal.settle();
        if (accepted <= 0) break;
        remaining -= accepted;
      }
    }
  }

  /** Extracts expected outputs from the leased machine; anything else extracted is staged too. */
  void collect(
      CraftingJob job,
      Map<ResourceKey, Long> outputs,
      IItemHandler machine,
      PlatformResourceCodec codec,
      Journal journal) {
    machine = meteredItems(job, machine, codec);
    if (machine == null) return;
    for (var output : outputs.entrySet())
      for (int slot = 0;
          output.getKey().kind() == ResourceKind.ITEM && slot < machine.getSlots();
          slot++) {
        long remaining =
            output.getValue() - job.dispatch().collected().getOrDefault(output.getKey(), 0L);
        if (remaining <= 0) break;
        var present = machine.getStackInSlot(slot);
        if (present.isEmpty() || !output.getKey().equals(WorldCraftingJobs.keyOf(codec, present)))
          continue;
        journal.begin();
        var got = machine.extractItem(slot, (int) Math.min(remaining, present.getCount()), false);
        if (!got.isEmpty()) {
          var key = WorldCraftingJobs.keyOf(codec, got);
          if (key == null || !key.equals(output.getKey()) || got.getCount() > remaining)
            throw new IllegalStateException("Machine returned an unexpected stack");
          job.collected(key, got.getCount());
        }
        journal.settle();
      }
  }

  /** Job staging resides in its request segment; external machine dispatch is a separate leg. */
  private long meterMachine(
      CraftingJob job,
      ResourceKey key,
      long amount,
      boolean simulate,
      LongUnaryOperator operation) {
    var start = origin.apply(job);
    if (start == null || job.dispatch() == null) return 0;
    var machine = job.dispatch().machine();
    if (simulate) {
      long offer = WorldNetworks.get(server).movementAvailable(start, machine, key, amount);
      return offer == 0 ? 0 : operation.applyAsLong(offer);
    }
    var permit = WorldNetworks.get(server).movement(start, machine, key, amount);
    if (permit.amount() == 0) {
      permit.finish(0);
      return 0;
    }
    long actual = operation.applyAsLong(permit.amount());
    permit.finish(actual);
    return actual;
  }

  private IItemHandler meteredItems(
      CraftingJob job, IItemHandler delegate, PlatformResourceCodec codec) {
    if (origin.apply(job) == null || job.dispatch() == null) return null;
    return new IItemHandler() {
      @Override
      public int getSlots() {
        return delegate.getSlots();
      }

      @Override
      public ItemStack getStackInSlot(int slot) {
        return delegate.getStackInSlot(slot);
      }

      @Override
      public int getSlotLimit(int slot) {
        return delegate.getSlotLimit(slot);
      }

      @Override
      public boolean isItemValid(int slot, ItemStack stack) {
        return delegate.isItemValid(slot, stack);
      }

      @Override
      public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        long accepted =
            meterMachine(
                job,
                codec.itemKey(stack),
                stack.getCount(),
                simulate,
                offered -> {
                  var rest =
                      delegate.insertItem(slot, stack.copyWithCount((int) offered), simulate);
                  if (!rest.isEmpty() && !ItemStack.isSameItemSameComponents(rest, stack))
                    throw new IllegalStateException(
                        "Processing handler changed an offered identity");
                  return offered - rest.getCount();
                });
        return stack.copyWithCount(stack.getCount() - (int) accepted);
      }

      @Override
      public ItemStack extractItem(int slot, int amount, boolean simulate) {
        var present = delegate.getStackInSlot(slot);
        if (present.isEmpty()) return ItemStack.EMPTY;
        var extracted = new ItemStack[] {ItemStack.EMPTY};
        meterMachine(
            job,
            codec.itemKey(present),
            amount,
            simulate,
            offered -> {
              extracted[0] = delegate.extractItem(slot, (int) offered, simulate);
              return extracted[0].getCount();
            });
        return extracted[0];
      }
    };
  }

  private IFluidHandler meteredFluids(
      CraftingJob job, IFluidHandler delegate, PlatformResourceCodec codec) {
    if (origin.apply(job) == null || job.dispatch() == null) return null;
    return new IFluidHandler() {
      @Override
      public int getTanks() {
        return delegate.getTanks();
      }

      @Override
      public FluidStack getFluidInTank(int tank) {
        return delegate.getFluidInTank(tank);
      }

      @Override
      public int getTankCapacity(int tank) {
        return delegate.getTankCapacity(tank);
      }

      @Override
      public boolean isFluidValid(int tank, FluidStack stack) {
        return delegate.isFluidValid(tank, stack);
      }

      @Override
      public int fill(FluidStack stack, FluidAction action) {
        if (stack.isEmpty()) return 0;
        return (int)
            meterMachine(
                job,
                codec.fluidKey(stack),
                stack.getAmount(),
                action.simulate(),
                offered -> delegate.fill(stack.copyWithAmount((int) offered), action));
      }

      @Override
      public FluidStack drain(FluidStack stack, FluidAction action) {
        if (stack.isEmpty()) return FluidStack.EMPTY;
        var got = new FluidStack[] {FluidStack.EMPTY};
        meterMachine(
            job,
            codec.fluidKey(stack),
            stack.getAmount(),
            action.simulate(),
            offered -> {
              got[0] = delegate.drain(stack.copyWithAmount((int) offered), action);
              return got[0].getAmount();
            });
        return got[0];
      }

      @Override
      public FluidStack drain(int amount, FluidAction action) {
        var present = delegate.drain(amount, FluidAction.SIMULATE);
        return present.isEmpty() ? present : drain(present, action);
      }
    };
  }
}
