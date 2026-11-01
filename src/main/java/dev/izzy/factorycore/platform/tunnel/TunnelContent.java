package dev.izzy.factorycore.platform.tunnel;

import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/** Q37: the Tunnel's registrations. */
public final class TunnelContent {
  public static final DeferredBlock<TunnelBlock> BLOCK =
      FactoryRegistries.BLOCKS.registerBlock(
          "tunnel", TunnelBlock::new, DeviceProperties.machine());

  public static final DeferredItem<BlockItem> ITEM =
      FactoryRegistries.ITEMS.register(
          "tunnel", () -> new BlockItem(BLOCK.get(), new Item.Properties()));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TunnelEntity>> ENTITY =
      FactoryRegistries.entity("tunnel", TunnelEntity::new, BLOCK);

  private TunnelContent() {}

  public static void register(IEventBus bus) {
    bus.addListener(TunnelContent::capabilities);
  }

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.ItemHandler.BLOCK, ENTITY.get(), (tunnel, face) -> tunnel.items(face));
    event.registerBlockEntity(
        Capabilities.FluidHandler.BLOCK, ENTITY.get(), (tunnel, face) -> tunnel.fluids(face));
    event.registerBlockEntity(
        Capabilities.EnergyStorage.BLOCK, ENTITY.get(), (tunnel, face) -> tunnel.energy(face));
  }
}
