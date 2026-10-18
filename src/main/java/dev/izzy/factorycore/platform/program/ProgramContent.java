package dev.izzy.factorycore.platform.program;

import com.mojang.serialization.Codec;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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

public final class ProgramContent {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.DataComponents COMPONENTS =
      DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, FactoryCoreMod.MOD_ID);

  /** Draft source carried by a broken controller; deployment is never carried. */
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> DRAFT =
      COMPONENTS.registerComponentType(
          "program_draft",
          builder ->
              builder
                  .persistent(Codec.string(0, ProgramControllerEntity.MAX_DRAFT))
                  .networkSynchronized(
                      ByteBufCodecs.stringUtf8(ProgramControllerEntity.MAX_DRAFT * 3)));

  /** Stored FE carried by a broken controller. */
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> ENERGY =
      COMPONENTS.registerComponentType(
          "program_energy",
          builder ->
              builder
                  .persistent(
                      Codec.LONG.validate(
                          value ->
                              value >= 0 && value <= ProgramControllerEntity.CAPACITY
                                  ? com.mojang.serialization.DataResult.success(value)
                                  : com.mojang.serialization.DataResult.error(
                                      () -> "Program energy out of range")))
                  .networkSynchronized(ByteBufCodecs.VAR_LONG));

  public static final DeferredBlock<ProgramControllerBlock> BLOCK =
      BLOCKS.registerBlock(
          "program_controller",
          ProgramControllerBlock::new,
          BlockBehaviour.Properties.of()
              .strength(3.5f)
              .requiresCorrectToolForDrops()
              .sound(SoundType.METAL));

  public static final DeferredItem<BlockItem> ITEM =
      ITEMS.register("program_controller", () -> new BlockItem(BLOCK.get(), new Item.Properties()));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ProgramControllerEntity>>
      ENTITY =
          ENTITIES.register(
              "program_controller",
              () ->
                  BlockEntityType.Builder.of(ProgramControllerEntity::new, BLOCK.get())
                      .build(null));

  public static final DeferredHolder<
          net.minecraft.world.inventory.MenuType<?>,
          net.minecraft.world.inventory.MenuType<ProgramMenu>>
      MENU =
          MENUS.register(
              "program_controller",
              () ->
                  net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                      ProgramMenu::new));

  private ProgramContent() {}

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.EnergyStorage.BLOCK,
        ENTITY.get(),
        (controller, face) -> controller.energyStorage());
  }

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    COMPONENTS.register(bus);
    MENUS.register(bus);
    bus.addListener(ProgramContent::capabilities);
    bus.addListener(ProgramPackets::register);
  }
}
