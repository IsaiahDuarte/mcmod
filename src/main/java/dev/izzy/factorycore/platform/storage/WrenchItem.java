package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.crafting.CrafterBlock;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/**
 * Q22: the Wrench. Use on an Importer, Exporter, cable port, Storage Interface or Crafter to turn
 * it to the next face that touches a block. A port's turn is a configuration change under the
 * Interface-switch rules ({@link ConnectorEntity#turn}); a Crafter's face is only cosmetic and
 * needs EDIT. Sneak-use picks the device up exactly as breaking does (same drops: the leased item
 * with its settings, its patterns and upgrades) straight into the inventory.
 */
public final class WrenchItem extends Item {
  public WrenchItem() {
    super(new Properties().stacksTo(1));
  }

  /** Before the block's own use, so a device that opens a screen is turned instead. */
  @Override
  public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
    var level = context.getLevel();
    var position = context.getClickedPos();
    var state = level.getBlockState(position);
    if (!wrenchable(state)) return InteractionResult.PASS;
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
    return player.isShiftKeyDown()
        ? pickUp(player, (ServerLevel) level, position, state)
        : turn(player, (ServerLevel) level, position, state);
  }

  /** Blocks the Wrench works on: ports, a Storage Interface and the Crafter. */
  static boolean wrenchable(BlockState state) {
    return state.getBlock() instanceof PortBlock
        || state.getBlock() instanceof CrafterBlock
        || state.getBlock() instanceof InterfaceBlock
            && state.getValue(InterfaceBlock.KIND) == InterfaceKind.STORAGE;
  }

  /**
   * The next face after {@code from} (in face order, wrapping) whose neighbour is a block other
   * than a Factory Core device or cable; null when none is.
   */
  static Direction nextFace(Level level, BlockPos position, Direction from) {
    for (int step = 1; step < 6; step++) {
      var face = Direction.from3DDataValue((from.get3DDataValue() + step) % 6);
      var neighbour = level.getBlockState(position.relative(face));
      if (!neighbour.isAir() && !(neighbour.getBlock() instanceof NetworkedBlock)) return face;
    }
    return null;
  }

  private static InteractionResult turn(
      ServerPlayer player, ServerLevel level, BlockPos position, BlockState state) {
    var entity = level.getBlockEntity(position);
    if (entity instanceof CrafterEntity crafter) {
      if (!crafter.editableBy(player)) return say(player, "wrench_denied");
      var face = crafter.facing();
      var next = Direction.from3DDataValue((face.get3DDataValue() + 1) % 6);
      level.setBlock(position, state.setValue(BlockStateProperties.FACING, next), Block.UPDATE_ALL);
      return turned(player, next);
    }
    if (!(entity instanceof ConnectorEntity port)) return InteractionResult.PASS;
    var face = nextFace(level, position, port.targetDirection());
    if (face == null) return say(player, "wrench_no_face");
    try {
      if (port.turn(Actors.of(player), face) != NetworkAuthority.Decision.ALLOWED)
        return say(player, "wrench_denied");
    } catch (IllegalStateException | IllegalArgumentException refused) {
      String reason = refused.getMessage() == null ? "" : refused.getMessage();
      if (reason.equals("turn_busy")) return say(player, "wrench_busy");
      player.displayClientMessage(
          Component.translatable("message.factorycore.wrench_fault", reason), true);
      return InteractionResult.FAIL;
    }
    return turned(player, face);
  }

  private static InteractionResult turned(ServerPlayer player, Direction face) {
    player.displayClientMessage(
        Component.translatable(
            "message.factorycore.wrench_turned",
            Component.translatable("gui.factorycore.crafter.side." + EnumKeys.key(face))),
        true);
    return InteractionResult.CONSUME;
  }

  /** Breaks the block as the player would (protection applies) and hands every drop over. */
  private static InteractionResult pickUp(
      ServerPlayer player, ServerLevel level, BlockPos position, BlockState state) {
    if (!level.mayInteract(player, position)
        || player.blockActionRestricted(level, position, player.gameMode.getGameModeForPlayer())
        || CommonHooks.fireBlockBreak(
                level, player.gameMode.getGameModeForPlayer(), player, position, state)
            .isCanceled()) return say(player, "wrench_protected");
    var drops = NetworkedBlock.collectDrops(() -> level.destroyBlock(position, true, player));
    if (level.getBlockState(position) == state) return say(player, "wrench_protected");
    for (var drop : drops) if (!player.getInventory().add(drop)) player.drop(drop, false);
    player.displayClientMessage(
        Component.translatable("message.factorycore.wrench_picked", state.getBlock().getName()),
        true);
    return InteractionResult.CONSUME;
  }

  private static InteractionResult say(ServerPlayer player, String key) {
    player.displayClientMessage(Component.translatable("message.factorycore." + key), true);
    return InteractionResult.FAIL;
  }

  /** Loot-table drops of a block the Wrench is picking up go to the same collection. */
  static void drops(BlockDropsEvent event) {
    var items = event.getDrops();
    for (var item : List.copyOf(items))
      if (NetworkedBlock.collect(item.getItem())) items.remove(item);
  }

  @Override
  public void appendHoverText(
      ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    super.appendHoverText(stack, context, tooltip, flag);
    tooltip.add(Component.translatable("tooltip.factorycore.wrench"));
    tooltip.add(Component.translatable("tooltip.factorycore.wrench.sneak"));
  }
}
