package dev.izzy.factorycore.platform.program;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

public final class ProgramContent {

  /** Draft source carried by a broken controller; deployment is never carried. */
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> DRAFT =
      FactoryRegistries.COMPONENTS.registerComponentType(
          "program_draft",
          builder ->
              builder
                  .persistent(Codec.string(0, ProgramControllerEntity.MAX_DRAFT))
                  .networkSynchronized(
                      ByteBufCodecs.stringUtf8(ProgramControllerEntity.MAX_DRAFT * 3)));

  /** Stored FE carried by a broken controller. */
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> ENERGY =
      FactoryRegistries.COMPONENTS.registerComponentType(
          "program_energy",
          builder ->
              builder
                  .persistent(
                      Codec.LONG.validate(
                          value ->
                              value >= 0 && value <= ProgramControllerEntity.CAPACITY
                                  ? DataResult.success(value)
                                  : DataResult.error(() -> "Program energy out of range")))
                  .networkSynchronized(ByteBufCodecs.VAR_LONG));

  public static final DeferredBlock<ProgramControllerBlock> BLOCK =
      FactoryRegistries.BLOCKS.registerBlock(
          "program_controller", ProgramControllerBlock::new, DeviceProperties.machine());

  public static final DeferredItem<BlockItem> ITEM =
      FactoryRegistries.ITEMS.register(
          "program_controller", () -> new BlockItem(BLOCK.get(), new Item.Properties()));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ProgramControllerEntity>>
      ENTITY = FactoryRegistries.entity("program_controller", ProgramControllerEntity::new, BLOCK);

  public static final DeferredHolder<MenuType<?>, MenuType<ProgramMenu>> MENU =
      FactoryRegistries.menu("program_controller", ProgramMenu::new);

  private ProgramContent() {}

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.EnergyStorage.BLOCK,
        ENTITY.get(),
        (controller, face) -> controller.energyStorage());
  }

  public static void register(IEventBus bus) {
    bus.addListener(ProgramContent::capabilities);
    bus.addListener(ProgramPackets::register);
  }
}
