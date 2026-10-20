package dev.izzy.factorycore.platform;

import net.minecraft.server.MinecraftServer;

/** World state is owned by the server thread; every service guards entry with this. */
public final class ServerThread {
  private ServerThread() {}

  /** Throws {@link IllegalStateException} naming {@code what} when called off the server thread. */
  public static void require(MinecraftServer server, String what) {
    if (!server.isSameThread())
      throw new IllegalStateException(what + ": must run on the server thread");
  }
}
