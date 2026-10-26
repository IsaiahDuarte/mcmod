package dev.izzy.factorycore.clienttesting;

import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.PortContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Places the Machine ports the verification worlds use since H7 removed Machine mode. */
final class Ports {
  private Ports() {}

  /** A cable port at {@code at} aimed at {@code facing}, with a fresh lease (ADR 0065). */
  static MachineInterfaceEntity cable(ServerLevel level, BlockPos at, Direction facing) {
    var block = PortContent.CABLE_PORT.get();
    level.setBlock(at, block.aimed(level, at, facing), 3);
    var port = (MachineInterfaceEntity) level.getBlockEntity(at);
    port.place(new ItemStack(PortContent.CABLE_PORT_ITEM.get()));
    return port;
  }

  /**
   * An Exporter at {@code at} aimed at {@code facing}, placed without a placer (it stays paused).
   */
  static MachineInterfaceEntity exporter(ServerLevel level, BlockPos at, Direction facing) {
    var block = PortContent.EXPORTER.get();
    level.setBlock(
        at,
        block
            .defaultBlockState()
            .setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                facing),
        3);
    var port = (MachineInterfaceEntity) level.getBlockEntity(at);
    port.place(new ItemStack(PortContent.EXPORTER_ITEM.get()));
    return port;
  }
}
