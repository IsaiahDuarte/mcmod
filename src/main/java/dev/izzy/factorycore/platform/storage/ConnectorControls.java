package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.session.MenuSession;
import dev.izzy.factorycore.platform.upgrade.ThroughputStatus;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** Storage- and Machine-mode behavior of the unified {@link InterfaceBlock} (ADR 0059). */
final class ConnectorControls {
  private ConnectorControls() {}

  /** Placement: binds the connector or machine lease carried by (or created for) the item. */
  static void placed(Level level, BlockPos position, LivingEntity placer, ItemStack stack) {
    if (level.getBlockEntity(position) instanceof ConnectorEntity entity) {
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

  /** Empty-hand use in Storage or Machine mode: the settings screen or offhand shortcuts. */
  static InteractionResult use(Level level, BlockPos position, Player player) {
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
      if (!(entity instanceof MachineInterfaceEntity)
          && offhand.isEmpty()
          && !player.isShiftKeyDown()
          && player instanceof ServerPlayer viewer) {
        if (!entity.canUse(viewer)) return InteractionResult.FAIL;
        MenuSession.open(
            viewer,
            Component.translatable("gui.factorycore.interface.title.storage"),
            (id, inventory, session) -> new ConnectorSettingsMenu(id, inventory, entity, session));
        return InteractionResult.CONSUME;
      }
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
}
