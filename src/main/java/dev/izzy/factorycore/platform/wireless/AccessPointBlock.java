package dev.izzy.factorycore.platform.wireless;

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

public final class AccessPointBlock extends BaseEntityBlock {
  public static final MapCodec<AccessPointBlock> CODEC = simpleCodec(AccessPointBlock::new);

  public AccessPointBlock(Properties properties) {
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

  /**
   * Server lamp refresh, one bounded membership lookup per period: lit while connected and holding
   * a tick of FE, fault while connected without power or on an invalid network, off when unwired.
   */
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
            WirelessContent.ACCESS_POINT_ENTITY.get(),
            (tickLevel, position, tickState, point) -> {
              if (!dev.izzy.factorycore.platform.network.DeviceLamp.due(tickLevel, position))
                return;
              var status =
                  WorldNetworks.get(
                          ((net.minecraft.server.level.ServerLevel) tickLevel).getServer())
                      .membership(point.origin())
                      .status();
              var lamp =
                  dev.izzy.factorycore.platform.network.DeviceLamp.of(
                      status,
                      tickState.getValue(
                          dev.izzy.factorycore.platform.network.DeviceLamp.PROPERTY));
              if (lamp == dev.izzy.factorycore.platform.network.DeviceLamp.ACTIVE
                  && point.energy() < AccessPointEntity.TICK_COST)
                lamp = dev.izzy.factorycore.platform.network.DeviceLamp.FAULT;
              dev.izzy.factorycore.platform.network.DeviceLamp.show(
                  tickLevel, position, tickState, lamp);
            });
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
    return new AccessPointEntity(position, state);
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof AccessPointEntity point))
      return InteractionResult.FAIL;
    var status =
        WorldNetworks.get(((net.minecraft.server.level.ServerLevel) level).getServer())
            .membership(point.origin())
            .status();
    player.displayClientMessage(
        Component.translatable(
            "message.factorycore.access_point_status",
            point.energy(),
            AccessPointEntity.CAPACITY,
            AccessPointEntity.RANGE,
            Component.translatable(
                "status.factorycore." + status.name().toLowerCase(java.util.Locale.ROOT))),
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
