package dev.izzy.factorycore.platform.storage;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.io.IOException;
import java.util.*;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Operator-only inspection; known cleanup remains exclusively the authenticated original owner. */
public final class RecoveryCommands {
  private static final Map<MinecraftServer, Map<UUID, Integer>> REQUESTS = new WeakHashMap<>();

  private static final Map<MinecraftServer, Integer> RETIRE_TIMES = new WeakHashMap<>();

  private RecoveryCommands() {}

  public static void stopped(ServerStoppedEvent event) {
    DeviceRecoveryCommands.stopped(event.getServer());
    REQUESTS.remove(event.getServer());
    RETIRE_TIMES.remove(event.getServer());
  }

  public static void register(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("factorycore")
                .then(
                    Commands.literal("recovery")
                        .then(DeviceRecoveryCommands.commands(false))
                        .then(DeviceRecoveryCommands.commands(true))
                        .then(BufferRecoveryCommands.commands())
                        .then(
                            Commands.literal("list")
                                .requires(s -> s.hasPermission(2))
                                .executes(c -> list(c.getSource(), null))
                                .then(
                                    Commands.argument("after", StringArgumentType.word())
                                        .executes(
                                            c ->
                                                list(
                                                    c.getSource(),
                                                    StringArgumentType.getString(c, "after")))))
                        .then(
                            Commands.literal("show")
                                .requires(s -> s.hasPermission(2))
                                .then(
                                    Commands.argument("id", StringArgumentType.word())
                                        .executes(
                                            c ->
                                                show(
                                                    c.getSource(),
                                                    StringArgumentType.getString(c, "id")))))
                        .then(
                            Commands.literal("retire")
                                .requires(source -> source.hasPermission(2))
                                .then(
                                    Commands.argument("id", StringArgumentType.word())
                                        .executes(
                                            context ->
                                                retire(
                                                    context.getSource(),
                                                    StringArgumentType.getString(context, "id")))))
                        .then(
                            Commands.literal("return")
                                .requires(
                                    s ->
                                        s.getEntity() instanceof ServerPlayer player
                                            && s.source == player)
                                .then(
                                    Commands.argument("id", StringArgumentType.word())
                                        .executes(
                                            c ->
                                                returning(
                                                    c.getSource(),
                                                    StringArgumentType.getString(c, "id")))))));
  }

  private static UUID parse(CommandSourceStack source, String value) {
    try {
      if (value.length() != 36) throw new IllegalArgumentException("UUID length");
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      fail(source, "invalid");
      return null;
    }
  }

  private static void fail(CommandSourceStack source, String reason) {
    source.sendFailure(Component.translatable("command.factorycore.recovery." + reason));
  }

  private static int list(CommandSourceStack source, String after) {
    UUID cursor = after == null ? null : parse(source, after);
    if (after != null && cursor == null) return 0;
    try {
      var page = DeviceSavedData.get(source.getServer()).transfers().page(cursor, 16);
      for (var record : page.records()) source.sendSuccess(() -> summary(record), false);
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.page",
                  page.records().size(),
                  page.after() == null ? "—" : page.after().toString()),
          false);
      return page.records().size();
    } catch (IllegalStateException quarantined) {
      fail(source, "unavailable");
      return 0;
    }
  }

  private static Component summary(TransferRegistry.Stored record) {
    var move = record.transfer();
    return Component.translatable(
        "command.factorycore.recovery.row",
        record.id().toString(),
        move.owner().toString(),
        move.state().name(),
        move.key().kind().name(),
        move.key().registryId(),
        move.staged(),
        move.pending().name());
  }

  private static int show(CommandSourceStack source, String text) {
    UUID id = parse(source, text);
    if (id == null) return 0;
    try {
      var records = DeviceSavedData.get(source.getServer()).transfers();
      if (!records.contains(id)) {
        fail(source, "missing");
        return 0;
      }
      var record = new TransferRegistry.Stored(id, records.get(id), records.context(id));
      source.sendSuccess(() -> summary(record), false);
      var move = record.transfer();
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.counts",
                  move.delivered(),
                  move.returned(),
                  move.pendingAmount(),
                  move.reason().name()),
          false);
      if (!move.diagnostic().isEmpty())
        source.sendSuccess(
            () -> Component.literal(move.diagnostic().replaceAll("[\\p{Cntrl}§]", "")), false);
      var context = record.context();
      if (context != null)
        source.sendSuccess(
            () ->
                Component.translatable(
                    "command.factorycore.recovery.context",
                    context.network().toString(),
                    context.segment().toString(),
                    context.origin().dimension(),
                    net.minecraft.core.BlockPos.of(context.origin().block()).toShortString(),
                    context.source().device().toString(),
                    context.destination().device().toString()),
            false);
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery."
                      + (move.pending() != StagedTransfer.PendingCall.NONE
                              || move.state() == StagedTransfer.State.QUARANTINED
                          ? "uncertain"
                          : move.state() == StagedTransfer.State.COMPLETE
                              ? "complete"
                              : context == null ? "legacy" : "known")),
          false);
      return 1;
    } catch (IllegalStateException quarantined) {
      fail(source, "unavailable");
      return 0;
    }
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed server.
  private static int retire(CommandSourceStack source, String text) {
    UUID id = parse(source, text);
    if (id == null) return 0;
    var server = source.getServer();
    try {
      var saved = DeviceSavedData.get(server);
      var records = saved.transfers();
      if (!records.contains(id)) {
        fail(source, "missing");
        return 0;
      }
      var move = records.get(id);
      if (move.state() != StagedTransfer.State.COMPLETE
          || move.pending() != StagedTransfer.PendingCall.NONE
          || move.staged() != 0) {
        fail(source, "not_complete");
        return 0;
      }
      int tick = server.getTickCount();
      var previous = RETIRE_TIMES.get(server);
      if (previous != null && tick - previous < 20) {
        fail(source, "rate");
        return 0;
      }
      RETIRE_TIMES.put(server, tick);
      var path =
          RecoveryAudit.preserve(
              server.getWorldPath(LevelResource.ROOT),
              saved.registry().world(),
              new TransferRegistry.Stored(id, move, records.context(id)));
      records.retire(id);
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.retired",
                  id.toString(),
                  path.getFileName().toString()),
          false);
      return 1;
    } catch (IOException evidenceFailure) {
      fail(source, "audit");
      return 0;
    } catch (IllegalStateException unavailable) {
      fail(source, "unavailable");
      return 0;
    }
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed server.
  private static int returning(CommandSourceStack source, String text) {
    UUID id = parse(source, text);
    if (id == null) return 0;
    var player = (ServerPlayer) source.getEntity();
    var server = source.getServer();
    if (source.source != player
        || server.getPlayerList().getPlayer(player.getUUID()) != player
        || !player.isAlive()) {
      fail(source, "owner");
      return 0;
    }
    try {
      var saved = DeviceSavedData.get(server);
      var records = saved.transfers();
      if (!records.contains(id) || !records.get(id).owner().equals(player.getUUID())) {
        fail(source, "owner");
        return 0;
      }
      var move = records.get(id);
      if (move.pending() != StagedTransfer.PendingCall.NONE
          || move.state() == StagedTransfer.State.QUARANTINED) {
        fail(source, "uncertain");
        return 0;
      }
      if (move.state() == StagedTransfer.State.COMPLETE) {
        fail(source, "complete");
        return 0;
      }
      if (records.context(id) == null) {
        fail(source, "legacy");
        return 0;
      }
      var times = REQUESTS.computeIfAbsent(server, s -> new HashMap<>());
      int tick = server.getTickCount();
      var previous = times.get(player.getUUID());
      if (previous != null && tick - previous < 20) {
        fail(source, "rate");
        return 0;
      }
      times.entrySet().removeIf(entry -> tick - entry.getValue() >= 20);
      if (previous == null && times.size() >= 256) {
        fail(source, "limit");
        return 0;
      }
      times.put(player.getUUID(), tick);
      var path =
          RecoveryAudit.preserve(
              server.getWorldPath(LevelResource.ROOT),
              saved.registry().world(),
              new TransferRegistry.Stored(id, move, records.context(id)));
      var result =
          WorldNetworks.get(server)
              .transfers()
              .progress(
                  id,
                  new NetworkPermissions.Actor(player.getUUID(), player.hasPermissions(2)),
                  true,
                  completed ->
                      source.sendSuccess(
                          () ->
                              Component.translatable(
                                  "command.factorycore.recovery.result",
                                  id.toString(),
                                  completed.reason().name(),
                                  completed.transfer() == null
                                      ? 0
                                      : completed.transfer().returned()),
                          false));
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery.queued",
                  result.rejection().name(),
                  path.getFileName().toString()),
          false);
      return result.rejection() == OperationResult.Reason.OK ? 1 : 0;
    } catch (IOException evidenceFailure) {
      fail(source, "audit");
      return 0;
    } catch (IllegalStateException quarantined) {
      fail(source, "unavailable");
      return 0;
    }
  }
}
