package dev.izzy.factorycore.platform.network;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Cables have no entity; block callbacks invalidate indexed physical membership.
 *
 * <p>The six boolean properties are presentation only: they select the multipart arms and the
 * matching selection/collision shape. Discovery still reads block identity through {@link
 * WorldWireReader}, so a stale arm can never create or remove a network link.
 *
 * <p>Q29: {@link #COLOR} is not presentation: two cables whose colours do not {@link
 * CableColor#joins join} have no link, in discovery and in the arms alike. Use a dye on a placed
 * cable to colour it, or a wet sponge to clear it; the item keeps its colour in {@code
 * block_state}.
 *
 * <p>Q30: with {@link #FACADE} set the cable is a full block drawn as another block ({@link
 * FacadeEntity}, the only time a cable has an entity). The facade changes nothing about links;
 * breaking a faced cable takes the facade off first.
 */
public final class NetworkCableBlock extends NetworkedBlock implements EntityBlock {
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

  public static final EnumProperty<CableColor> COLOR =
      EnumProperty.create("color", CableColor.class);

  public static final BooleanProperty FACADE = BooleanProperty.create("facade");

  private final Map<BlockState, VoxelShape> shapes = new HashMap<>();

  public NetworkCableBlock(Properties properties) {
    super(properties);
    var initial = stateDefinition.any().setValue(COLOR, CableColor.NONE).setValue(FACADE, false);
    for (var side : PipeBlock.PROPERTY_BY_DIRECTION.values())
      initial = initial.setValue(side, false);
    registerDefaultState(initial);
    for (var state : stateDefinition.getPossibleStates()) {
      var shape = CORE;
      for (var side : Direction.values())
        if (state.getValue(property(side))) shape = Shapes.or(shape, ARMS.get(side));
      shapes.put(state, state.getValue(FACADE) ? Shapes.block() : shape.optimize());
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

  /** A cable's colour; NONE for any other block. */
  public static CableColor color(BlockState state) {
    return state.hasProperty(COLOR) ? state.getValue(COLOR) : CableColor.NONE;
  }

  /**
   * True when {@code self} and its neighbour on {@code towardNeighbor} are linked: the neighbour's
   * face is a wired port and their colours join.
   */
  public static boolean linksTo(BlockState self, BlockState neighbor, Direction towardNeighbor) {
    return linksTo(neighbor, towardNeighbor) && color(self).joins(color(neighbor));
  }

  /** The cable state with every arm recomputed from loaded neighbors. */
  public BlockState connected(BlockGetter level, BlockPos position) {
    return connected(level, position, CableColor.NONE);
  }

  /** As {@link #connected(BlockGetter, BlockPos)}, for a cable of {@code color}. */
  public BlockState connected(BlockGetter level, BlockPos position, CableColor color) {
    var state = defaultBlockState().setValue(COLOR, color);
    for (var side : Direction.values())
      state =
          state.setValue(
              property(side), linksTo(state, level.getBlockState(position.relative(side)), side));
    return state;
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(PipeBlock.PROPERTY_BY_DIRECTION.values().toArray(BooleanProperty[]::new));
    builder.add(COLOR, FACADE);
  }

  /** Only a faced cable has an entity: it holds the block the facade shows. */
  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return state.getValue(FACADE) ? new FacadeEntity(position, state) : null;
  }

  /** A facade going on or off changes no link, so the network need not rebuild. */
  @Override
  protected boolean cosmeticOnly(BlockState before, BlockState after) {
    return before.is(this)
            && after.is(this)
            && before.setValue(FACADE, false).equals(after.setValue(FACADE, false))
        || super.cosmeticOnly(before, after);
  }

  /** Q30: the facade a faced cable drops when it is broken outright (explosion, Wrench). */
  @Override
  protected List<ItemStack> dropsOnBreak(BlockEntity entity) {
    return entity instanceof FacadeEntity facade && facade.shown() != null
        ? List.of(FacadeItem.of(facade.shown(), 1))
        : List.of();
  }

  /**
   * Q30: a player breaking a faced cable takes only the facade off (dropped unless in creative);
   * the cable stays, with the same links.
   */
  @Override
  public boolean onDestroyedByPlayer(
      BlockState state,
      Level level,
      BlockPos position,
      Player player,
      boolean willHarvest,
      net.minecraft.world.level.material.FluidState fluid) {
    if (!state.getValue(FACADE))
      return super.onDestroyedByPlayer(state, level, position, player, willHarvest, fluid);
    if (!level.isClientSide()) {
      if (level.getBlockEntity(position) instanceof FacadeEntity facade
          && facade.shown() != null
          && !player.getAbilities().instabuild)
        popResource(level, position, FacadeItem.of(facade.shown(), 1));
      level.removeBlockEntity(position);
      level.setBlock(position, state.setValue(FACADE, false), Block.UPDATE_ALL);
    }
    return false;
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    var carried = context.getItemInHand().get(DataComponents.BLOCK_STATE);
    var color = carried == null ? null : carried.get(COLOR);
    return connected(
        context.getLevel(), context.getClickedPos(), color == null ? CableColor.NONE : color);
  }

  /** A cable item of {@code color}: plain when NONE, so it stacks with crafted cable. */
  public static ItemStack item(CableColor color, int count) {
    var stack = new ItemStack(NetworkContent.CABLE_ITEM.get(), count);
    if (color != CableColor.NONE)
      stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(COLOR, color));
    return stack;
  }

