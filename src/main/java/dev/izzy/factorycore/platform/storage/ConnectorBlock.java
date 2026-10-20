package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.upgrade.ThroughputStatus;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

public final class ConnectorBlock extends NetworkedBlock implements EntityBlock {
  private final boolean machine;

  public ConnectorBlock(Properties properties) {
    this(properties, false);
  }

  ConnectorBlock(Properties properties, boolean machine) {
    super(properties);
    this.machine = machine;
    registerDefaultState(
        stateDefinition.any().setValue(BlockStateProperties.FACING, Direction.NORTH));
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

  private static final Map<Direction, VoxelShape> SHAPES =
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
    return SHAPES.get(state.getValue(BlockStateProperties.FACING));
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return machine
        ? new MachineInterfaceEntity(position, state)
        : new ConnectorEntity(position, state);
  }

  @Override
  public void setPlacedBy(
      Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack) {
    super.setPlacedBy(level, position, state, placer, stack);
    if (!level.isClientSide() && level.getBlockEntity(position) instanceof ConnectorEntity entity) {
      try {
        entity.place(stack);
      } catch (IllegalArgumentException | IllegalStateException failure) {
        if (placer instanceof Player player)
          player.displayClientMessage(
              Component.translatable("message.factorycore.connector_fault", failure.getMessage()),
              true);
      }
    }
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof ConnectorEntity entity))
      return InteractionResult.FAIL;
    try {
      if (entity instanceof MachineInterfaceEntity local) {
        if (!(player instanceof ServerPlayer server) || !local.canUse(server))
          return InteractionResult.FAIL;
        var held = player.getOffhandItem();
        String label =
            !player.isShiftKeyDown()
                    && held.has(DataComponents.CUSTOM_NAME)
                    && (held.is(Items.NAME_TAG) || held.is(Items.PAPER))
                ? held.getHoverName().getString()
                : player.isShiftKeyDown() && held.is(Items.PAPER) ? "" : null;
        if (label != null) {
          var actor = Actors.of(server);
          if (local.relabel(actor, label) != NetworkAuthority.Decision.ALLOWED)
            return InteractionResult.FAIL;
          server.displayClientMessage(
              Component.translatable(
                  "message.factorycore.machine_label",
                  label.isEmpty()
                      ? Component.translatable("message.factorycore.machine_unlabelled")
                      : label),
              false);
          return InteractionResult.CONSUME;
        }
        if (held.isEmpty() && !player.isShiftKeyDown()) {
          server.openMenu(local, b -> b.writeUUID(local.sessionForOpen()));
          return InteractionResult.CONSUME;
        }
        server.displayClientMessage(
            Component.translatable(
                "message.factorycore.machine_binding",
                local.label().isEmpty()
                    ? Component.translatable("message.factorycore.machine_unlabelled")
                    : local.label(),
                local.settings().firstSlot(),
                local.settings().slotCount()),
            false);
      }
      entity.validatePlaced();
      var settings = entity.settings();
      var offhand = player.getOffhandItem();
      boolean materialControl =
          offhand.is(Items.REDSTONE)
              || offhand.is(Items.IRON_INGOT)
              || offhand.is(Items.GOLD_INGOT);
      if (player.isShiftKeyDown() && offhand.isEmpty()
          || !player.isShiftKeyDown() && materialControl) {
        if (offhand.is(Items.REDSTONE))
          settings =
              new ConnectorSettings(
                  settings.kind(),
                  ConnectorSettings.Access.values()[(settings.access().ordinal() + 1) % 3],
                  settings.priority(),
                  settings.firstSlot(),
                  settings.slotCount(),
                  settings.rate());
        else if (offhand.is(Items.IRON_INGOT) || offhand.is(Items.GOLD_INGOT))
          settings =
              new ConnectorSettings(
                  settings.kind(),
                  settings.access(),
                  offhand.is(Items.IRON_INGOT) ? offhand.getCount() : -offhand.getCount(),
                  settings.firstSlot(),
                  settings.slotCount(),
                  settings.rate());
        else if (offhand.isEmpty())
          settings =
              ConnectorSettings.defaults(
                  ResourceKind.values()[(settings.kind().ordinal() + 1) % 3]);
        else return InteractionResult.PASS;
        var actor =
            new NetworkPermissions.Actor(
                player.getUUID(),
                player instanceof ServerPlayer server
                    && server.hasPermissions(Actors.OPERATOR_LEVEL));
        if (entity.configure(actor, settings) != NetworkAuthority.Decision.ALLOWED) {
          player.displayClientMessage(
              Component.translatable("message.factorycore.connector_denied"), true);
          return InteractionResult.FAIL;
        }
      }
      var runtime = WorldNetworks.get(entity.serverLevel().getServer());
      var binding = runtime.connectors().bind(entity);
      player.displayClientMessage(
          Component.translatable(
              "message.factorycore.connector_status",
              entity.position().dimension() + " @ " + position.toShortString(),
              Component.translatable(
                  "resource.factorycore." + settings.kind().name().toLowerCase(Locale.ROOT)),
              Component.translatable(
                  "access.factorycore." + settings.access().name().toLowerCase(Locale.ROOT)),
              Component.translatable(
                  "direction.factorycore." + entity.targetDirection().getOpposite().getName()),
              settings.priority(),
              settings.rate(),
              Component.translatable(
                  "reason.factorycore." + binding.reason().name().toLowerCase(Locale.ROOT))),
          false);
      player.displayClientMessage(
          ThroughputStatus.port(entity.serverLevel().getServer(), entity), false);
      return InteractionResult.CONSUME;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      player.displayClientMessage(
          Component.translatable("message.factorycore.connector_fault", failure.getMessage()),
          true);
      return InteractionResult.FAIL;
    }
  }

  @Override
  public boolean dropsLeasedItem() {
    return true;
  }

  @Override
  protected List<ItemStack> dropsOnBreak(BlockEntity blockEntity) {
    return blockEntity instanceof ConnectorEntity entity
        ? List.of(entity.portableForBreak())
        : List.of();
  }

  /** Every face except the one facing its target machine. */
  @Override
  public boolean wiredFace(BlockState state, Direction face) {
    return face != state.getValue(BlockStateProperties.FACING);
  }
}
