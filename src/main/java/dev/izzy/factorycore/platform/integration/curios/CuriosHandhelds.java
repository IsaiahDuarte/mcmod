package dev.izzy.factorycore.platform.integration.curios;

import dev.izzy.factorycore.platform.wireless.HandheldSlots;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Q110 (QD1): lets the open key find a Wireless Terminal worn in a Curios slot. Loaded by name only
 * when Curios is present (ADR 0069), so mandatory classes never reference Curios.
 */
public final class CuriosHandhelds {
  private CuriosHandhelds() {}

  public static void install() {
    HandheldSlots.install(CuriosHandhelds::find);
  }

  private static List<Function<ServerPlayer, ItemStack>> find(
      ServerPlayer player, Predicate<ItemStack> wanted) {
    var found = new ArrayList<Function<ServerPlayer, ItemStack>>();
    var inventory = CuriosApi.getCuriosInventory(player).orElse(null);
    if (inventory == null) return found;
    for (var entry : inventory.getCurios().entrySet()) {
      var type = entry.getKey();
      var stacks = entry.getValue().getStacks();
      for (int slot = 0; slot < stacks.getSlots(); slot++) {
        if (!wanted.test(stacks.getStackInSlot(slot))) continue;
        int index = slot;
        found.add(
            holder ->
                CuriosApi.getCuriosInventory(holder)
                    .flatMap(curios -> curios.getStacksHandler(type))
                    .map(
                        handler ->
                            index < handler.getStacks().getSlots()
                                ? handler.getStacks().getStackInSlot(index)
                                : ItemStack.EMPTY)
                    .orElse(ItemStack.EMPTY));
      }
    }
    return found;
  }
}
