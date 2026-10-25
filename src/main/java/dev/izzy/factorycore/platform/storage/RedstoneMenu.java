package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.storage.RedstoneGate;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * C2 redstone row of the Machine and Buffer screens. The state reaches the client through a vanilla
 * data slot (control ordinal, plus 4 when powered); a change is menu button {@link #BUTTON} plus
 * the control's ordinal and needs EDIT where the Interface is.
 */
public final class RedstoneMenu {
  public static final int BUTTON = 300;

  private RedstoneMenu() {}

  /** Server: reads the owner's gate. Client ({@code owner} null): holds what the server sent. */
  public static DataSlot slot(RedstoneControlled owner) {
    if (owner == null) return DataSlot.standalone();
    return new DataSlot() {
      @Override
      public int get() {
        var gate = owner.redstone();
        return gate.control().ordinal() | (gate.powered() ? 4 : 0);
      }

      @Override
      public void set(int ignored) {}
    };
  }

  public static RedstoneGate.Control control(int state) {
    var values = RedstoneGate.Control.values();
    return values[Math.floorMod(state & 3, values.length)];
  }

  public static boolean powered(int state) {
    return (state & 4) != 0;
  }

  public static boolean requested(int button) {
    return button >= BUTTON && button < BUTTON + RedstoneGate.Control.values().length;
  }

  /** Applies a requested control; returns the status key ("redstone_set" or "redstone_denied"). */
  public static <E extends BlockEntity & RedstoneControlled> String click(
      ServerPlayer sender, E owner, int button) {
    if (!WorldNetworks.get(sender.server)
        .allows(
            Actors.of(sender),
            dev.izzy.factorycore.platform.Positions.of(owner.getLevel(), owner.getBlockPos()),
            NetworkPermissions.Permission.EDIT)) return "redstone_denied";
    owner.redstone().control(RedstoneGate.Control.values()[button - BUTTON]);
    owner.redstone().observe(owner.getLevel().hasNeighborSignal(owner.getBlockPos()));
    owner.redstoneWake();
    return "redstone_set";
  }
}
