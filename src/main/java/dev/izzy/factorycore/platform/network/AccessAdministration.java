package dev.izzy.factorycore.platform.network;

import com.mojang.authlib.GameProfile;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Positions;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Shared player access administration behind {@code /factorycore access} and the Network Access
 * screen (ADR 0049, ADR 0055). Every mutation goes through {@link WorldNetworks#setGrants} or
 * {@link WorldNetworks#transferOwnership}, which re-check current Manage at the origin. Callers
 * supply a server-authenticated {@link NetworkPermissions.Actor}; nothing here trusts client data.
 * All methods run on the server thread.
 */
public final class AccessAdministration {
  /** Typed result of one administration request; {@code NO_NETWORK} changes nothing. */
  public enum Outcome {
    OK,
    NO_NETWORK,
    DENIED,
    LIMIT,
    OWNER_IMPLICIT
  }

  /** Outcome plus the principal's resulting explicit grants (meaningful only for OK). */
  public record Result(Outcome outcome, Set<Permission> permissions) {
    public Result {
      permissions = permissions.isEmpty() ? Set.of() : Set.copyOf(permissions);
    }
  }

  private AccessAdministration() {}

  /** Server-authenticated actor for a connected player; operator status is read now. */
  public static NetworkPermissions.Actor actor(ServerPlayer player) {
    return Actors.of(player);
  }

  public static NodeRegistry.Position origin(Level level, BlockPos pos) {
    return Positions.of(level, pos);
  }

  public static boolean member(MinecraftServer server, NodeRegistry.Position origin) {
    return WorldNetworks.get(server).membership(origin).status() == NetworkTopology.Status.VALID;
  }

  /**
   * Adds {@code add} and then removes {@code remove} from {@code principal}'s current explicit
   * grants, computed from canonical state at execution time (never from a client snapshot).
   */
  public static Result change(
      MinecraftServer server,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      UUID principal,
      Set<Permission> add,
      Set<Permission> remove) {
    if (!member(server, origin)) return new Result(Outcome.NO_NETWORK, Set.of());
    var networks = WorldNetworks.get(server);
    var state = networks.grants(actor, origin);
    if (state == null) return new Result(Outcome.DENIED, Set.of());
    var next = EnumSet.noneOf(Permission.class);
    next.addAll(state.grants().getOrDefault(principal, Set.of()));
    next.addAll(add);
    next.removeAll(remove);
    return new Result(outcome(networks.setGrants(actor, origin, principal, next)), next);
  }

  /** Explicit ownership transfer; see {@link WorldNetworks#transferOwnership}. */
  public static Outcome transfer(
      MinecraftServer server,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      UUID nextOwner) {
    if (!member(server, origin)) return Outcome.NO_NETWORK;
    return outcome(WorldNetworks.get(server).transferOwnership(actor, origin, nextOwner));
  }

  private static Outcome outcome(NetworkPermissions.Change change) {
    return switch (change) {
      case OK -> Outcome.OK;
      case DENIED -> Outcome.DENIED;
      case LIMIT -> Outcome.LIMIT;
      case OWNER_IMPLICIT -> Outcome.OWNER_IMPLICIT;
    };
  }

  /** Revocation/grant feedback for an online affected player other than the actor. */
  public static void notifyChanged(
      MinecraftServer server,
      Component by,
      Entity actorEntity,
      UUID principal,
      BlockPos pos,
      Set<Permission> next) {
    var target = server.getPlayerList().getPlayer(principal);
    if (target != null && target != actorEntity)
      target.sendSystemMessage(
          Component.translatable(
              "command.factorycore.access.notified", by, text(pos), describe(next)));
  }

  /** Ownership-transfer feedback for the online previous and next owner other than the actor. */
  public static void notifyTransferred(
      MinecraftServer server,
      Component by,
      Entity actorEntity,
      UUID previous,
      UUID next,
      BlockPos pos) {
    var nextName = name(server, next);
    for (var id : new UUID[] {previous, next}) {
      var target = server.getPlayerList().getPlayer(id);
      if (target != null && target != actorEntity)
        target.sendSystemMessage(
            Component.translatable(
                "message.factorycore.access.transferred", by, text(pos), nextName));
    }
  }

  public static String token(Permission permission) {
    return permission.name().toLowerCase(Locale.ROOT);
  }

  public static Component describe(Set<Permission> permissions) {
    return permissions.isEmpty()
        ? Component.translatable("command.factorycore.access.nothing")
        : Component.literal(
            permissions.stream()
                .sorted()
                .map(AccessAdministration::token)
                .collect(Collectors.joining(", ")));
  }

  /** Online name, then the server profile cache, then the UUID; never a remote lookup. */
  public static Component name(MinecraftServer server, UUID id) {
    var online = server.getPlayerList().getPlayer(id);
    if (online != null) return online.getName();
    var cache = server.getProfileCache();
    var cached = cache == null ? Optional.<GameProfile>empty() : cache.get(id);
    return Component.literal(cached.map(GameProfile::getName).orElse(id.toString()));
  }

  public static String text(BlockPos pos) {
    return pos.getX() + " " + pos.getY() + " " + pos.getZ();
  }

  /** Bit {@code ordinal} set for each permission. */
  public static int mask(Set<Permission> permissions) {
    int mask = 0;
    for (var permission : permissions) mask |= 1 << permission.ordinal();
    return mask;
  }

  public static Set<Permission> permissions(int mask) {
    var result = EnumSet.noneOf(Permission.class);
    for (var permission : Permission.values())
      if ((mask & 1 << permission.ordinal()) != 0) result.add(permission);
    return result;
  }
}
