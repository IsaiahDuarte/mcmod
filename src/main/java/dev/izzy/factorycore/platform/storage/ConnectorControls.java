package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.session.MenuSession;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Storage- and Machine-mode behavior of the unified {@link InterfaceBlock} (ADR 0059). */
final class ConnectorControls {
  private ConnectorControls() {}

  /** Placement: binds the connector or machine lease carried by (or created for) the item. */
  static void placed(Level level, BlockPos position, LivingEntity placer, ItemStack stack) {
    if (level.getBlockEntity(position) instanceof ConnectorEntity entity) {
      try {
        entity.place(stack);
      } catch (IllegalArgumentException | IllegalStateException failure) {
        if (placer instanceof Player player)
          player.displayClientMessage(
              Component.translatable("message.factorycore.connector_fault", failure.getMessage()),
              true);
      }
    }
  }

  /** Empty-hand use in Storage or Machine mode opens the settings screen (U16: no shortcuts). */
  static InteractionResult use(Level level, BlockPos position, Player player) {
    if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof ConnectorEntity entity)
        || !(player instanceof ServerPlayer server)) return InteractionResult.FAIL;
    try {
      if (entity instanceof MachineInterfaceEntity local) {
        if (!local.canUse(server)) return InteractionResult.FAIL;
        server.openMenu(local, b -> b.writeUUID(local.sessionForOpen()));
        return InteractionResult.CONSUME;
      }
      entity.validatePlaced();
      if (!entity.canUse(server)) return InteractionResult.FAIL;
      MenuSession.open(
          server,
          Component.translatable("gui.factorycore.interface.title.storage"),
          (id, inventory, session) -> new ConnectorSettingsMenu(id, inventory, entity, session));
      return InteractionResult.CONSUME;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      player.displayClientMessage(
          Component.translatable("message.factorycore.connector_fault", failure.getMessage()),
          true);
      return InteractionResult.FAIL;
    }
  }
}
