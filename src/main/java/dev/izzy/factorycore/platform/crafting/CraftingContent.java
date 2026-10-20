package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

public final class CraftingContent {

  public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternContents>>
      PATTERN =
          FactoryRegistries.COMPONENTS.registerComponentType(
              "pattern",
              builder ->
                  builder
                      .persistent(PatternContents.CODEC)
                      .networkSynchronized(PatternContents.STREAM_CODEC));

  /** Exact unsupported Crafting Unit data, carried unchanged by its broken block item. */
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<CustomData>> RETAINED =
      FactoryRegistries.COMPONENTS.registerComponentType(
          "unit_retained", builder -> builder.persistent(CustomData.CODEC));

  public static final DeferredHolder<DataComponentType<?>, DataComponentType<ProcessingContents>>
      PROCESSING =
          FactoryRegistries.COMPONENTS.registerComponentType(
              "processing_pattern",
              builder ->
                  builder
                      .persistent(ProcessingContents.CODEC)
                      .networkSynchronized(ProcessingContents.STREAM_CODEC));

  public static final DeferredItem<Item> BLANK_PATTERN =
      FactoryRegistries.ITEMS.register("blank_pattern", () -> new Item(new Item.Properties()));

  public static final DeferredItem<PatternItem> CRAFTING_PATTERN =
      FactoryRegistries.ITEMS.register("crafting_pattern", PatternItem::new);

  public static final DeferredItem<ProcessingPatternItem> PROCESSING_PATTERN =
      FactoryRegistries.ITEMS.register("processing_pattern", ProcessingPatternItem::new);

  public static final DeferredBlock<CraftingInterfaceBlock> INTERFACE =
      FactoryRegistries.BLOCKS.registerBlock(
          "crafting_interface", CraftingInterfaceBlock::new, DeviceProperties.machine());

  public static final DeferredItem<BlockItem> INTERFACE_ITEM =
      FactoryRegistries.ITEMS.registerSimpleBlockItem("crafting_interface", INTERFACE);

  public static final DeferredBlock<CraftingUnitBlock> UNIT =
      FactoryRegistries.BLOCKS.registerBlock(
          "crafting_unit", CraftingUnitBlock::new, DeviceProperties.machine());

  public static final DeferredItem<BlockItem> UNIT_ITEM =
      FactoryRegistries.ITEMS.registerSimpleBlockItem("crafting_unit", UNIT);

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CraftingUnitEntity>>
      UNIT_ENTITY = FactoryRegistries.entity("crafting_unit", CraftingUnitEntity::new, UNIT);

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CraftingInterfaceEntity>>
      INTERFACE_ENTITY =
          FactoryRegistries.entity("crafting_interface", CraftingInterfaceEntity::new, INTERFACE);

  private CraftingContent() {}

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.ItemHandler.BLOCK, UNIT_ENTITY.get(), (unit, face) -> unit.patterns());
    // The faced side belongs to the machine; patterns may be fed from every other side.
    event.registerBlockEntity(
        Capabilities.ItemHandler.BLOCK,
        INTERFACE_ENTITY.get(),
        (holder, face) -> face == holder.facing() ? null : holder.patterns());
  }

  public static void register(IEventBus bus) {
    bus.addListener(CraftingContent::capabilities);
  }
}
