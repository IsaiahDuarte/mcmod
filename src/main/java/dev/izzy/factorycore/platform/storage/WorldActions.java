package dev.izzy.factorycore.platform.storage;

import com.mojang.authlib.GameProfile;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Q34/Q35 (QD9, ADR 0070): what Constructors and Destructors do to the block in front of them.
 *
 * <p>Both act as a fake player owned by the player who applied the running preset, through the
 * game's own place and break paths, so protection mods can refuse either; a refused or blocked
 * action changes nothing and keeps every item in the buffer. Nothing is voided: a Destructor acts
 * only with an empty buffer, so it holds at most one block's drops (or one pickup) at a time; while
 * storage cannot take them, the buffer stays full and it breaks nothing more.
 */
final class WorldActions {
  static final String FIELD = "FactoryCoreWorldBuffer";
  static final int CONSTRUCTOR_SLOTS = 1, DESTRUCTOR_SLOTS = 9;

  /** Ticks between actions. */
  static final int PERIOD = 4;

  private WorldActions() {}

  private static FakePlayer actor(
      ServerLevel level,
      BlockPos position,
      Direction facing,
      MachinePresets.Deployment deployment) {
    var player =
        FakePlayerFactory.get(
            level, new GameProfile(deployment.authority().principal(), "[FactoryCore]"));
    player.setGameMode(GameType.SURVIVAL);
    var at = Vec3.atCenterOf(position);
    player.moveTo(
        at.x,
        at.y - player.getEyeHeight(),
        at.z,
        facing.toYRot(),
        facing == Direction.UP ? -90 : facing == Direction.DOWN ? 90 : 0);
    return player;
  }

  /**
   * Places the buffered block, launches a firework, or drops the buffered stack in front. True when
   * the buffer changed.
   */
  static boolean construct(
      ServerLevel level,
      BlockPos position,
      Direction facing,
      IItemHandler buffer,
      MachinePresets.Deployment deployment) {
    var stack = buffer.getStackInSlot(0);
    if (stack.isEmpty()) return false;
    var target = position.relative(facing);
    var state = level.getBlockState(target);
    if (stack.getItem() instanceof BlockItem) {
      if (!state.canBeReplaced()) return false;
      var player = actor(level, position, facing, deployment);
      var one = stack.copyWithCount(1);
      player.setItemInHand(InteractionHand.MAIN_HAND, one);
      try {
        one.useOn(
            new UseOnContext(
                player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(target), facing.getOpposite(), target, false)));
      } finally {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
      }
      if (!one.isEmpty()) return false; // Refused (an event, or the block can't go there).
      buffer.extractItem(0, 1, false);
      return true;
    }
    // Launching or dropping needs open space in front.
    if (!state.getCollisionShape(level, target).isEmpty()) return false;
    var at = Vec3.atCenterOf(target);
    if (stack.getItem() instanceof FireworkRocketItem) {
      level.addFreshEntity(
          new FireworkRocketEntity(level, at.x, at.y, at.z, stack.copyWithCount(1)));
      buffer.extractItem(0, 1, false);
      return true;
    }
    var dropped = buffer.extractItem(0, stack.getCount(), false);
    var entity = new ItemEntity(level, at.x, at.y, at.z, dropped, 0, 0, 0);
    entity.setDefaultPickUpDelay();
    level.addFreshEntity(entity);
    return true;
  }

  /** True when the Destructor may break {@code state}. */
  private static boolean breakable(ServerLevel level, BlockPos target) {
    var state = level.getBlockState(target);
    return !state.isAir()
        && !state.hasBlockEntity()
        && state.getFluidState().isEmpty()
        && state.getDestroySpeed(level, target) >= 0
        && !BuiltInRegistries.BLOCK
            .getKey(state.getBlock())
            .getNamespace()
            .equals(FactoryCoreMod.MOD_ID);
  }

  /**
   * Breaks the block in front into the buffer when every drop fits, or picks up item entities
   * there. True when the buffer changed.
   */
  static boolean destruct(
      ServerLevel level,
      BlockPos position,
      Direction facing,
      IItemHandler buffer,
      MachinePresets.Deployment deployment) {
    // One block's drops (or one pickup) at a time: while storage cannot take them, nothing more.
    for (int slot = 0; slot < buffer.getSlots(); slot++)
      if (!buffer.getStackInSlot(slot).isEmpty()) return false;
    var target = position.relative(facing);
    var state = level.getBlockState(target);
    if (state.isAir()) return pickUp(level, target, buffer);
    if (!breakable(level, target)) return false;
    var player = actor(level, position, facing, deployment);
    var tool = new ItemStack(Items.NETHERITE_PICKAXE);
    var drops = Block.getDrops(state, level, target, null, player, tool);
    if (!fits(buffer, drops)) return false;
    if (CommonHooks.fireBlockBreak(level, GameType.SURVIVAL, player, target, state).isCanceled())
      return false;
    if (!level.destroyBlock(target, false, player)) return false;
    for (var drop : drops) insert(buffer, drop.copy());
    return true;
  }

  private static boolean pickUp(ServerLevel level, BlockPos target, IItemHandler buffer) {
    boolean changed = false;
    for (var entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(target))) {
      if (!entity.isAlive()) continue;
      var stack = entity.getItem();
      var left = insert(buffer, stack.copy());
      if (left.getCount() == stack.getCount()) continue;
      changed = true;
      if (left.isEmpty()) entity.discard();
      else entity.setItem(left);
    }
    return changed;
  }

  /** True when every stack in {@code drops} fits in {@code buffer} together. */
  private static boolean fits(IItemHandler buffer, java.util.List<ItemStack> drops) {
    var trial = new ItemStackHandler(buffer.getSlots());
    for (int slot = 0; slot < buffer.getSlots(); slot++)
      trial.setStackInSlot(slot, buffer.getStackInSlot(slot).copy());
    for (var drop : drops) if (!insert(trial, drop.copy()).isEmpty()) return false;
    return true;
  }

  private static ItemStack insert(IItemHandler buffer, ItemStack stack) {
    for (int slot = 0; slot < buffer.getSlots() && !stack.isEmpty(); slot++)
      stack = buffer.insertItem(slot, stack, false);
    return stack;
  }
}
