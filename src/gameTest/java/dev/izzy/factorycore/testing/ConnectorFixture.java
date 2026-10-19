package dev.izzy.factorycore.testing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Real test-only capability host: side restrictions and post-mutation fault injection. */
public final class ConnectorFixture {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks("factorycore_tests");
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "factorycore_tests");
  public static final DeferredBlock<FixtureBlock> BLOCK =
      BLOCKS.registerBlock("provider", FixtureBlock::new, BlockBehaviour.Properties.of());
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ProviderEntity>> ENTITY =
      ENTITIES.register(
          "provider",
          () -> BlockEntityType.Builder.of(ProviderEntity::new, BLOCK.get()).build(null));

  public static final DeferredBlock<OpaqueBlock> OPAQUE =
      BLOCKS.registerBlock("opaque_provider", OpaqueBlock::new, BlockBehaviour.Properties.of());
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OpaqueEntity>>
      OPAQUE_ENTITY =
          ENTITIES.register(
              "opaque_provider",
              () -> BlockEntityType.Builder.of(OpaqueEntity::new, OPAQUE.get()).build(null));
  private static final ItemStackHandler OPAQUE_SHARED = new ItemStackHandler(1);

  public static final class OpaqueBlock extends Block implements EntityBlock {
    public OpaqueBlock(Properties properties) {
      super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
      return new OpaqueEntity(position, state);
    }
  }

  public static final class OpaqueEntity extends BlockEntity {
    public final net.neoforged.neoforge.items.IItemHandler handler =
        new net.neoforged.neoforge.items.wrapper.RangedWrapper(OPAQUE_SHARED, 0, 1);

    public OpaqueEntity(BlockPos position, BlockState state) {
      super(OPAQUE_ENTITY.get(), position, state);
    }
  }

  private ConnectorFixture() {}

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ENTITIES.register(bus);
    bus.addListener(ConnectorFixture::capabilities);
  }

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.ItemHandler.BLOCK, OPAQUE_ENTITY.get(), (entity, side) -> entity.handler);
    event.registerBlockEntity(
        Capabilities.ItemHandler.BLOCK,
        ENTITY.get(),
        (entity, side) -> side == Direction.WEST || entity.partitioned ? entity.items : null);
    event.registerBlockEntity(
        Capabilities.FluidHandler.BLOCK,
        ENTITY.get(),
        (entity, side) ->
            side == Direction.WEST || entity.partitioned
                ? entity.partitioned ? entity.indexedFluids : entity.fluids
                : null);
    event.registerBlockEntity(
        Capabilities.EnergyStorage.BLOCK,
        ENTITY.get(),
        (entity, side) -> side == Direction.WEST || entity.partitioned ? entity.energy : null);
  }

  public static final class FixtureBlock extends Block implements EntityBlock {
    public FixtureBlock(Properties properties) {
      super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
      return new ProviderEntity(position, state);
    }
  }

  /** Actual two-tank capability whose indexed methods mutate only their named real FluidTank. */
  public static final class IndexedTanks
      implements dev.izzy.factorycore.platform.resource.IndexedFluidHandler {
    public final FluidTank[] tanks = {new FluidTank(2000), new FluidTank(2000)};

    @Override
    public int getTanks() {
      return tanks.length;
    }

    @Override
    public net.neoforged.neoforge.fluids.FluidStack getFluidInTank(int tank) {
      return tanks[tank].getFluid();
    }

    @Override
    public int getTankCapacity(int tank) {
      return tanks[tank].getCapacity();
    }

    @Override
    public boolean isFluidValid(int tank, net.neoforged.neoforge.fluids.FluidStack stack) {
      return tanks[tank].isFluidValid(stack);
    }

    @Override
    public int fillTank(
        int tank, net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
      return tanks[tank].fill(stack, action);
    }

    @Override
    public net.neoforged.neoforge.fluids.FluidStack drainTank(
        int tank, net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
      return tanks[tank].drain(stack, action);
    }

    @Override
    public int fill(net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
      return tanks[0].fill(stack, action);
    }

    @Override
    public net.neoforged.neoforge.fluids.FluidStack drain(
        net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
      return tanks[0].drain(stack, action);
    }

    @Override
    public net.neoforged.neoforge.fluids.FluidStack drain(int amount, FluidAction action) {
      return tanks[0].drain(amount, action);
    }
  }

  public static final class ProviderEntity extends BlockEntity
      implements dev.izzy.factorycore.platform.resource.ExternalBackingProvider {
    @Override
    public java.util.List<BlockPos> factoryCoreBackingPositions(
        dev.izzy.factorycore.core.resource.ResourceKind kind) {
      return extendedBacking
          ? java.util.List.of(getBlockPos(), getBlockPos().north())
          : java.util.List.of(getBlockPos());
    }

    @Override
    public java.util.List<dev.izzy.factorycore.platform.resource.ExternalBackingProvider.PortWindow>
        factoryCorePorts(dev.izzy.factorycore.core.resource.ResourceKind kind) {
      return partitioned && kind != dev.izzy.factorycore.core.resource.ResourceKind.ENERGY
          ? java.util.List.of(new PortWindow(0, 1), new PortWindow(1, 1))
          : java.util.List.of();
    }

    public boolean partitioned;
    public final IndexedTanks indexedFluids = new IndexedTanks();
    public boolean failAfterExtraction;
    public boolean failSecondStockRead;
    public boolean extendedBacking;
    public Runnable afterInsertion = () -> {};
    public final ItemStackHandler items =
        new ItemStackHandler(2) {
          @Override
          public ItemStack getStackInSlot(int slot) {
            if (slot == 1 && failSecondStockRead)
              throw new IllegalStateException("Injected provider stock read failure");
            return super.getStackInSlot(slot);
          }

          @Override
          public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            var remainder = super.insertItem(slot, stack, simulate);
            if (!simulate && remainder.getCount() < stack.getCount()) afterInsertion.run();
            return remainder;
          }

          @Override
          public ItemStack extractItem(int slot, int amount, boolean simulate) {
            var extracted = super.extractItem(slot, amount, simulate);
            if (!simulate && failAfterExtraction && !extracted.isEmpty())
              throw new IllegalStateException("Injected provider failure after extraction");
            return extracted;
          }
        };
    public final FluidTank fluids = new FluidTank(2000);
    public final EnergyStorage energy = new EnergyStorage(2000, 1000, 1000);

    public ProviderEntity(BlockPos position, BlockState state) {
      super(ENTITY.get(), position, state);
    }
  }
}
