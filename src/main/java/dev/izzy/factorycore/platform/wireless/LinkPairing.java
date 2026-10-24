package dev.izzy.factorycore.platform.wireless;

import dev.izzy.factorycore.platform.Actors;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Pairing two linked gateways (ADR 0061, rules of ADR 0040): sneak-use one, then the other in the
 * same dimension within 60 seconds. Pairs are one-to-one; re-pairing clears previous partners.
 */
public final class LinkPairing {
  private record Selection(String dimension, BlockPos position, long tick) {}

  private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
  private static final long SELECTION_TICKS = 1_200;

  private LinkPairing() {}

  /** One sneak-use on a linked gateway: select it, or pair it with the selected one. */
  public static void use(ServerPlayer player, BlockPos position) {
    var level = player.serverLevel();
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
      return;
    }
    player.displayClientMessage(
        Component.translatable(
            "message.factorycore.link_" + pair(player, selected.position(), position)),
        true);
  }

  /** Pairs two loaded links one-to-one, clearing any previous partners; returns a status key. */
  public static String pair(ServerPlayer player, BlockPos first, BlockPos second) {
    ServerLevel level = player.serverLevel();
    if (!LinkModule.inRange(first, second)) return "out_of_range";
    var a = LinkModule.loaded(level, first);
    var b = LinkModule.loaded(level, second);
    if (a == null || b == null || !a.current() || !b.current()) return "missing";
    if (a.needsRecovery() || b.needsRecovery()) return "unknown_data";
    var actor = Actors.of(player);
    if (!a.mayConfigure(actor) || !b.mayConfigure(actor)) return "denied";
    for (var link : new LinkModule[] {a, b}) {
      var old = link.partner();
      if (old != null
          && !old.equals(first)
          && !old.equals(second)
          && LinkModule.loaded(level, old) instanceof LinkModule previous
          && link.position().equals(previous.partner())) {
        previous.partner(null);
        previous.refresh();
      }
    }
    a.partner(second);
    b.partner(first);
    a.refresh();
    return "paired";
  }
}
