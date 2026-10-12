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
        (entity, side) -> side == Direction.WEST ? entity.items : null);
    event.registerBlockEntity(
        Capabilities.FluidHandler.BLOCK,
        ENTITY.get(),
        (entity, side) -> side == Direction.WEST ? entity.fluids : null);
    event.registerBlockEntity(
        Capabilities.EnergyStorage.BLOCK,
        ENTITY.get(),
        (entity, side) -> side == Direction.WEST ? entity.energy : null);
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

  public static final class ProviderEntity extends BlockEntity
      implements dev.izzy.factorycore.platform.resource.ExternalBackingProvider {
    @Override
    public java.util.List<BlockPos> factoryCoreBackingPositions(
        dev.izzy.factorycore.core.resource.ResourceKind kind) {
      return extendedBacking
          ? java.util.List.of(getBlockPos(), getBlockPos().north())
          : java.util.List.of(getBlockPos());
    }

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
