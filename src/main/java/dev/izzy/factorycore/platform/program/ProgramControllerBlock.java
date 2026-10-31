package dev.izzy.factorycore.platform.program;

import com.mojang.serialization.MapCodec;
import dev.izzy.factorycore.platform.CarriedComponents;
import dev.izzy.factorycore.platform.network.DeviceLamp;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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

/** Wired Program Controller. One deployed program runs in its segment. */
public final class ProgramControllerBlock extends NetworkedBlock
    implements EntityBlock, CarriedComponents, DeviceLamp.Legend {
  public static final MapCodec<ProgramControllerBlock> CODEC =
      simpleCodec(ProgramControllerBlock::new);

  public ProgramControllerBlock(Properties properties) {
    super(properties);
    registerDefaultState(stateDefinition.any().setValue(DeviceLamp.PROPERTY, DeviceLamp.OFF));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(DeviceLamp.PROPERTY);
  }

  /** Server lamp refresh: program state plus one bounded membership lookup per period. */
  @Override
  public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
      Level level, BlockState state, BlockEntityType<T> type) {
    return level.isClientSide()
        ? null
        : ticker(
            type,
            ProgramContent.ENTITY.get(),
            (tickLevel, position, tickState, controller) -> {
              if (!DeviceLamp.due(tickLevel, position)) return;
              DeviceLamp.show(tickLevel, position, tickState, lamp(controller, tickState));
            });
  }

  private static DeviceLamp lamp(ProgramControllerEntity controller, BlockState state) {
    var current = state.getValue(DeviceLamp.PROPERTY);
    return switch (controller.state()) {
      case STOPPED -> DeviceLamp.OFF;
      case PAUSED -> DeviceLamp.PAUSED;
      case TRAPPED -> DeviceLamp.FAULT;
      case RUNNING -> {
        var status =
            WorldNetworks.get(((ServerLevel) controller.getLevel()).getServer())
                .membership(controller.origin())
                .status();
        var network = DeviceLamp.of(status, current);
        yield network == DeviceLamp.OFF ? DeviceLamp.FAULT : network;
      }
    };
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
    return new ProgramControllerEntity(position, state);
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof ProgramControllerEntity controller))
      return InteractionResult.FAIL;
    if (player instanceof ServerPlayer server
        && WorldPrograms.get(server.server).open(server, controller))
      return InteractionResult.CONSUME;
    var status =
        WorldNetworks.get(((ServerLevel) level).getServer())
            .membership(controller.origin())
            .status();
    player.displayClientMessage(
        Component.translatable(
            "message.factorycore.program_status",
            Component.translatable(
                "status.factorycore.program." + controller.state().name().toLowerCase(Locale.ROOT)),
            controller.energy(),
            ProgramControllerEntity.CAPACITY,
            Component.translatable("status.factorycore." + status.name().toLowerCase(Locale.ROOT)),
            controller.message()),
        true);
    return InteractionResult.CONSUME;
  }

  @Override
  public List<DataComponentType<?>> carriedComponents() {
    return List.of(
        ProgramContent.DRAFT.get(),
        ProgramContent.ENERGY.get(),
        net.minecraft.core.component.DataComponents.CUSTOM_NAME);
  }

  @Override
  public String lampLegend() {
    return "tooltip.factorycore.lamp_legend_program";
  }
}
