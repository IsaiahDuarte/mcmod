package dev.izzy.factorycore.platform.network;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /factorycore access list|grant|revoke <pos> ...}: grant administration for the network
 * that owns the block at {@code pos} (ADR 0049). Every change goes through {@link
 * WorldNetworks#setGrants}, which checks Manage at that position: the owner always has it,
 * operators (permission level 2) may administer, and other players only with an explicit Manage
 * grant. Principals are online player names or UUIDs; offline names are never looked up remotely.
 */
public final class AccessCommands {
  private AccessCommands() {}

  public static void register(RegisterCommandsEvent event) {
    SuggestionProvider<CommandSourceStack> players =
        (context, builder) ->
            SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder);
    SuggestionProvider<CommandSourceStack> permissions =
        (context, builder) ->
            SharedSuggestionProvider.suggest(
                java.util.stream.Stream.concat(
                    java.util.stream.Stream.of("all"),
                    java.util.Arrays.stream(Permission.values()).map(AccessCommands::token)),
                builder);
    event
        .getDispatcher()
        .register(
            Commands.literal("factorycore")
                .then(
                    Commands.literal("access")
                        .then(
                            Commands.literal("list")
                                .then(position().executes(c -> list(c.getSource(), loaded(c)))))
                        .then(
                            Commands.literal("grant")
                                .then(
                                    position()
                                        .then(
                                            Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .then(
                                                    Commands.argument(
                                                            "permissions",
                                                            StringArgumentType.greedyString())
                                                        .suggests(permissions)
                                                        .executes(c -> change(c, true, true))))))
                        .then(
                            Commands.literal("revoke")
                                .then(
                                    position()
                                        .then(
                                            Commands.argument("player", StringArgumentType.word())
                                                .suggests(players)
                                                .executes(c -> change(c, false, false))
                                                .then(
                                                    Commands.argument(
                                                            "permissions",
                                                            StringArgumentType.greedyString())
                                                        .suggests(permissions)
                                                        .executes(
                                                            c -> change(c, false, true))))))));
  }

  private static RequiredArgumentBuilder<CommandSourceStack, ?> position() {
    return Commands.argument("pos", BlockPosArgument.blockPos());
  }

  private static BlockPos loaded(CommandContext<CommandSourceStack> context)
      throws CommandSyntaxException {
    return BlockPosArgument.getLoadedBlockPos(context, "pos");
  }

  /** Server-authenticated actor; never a client-supplied operator flag. */
  private static NetworkPermissions.Actor actor(CommandSourceStack source) {
    boolean operator = source.hasPermission(2);
    return source.getEntity() instanceof ServerPlayer player
        ? new NetworkPermissions.Actor(player.getUUID(), operator)
        : new NetworkPermissions.Actor(Util.NIL_UUID, operator);
  }

  private static NodeRegistry.Position origin(CommandSourceStack source, BlockPos pos) {
    return new NodeRegistry.Position(
        source.getLevel().dimension().location().toString(), pos.asLong());
  }

  private static boolean member(CommandSourceStack source, BlockPos pos) {
    if (WorldNetworks.get(source.getServer()).membership(origin(source, pos)).status()
        == NetworkTopology.Status.VALID) return true;
    source.sendFailure(Component.translatable("command.factorycore.access.no_network", text(pos)));
    return false;
  }

  private static int list(CommandSourceStack source, BlockPos pos) {
    if (!member(source, pos)) return 0;
    var state = WorldNetworks.get(source.getServer()).grants(actor(source), origin(source, pos));
    if (state == null) {
      source.sendFailure(Component.translatable("command.factorycore.access.denied"));
      return 0;
    }
    source.sendSuccess(
        () ->
            Component.translatable(
                "command.factorycore.access.owner_row",
                name(source, state.owner()),
                state.generation()),
        false);
    if (state.grants().isEmpty())
      source.sendSuccess(() -> Component.translatable("command.factorycore.access.none"), false);
    state.grants().entrySet().stream()
        .sorted(java.util.Map.Entry.comparingByKey())
        .forEach(
            entry ->
                source.sendSuccess(
                    () ->
                        Component.translatable(
                            "command.factorycore.access.grant_row",
                            name(source, entry.getKey()),
                            entry.getKey().toString(),
                            describe(entry.getValue())),
                    false));
    return 1 + state.grants().size();
  }

  private static int change(
      CommandContext<CommandSourceStack> context, boolean grant, boolean listed)
      throws CommandSyntaxException {
    var source = context.getSource();
    var pos = loaded(context);
    String player = StringArgumentType.getString(context, "player");
    UUID principal = principal(source, player);
    if (principal == null) {
      source.sendFailure(
          Component.translatable("command.factorycore.access.unknown_player", player));
      return 0;
    }
    Set<Permission> named = EnumSet.allOf(Permission.class);
    if (listed) {
      named = parse(StringArgumentType.getString(context, "permissions"));
      if (named == null) {
        source.sendFailure(
            Component.translatable(
                "command.factorycore.access.bad_permission",
                StringArgumentType.getString(context, "permissions")));
        return 0;
      }
    }
    if (!member(source, pos)) return 0;
    var networks = WorldNetworks.get(source.getServer());
    var actor = actor(source);
    var origin = origin(source, pos);
    var state = networks.grants(actor, origin);
    if (state == null) {
      source.sendFailure(Component.translatable("command.factorycore.access.denied"));
      return 0;
    }
    var next = EnumSet.noneOf(Permission.class);
    next.addAll(state.grants().getOrDefault(principal, Set.of()));
    if (grant) next.addAll(named);
    else next.removeAll(named);
    var result = networks.setGrants(actor, origin, principal, next);
    switch (result) {
      case OK -> {
        source.sendSuccess(
            () ->
                Component.translatable(
                    "command.factorycore.access.updated",
                    name(source, principal),
                    text(pos),
                    describe(next)),
            true);
        var target = source.getServer().getPlayerList().getPlayer(principal);
        if (target != null && target != source.getEntity())
          target.sendSystemMessage(
              Component.translatable(
                  "command.factorycore.access.notified",
                  source.getDisplayName(),
                  text(pos),
                  describe(next)));
        return 1;
      }
      case OWNER_IMPLICIT ->
          source.sendFailure(Component.translatable("command.factorycore.access.owner"));
      case LIMIT ->
          source.sendFailure(
              Component.translatable(
                  "command.factorycore.access.limit", NetworkPermissions.MAX_PRINCIPALS));
      case DENIED ->
          source.sendFailure(Component.translatable("command.factorycore.access.denied"));
    }
    return 0;
  }

  /** Online name first, then a UUID; offline names are never resolved over the network. */
  private static UUID principal(CommandSourceStack source, String player) {
    var online = source.getServer().getPlayerList().getPlayerByName(player);
    if (online != null) return online.getUUID();
    try {
      return UUID.fromString(player);
    } catch (IllegalArgumentException notUuid) {
      return null;
    }
  }

  /** Space- or comma-separated permission names, or {@code all}; null for any unknown name. */
  static Set<Permission> parse(String text) {
    var result = EnumSet.noneOf(Permission.class);
    for (var token : text.toLowerCase(Locale.ROOT).split("[\\s,]+")) {
      if (token.isEmpty()) continue;
      if (token.equals("all")) {
        result.addAll(EnumSet.allOf(Permission.class));
        continue;
      }
      Permission match = null;
      for (var permission : Permission.values())
        if (token(permission).equals(token)) match = permission;
      if (match == null) return null;
      result.add(match);
    }
    return result.isEmpty() ? null : result;
  }

  private static String token(Permission permission) {
    return permission.name().toLowerCase(Locale.ROOT);
  }

  private static Component describe(Set<Permission> permissions) {
    return permissions.isEmpty()
        ? Component.translatable("command.factorycore.access.nothing")
        : Component.literal(
            permissions.stream()
                .sorted()
                .map(AccessCommands::token)
                .collect(Collectors.joining(", ")));
  }

  private static Component name(CommandSourceStack source, UUID id) {
    var online = source.getServer().getPlayerList().getPlayer(id);
    if (online != null) return online.getName();
    var cache = source.getServer().getProfileCache();
    var cached =
        cache == null ? java.util.Optional.<com.mojang.authlib.GameProfile>empty() : cache.get(id);
    return Component.literal(
        cached.map(com.mojang.authlib.GameProfile::getName).orElse(id.toString()));
  }

  private static String text(BlockPos pos) {
    return pos.getX() + " " + pos.getY() + " " + pos.getZ();
  }
}
