package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

public final class BufferedInterfaceBlock extends Block implements EntityBlock {
  public BufferedInterfaceBlock(Properties properties) {
    super(properties);
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new BufferedInterfaceEntity(position, state);
  }

  @Override
  public void setPlacedBy(
      Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack) {
    super.setPlacedBy(level, position, state, placer, stack);
    if (!level.isClientSide()
        && placer != null
        && level.getBlockEntity(position) instanceof BufferedInterfaceEntity buffer)
      try {
        buffer.place(stack, placer.getUUID());
      } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
        if (placer instanceof Player player)
          player.displayClientMessage(
              Component.translatable("message.factorycore.buffer_fault", failure.getMessage()),
              true);
      }
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof BufferedInterfaceEntity buffer))
      return InteractionResult.FAIL;
    try {
      buffer.validatePlaced();
      var actor =
          new NetworkPermissions.Actor(
              player.getUUID(), player instanceof ServerPlayer server && server.hasPermissions(2));
      if (player.isShiftKeyDown()
          && player.getOffhandItem().is(net.minecraft.world.item.Items.REDSTONE)) {
        var admission =
            buffer.returnStaging(
                actor,
                result ->
                    player.displayClientMessage(
                        Component.translatable(
                            "message.factorycore.buffer_cleanup",
                            result.transfer() == null ? 0 : result.transfer().staged(),
                            Component.translatable(
                                "status.factorycore.buffer_reason."
                                    + result.reason().name().toLowerCase(java.util.Locale.ROOT))),
                        false));
        player.displayClientMessage(
            Component.translatable(
                "message.factorycore.buffer_cleanup_queued",
                Component.translatable(
                    "status.factorycore.buffer_reason."
                        + admission.rejection().name().toLowerCase(java.util.Locale.ROOT))),
            false);
        return admission.rejection() == dev.izzy.factorycore.core.resource.OperationResult.Reason.OK
            ? InteractionResult.CONSUME
            : InteractionResult.FAIL;
      }
      if (!player.getOffhandItem().isEmpty()) {
        if (!(player instanceof ServerPlayer server)
            || server.server.getPlayerList().getPlayer(server.getUUID()) != server)
          return InteractionResult.FAIL;
        var current = DeviceSavedData.get(server.server).buffers().deployment(buffer.handle().id());
        var mode = current == null ? BufferRegistry.Mode.PAUSED : current.mode();
        var policy =
            current == null
                ? dev.izzy.factorycore.core.storage.BufferPolicy.DEFAULT
                : current.policy();
        var sample = player.getOffhandItem();
        if (player.isShiftKeyDown() && sample.is(net.minecraft.world.item.Items.COMPASS)) {
          mode =
              switch (mode) {
                case PAUSED -> BufferRegistry.Mode.IMPORT;
                case IMPORT -> BufferRegistry.Mode.EXPORT;
                case EXPORT -> BufferRegistry.Mode.STOCK;
                case STOCK -> BufferRegistry.Mode.PAUSED;
              };
        } else if (player.isShiftKeyDown() && sample.is(net.minecraft.world.item.Items.CHEST)) {
          int target =
              switch (policy.target()) {
                case 0 -> 8;
                case 8 -> 16;
                case 16 -> 32;
                case 32 -> 64;
                case 64 -> 128;
                case 128 -> 256;
                case 256 -> 512;
                case 512 -> 576;
                default -> 0;
              };
          policy =
              new dev.izzy.factorycore.core.storage.BufferPolicy(
                  policy.filter(), policy.rate(), target);
        } else if (player.isShiftKeyDown() && sample.is(net.minecraft.world.item.Items.CLOCK)) {
          int rate =
              switch (policy.rate()) {
                case 64 -> 32;
                case 32 -> 16;
                case 16 -> 8;
                case 8 -> 4;
                case 4 -> 1;
                default -> 64;
              };
          policy =
              new dev.izzy.factorycore.core.storage.BufferPolicy(
                  policy.filter(), rate, policy.target());
        } else if (player.isShiftKeyDown() && sample.is(net.minecraft.world.item.Items.PAPER)) {
          policy =
              new dev.izzy.factorycore.core.storage.BufferPolicy(
                  dev.izzy.factorycore.core.resource.ResourceFilter.all(
                      dev.izzy.factorycore.core.resource.ResourceKind.ITEM),
                  policy.rate(),
                  policy.target());
        } else {
          dev.izzy.factorycore.core.resource.ResourceFilter.Term term;
          var key =
              new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(
                      server.registryAccess())
                  .itemKey(sample);
          var filterMode = dev.izzy.factorycore.core.resource.ResourceFilter.Mode.INCLUDE;
          if (sample.is(net.minecraft.world.item.Items.NAME_TAG)
              && sample.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
            String text = sample.getHoverName().getString();
            term =
                text.startsWith("#")
                    ? new dev.izzy.factorycore.core.resource.ResourceFilter.Tag(text.substring(1))
                    : new dev.izzy.factorycore.core.resource.ResourceFilter.Registry(text);
            if (player.isShiftKeyDown())
              filterMode = dev.izzy.factorycore.core.resource.ResourceFilter.Mode.EXCLUDE;
          } else
            term =
                player.isShiftKeyDown()
                    ? new dev.izzy.factorycore.core.resource.ResourceFilter.Exact(key)
                    : new dev.izzy.factorycore.core.resource.ResourceFilter.Registry(
                        key.registryId());
          policy =
              new dev.izzy.factorycore.core.storage.BufferPolicy(
                  new dev.izzy.factorycore.core.resource.ResourceFilter(
                      dev.izzy.factorycore.core.resource.ResourceKind.ITEM,
                      filterMode,
                      List.of(term)),
                  policy.rate(),
                  policy.target());
        }
        buffer.configure(actor, mode, policy);
        player.displayClientMessage(
            Component.translatable(
                "message.factorycore.buffer_mode",
                Component.translatable(
                    "status.factorycore.buffer_mode."
                        + mode.name().toLowerCase(java.util.Locale.ROOT))),
            false);
        player.displayClientMessage(
            Component.translatable(
                "message.factorycore.buffer_policy",
                policy.rate(),
                Component.translatable(
                    "status.factorycore.buffer_filter."
                        + policy.filter().mode().name().toLowerCase(java.util.Locale.ROOT)),
                policy.filter().terms().size()),
            false);
        player.displayClientMessage(
            Component.translatable("message.factorycore.buffer_target", policy.target()), false);
        return InteractionResult.CONSUME;
      }
      if (player.isShiftKeyDown() && player.getOffhandItem().isEmpty()) {
        var current =
            DeviceSavedData.get(buffer.serverLevel().getServer())
                .buffers()
                .deployment(buffer.handle().id());
        var mode =
            current != null && current.mode() == BufferRegistry.Mode.IMPORT
                ? BufferRegistry.Mode.PAUSED
                : BufferRegistry.Mode.IMPORT;
        buffer.configure(actor, mode);
        player.displayClientMessage(
            Component.translatable(
                "message.factorycore.buffer_mode",
                Component.translatable(
                    "status.factorycore.buffer_mode."
                        + mode.name().toLowerCase(java.util.Locale.ROOT))),
            false);
        return InteractionResult.CONSUME;
      }
      if (WorldNetworks.get(buffer.serverLevel().getServer())
              .authorize(actor, buffer.position(), NetworkPermissions.Permission.VIEW)
          != NetworkAuthority.Decision.ALLOWED) {
        player.displayClientMessage(
            Component.translatable("message.factorycore.buffer_denied"), true);
        return InteractionResult.FAIL;
      }
      var slots = buffer.inventory().snapshot().slots();
      int items = slots.stream().mapToInt(slot -> slot.count()).sum();
      long occupied = slots.stream().filter(slot -> slot.key() != null).count();
      player.displayClientMessage(
          Component.translatable("message.factorycore.buffer_status", items, occupied), false);
      var deployment =
          DeviceSavedData.get(buffer.serverLevel().getServer())
              .buffers()
              .deployment(buffer.handle().id());
      var policy =
          deployment == null
              ? dev.izzy.factorycore.core.storage.BufferPolicy.DEFAULT
              : deployment.policy();
      player.displayClientMessage(
          Component.translatable(
              "message.factorycore.buffer_policy",
              policy.rate(),
              Component.translatable(
                  "status.factorycore.buffer_filter."
                      + policy.filter().mode().name().toLowerCase(java.util.Locale.ROOT)),
              policy.filter().terms().size()),
          false);
      player.displayClientMessage(
          Component.translatable("message.factorycore.buffer_target", policy.target()), false);
      var status = WorldNetworks.get(buffer.serverLevel().getServer()).imports().status(buffer);
      player.displayClientMessage(
          Component.translatable(
              "message.factorycore.buffer_import_status",
              Component.translatable(
                  "status.factorycore.buffer_mode."
                      + status.mode().name().toLowerCase(java.util.Locale.ROOT)),
              Component.translatable(
                  "status.factorycore.buffer_reason."
                      + status.reason().name().toLowerCase(java.util.Locale.ROOT))),
          false);
      if (player instanceof ServerPlayer server && buffer.canUse(server))
        server.openMenu(
            buffer,
            b -> {
              b.writeBlockPos(position);
              b.writeUUID(buffer.sessionForOpen());
            });
      return InteractionResult.CONSUME;
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
      player.displayClientMessage(
          Component.translatable("message.factorycore.buffer_fault", failure.getMessage()), true);
      return InteractionResult.FAIL;
    }
  }

  @Override
  protected List<ItemStack> getDrops(BlockState state, LootParams.Builder parameters) {
    return List.of();
  }

  @Override
  protected void onPlace(
      BlockState state, Level level, BlockPos position, BlockState previous, boolean moved) {
    super.onPlace(state, level, position, previous, moved);
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
    WorldNetworks.changed(level, position);
    if (!state.is(next.getBlock())
        && !level.isClientSide()
        && level.getBlockEntity(position) instanceof BufferedInterfaceEntity buffer)
      Containers.dropItemStack(
          level,
          position.getX() + .5,
          position.getY() + .5,
          position.getZ() + .5,
          buffer.portableForBreak());
    super.onRemove(state, level, position, next, moved);
  }
}
