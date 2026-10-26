package dev.izzy.factorycore.platform.crafting;

import com.mojang.serialization.MapCodec;
import dev.izzy.factorycore.platform.CarriedComponents;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Crafter (ADR 0060): a wired holder of up to nine crafting-table or processing patterns that
 * faces the machine its processing patterns use. Pattern items are ordinary, physically owned.
 */
public final class CrafterBlock extends NetworkedBlock implements EntityBlock, CarriedComponents {
  public static final MapCodec<CrafterBlock> CODEC = simpleCodec(CrafterBlock::new);

  public CrafterBlock(Properties properties) {
    super(properties);
    registerDefaultState(
        stateDefinition.any().setValue(BlockStateProperties.FACING, Direction.NORTH));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(BlockStateProperties.FACING);
  }

  /** Faces the clicked block, so placing against a furnace binds that furnace. */
  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return defaultBlockState()
        .setValue(BlockStateProperties.FACING, context.getClickedFace().getOpposite());
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
    return new CrafterEntity(position, state);
  }

  /** H8: a neighbour that becomes or stops being a machine changes which sides take patterns. */
  @Override
  protected void neighborChanged(
      BlockState state,
      Level level,
      BlockPos position,
      Block neighbor,
      BlockPos from,
      boolean moved) {
    super.neighborChanged(state, level, position, neighbor, from, moved);
    if (!level.isClientSide()) level.invalidateCapabilities(position);
  }

  /** Using a pattern inserts it into the first free slot. */
  @Override
  protected ItemInteractionResult useItemOn(
      ItemStack held,
      BlockState state,
      Level level,
      BlockPos position,
      Player player,
      InteractionHand hand,
      BlockHitResult hit) {
    if (!(held.is(CraftingContent.CRAFTING_PATTERN.get())
            || held.is(CraftingContent.PROCESSING_PATTERN.get()))
        || !(level.getBlockEntity(position) instanceof PatternHolderEntity unit))
      return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
    for (int slot = 0; slot < PatternHolderEntity.SLOTS; slot++) {
      var rest = unit.patterns().insertItem(slot, held.copyWithCount(1), false);
      if (rest.isEmpty()) {
        held.shrink(1);
        return ItemInteractionResult.CONSUME;
      }
    }
    player.displayClientMessage(
        Component.translatable(
            unit.patterns().isItemValid(0, held)
                ? "message.factorycore.crafting_unit_full"
                : "message.factorycore.crafting_unit_invalid"),
        true);
    return ItemInteractionResult.FAIL;
  }

  /** Empty hand reports patterns and network state; sneaking takes the last pattern back. */
  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof PatternHolderEntity unit))
      return InteractionResult.FAIL;
    if (player.isShiftKeyDown()) {
      for (int slot = PatternHolderEntity.SLOTS - 1; slot >= 0; slot--) {
        var taken = unit.patterns().extractItem(slot, 1, false);
        if (!taken.isEmpty()) {
          if (!player.getInventory().add(taken))
            Containers.dropItemStack(
                level, position.getX() + 0.5, position.getY() + 1, position.getZ() + 0.5, taken);
          return InteractionResult.CONSUME;
        }
      }
      return InteractionResult.PASS;
    }
    var status =
        WorldNetworks.get(((ServerLevel) level).getServer()).membership(unit.origin()).status();
    player.displayClientMessage(
        Component.translatable(
            "message.factorycore.crafting_unit_status",
            unit.patternCount(),
            PatternHolderEntity.SLOTS,
            Component.translatable("status.factorycore." + status.name().toLowerCase(Locale.ROOT))),
        true);
    return InteractionResult.CONSUME;
  }

  @Override
  protected List<ItemStack> dropsOnBreak(BlockEntity blockEntity) {
    return blockEntity instanceof PatternHolderEntity unit ? unitDrops(unit) : List.of();
  }

  /** A healthy unit hands back its patterns; a faulted one keeps them for recovery. */
  private static List<ItemStack> unitDrops(PatternHolderEntity unit) {
    if (!unit.diagnostic().isEmpty()) return List.of();
    var drops = new java.util.ArrayList<ItemStack>();
    for (int slot = 0; slot < PatternHolderEntity.SLOTS; slot++)
      drops.add(unit.patterns().getStackInSlot(slot).copy());
    // E2: installed speed upgrades come back with the Crafter.
    if (unit instanceof CrafterEntity crafter)
      switch (crafter.tier()) {
        case BASE -> {}
        case ADVANCED ->
            drops.add(
                new ItemStack(
                    dev.izzy.factorycore.platform.upgrade.ThroughputContent.THROUGHPUT.get()));
        case ENDGAME -> {
          drops.add(
              new ItemStack(
                  dev.izzy.factorycore.platform.upgrade.ThroughputContent.THROUGHPUT.get()));
          drops.add(
              new ItemStack(
                  dev.izzy.factorycore.platform.upgrade.ThroughputContent.OVERDRIVE.get()));
        }
      }
    return drops;
  }

  @Override
  public List<DataComponentType<?>> carriedComponents() {
    return List.of(CraftingContent.RETAINED.get());
  }
}
