package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CraftingContent {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.DataComponents COMPONENTS =
      DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, FactoryCoreMod.MOD_ID);

  public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternContents>>
      PATTERN =
          COMPONENTS.registerComponentType(
              "pattern",
              builder ->
                  builder
                      .persistent(PatternContents.CODEC)
                      .networkSynchronized(PatternContents.STREAM_CODEC));

  /** Exact unsupported Crafting Unit data, carried unchanged by its broken block item. */
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<CustomData>> RETAINED =
      COMPONENTS.registerComponentType(
          "unit_retained", builder -> builder.persistent(CustomData.CODEC));

  public static final DeferredHolder<DataComponentType<?>, DataComponentType<ProcessingContents>>
      PROCESSING =
          COMPONENTS.registerComponentType(
              "processing_pattern",
              builder ->
                  builder
                      .persistent(ProcessingContents.CODEC)
                      .networkSynchronized(ProcessingContents.STREAM_CODEC));

  public static final DeferredItem<Item> BLANK_PATTERN =
      ITEMS.register("blank_pattern", () -> new Item(new Item.Properties()));

  public static final DeferredItem<PatternItem> CRAFTING_PATTERN =
      ITEMS.register("crafting_pattern", PatternItem::new);

  public static final DeferredItem<ProcessingPatternItem> PROCESSING_PATTERN =
      ITEMS.register("processing_pattern", ProcessingPatternItem::new);

  public static final DeferredBlock<CraftingInterfaceBlock> INTERFACE =
      BLOCKS.registerBlock(
          "crafting_interface",
          CraftingInterfaceBlock::new,
          BlockBehaviour.Properties.of()
              .strength(3.5f)
              .requiresCorrectToolForDrops()
              .sound(SoundType.METAL));

  public static final DeferredItem<BlockItem> INTERFACE_ITEM =
      ITEMS.registerSimpleBlockItem("crafting_interface", INTERFACE);

  public static final DeferredBlock<CraftingUnitBlock> UNIT =
      BLOCKS.registerBlock(
          "crafting_unit",
          CraftingUnitBlock::new,
          BlockBehaviour.Properties.of()
              .strength(3.5f)
              .requiresCorrectToolForDrops()
              .sound(SoundType.METAL));

  public static final DeferredItem<BlockItem> UNIT_ITEM =
      ITEMS.registerSimpleBlockItem("crafting_unit", UNIT);

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CraftingUnitEntity>>
      UNIT_ENTITY =
          ENTITIES.register(
              "crafting_unit",
              () -> BlockEntityType.Builder.of(CraftingUnitEntity::new, UNIT.get()).build(null));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CraftingInterfaceEntity>>
      INTERFACE_ENTITY =
          ENTITIES.register(
              "crafting_interface",
              () ->
                  BlockEntityType.Builder.of(CraftingInterfaceEntity::new, INTERFACE.get())
                      .build(null));

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
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    COMPONENTS.register(bus);
    bus.addListener(CraftingContent::capabilities);
  }
}
