package dev.izzy.factorycore.platform.client.integration.emi;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/** Q16: registry IDs of EMI ingredients, choosing among alternatives as the JEI plugin does. */
final class EmiStacks {
  private EmiStacks() {}

  /** The item IDs an ingredient accepts (at most 64), in EMI's order. */
  static List<String> itemIds(EmiIngredient ingredient) {
    return ingredient.getEmiStacks().stream()
        .map(EmiStacks::itemId)
        .filter(id -> id != null)
        .distinct()
        .limit(64)
        .toList();
  }

  static String itemId(EmiStack stack) {
    return stack.getKey() instanceof Item item && item != net.minecraft.world.item.Items.AIR
        ? BuiltInRegistries.ITEM.getKey(item).toString()
        : null;
  }

  static String fluidId(EmiStack stack) {
    return stack.getKey() instanceof Fluid fluid
        ? BuiltInRegistries.FLUID.getKey(fluid).toString()
        : null;
  }

  /** The alternative the player has most of, else the first; null when none is an item. */
  static EmiStack best(EmiIngredient ingredient, Map<String, Long> available) {
    EmiStack chosen = null;
    long most = 0;
    for (var stack : ingredient.getEmiStacks()) {
      var id = itemId(stack);
      if (id == null) continue;
      if (chosen == null) chosen = stack;
      long count = available.getOrDefault(id, 0L);
      if (count > most) {
        most = count;
        chosen = stack;
      }
    }
    return chosen;
  }
}
