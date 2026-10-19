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
    registerDefaultState(
        stateDefinition
            .any()
            .setValue(
                dev.izzy.factorycore.platform.network.DeviceLamp.PROPERTY,
                dev.izzy.factorycore.platform.network.DeviceLamp.OFF));
  }

  @Override
  protected void createBlockStateDefinition(
      net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(dev.izzy.factorycore.platform.network.DeviceLamp.PROPERTY);
  }

  /** Server lamp refresh: program state plus one bounded membership lookup per period. */
  @Override
  public <T extends BlockEntity>
      net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
          Level level,
          BlockState state,
          net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
    return level.isClientSide()
        ? null
        : createTickerHelper(
            type,
            ProgramContent.ENTITY.get(),
            (tickLevel, position, tickState, controller) -> {
              if (!dev.izzy.factorycore.platform.network.DeviceLamp.due(tickLevel, position))
                return;
              dev.izzy.factorycore.platform.network.DeviceLamp.show(
                  tickLevel, position, tickState, lamp(controller, tickState));
            });
  }

  private static dev.izzy.factorycore.platform.network.DeviceLamp lamp(
      ProgramControllerEntity controller, BlockState state) {
    var current = state.getValue(dev.izzy.factorycore.platform.network.DeviceLamp.PROPERTY);
    return switch (controller.state()) {
      case STOPPED -> dev.izzy.factorycore.platform.network.DeviceLamp.OFF;
      case PAUSED -> dev.izzy.factorycore.platform.network.DeviceLamp.PAUSED;
      case TRAPPED -> dev.izzy.factorycore.platform.network.DeviceLamp.FAULT;
      case RUNNING -> {
        var status =
            WorldNetworks.get(
                    ((net.minecraft.server.level.ServerLevel) controller.getLevel()).getServer())
                .membership(controller.origin())
                .status();
        var network = dev.izzy.factorycore.platform.network.DeviceLamp.of(status, current);
        yield network == dev.izzy.factorycore.platform.network.DeviceLamp.OFF
            ? dev.izzy.factorycore.platform.network.DeviceLamp.FAULT
            : network;
      }
    };
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
    if (!dev.izzy.factorycore.platform.network.DeviceLamp.onlyLampChanged(previous, state))
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
    if (!dev.izzy.factorycore.platform.network.DeviceLamp.onlyLampChanged(state, next))
      WorldNetworks.changed(level, position);
    super.onRemove(state, level, position, next, moved);
  }
}
