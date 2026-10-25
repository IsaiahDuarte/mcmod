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

  /** Exact unsupported Crafter data, carried unchanged by its broken block item. */
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

  /** The Crafter (ADR 0060): crafting-table and processing patterns in one faced block. */
  public static final DeferredBlock<CrafterBlock> CRAFTER =
      FactoryRegistries.BLOCKS.registerBlock(
          "crafter", CrafterBlock::new, DeviceProperties.machine());

  public static final DeferredItem<BlockItem> CRAFTER_ITEM =
      FactoryRegistries.ITEMS.registerSimpleBlockItem("crafter", CRAFTER);

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CrafterEntity>>
      CRAFTER_ENTITY = FactoryRegistries.entity("crafter", CrafterEntity::new, CRAFTER);

  private CraftingContent() {}

  private static void capabilities(RegisterCapabilitiesEvent event) {
    // The faced side belongs to the machine; patterns may be fed from every other side.
    event.registerBlockEntity(
        Capabilities.ItemHandler.BLOCK,
        CRAFTER_ENTITY.get(),
        (holder, face) -> face == holder.facing() ? null : holder.patterns());
  }

  public static void register(IEventBus bus) {
    bus.addListener(CraftingContent::capabilities);
    bus.addListener(JobNotice::register);
  }
}
