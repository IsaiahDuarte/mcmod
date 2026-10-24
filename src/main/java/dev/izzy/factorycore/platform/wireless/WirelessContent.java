package dev.izzy.factorycore.platform.wireless;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import dev.izzy.factorycore.platform.network.NetworkContent;
import net.minecraft.core.component.DataComponentType;
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

public final class WirelessContent {

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
      FactoryRegistries.COMPONENTS.registerComponentType(
          "wireless_binding",
          builder -> builder.persistent(Binding.CODEC).networkSynchronized(Binding.STREAM_CODEC));

  /** FE stored in a handheld terminal. */
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ENERGY =
      FactoryRegistries.COMPONENTS.registerComponentType(
          "handheld_energy",
          builder ->
              builder
                  .persistent(Codec.intRange(0, WirelessTerminalItem.CAPACITY))
                  .networkSynchronized(ByteBufCodecs.VAR_INT));

  public static final DeferredBlock<AccessPointBlock> ACCESS_POINT =
      FactoryRegistries.BLOCKS.registerBlock(
          "wireless_access_point", AccessPointBlock::new, DeviceProperties.machine());

  public static final DeferredItem<BlockItem> ACCESS_POINT_ITEM =
      FactoryRegistries.ITEMS.register(
          "wireless_access_point", () -> new BlockItem(ACCESS_POINT.get(), new Item.Properties()));

  public static final DeferredItem<WirelessTerminalItem> HANDHELD =
      FactoryRegistries.ITEMS.register("wireless_terminal", WirelessTerminalItem::new);

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AccessPointEntity>>
      ACCESS_POINT_ENTITY =
          FactoryRegistries.entity("wireless_access_point", AccessPointEntity::new, ACCESS_POINT);

  /** The Wireless Link gateway upgrade (ADR 0061). */
  public static final DeferredItem<LinkItem> LINK_ITEM =
      FactoryRegistries.ITEMS.register("wireless_link", LinkItem::new);

  private WirelessContent() {}

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.EnergyStorage.BLOCK,
        ACCESS_POINT_ENTITY.get(),
        (point, face) -> point.energyStorage());
    // A gateway accepts FE from any face only while it carries a wireless link.
    event.registerBlockEntity(
        Capabilities.EnergyStorage.BLOCK,
        NetworkContent.NODE_ENTITY.get(),
        (node, face) -> node.link() == null ? null : node.link().energyStorage());
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
    bus.addListener(WirelessContent::capabilities);
  }
}
