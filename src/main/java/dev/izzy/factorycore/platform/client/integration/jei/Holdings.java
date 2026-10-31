package dev.izzy.factorycore.platform.client.integration.jei;

import dev.izzy.factorycore.platform.storage.TerminalMenu;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;

/**
 * Q112: how many of each item ID the player has to hand for a recipe-viewer transfer: undamaged
 * stacks in the inventory plus the available amount of each terminal row shown. Damaged tools and
 * armour are left out, so a transfer never prefers wearing them down.
 */
final class Holdings {
  private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);

  private Holdings() {}

  static Map<String, Long> of(Player player, TerminalMenu menu) {
    var counts = new HashMap<String, Long>();
    for (var stack : player.getInventory().items)
      if (!stack.isEmpty() && !stack.isDamaged())
        counts.merge(
            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
            (long) stack.getCount(),
            Holdings::add);
    for (var row : menu.view().rows()) {
      long available;
      try {
        available = new BigInteger(row.available()).min(MAX).longValue();
      } catch (NumberFormatException unreadable) {
        available = 0;
      }
      counts.merge(row.icon(), available, Holdings::add);
    }
    return counts;
  }

  private static long add(long a, long b) {
    long sum = a + b;
    return sum < 0 ? Long.MAX_VALUE : sum;
  }
}
