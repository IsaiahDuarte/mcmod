package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Importer and Exporter (playtest H6, ADR 0066): a block placed against a machine, like a hopper or
 * another mod's import/export bus. Its socket faces the block it was placed on; cables connect on
 * the other five faces. It is backed by the Machine-port device ({@link MachineInterfaceEntity}),
 * so leases, rates, staging, recovery, labels and upgrades are the Interface's. The role fixes
 * which presets it can run: an Importer only imports, an Exporter exports or keeps a stock.
 */
public final class PortBlock extends NetworkedBlock implements EntityBlock {
  public enum Role {
    IMPORTER,
    EXPORTER;

    /** The preset modes this role may deploy; Paused is always allowed. */
    public boolean allows(MachinePresets.Mode mode) {
      return mode == MachinePresets.Mode.PAUSED
          || (this == IMPORTER
              ? mode == MachinePresets.Mode.IMPORT
              : mode == MachinePresets.Mode.EXPORT || mode == MachinePresets.Mode.STOCK);
    }
  }

  /**
   * Q34/Q35 (QD9, ADR 0070): what the port does with the world in front of it. A Constructor is an
   * Exporter into its own buffer that places, launches or drops from it; a Destructor is an
   * Importer from its own buffer that it breaks blocks and picks up items into.
   */
  public enum WorldAction {
    NONE,
    CONSTRUCT,
    DESTRUCT
  }

  private final Role role;
  private final WorldAction action;

  public PortBlock(Role role, Properties properties) {
    this(role, WorldAction.NONE, properties);
  }

  public PortBlock(Role role, WorldAction action, Properties properties) {
    super(properties);
    this.role = role;
    this.action = action;
    registerDefaultState(
        stateDefinition.any().setValue(BlockStateProperties.FACING, Direction.NORTH));
  }

  public Role role() {
    return role;
  }

  /** The world action of the port at {@code state}; NONE for any other block. */
  public static WorldAction action(BlockState state) {
    return state.getBlock() instanceof PortBlock port ? port.action : WorldAction.NONE;
  }

  @Override
  public <T extends BlockEntity>
      net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
          Level level,
          BlockState state,
          net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
    if (level.isClientSide() || action == WorldAction.NONE) return null;
    return ticker(
        type,
        MachineInterfaceContent.ENTITY.get(),
        (tickLevel, position, tickState, port) -> port.worldTick());
  }

  /** The role of the port at {@code state}, or null for any other block (an Interface). */
  public static Role role(BlockState state) {
    return state.getBlock() instanceof PortBlock port ? port.role : null;
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(BlockStateProperties.FACING);
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return defaultBlockState()
        .setValue(BlockStateProperties.FACING, context.getClickedFace().getOpposite());
  }

  private static final Map<Direction, VoxelShape> SOCKETS =
      Map.of(
          Direction.NORTH, Shapes.or(Block.box(0, 0, 3, 16, 16, 16), Block.box(3, 3, 0, 13, 13, 3)),
          Direction.SOUTH,
              Shapes.or(Block.box(0, 0, 0, 16, 16, 13), Block.box(3, 3, 13, 13, 13, 16)),
          Direction.EAST,
              Shapes.or(Block.box(0, 0, 0, 13, 16, 16), Block.box(13, 3, 3, 16, 13, 13)),
          Direction.WEST, Shapes.or(Block.box(3, 0, 0, 16, 16, 16), Block.box(0, 3, 3, 3, 13, 13)),
          Direction.UP, Shapes.or(Block.box(0, 0, 0, 16, 13, 16), Block.box(3, 13, 3, 13, 16, 13)),
          Direction.DOWN, Shapes.or(Block.box(0, 3, 0, 16, 16, 16), Block.box(3, 0, 3, 13, 3, 13)));

  @Override
  protected VoxelShape getShape(
      BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
    return SOCKETS.get(state.getValue(BlockStateProperties.FACING));
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new MachineInterfaceEntity(position, state);
  }

  /** C2: the preset reads this block's redstone input. */
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
    if (level.isClientSide()) return;
    ConnectorControls.placed(level, position, placer, stack);
    if (placer instanceof Player player
        && level.getBlockEntity(position) instanceof MachineInterfaceEntity port) {
      port.requestDefaultPreset(player.getUUID());
      level.scheduleTick(position, this, MachineInterfaceEntity.RESUME_TICKS);
    }
  }

  /** Deferred work after placement or an upgrade: the default preset, or a deployer's resume. */
  @Override
  protected void tick(BlockState state, ServerLevel level, BlockPos position, RandomSource random) {
    if (level.getBlockEntity(position) instanceof MachineInterfaceEntity port
        && (port.applyDefaultPreset() | port.applyPendingResume()))
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
    if (!(entity instanceof ConnectorEntity connector)) return List.of();
    // Q34/Q35: a Constructor's or Destructor's buffer comes back with it.
    var drops = new java.util.ArrayList<ItemStack>();
    drops.add(connector.portableForBreak());
    if (entity instanceof MachineInterfaceEntity port) drops.addAll(port.worldBufferContents());
    return drops;
  }

  /** Cables connect on every face but the socket. */
  @Override
  public boolean wiredFace(BlockState state, Direction face) {
    return face != state.getValue(BlockStateProperties.FACING);
  }
}
