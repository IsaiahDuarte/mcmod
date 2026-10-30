package dev.izzy.factorycore.platform.wireless;

import com.mojang.serialization.MapCodec;
import dev.izzy.factorycore.platform.network.DeviceLamp;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

public final class AccessPointBlock extends NetworkedBlock implements EntityBlock {
  public static final MapCodec<AccessPointBlock> CODEC = simpleCodec(AccessPointBlock::new);

  public AccessPointBlock(Properties properties) {
    super(properties);
    registerDefaultState(stateDefinition.any().setValue(DeviceLamp.PROPERTY, DeviceLamp.OFF));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(DeviceLamp.PROPERTY);
  }

  /**
   * Server lamp refresh, one bounded membership lookup per period: lit while connected and holding
   * a tick of FE, fault while connected without power or on an invalid network, off when unwired.
   */
  @Override
  public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
      Level level, BlockState state, BlockEntityType<T> type) {
    return level.isClientSide()
        ? null
        : ticker(
            type,
            WirelessContent.ACCESS_POINT_ENTITY.get(),
            (tickLevel, position, tickState, point) -> {
              if (!DeviceLamp.due(tickLevel, position)) return;
              var status =
                  WorldNetworks.get(((ServerLevel) tickLevel).getServer())
                      .membership(point.origin())
                      .status();
              var lamp = DeviceLamp.of(status, tickState.getValue(DeviceLamp.PROPERTY));
              if (lamp == DeviceLamp.ACTIVE && point.energy() < point.tickCost())
                lamp = DeviceLamp.FAULT;
              DeviceLamp.show(tickLevel, position, tickState, lamp);
            });
  }

  @Override
  protected MapCodec<? extends Block> codec() {
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
        WorldNetworks.get(((ServerLevel) level).getServer()).membership(point.origin()).status();
    player.displayClientMessage(
        Component.translatable(
            "message.factorycore.access_point_status",
            point.energy(),
            AccessPointEntity.CAPACITY,
            point.range(),
            Component.translatable("status.factorycore." + status.name().toLowerCase(Locale.ROOT))),
        true);
    return InteractionResult.CONSUME;
  }

  /** Q15: installed range upgrades come back with the Access Point. */
  @Override
  protected List<ItemStack> dropsOnBreak(BlockEntity blockEntity) {
    if (!(blockEntity instanceof AccessPointEntity point)) return List.of();
    return switch (point.tier()) {
      case BASE -> List.of();
      case ADVANCED -> List.of(new ItemStack(ThroughputContent.THROUGHPUT.get()));
      case ENDGAME ->
          List.of(
              new ItemStack(ThroughputContent.THROUGHPUT.get()),
              new ItemStack(ThroughputContent.OVERDRIVE.get()));
    };
  }
}
