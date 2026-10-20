package dev.izzy.factorycore.platform.network;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Cables have no entity; block callbacks invalidate indexed physical membership.
 *
 * <p>The six boolean properties are presentation only: they select the multipart arms and the
 * matching selection/collision shape. Discovery still reads block identity through {@link
 * WorldWireReader}, so a stale arm can never create or remove a network link.
 */
public final class NetworkCableBlock extends NetworkedBlock {
  private static final VoxelShape CORE = box(6, 6, 6, 10, 10, 10);
  private static final Map<Direction, VoxelShape> ARMS = new EnumMap<>(Direction.class);

  static {
    ARMS.put(Direction.NORTH, box(6, 6, 0, 10, 10, 6));
    ARMS.put(Direction.SOUTH, box(6, 6, 10, 10, 10, 16));
    ARMS.put(Direction.WEST, box(0, 6, 6, 6, 10, 10));
    ARMS.put(Direction.EAST, box(10, 6, 6, 16, 10, 10));
    ARMS.put(Direction.DOWN, box(6, 0, 6, 10, 6, 10));
    ARMS.put(Direction.UP, box(6, 10, 6, 10, 16, 10));
  }

  private final Map<BlockState, VoxelShape> shapes = new HashMap<>();

  public NetworkCableBlock(Properties properties) {
    super(properties);
    var initial = stateDefinition.any();
    for (var side : PipeBlock.PROPERTY_BY_DIRECTION.values())
      initial = initial.setValue(side, false);
    registerDefaultState(initial);
    for (var state : stateDefinition.getPossibleStates()) {
      var shape = CORE;
      for (var side : Direction.values())
        if (state.getValue(property(side))) shape = Shapes.or(shape, ARMS.get(side));
      shapes.put(state, shape.optimize());
    }
  }

  public static BooleanProperty property(Direction side) {
    return PipeBlock.PROPERTY_BY_DIRECTION.get(side);
  }

  /**
   * True when a cable arm should be drawn toward {@code neighbor}: its face toward this cable is a
   * wired port ({@link NetworkedBlock#wiredFace}).
   */
  public static boolean linksTo(BlockState neighbor, Direction towardNeighbor) {
    return NetworkedBlock.wired(neighbor, towardNeighbor.getOpposite());
  }

  /** The cable state with every arm recomputed from loaded neighbors. */
  public BlockState connected(BlockGetter level, BlockPos position) {
    var state = defaultBlockState();
    for (var side : Direction.values())
      state =
          state.setValue(
              property(side), linksTo(level.getBlockState(position.relative(side)), side));
    return state;
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(PipeBlock.PROPERTY_BY_DIRECTION.values().toArray(BooleanProperty[]::new));
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return connected(context.getLevel(), context.getClickedPos());
  }

  @Override
  protected BlockState updateShape(
      BlockState state,
      Direction side,
      BlockState neighbor,
      LevelAccessor level,
      BlockPos position,
      BlockPos neighborPosition) {
    return state.setValue(property(side), linksTo(neighbor, side));
  }

  @Override
  protected VoxelShape getShape(
      BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
    return shapes.get(state);
  }
}
