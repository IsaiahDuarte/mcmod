package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** Buffer-mode behavior of the unified {@link InterfaceBlock} (ADR 0059). */
final class BufferControls {
  private BufferControls() {}

  /**
   * Placement: binds the lease, then asks for the U6 Import default under a live placing player.
   */
  static void placed(
      Block block, Level level, BlockPos position, LivingEntity placer, ItemStack stack) {
    if (placer == null
        || !(level.getBlockEntity(position) instanceof BufferedInterfaceEntity buffer)) return;
    try {
      buffer.place(stack, placer.getUUID());
      if (placer instanceof ServerPlayer) {
        buffer.requestDefaultImport(placer.getUUID());
        level.scheduleTick(position, block, BufferedInterfaceEntity.DEFAULT_IMPORT_TICKS);
      }
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
      if (placer instanceof Player player)
        player.displayClientMessage(
            Component.translatable("message.factorycore.buffer_fault", failure.getMessage()), true);
    }
  }

  /** Empty-hand use in Buffer mode opens the settings screen (U16: no shortcuts). */
  static InteractionResult use(Level level, BlockPos position, Player player) {
    if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof BufferedInterfaceEntity buffer)
        || !(player instanceof ServerPlayer server)) return InteractionResult.FAIL;
    try {
      buffer.validatePlaced();
      if (!WorldNetworks.get(server.server)
          .allows(Actors.of(server), buffer.position(), NetworkPermissions.Permission.VIEW)) {
        player.displayClientMessage(
            Component.translatable("message.factorycore.buffer_denied"), true);
        return InteractionResult.FAIL;
      }
      // Contents, policy, rate and import state are on the screen and its status strip (U7).
      if (buffer.canUse(server))
        server.openMenu(
            buffer,
            b -> {
              b.writeBlockPos(position);
              b.writeUUID(buffer.sessionForOpen());
            });
      return InteractionResult.CONSUME;
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
      player.displayClientMessage(
          Component.translatable("message.factorycore.buffer_fault", failure.getMessage()), true);
      return InteractionResult.FAIL;
    }
  }
}
