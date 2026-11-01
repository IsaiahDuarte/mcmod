package dev.izzy.factorycore.platform.tunnel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Q37: sneak-use a tunnel with an empty hand to make it an input, then sneak-use others within 60
 * seconds to make each an output of it; sneak-use the input again to finish.
 */
final class TunnelPairing {
  private record Selection(ResourceKey<Level> dimension, BlockPos position, long tick) {}

  private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
  static final long SELECTION_TICKS = 1_200;

  private TunnelPairing() {}

  static void use(ServerPlayer player, TunnelEntity tunnel) {
    long now = player.getServer().overworld().getGameTime();
    var selected = SELECTIONS.remove(player.getUUID());
    TunnelEntity input = null;
    if (selected != null && now - selected.tick() <= SELECTION_TICKS) {
      var level = player.getServer().getLevel(selected.dimension());
      if (level != null
          && dev.izzy.factorycore.platform.Loaded.blockEntity(level, selected.position())
              instanceof TunnelEntity found
          && found.role() == TunnelBlock.Role.INPUT) input = found;
    }
    String key;
    if (!tunnel.mayConfigure(player)) key = "denied";
    else if (input == tunnel) key = "done";
    else if (input == null) {
      tunnel.becomeInput();
      SELECTIONS.put(
          player.getUUID(),
          new Selection(tunnel.getLevel().dimension(), tunnel.getBlockPos(), now));
      key = "input";
    } else {
      tunnel.becomeOutput(input.frequency());
      SELECTIONS.put(
          player.getUUID(), new Selection(selected.dimension(), selected.position(), now));
      key = "output";
    }
    player.displayClientMessage(Component.translatable("message.factorycore.tunnel_" + key), true);
  }
}
