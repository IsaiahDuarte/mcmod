package dev.izzy.factorycore.platform.program;

import com.mojang.serialization.MapCodec;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Wired Program Controller. One deployed program runs in its segment. */
public final class ProgramControllerBlock extends BaseEntityBlock {
  public static final MapCodec<ProgramControllerBlock> CODEC =
      simpleCodec(ProgramControllerBlock::new);

  public ProgramControllerBlock(Properties properties) {
    super(properties);
  }

  @Override
  protected MapCodec<? extends BaseEntityBlock> codec() {
    return CODEC;
  }

  @Override
  protected RenderShape getRenderShape(BlockState state) {
    return RenderShape.MODEL;
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new ProgramControllerEntity(position, state);
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof ProgramControllerEntity controller))
      return InteractionResult.FAIL;
    if (player instanceof net.minecraft.server.level.ServerPlayer server
        && dev.izzy.factorycore.platform.storage.WorldPrograms.get(server.server)
            .open(server, controller)) return InteractionResult.CONSUME;
    var status =
        WorldNetworks.get(((net.minecraft.server.level.ServerLevel) level).getServer())
            .membership(controller.origin())
            .status();
    player.displayClientMessage(
        Component.translatable(
            "message.factorycore.program_status",
            Component.translatable(
                "status.factorycore.program."
                    + controller.state().name().toLowerCase(java.util.Locale.ROOT)),
            controller.energy(),
            ProgramControllerEntity.CAPACITY,
            Component.translatable(
                "status.factorycore." + status.name().toLowerCase(java.util.Locale.ROOT)),
            controller.message()),
        true);
    return InteractionResult.CONSUME;
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
    super.onRemove(state, level, position, next, moved);
  }
}
