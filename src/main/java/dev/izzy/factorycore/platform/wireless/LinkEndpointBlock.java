package dev.izzy.factorycore.platform.wireless;

import com.mojang.serialization.MapCodec;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.DeviceLamp;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.upgrade.ThroughputStatus;
import dev.izzy.factorycore.platform.upgrade.ThroughputUpgradeItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Wireless Link Endpoint. Sneak-use one endpoint, then sneak-use another within range to pair them;
 * plain use shows the link state.
 */
public final class LinkEndpointBlock extends NetworkedBlock implements EntityBlock {
  public static final MapCodec<LinkEndpointBlock> CODEC = simpleCodec(LinkEndpointBlock::new);

  /** Server-thread pairing selections: player → first endpoint and the tick it was chosen. */
  private record Selection(String dimension, BlockPos position, long tick) {}

  private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
  private static final long SELECTION_TICKS = 1_200;

  public LinkEndpointBlock(Properties properties) {
    super(properties);
    registerDefaultState(
        stateDefinition
            .any()
            .setValue(BlockStateProperties.FACING, Direction.UP)
            .setValue(DeviceLamp.PROPERTY, DeviceLamp.OFF));
  }

  @Override
  protected MapCodec<? extends Block> codec() {
    return CODEC;
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(BlockStateProperties.FACING, DeviceLamp.PROPERTY);
  }

  /** The back face goes against the clicked block, normally a gateway. */
  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return defaultBlockState().setValue(BlockStateProperties.FACING, context.getClickedFace());
  }

  @Override
  protected RenderShape getRenderShape(BlockState state) {
    return RenderShape.MODEL;
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new LinkEndpointEntity(position, state);
  }

  @Override
  public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
      Level level, BlockState state, BlockEntityType<T> type) {
    return level.isClientSide()
        ? null
        : ticker(type, WirelessContent.LINK_ENTITY.get(), (l, p, s, link) -> link.serverTick());
  }

  @Override
  public void setPlacedBy(
      Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack) {
    super.setPlacedBy(level, position, state, placer, stack);
    if (!level.isClientSide()
        && level.getBlockEntity(position) instanceof LinkEndpointEntity link) {
      if (placer instanceof Player player) link.owner(player.getUUID());
      link.restoreRecovery(stack, level.registryAccess());
    }
  }

  /** An upgrade in hand installs through the item instead of starting a pairing selection. */
  @Override
  protected ItemInteractionResult useItemOn(
      ItemStack stack,
      BlockState state,
      Level level,
      BlockPos position,
      Player player,
      InteractionHand hand,
      BlockHitResult hit) {
    if (stack.getItem() instanceof ThroughputUpgradeItem)
      return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
    return super.useItemOn(stack, state, level, position, player, hand, hit);
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof LinkEndpointEntity link)
        || !(player instanceof ServerPlayer server)) return InteractionResult.FAIL;
    if (!player.isShiftKeyDown()) {
      var diagnostic = link.diagnostic();
      player.displayClientMessage(
          Component.translatable(
              "message.factorycore.link_status",
              Component.translatable(
                  "status.factorycore.link." + (diagnostic.isEmpty() ? "active" : diagnostic)),
              link.energy(),
              LinkEndpointEntity.CAPACITY),
          true);
      player.displayClientMessage(ThroughputStatus.link(server.server, link), false);
      return InteractionResult.CONSUME;
    }
    var dimension = level.dimension().location().toString();
    var selected = SELECTIONS.remove(player.getUUID());
    long now = level.getGameTime();
    if (selected == null
        || now - selected.tick() > SELECTION_TICKS
        || !selected.dimension().equals(dimension)
        || selected.position().equals(position)) {
      SELECTIONS.put(player.getUUID(), new Selection(dimension, position, now));
      player.displayClientMessage(
          Component.translatable("message.factorycore.link_selected"), true);
      return InteractionResult.CONSUME;
    }
    String outcome = pair(server, selected.position(), position);
    player.displayClientMessage(
        Component.translatable("message.factorycore.link_" + outcome), true);
    return InteractionResult.CONSUME;
  }

  /**
   * May {@code player} configure this endpoint: Manage on its valid network, or owner of an orphan.
   */
  public static boolean mayConfigure(ServerPlayer player, LinkEndpointEntity link) {
    var origin = Positions.of(link.getLevel(), link.getBlockPos());
    var networks = WorldNetworks.get(player.server);
    if (networks.membership(origin).status() == NetworkTopology.Status.VALID)
      return networks.allows(Actors.of(player), origin, NetworkPermissions.Permission.MANAGE);
    return player.getUUID().equals(link.owner()) || player.hasPermissions(Actors.OPERATOR_LEVEL);
  }

  /**
   * Pairs two loaded endpoints one-to-one, clearing any previous partners; returns a status key.
   */
  public static String pair(ServerPlayer player, BlockPos first, BlockPos second) {
    ServerLevel level = player.serverLevel();
    if (!LinkEndpointEntity.inRange(first, second)) return "out_of_range";
    var a = LinkEndpointEntity.loaded(level, first);
    var b = LinkEndpointEntity.loaded(level, second);
    if (a == null || b == null || !a.current() || !b.current()) return "missing";
    if (a.needsRecovery() || b.needsRecovery()) return "unknown_data";
    if (!mayConfigure(player, a) || !mayConfigure(player, b)) return "denied";
    for (var link : new LinkEndpointEntity[] {a, b}) {
      var old = link.partner();
      if (old != null
          && !old.equals(first)
          && !old.equals(second)
          && LinkEndpointEntity.loaded(level, old) instanceof LinkEndpointEntity previous
          && link.getBlockPos().equals(previous.partner())) {
        previous.partner(null);
        previous.refresh();
      }
    }
    a.partner(second);
    b.partner(first);
    a.refresh();
    return "paired";
  }

  @Override
  protected List<ItemStack> getDrops(BlockState state, LootParams.Builder parameters) {
    if (parameters.getOptionalParameter(LootContextParams.BLOCK_ENTITY)
            instanceof LinkEndpointEntity link
        && link.needsRecovery())
      return List.of(); // onRemove emits the single evidence-bearing recovery item.
    return super.getDrops(state, parameters);
  }

  @Override
  protected List<ItemStack> dropsOnBreak(BlockEntity blockEntity) {
    return blockEntity instanceof LinkEndpointEntity link ? link.upgradeDrops() : List.of();
  }

  /** Only the back face; the partner link is a wireless port. */
  @Override
  public boolean wiredFace(BlockState state, Direction face) {
    return face == state.getValue(BlockStateProperties.FACING).getOpposite();
  }
}
