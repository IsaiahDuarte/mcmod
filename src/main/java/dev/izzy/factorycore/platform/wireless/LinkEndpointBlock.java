package dev.izzy.factorycore.platform.wireless;

import com.mojang.serialization.MapCodec;
import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Wireless Link Endpoint. Sneak-use one endpoint, then sneak-use another within range to pair them;
 * plain use shows the link state.
 */
@SuppressWarnings("PMD.CloseResource") // Minecraft owns borrowed levels.
public final class LinkEndpointBlock extends BaseEntityBlock {
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
            .setValue(BlockStateProperties.FACING, net.minecraft.core.Direction.UP)
            .setValue(
                dev.izzy.factorycore.platform.network.DeviceLamp.PROPERTY,
                dev.izzy.factorycore.platform.network.DeviceLamp.OFF));
  }

  @Override
  protected MapCodec<? extends BaseEntityBlock> codec() {
    return CODEC;
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(
        BlockStateProperties.FACING, dev.izzy.factorycore.platform.network.DeviceLamp.PROPERTY);
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
        : createTickerHelper(
            type, WirelessContent.LINK_ENTITY.get(), (l, p, s, link) -> link.serverTick());
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
  protected net.minecraft.world.ItemInteractionResult useItemOn(
      ItemStack stack,
      BlockState state,
      Level level,
      BlockPos position,
      Player player,
      net.minecraft.world.InteractionHand hand,
      BlockHitResult hit) {
    if (stack.getItem() instanceof dev.izzy.factorycore.platform.upgrade.ThroughputUpgradeItem)
      return net.minecraft.world.ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
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
      player.displayClientMessage(
          dev.izzy.factorycore.platform.upgrade.ThroughputStatus.link(server.server, link), false);
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
    var origin =
        new NodeRegistry.Position(
            link.getLevel().dimension().location().toString(), link.getBlockPos().asLong());
    var networks = WorldNetworks.get(player.server);
    if (networks.membership(origin).status() == NetworkTopology.Status.VALID)
      return networks.authorize(
              new NetworkPermissions.Actor(player.getUUID(), player.hasPermissions(2)),
              origin,
              NetworkPermissions.Permission.MANAGE)
          == NetworkAuthority.Decision.ALLOWED;
    return player.getUUID().equals(link.owner()) || player.hasPermissions(2);
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
  protected java.util.List<ItemStack> getDrops(
      BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder parameters) {
    if (parameters.getOptionalParameter(
                net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY)
            instanceof LinkEndpointEntity link
        && link.needsRecovery())
      return java.util.List.of(); // onRemove emits the single evidence-bearing recovery item.
    return super.getDrops(state, parameters);
  }

  @Override
  protected void onPlace(
      BlockState state, Level level, BlockPos position, BlockState previous, boolean moved) {
    super.onPlace(state, level, position, previous, moved);
    if (!dev.izzy.factorycore.platform.network.DeviceLamp.onlyLampChanged(previous, state))
      WorldNetworks.changed(level, position);
  }

  @Override
  protected void neighborChanged(
      BlockState state,
      Level level,
      BlockPos position,
      Block neighbor,
      BlockPos from,
      boolean moved) {
    super.neighborChanged(state, level, position, neighbor, from, moved);
    WorldNetworks.changed(level, position);
  }

  @Override
  protected void onRemove(
      BlockState state, Level level, BlockPos position, BlockState next, boolean moved) {
    if (!dev.izzy.factorycore.platform.network.DeviceLamp.onlyLampChanged(state, next))
      WorldNetworks.changed(level, position);
    // The block itself drops through its loot table; an installed Overdrive Upgrade is returned
    // separately so breaking an endpoint never destroys a paid upgrade.
    if (!state.is(next.getBlock())
        && !level.isClientSide()
        && level.getBlockEntity(position) instanceof LinkEndpointEntity link)
      for (var drop : link.upgradeDrops())
        net.minecraft.world.Containers.dropItemStack(
            level, position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5, drop);
    super.onRemove(state, level, position, next, moved);
  }
}
