package dev.izzy.factorycore.platform.wireless;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.ComponentEnergyStorage;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WirelessContent {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.DataComponents COMPONENTS =
      DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, FactoryCoreMod.MOD_ID);

  /** Access point a handheld terminal is bound to. */
  public record Binding(String dimension, long position) {
    public static final Codec<Binding> CODEC =
        RecordCodecBuilder.create(
            instance ->
                instance
                    .group(
                        Codec.string(1, 256).fieldOf("dimension").forGetter(Binding::dimension),
                        Codec.LONG.fieldOf("position").forGetter(Binding::position))
                    .apply(instance, Binding::new));
    public static final StreamCodec<io.netty.buffer.ByteBuf, Binding> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(256),
            Binding::dimension,
            ByteBufCodecs.VAR_LONG,
            Binding::position,
            Binding::new);
  }

  public static final DeferredHolder<DataComponentType<?>, DataComponentType<Binding>> BINDING =
      COMPONENTS.registerComponentType(
          "wireless_binding",
          builder -> builder.persistent(Binding.CODEC).networkSynchronized(Binding.STREAM_CODEC));

  /** FE stored in a handheld terminal. */
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ENERGY =
      COMPONENTS.registerComponentType(
          "handheld_energy",
          builder ->
              builder
                  .persistent(Codec.intRange(0, WirelessTerminalItem.CAPACITY))
                  .networkSynchronized(ByteBufCodecs.VAR_INT));

  public static final DeferredBlock<AccessPointBlock> ACCESS_POINT =
      BLOCKS.registerBlock(
          "wireless_access_point", AccessPointBlock::new, DeviceProperties.machine());

  public static final DeferredItem<BlockItem> ACCESS_POINT_ITEM =
      ITEMS.register(
          "wireless_access_point", () -> new BlockItem(ACCESS_POINT.get(), new Item.Properties()));

  public static final DeferredItem<WirelessTerminalItem> HANDHELD =
      ITEMS.register("wireless_terminal", WirelessTerminalItem::new);

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AccessPointEntity>>
      ACCESS_POINT_ENTITY =
          ENTITIES.register(
              "wireless_access_point",
              () ->
                  BlockEntityType.Builder.of(AccessPointEntity::new, ACCESS_POINT.get())
                      .build(null));

  public static final DeferredBlock<LinkEndpointBlock> LINK =
      BLOCKS.registerBlock(
          "wireless_link_endpoint", LinkEndpointBlock::new, DeviceProperties.machine());

  public static final DeferredItem<BlockItem> LINK_ITEM =
      ITEMS.register(
          "wireless_link_endpoint", () -> new BlockItem(LINK.get(), new Item.Properties()));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LinkEndpointEntity>>
      LINK_ENTITY =
          ENTITIES.register(
              "wireless_link_endpoint",
              () -> BlockEntityType.Builder.of(LinkEndpointEntity::new, LINK.get()).build(null));

  private WirelessContent() {}

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.EnergyStorage.BLOCK,
        ACCESS_POINT_ENTITY.get(),
        (point, face) -> point.energyStorage());
    event.registerBlockEntity(
        Capabilities.EnergyStorage.BLOCK, LINK_ENTITY.get(), (link, face) -> link.energyStorage());
    event.registerItem(
        Capabilities.EnergyStorage.ITEM,
        (stack, context) ->
            new ComponentEnergyStorage(
                stack,
                ENERGY.get(),
                WirelessTerminalItem.CAPACITY,
                WirelessTerminalItem.CHARGE_RATE,
                WirelessTerminalItem.CHARGE_RATE),
        HANDHELD.get());
  }

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    COMPONENTS.register(bus);
    bus.addListener(WirelessContent::capabilities);
  }
}
