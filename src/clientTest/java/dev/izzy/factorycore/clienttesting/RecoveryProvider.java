package dev.izzy.factorycore.clienttesting;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.resource.ExternalBackingProvider;
import java.util.List;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.*;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.*;

/** Excluded test-mod provider: optimistic simulation, finite actual acceptance, optional fault. */
final class RecoveryProvider {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks("factorycore_client_tests");
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "factorycore_client_tests");
  static final DeferredBlock<ProviderBlock> BLOCK =
      BLOCKS.registerBlock("recovery_provider", ProviderBlock::new, BlockBehaviour.Properties.of());
  private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Provider>> ENTITY =
      ENTITIES.register(
          "recovery_provider",
          () -> BlockEntityType.Builder.of(Provider::new, BLOCK.get()).build(null));

  private RecoveryProvider() {}

  static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ENTITIES.register(bus);
    bus.addListener(RecoveryProvider::capabilities);
  }

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.ItemHandler.BLOCK, ENTITY.get(), (provider, side) -> provider.items);
  }

  static final class ProviderBlock extends Block implements EntityBlock {
    ProviderBlock(Properties properties) {
      super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
      return new Provider(position, state);
    }
  }

  static final class Provider extends BlockEntity implements ExternalBackingProvider {
    int budget = 3, calls;
    boolean failAfterInsert;
    final ItemStackHandler items =
        new ItemStackHandler(1) {
          @Override
          public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (simulate || stack.isEmpty()) return super.insertItem(slot, stack, simulate);
            calls++;
            int offered = Math.min(budget, stack.getCount());
            var leftover = super.insertItem(slot, stack.copyWithCount(offered), false);
            int accepted = offered - leftover.getCount();
            budget -= accepted;
            setChanged();
            if (failAfterInsert && accepted > 0)
              throw new IllegalStateException("Injected post-insert uncertainty");
            return accepted == stack.getCount()
                ? ItemStack.EMPTY
                : stack.copyWithCount(stack.getCount() - accepted);
          }

          @Override
          protected void onContentsChanged(int slot) {
            setChanged();
          }
        };

    Provider(BlockPos position, BlockState state) {
      super(ENTITY.get(), position, state);
    }

    @Override
    public List<BlockPos> factoryCoreBackingPositions(ResourceKind kind) {
      return List.of(getBlockPos());
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
      super.saveAdditional(tag, registries);
      tag.put("Items", items.serializeNBT(registries));
      tag.putInt("Budget", budget);
      tag.putInt("Calls", calls);
      tag.putBoolean("Fault", failAfterInsert);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
      super.loadAdditional(tag, registries);
      items.deserializeNBT(registries, tag.getCompound("Items"));
      budget = tag.getInt("Budget");
      calls = tag.getInt("Calls");
      failAfterInsert = tag.getBoolean("Fault");
    }
  }
}
