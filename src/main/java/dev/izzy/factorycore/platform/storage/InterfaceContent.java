package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import dev.izzy.factorycore.platform.LeasedBlockItem;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * The unified Interface block and item (ADR 0059). The three backing block-entity types stay in
 * {@link ConnectorContent}, {@link MachineInterfaceContent} and {@link BufferedInterfaceContent}.
 */
public final class InterfaceContent {
  public static final DeferredBlock<InterfaceBlock> BLOCK =
      FactoryRegistries.BLOCKS.registerBlock(
          "interface", InterfaceBlock::new, DeviceProperties.machine());
  public static final DeferredItem<LeasedBlockItem> ITEM =
      FactoryRegistries.ITEMS.register(
          "interface",
          () ->
              new LeasedBlockItem(
                  BLOCK.get(),
                  LeasedBlockItem.Tooltip.lines(
                          "tooltip.factorycore.interface",
                          "tooltip.factorycore.interface_modes",
                          "tooltip.factorycore.interface_use")
                      .then(
                          LeasedBlockItem.Tooltip.throughput(
                              ConnectorData.FIELD, tag -> ConnectorData.read(tag).tier()))
                      .then(
                          LeasedBlockItem.Tooltip.throughput(
                              BufferData.FIELD, tag -> BufferData.read(tag).tier()))));

  private InterfaceContent() {}

  /** The Interface in {@code kind}, its socket toward {@code facing} (ignored for Buffer). */
  public static BlockState state(InterfaceKind kind, Direction facing) {
    return BLOCK
        .get()
        .defaultBlockState()
        .setValue(InterfaceBlock.KIND, kind)
        .setValue(BlockStateProperties.FACING, facing);
  }

  public static void register(IEventBus bus) {
    // Loading this class adds its entries to FactoryRegistries before registration.
  }
}
