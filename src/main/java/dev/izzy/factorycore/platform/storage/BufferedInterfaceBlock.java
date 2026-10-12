package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

public final class BufferedInterfaceBlock extends Block implements EntityBlock {
  public BufferedInterfaceBlock(Properties properties) {
    super(properties);
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new BufferedInterfaceEntity(position, state);
  }

  @Override
  public void setPlacedBy(
      Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack) {
    super.setPlacedBy(level, position, state, placer, stack);
    if (!level.isClientSide()
        && placer != null
        && level.getBlockEntity(position) instanceof BufferedInterfaceEntity buffer)
      try {
        buffer.place(stack, placer.getUUID());
      } catch (IllegalArgumentException | IllegalStateException failure) {
        if (placer instanceof Player player)
          player.displayClientMessage(
              Component.translatable("message.factorycore.buffer_fault", failure.getMessage()),
              true);
      }
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof BufferedInterfaceEntity buffer))
      return InteractionResult.FAIL;
    try {
      buffer.validatePlaced();
      var actor =
          new NetworkPermissions.Actor(
              player.getUUID(), player instanceof ServerPlayer server && server.hasPermissions(2));
      if (WorldNetworks.get(buffer.serverLevel().getServer())
              .authorize(actor, buffer.position(), NetworkPermissions.Permission.VIEW)
          != NetworkAuthority.Decision.ALLOWED) {
        player.displayClientMessage(
            Component.translatable("message.factorycore.buffer_denied"), true);
        return InteractionResult.FAIL;
      }
      var slots = buffer.inventory().snapshot().slots();
      int items = slots.stream().mapToInt(slot -> slot.count()).sum();
      long occupied = slots.stream().filter(slot -> slot.key() != null).count();
      player.displayClientMessage(
          Component.translatable("message.factorycore.buffer_status", items, occupied), false);
      return InteractionResult.CONSUME;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      player.displayClientMessage(
          Component.translatable("message.factorycore.buffer_fault", failure.getMessage()), true);
      return InteractionResult.FAIL;
    }
  }

  @Override
  protected List<ItemStack> getDrops(BlockState state, LootParams.Builder parameters) {
    return List.of();
  }

  @Override
  protected void onPlace(
      BlockState state, Level level, BlockPos position, BlockState previous, boolean moved) {
    super.onPlace(state, level, position, previous, moved);
    WorldNetworks.changed(level, position);
  }

  @Override
  protected void neighborChanged(
      BlockState state,
      Level level,
      BlockPos position,
      Block neighbor,
      BlockPos from,
      boolean moved) {
    super.neighborChanged(state, level, position, neighbor, from, moved);
    WorldNetworks.changed(level, position);
  }

  @Override
  protected void onRemove(
      BlockState state, Level level, BlockPos position, BlockState next, boolean moved) {
    WorldNetworks.changed(level, position);
    if (!state.is(next.getBlock())
        && !level.isClientSide()
        && level.getBlockEntity(position) instanceof BufferedInterfaceEntity buffer)
      Containers.dropItemStack(
          level,
          position.getX() + .5,
          position.getY() + .5,
          position.getZ() + .5,
          buffer.portableForBreak());
    super.onRemove(state, level, position, next, moved);
  }
}
