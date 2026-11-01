package dev.izzy.factorycore.platform.tunnel;

import dev.izzy.factorycore.platform.network.NetworkedBlock;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Q37 (M36, QD10, ADR 0071): a point-to-point Tunnel. Its front face is its working face: an input
 * tunnel accepts items, fluids and FE there and forwards them to the blocks in front of its
 * outputs, and each output repeats the redstone signal the input receives. Its other faces join the
 * network; every end must be on one valid network and loaded.
 */
public final class TunnelBlock extends NetworkedBlock implements EntityBlock {
  public enum Role implements StringRepresentable {
    UNSET,
    INPUT,
    OUTPUT;

    @Override
    public String getSerializedName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public static final EnumProperty<Role> ROLE = EnumProperty.create("role", Role.class);

  public TunnelBlock(Properties properties) {
    super(properties);
    registerDefaultState(
        stateDefinition
            .any()
            .setValue(BlockStateProperties.FACING, Direction.NORTH)
            .setValue(ROLE, Role.UNSET));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(BlockStateProperties.FACING, ROLE);
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return defaultBlockState()
        .setValue(BlockStateProperties.FACING, context.getClickedFace().getOpposite());
  }

  /** The front face works on the block it faces; only the other faces join the network. */
  @Override
  public boolean wiredFace(BlockState state, Direction face) {
    return face != state.getValue(BlockStateProperties.FACING);
  }

  /** A role change is not a topology change. */
  @Override
  protected boolean cosmeticOnly(BlockState before, BlockState after) {
    return before.getBlock() == after.getBlock()
        && before.getValue(BlockStateProperties.FACING)
            == after.getValue(BlockStateProperties.FACING);
  }

  @Override
  public void setPlacedBy(
      Level level,
      BlockPos position,
      BlockState state,
      net.minecraft.world.entity.LivingEntity placer,
      net.minecraft.world.item.ItemStack stack) {
    super.setPlacedBy(level, position, state, placer, stack);
    if (!level.isClientSide()
        && placer instanceof Player player
        && level.getBlockEntity(position) instanceof TunnelEntity tunnel)
      tunnel.place(player.getUUID());
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new TunnelEntity(position, state);
  }

  @Override
  public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
      Level level, BlockState state, BlockEntityType<T> type) {
    return level.isClientSide() || type != TunnelContent.ENTITY.get()
        ? null
        : (l, p, s, entity) -> ((TunnelEntity) entity).serverTick();
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (player instanceof ServerPlayer server
        && level.getBlockEntity(position) instanceof TunnelEntity tunnel) {
      if (player.isShiftKeyDown()) TunnelPairing.use(server, tunnel);
      else tunnel.describe(server);
    }
    return InteractionResult.CONSUME;
  }

  @Override
  protected boolean isSignalSource(BlockState state) {
    return state.getValue(ROLE) == Role.OUTPUT;
  }

  /** An output powers the block it faces with the input's signal. */
  @Override
  protected int getSignal(BlockState state, BlockGetter level, BlockPos position, Direction from) {
    if (state.getValue(ROLE) != Role.OUTPUT
        || from != state.getValue(BlockStateProperties.FACING).getOpposite()) return 0;
    return level.getBlockEntity(position) instanceof TunnelEntity tunnel ? tunnel.signal() : 0;
  }

  @Override
  protected int getDirectSignal(
      BlockState state, BlockGetter level, BlockPos position, Direction from) {
    return getSignal(state, level, position, from);
  }

  @Override
  protected void neighborChanged(
      BlockState state,
      Level level,
      BlockPos position,
      Block block,
      BlockPos neighbour,
      boolean moving) {
    super.neighborChanged(state, level, position, block, neighbour, moving);
    if (!level.isClientSide()
        && state.getValue(ROLE) == Role.INPUT
        && level.getBlockEntity(position) instanceof TunnelEntity tunnel) tunnel.readSignal();
  }
}
