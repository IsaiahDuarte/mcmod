package dev.izzy.factorycore.platform;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * One service instance per running server, created on first use and dropped when that server stops.
 * Every scope is cleared by the single {@link #stopped} hook, so a new service needs no shutdown
 * wiring of its own.
 */
public final class ServerScoped<T> {
  private static final List<ServerScoped<?>> ALL = new CopyOnWriteArrayList<>();
  private final Map<MinecraftServer, T> instances = new HashMap<>();
  private final Function<MinecraftServer, T> factory;

  private ServerScoped(Function<MinecraftServer, T> factory) {
    this.factory = factory;
  }

  public static <T> ServerScoped<T> of(Function<MinecraftServer, T> factory) {
    var scope = new ServerScoped<>(factory);
    ALL.add(scope);
    return scope;
  }

  /** The server's instance, created on first use. */
  public T get(MinecraftServer server) {
    return instances.computeIfAbsent(server, factory);
  }

  /** The server's instance if it was already created, else null; never creates one. */
  public T peek(MinecraftServer server) {
    return instances.get(server);
  }

  public static void stopped(ServerStoppedEvent event) {
    for (var scope : ALL) scope.instances.remove(event.getServer());
  }
}
