package dev.izzy.factorycore.testing;

import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.wireless.LinkModule;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;

/** Installs wireless links on test gateways (ADR 0061), replacing placed endpoint blocks. */
public final class Links {
  private Links() {}

  /**
   * Installs a link on {@code side} (the gateway's upstream or downstream face) of {@code gate}.
   */
  public static LinkModule install(GameTestHelper h, BlockPos gate, Direction side, UUID owner) {
    NetworkNodeEntity gateway = h.getBlockEntity(gate);
    gateway.installLink(side, owner, null);
    return gateway.link();
  }
}