  /** The colour an item places, NONE for plain cable. */
  public static CableColor color(ItemStack stack) {
    var carried = stack.get(DataComponents.BLOCK_STATE);
    var color = carried == null ? null : carried.get(COLOR);
    return color == null ? CableColor.NONE : color;
  }

  @Override
  public ItemStack getCloneItemStack(
      BlockState state,
      net.minecraft.world.phys.HitResult target,
      LevelReader level,
      BlockPos position,
      Player player) {
    return item(state.getValue(COLOR), 1);
  }

  /**
   * Q29: a dye colours the cable (one dye, kept in creative); a wet sponge clears it. Either
   * rebuilds the network through the state change.
   */
  @Override
  protected ItemInteractionResult useItemOn(
      ItemStack held,
      BlockState state,
      Level level,
      BlockPos position,
      Player player,
      InteractionHand hand,
      BlockHitResult hit) {
    if (held.getItem() instanceof FacadeItem) {
      var shown = FacadeItem.shown(held);
      if (state.getValue(FACADE) || shown == null)
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
      if (!level.isClientSide()) {
        level.setBlock(position, state.setValue(FACADE, true), Block.UPDATE_ALL);
        if (level.getBlockEntity(position) instanceof FacadeEntity facade) facade.show(shown);
        held.consume(1, player);
      }
      return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }
    CableColor next;
    if (held.getItem() instanceof DyeItem dye) next = CableColor.of(dye.getDyeColor());
    else if (held.is(Items.WET_SPONGE)) next = CableColor.NONE;
    else return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    if (state.getValue(COLOR) == next) return ItemInteractionResult.CONSUME;
    if (!level.isClientSide()) {
      level.setBlock(
          position,
          connected(level, position, next).setValue(FACADE, state.getValue(FACADE)),
          Block.UPDATE_ALL);
      if (held.getItem() instanceof DyeItem) held.consume(1, player);
    }
    return ItemInteractionResult.sidedSuccess(level.isClientSide());
  }

  @Override
  protected BlockState updateShape(
      BlockState state,
      Direction side,
      BlockState neighbor,
      LevelAccessor level,
      BlockPos position,
      BlockPos neighborPosition) {
    return state.setValue(property(side), linksTo(state, neighbor, side));
  }

  @Override
  protected VoxelShape getShape(
      BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
    return shapes.get(state);
  }
}
