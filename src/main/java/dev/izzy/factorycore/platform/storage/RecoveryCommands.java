package dev.izzy.factorycore.platform.storage;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.crafting.ProcessingRecoveryCommands;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.commands.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Operator-only inspection; known cleanup remains exclusively the authenticated original owner. */
public final class RecoveryCommands {
  private RecoveryCommands() {}

  public static void stopped(ServerStoppedEvent event) {
    DeviceRecoveryCommands.stopped(event.getServer());
    RecoveryActions.stopped(event.getServer());
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
                        .then(NodeRecovery.BUFFER.commands())
                        .then(NodeRecovery.NODE.commands())
                        .then(NodeRecovery.PORT.commands())
                        .then(
                            Commands.literal("screen")
                                .requires(
                                    s ->
                                        s.getEntity() instanceof ServerPlayer player
                                            && s.source == player)
                                .executes(
                                    c -> {
                                      RecoveryMenu.open((ServerPlayer) c.getSource().getEntity());
                                      return 1;
                                    }))
                        .then(ProcessingRecoveryCommands.commands())
                        .then(
                            Commands.literal("list")
                                .requires(s -> s.hasPermission(Actors.OPERATOR_LEVEL))
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
                                .requires(s -> s.hasPermission(Actors.OPERATOR_LEVEL))
                                .then(
                                    Commands.argument("id", StringArgumentType.word())
                                        .executes(
                                            c ->
                                                show(
                                                    c.getSource(),
                                                    StringArgumentType.getString(c, "id")))))
                        .then(
                            Commands.literal("retire")
                                .requires(source -> source.hasPermission(Actors.OPERATOR_LEVEL))
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
                                                    StringArgumentType.getString(c, "id"),
                                                    false))))
                        .then(
                            Commands.literal("settle")
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
                                                    StringArgumentType.getString(c, "id"),
                                                    true))))));
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
                    BlockPos.of(context.origin().block()).toShortString(),
                    context.source().device().toString(),
                    context.destination().device().toString()),
            false);
      source.sendSuccess(
          () ->
              Component.translatable(
                  "command.factorycore.recovery." + RecoveryActions.classify(record).key()),
          false);
      return 1;
    } catch (IllegalStateException quarantined) {
      fail(source, "unavailable");
      return 0;
    }
  }

  private static int retire(CommandSourceStack source, String text) {
    UUID id = parse(source, text);
    if (id == null) return 0;
    var outcome = RecoveryActions.retire(source.getServer(), id);
    if (outcome.reason() != RecoveryActions.Result.RETIRED) {
      fail(source, outcome.reason().key());
      return 0;
    }
    source.sendSuccess(
        () ->
            Component.translatable(
                "command.factorycore.recovery.retired", id.toString(), outcome.evidence()),
        false);
    return 1;
  }

  /**
   * Original-owner known cleanup ({@code return}) or legacy settlement ({@code settle}, ADR 0047)
   * for a record without endpoint context. Both preserve before-state evidence first, use shared
   * scheduling and never extract. The command source itself must be the executing player.
   */
  private static int returning(CommandSourceStack source, String text, boolean settle) {
    UUID id = parse(source, text);
    if (id == null) return 0;
    var player = (ServerPlayer) source.getEntity();
    if (source.source != player) {
      fail(source, "owner");
      return 0;
    }
    Consumer<WorldStorageTransfers.Result> report =
        completed ->
            source.sendSuccess(
                () ->
                    Component.translatable(
                        "command.factorycore.recovery." + (settle ? "settled" : "result"),
                        id.toString(),
                        completed.reason().name(),
                        completed.transfer() == null ? 0 : completed.transfer().returned(),
                        completed.transfer() == null ? 0 : completed.transfer().delivered(),
                        completed.transfer() == null ? 0 : completed.transfer().staged()),
                false);
    var outcome = RecoveryActions.request(player, id, settle, report);
    if (outcome.reason() != RecoveryActions.Result.QUEUED) {
      fail(source, outcome.reason().key());
      return 0;
    }
    source.sendSuccess(
        () ->
            Component.translatable(
                "command.factorycore.recovery.queued",
                outcome.admission().name(),
                outcome.evidence()),
        false);
    return outcome.admission() == OperationResult.Reason.OK ? 1 : 0;
  }
}
