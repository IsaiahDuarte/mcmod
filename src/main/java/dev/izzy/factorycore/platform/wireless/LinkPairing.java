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
 * Pairing two linked gateways (ADR 0061, rules of ADR 0040): sneak-use one, then the other within
 * 60 seconds. Pairs are one-to-one; re-pairing clears previous partners. Q36 (QD10): the second may
 * be in another dimension (the player travels between the two uses); such a pair has no range limit
 * and costs {@link LinkModule#CROSS_DIMENSION_COST} FE per tick.
 */
public final class LinkPairing {
  private record Selection(
      net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
      BlockPos position,
      long tick) {}

  private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
  private static final long SELECTION_TICKS = 1_200;

  private LinkPairing() {}

  /** One sneak-use on a linked gateway: select it, or pair it with the selected one. */
  public static void use(ServerPlayer player, BlockPos position) {
    var level = player.serverLevel();
    var selected = SELECTIONS.remove(player.getUUID());
    // Overworld time is shared by every dimension, so the window spans a portal trip.
    long now = player.getServer().overworld().getGameTime();
    if (selected == null
        || now - selected.tick() > SELECTION_TICKS
        || player.getServer().getLevel(selected.dimension()) == null
        || (selected.dimension() == level.dimension() && selected.position().equals(position))) {
      SELECTIONS.put(player.getUUID(), new Selection(level.dimension(), position, now));
      player.displayClientMessage(
          Component.translatable("message.factorycore.link_selected"), true);
      return;
    }
    player.displayClientMessage(
        Component.translatable(
            "message.factorycore.link_"
                + pair(
                    player,
                    player.getServer().getLevel(selected.dimension()),
                    selected.position(),
                    level,
                    position)),
        true);
  }

  /** Pairs two loaded links in the player's dimension; returns a status key. */
  public static String pair(ServerPlayer player, BlockPos first, BlockPos second) {
    var level = player.serverLevel();
    return pair(player, level, first, level, second);
  }

  /**
   * Pairs two loaded links one-to-one, possibly across dimensions, clearing any previous partners;
   * returns a status key.
   */
  public static String pair(
      ServerPlayer player,
      ServerLevel firstLevel,
      BlockPos first,
      ServerLevel secondLevel,
      BlockPos second) {
    if (firstLevel == secondLevel && !LinkModule.inRange(first, second)) return "out_of_range";
    var a = LinkModule.loaded(firstLevel, first);
    var b = LinkModule.loaded(secondLevel, second);
    if (a == null || b == null || a == b || !a.current() || !b.current()) return "missing";
    if (a.needsRecovery() || b.needsRecovery()) return "unknown_data";
    var actor = Actors.of(player);
    if (!a.mayConfigure(actor) || !b.mayConfigure(actor)) return "denied";
    for (var link : new LinkModule[] {a, b}) {
      var previous = link.partnerOf();
      if (previous != null && previous != a && previous != b) {
        previous.partner(null, null);
        previous.refresh();
      }
    }
    a.partner(secondLevel, second);
    b.partner(firstLevel, first);
    a.refresh();
    return "paired";
  }
}
