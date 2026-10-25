package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import dev.izzy.factorycore.platform.resource.ExternalBackingProvider;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * The unified Interface (ADR 0059): one block whose {@link #KIND} selects the existing device that
 * backs it. Storage and Machine have a socket on {@code FACING} toward their container; Buffer is a
 * full cube wired on every face. Mode-specific behavior lives in {@link ConnectorControls} and
 * {@link BufferControls}.
 */
public final class InterfaceBlock extends NetworkedBlock implements EntityBlock {
  public static final EnumProperty<InterfaceKind> KIND =
      EnumProperty.create("kind", InterfaceKind.class);

  public InterfaceBlock(Properties properties) {
    super(properties);
    registerDefaultState(
        stateDefinition
            .any()
            .setValue(BlockStateProperties.FACING, Direction.NORTH)
            .setValue(KIND, InterfaceKind.BUFFER));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(BlockStateProperties.FACING, KIND);
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    var facing = context.getClickedFace().getOpposite();
    var kind = carried(context.getItemInHand());
    if (kind == null) kind = detect(context.getLevel(), context.getClickedPos(), facing);
    return defaultBlockState().setValue(BlockStateProperties.FACING, facing).setValue(KIND, kind);
  }

  /** The kind of the lease an item carries, so a moved Interface keeps its mode; else null. */
  public static InterfaceKind carried(ItemStack stack) {
    var custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    if (custom.contains(BufferData.FIELD)) return InterfaceKind.BUFFER;
    if (!custom.contains(ConnectorData.FIELD)) return null;
    try {
      return ConnectorData.read(custom.get(ConnectorData.FIELD)).lease().kind()
              == NodeRegistry.Kind.MACHINE_INTERFACE
          ? InterfaceKind.MACHINE
          : InterfaceKind.STORAGE;
    } catch (IllegalArgumentException unreadable) {
      return InterfaceKind.STORAGE;
    }
  }

  /**
   * A fresh Interface's mode from what its socket faces: Storage for storage containers (chests,
   * barrels, shulker boxes, identified backing providers), Machine for anything else with an item,
   * fluid or energy capability, Buffer otherwise.
   */
  public static InterfaceKind detect(Level level, BlockPos position, Direction facing) {
    var target = position.relative(facing);
    var entity = level.getBlockEntity(target);
    if (entity instanceof ChestBlockEntity
        || entity instanceof BarrelBlockEntity
        || entity instanceof ShulkerBoxBlockEntity
        || entity instanceof ExternalBackingProvider) return InterfaceKind.STORAGE;
    var side = facing.getOpposite();
    if (level.getCapability(Capabilities.ItemHandler.BLOCK, target, side) != null
        || level.getCapability(Capabilities.FluidHandler.BLOCK, target, side) != null
        || level.getCapability(Capabilities.EnergyStorage.BLOCK, target, side) != null)
      return InterfaceKind.MACHINE;
    return InterfaceKind.BUFFER;
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
    var kind = state.getValue(KIND);
    return kind == InterfaceKind.BUFFER || kind == InterfaceKind.SIGNAL
        ? Shapes.block()
        : SOCKETS.get(state.getValue(BlockStateProperties.FACING));
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return switch (state.getValue(KIND)) {
      case STORAGE -> new ConnectorEntity(position, state);
      case MACHINE -> new MachineInterfaceEntity(position, state);
      case BUFFER -> new BufferedInterfaceEntity(position, state);
      case SIGNAL ->
          new dev.izzy.factorycore.platform.signal.SignalInterfaceEntity(position, state);
    };
  }

  /** C1: only Signal mode emits redstone, the same strength on every side. */
  @Override
  protected boolean isSignalSource(BlockState state) {
    return state.getValue(KIND) == InterfaceKind.SIGNAL;
  }

  @Override
  protected int getSignal(
      BlockState state, BlockGetter level, BlockPos position, Direction direction) {
    return state.getValue(KIND) == InterfaceKind.SIGNAL
            && level.getBlockEntity(position)
                instanceof dev.izzy.factorycore.platform.signal.SignalInterfaceEntity signal
        ? signal.power()
        : 0;
  }

  @Override
  public void setPlacedBy(
      Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack) {
    super.setPlacedBy(level, position, state, placer, stack);
    if (level.isClientSide()) return;
    if (state.getValue(KIND) == InterfaceKind.BUFFER)
      BufferControls.placed(this, level, position, placer, stack);
    else if (state.getValue(KIND) == InterfaceKind.SIGNAL) {
      if (level.getBlockEntity(position)
              instanceof dev.izzy.factorycore.platform.signal.SignalInterfaceEntity signal
          && placer != null) signal.place(placer.getUUID());
    } else ConnectorControls.placed(level, position, placer, stack);
  }

  /** Deferred work after placement or upgrades: the buffer Import default, a preset resume. */
  @Override
  protected void tick(BlockState state, ServerLevel level, BlockPos position, RandomSource random) {
    var entity = level.getBlockEntity(position);
    boolean again =
        entity instanceof BufferedInterfaceEntity buffer
            ? buffer.applyDefaultImport()
            : entity instanceof MachineInterfaceEntity machine && machine.applyPendingResume();
    if (again)
      level.scheduleTick(
          position,
          this,
          entity instanceof BufferedInterfaceEntity
              ? BufferedInterfaceEntity.DEFAULT_IMPORT_TICKS
              : MachineInterfaceEntity.RESUME_TICKS);
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (state.getValue(KIND) == InterfaceKind.SIGNAL) {
      if (player instanceof net.minecraft.server.level.ServerPlayer server
          && level.getBlockEntity(position)
              instanceof dev.izzy.factorycore.platform.signal.SignalInterfaceEntity signal) {
        if (!signal.canUse(server)
            || !dev.izzy.factorycore.platform.network.WorldNetworks.get(server.server)
                .allows(
                    dev.izzy.factorycore.platform.Actors.of(server),
                    signal.origin(),
                    dev.izzy.factorycore.core.network.NetworkPermissions.Permission.VIEW)) {
          server.displayClientMessage(
              net.minecraft.network.chat.Component.translatable(
                  "message.factorycore.signal_unavailable"),
              true);
          return InteractionResult.CONSUME;
        }
        dev.izzy.factorycore.platform.signal.SignalMenu.open(server, signal);
      }
      return InteractionResult.sidedSuccess(level.isClientSide());
    }
    return state.getValue(KIND) == InterfaceKind.BUFFER
        ? BufferControls.use(level, position, player)
        : ConnectorControls.use(level, position, player);
  }

  @Override
  public boolean dropsLeasedItem() {
    return true;
  }

  @Override
  protected List<ItemStack> dropsOnBreak(BlockEntity entity) {
    if (entity instanceof BufferedInterfaceEntity buffer) return List.of(buffer.portableForBreak());
    if (entity instanceof ConnectorEntity connector) return List.of(connector.portableForBreak());
    // A Signal Interface has no lease or contents; it drops a plain Interface (settings are lost).
    if (entity instanceof dev.izzy.factorycore.platform.signal.SignalInterfaceEntity)
      return List.of(new ItemStack(InterfaceContent.ITEM.get()));
    return List.of();
  }

  /** Storage and Machine connect cables on every face but the socket; Buffer and Signal all six. */
  @Override
  public boolean wiredFace(BlockState state, Direction face) {
    return state.getValue(KIND) == InterfaceKind.BUFFER
        || state.getValue(KIND) == InterfaceKind.SIGNAL
        || face != state.getValue(BlockStateProperties.FACING);
  }
}
