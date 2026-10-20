package dev.izzy.factorycore.platform;

import java.util.Arrays;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The mod's only deferred registers. Each feature's {@code *Content} class adds its entries here
 * when it loads, so {@link FactoryCoreMod} loads every content class before registration fires.
 * Registry ids are save format: never rename one.
 */
public final class FactoryRegistries {
  public static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(FactoryCoreMod.MOD_ID);
  public static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(FactoryCoreMod.MOD_ID);
  public static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FactoryCoreMod.MOD_ID);
  public static final DeferredRegister<MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, FactoryCoreMod.MOD_ID);
  public static final DeferredRegister.DataComponents COMPONENTS =
      DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, FactoryCoreMod.MOD_ID);
  public static final DeferredRegister<CreativeModeTab> TABS =
      DeferredRegister.create(Registries.CREATIVE_MODE_TAB, FactoryCoreMod.MOD_ID);

  private FactoryRegistries() {}

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    MENUS.register(bus);
    COMPONENTS.register(bus);
    TABS.register(bus);
  }

  /** A block entity type valid for {@code blocks}, resolved when the type is built. */
  public static <E extends BlockEntity>
      DeferredHolder<BlockEntityType<?>, BlockEntityType<E>> entity(
          String id, BlockEntityType.BlockEntitySupplier<E> factory, DeferredBlock<?>... blocks) {
    return ENTITIES.register(
        id,
        () ->
            BlockEntityType.Builder.of(
                    factory, Arrays.stream(blocks).map(DeferredBlock::get).toArray(Block[]::new))
                .build(null));
  }

  /** A menu type whose client side is built from the opening packet's buffer. */
  public static <M extends AbstractContainerMenu> DeferredHolder<MenuType<?>, MenuType<M>> menu(
      String id, IContainerFactory<M> factory) {
    return MENUS.register(id, () -> IMenuTypeExtension.create(factory));
  }
}
