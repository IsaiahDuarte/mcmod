package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.network.NetworkCableBlock;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A cable port (playtest H5, ADR 0065): a network cable that reaches the machine beside it. The
 * Label Tool makes one by labelling a machine that touches a cable; that cable becomes this block,
 * aimed at the machine ({@code FACING}), and keeps connecting the network on its other five faces.
 * It is backed by the Machine-port device ({@link MachineInterfaceEntity}), so programs reach the
 * machine by its label on any side, with the Interface's leases, staging and recovery. Breaking an
 * idle port retires its lease and drops a plain cable; one with work in transit drops a port item
 * that carries the lease, so the work can still be recovered.
 */
public final class CablePortBlock extends NetworkedBlock implements EntityBlock {
  private static final VoxelShape CORE = box(6, 6, 6, 10, 10, 10);
  private static final Map<Direction, VoxelShape> ARMS = new EnumMap<>(Direction.class),
      PLATES = new EnumMap<>(Direction.class);

  static {
    ARMS.put(Direction.NORTH, box(6, 6, 0, 10, 10, 6));
    ARMS.put(Direction.SOUTH, box(6, 6, 10, 10, 10, 16));
    ARMS.put(Direction.WEST, box(0, 6, 6, 6, 10, 10));
    ARMS.put(Direction.EAST, box(10, 6, 6, 16, 10, 10));
    ARMS.put(Direction.DOWN, box(6, 0, 6, 10, 6, 10));
    ARMS.put(Direction.UP, box(6, 10, 6, 10, 16, 10));
    PLATES.put(Direction.NORTH, Shapes.or(box(3, 3, 0, 13, 13, 2), box(6, 6, 2, 10, 10, 6)));
    PLATES.put(Direction.SOUTH, Shapes.or(box(3, 3, 14, 13, 13, 16), box(6, 6, 10, 10, 10, 14)));
    PLATES.put(Direction.WEST, Shapes.or(box(0, 3, 3, 2, 13, 13), box(2, 6, 6, 6, 10, 10)));
    PLATES.put(Direction.EAST, Shapes.or(box(14, 3, 3, 16, 13, 13), box(10, 6, 6, 14, 10, 10)));
    PLATES.put(Direction.DOWN, Shapes.or(box(3, 0, 3, 13, 2, 13), box(6, 2, 6, 10, 6, 10)));
    PLATES.put(Direction.UP, Shapes.or(box(3, 14, 3, 13, 16, 13), box(6, 10, 6, 10, 14, 10)));
  }

  private final Map<BlockState, VoxelShape> shapes = new HashMap<>();

  public CablePortBlock(Properties properties) {
    super(properties);
    var initial = stateDefinition.any().setValue(BlockStateProperties.FACING, Direction.NORTH);
    for (var side : PipeBlock.PROPERTY_BY_DIRECTION.values())
      initial = initial.setValue(side, false);
    registerDefaultState(initial);
    for (var state : stateDefinition.getPossibleStates()) {
      var shape = Shapes.or(CORE, PLATES.get(state.getValue(BlockStateProperties.FACING)));
      for (var side : Direction.values())
        if (state.getValue(NetworkCableBlock.property(side)))
          shape = Shapes.or(shape, ARMS.get(side));
      shapes.put(state, shape.optimize());
    }
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(BlockStateProperties.FACING);
    builder.add(PipeBlock.PROPERTY_BY_DIRECTION.values().toArray(BooleanProperty[]::new));
  }

  /**
   * The port aimed at {@code toward}, with arms drawn to the wired neighbours of the other faces.
   */
  public BlockState aimed(BlockGetter level, BlockPos position, Direction toward) {
    var state = defaultBlockState().setValue(BlockStateProperties.FACING, toward);
    for (var side : Direction.values())
      state =
          state.setValue(
              NetworkCableBlock.property(side),
              side != toward
                  && NetworkCableBlock.linksTo(level.getBlockState(position.relative(side)), side));
    return state;
  }

  /** A re-placed port item (one that carried a lease) aims at the block it was placed against. */
  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return aimed(
        context.getLevel(), context.getClickedPos(), context.getClickedFace().getOpposite());
  }

  @Override
  protected BlockState updateShape(
      BlockState state,
      Direction side,
      BlockState neighbor,
      LevelAccessor level,
      BlockPos position,
      BlockPos neighborPosition) {
    return side == state.getValue(BlockStateProperties.FACING)
        ? state
        : state.setValue(
            NetworkCableBlock.property(side), NetworkCableBlock.linksTo(neighbor, side));
  }

  @Override
  protected VoxelShape getShape(
      BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
    return shapes.get(state);
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new MachineInterfaceEntity(position, state);
  }

  /** Programs read this port's redstone input ({@code redstone(machines(...))}). */
  @Override
  protected void neighborChanged(
      BlockState state,
      Level level,
      BlockPos position,
      Block neighbor,
      BlockPos from,
      boolean moved) {
    super.neighborChanged(state, level, position, neighbor, from, moved);
    if (!level.isClientSide()
        && level.getBlockEntity(position) instanceof RedstoneControlled controlled
        && controlled.redstone().observe(level.hasNeighborSignal(position)))
      controlled.redstoneWake();
  }

  @Override
  public void setPlacedBy(
      Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack) {
    super.setPlacedBy(level, position, state, placer, stack);
    if (!level.isClientSide()) ConnectorControls.placed(level, position, placer, stack);
  }

  /** Deferred work: the label a Label Tool asked for, once the network is valid again. */
  @Override
  protected void tick(BlockState state, ServerLevel level, BlockPos position, RandomSource random) {
    if (level.getBlockEntity(position) instanceof MachineInterfaceEntity port
        && (port.applyPendingLabel() | port.applyPendingResume()))
      level.scheduleTick(position, this, MachineInterfaceEntity.RESUME_TICKS);
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    return ConnectorControls.use(level, position, player);
  }

  @Override
  public boolean dropsLeasedItem() {
    return true;
  }

  @Override
  protected List<ItemStack> dropsOnBreak(BlockEntity entity) {
    return entity instanceof MachineInterfaceEntity port
        ? List.of(port.cablePortBreak())
        : List.of();
  }

  /** Cables connect on every face but the one aimed at the machine. */
  @Override
  public boolean wiredFace(BlockState state, Direction face) {
    return face != state.getValue(BlockStateProperties.FACING);
  }
}
