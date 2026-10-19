package dev.izzy.factorycore.testing;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Test-only deterministic machine with item and fluid ports: every 10 ticks it turns 1 cobblestone
 * + 1,000 mB water into 1 stone (output slot) + 250 mB lava (output tank). Fills go to the water
 * tank only; drains come from the lava tank only.
 */
public final class MixerFixture {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks("factorycore_tests");
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "factorycore_tests");
  public static final DeferredBlock<Mixer> BLOCK =
      BLOCKS.registerBlock("fixture_mixer", Mixer::new, BlockBehaviour.Properties.of());
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MixerEntity>> ENTITY =
      ENTITIES.register(
          "fixture_mixer",
          () -> BlockEntityType.Builder.of(MixerEntity::new, BLOCK.get()).build(null));

  private MixerFixture() {}

  public static final class Mixer extends BaseEntityBlock {
    public Mixer(Properties properties) {
      super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
      return simpleCodec(Mixer::new);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
      return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
      return new MixerEntity(position, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level, BlockState state, BlockEntityType<T> type) {
      return level.isClientSide()
          ? null
          : createTickerHelper(type, ENTITY.get(), (l, p, s, mixer) -> mixer.tick());
    }
  }

  public static final class MixerEntity extends BlockEntity
      implements dev.izzy.factorycore.platform.resource.ExternalBackingProvider {
    @Override
    public java.util.List<BlockPos> factoryCoreBackingPositions(
        dev.izzy.factorycore.core.resource.ResourceKind kind) {
      return java.util.List.of(getBlockPos());
    }

    /** Slot 0 input (cobblestone), slot 1 output (stone, extract only). */
    public final ItemStackHandler items =
        new ItemStackHandler(2) {
          @Override
          public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && stack.is(Items.COBBLESTONE);
          }

          @Override
          public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return slot == 1 ? super.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
          }
        };

    public final FluidTank water = new FluidTank(4_000, stack -> stack.is(Fluids.WATER));
    public final FluidTank lava = new FluidTank(4_000);
    private int timer;

    public final IFluidHandler fluids =
        new IFluidHandler() {
          @Override
          public int getTanks() {
            return 2;
          }

          @Override
          public FluidStack getFluidInTank(int tank) {
            return tank == 0 ? water.getFluid() : lava.getFluid();
          }

          @Override
          public int getTankCapacity(int tank) {
            return 4_000;
          }

          @Override
          public boolean isFluidValid(int tank, FluidStack stack) {
            return tank == 0 && stack.is(Fluids.WATER);
          }

          @Override
          public int fill(FluidStack resource, FluidAction action) {
            return water.fill(resource, action);
          }

          @Override
          public FluidStack drain(FluidStack resource, FluidAction action) {
            return lava.drain(resource, action);
          }

          @Override
          public FluidStack drain(int amount, FluidAction action) {
            return lava.drain(amount, action);
          }
        };

    public MixerEntity(BlockPos position, BlockState state) {
      super(ENTITY.get(), position, state);
    }

    void tick() {
      if (++timer < 10) return;
      timer = 0;
      if (items.getStackInSlot(0).isEmpty()
          || water.getFluidAmount() < 1_000
          || lava.getSpace() < 250
          || items.getStackInSlot(1).getCount() >= 64) return;
      items.getStackInSlot(0).shrink(1);
      water.drain(1_000, IFluidHandler.FluidAction.EXECUTE);
      lava.fill(new FluidStack(Fluids.LAVA, 250), IFluidHandler.FluidAction.EXECUTE);
      items.setStackInSlot(1, new ItemStack(Items.STONE, items.getStackInSlot(1).getCount() + 1));
    }
  }

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.ItemHandler.BLOCK, ENTITY.get(), (mixer, side) -> mixer.items);
    event.registerBlockEntity(
        Capabilities.FluidHandler.BLOCK, ENTITY.get(), (mixer, side) -> mixer.fluids);
  }

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ENTITIES.register(bus);
    bus.addListener(MixerFixture::capabilities);
  }
}
