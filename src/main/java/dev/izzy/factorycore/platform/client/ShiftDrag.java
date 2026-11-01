package dev.izzy.factorycore.platform.client;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Q134 (n17): holding Shift and dragging the left button across the player's inventory moves each
 * stack passed over, as a Shift-click on it would, once per drag, as in vanilla chests with common
 * inventory mods. The pressed slot is moved by the click itself.
 */
public final class ShiftDrag {
  private final Set<Slot> visited = new HashSet<>();
  private boolean active;

  /** A press starts a drag when it is Shift + left button on a player inventory slot. */
  public void press(int button, boolean shift, Slot slot) {
    visited.clear();
    active = button == 0 && shift && playerSlot(slot);
    if (active) visited.add(slot);
  }

  /** Moves {@code slot} with {@code move} the first time this drag passes over it. */
  public boolean drag(int button, boolean shift, Slot slot, Consumer<Slot> move) {
    if (!active || button != 0) return false;
    if (!shift) {
      active = false;
      return false;
    }
    if (playerSlot(slot) && slot.hasItem() && visited.add(slot)) move.accept(slot);
    return true;
  }

  public void release() {
    active = false;
    visited.clear();
  }

  private static boolean playerSlot(Slot slot) {
    return slot != null && slot.container instanceof Inventory && slot.getContainerSlot() < 36;
  }
}
