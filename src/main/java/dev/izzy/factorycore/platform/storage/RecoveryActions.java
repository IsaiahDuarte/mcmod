package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import dev.izzy.factorycore.core.resource.TransferRegistry;
import dev.izzy.factorycore.platform.network.AccessAdministration;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Shared transfer-record recovery actions behind {@code /factorycore recovery} and the Recovery
 * screen (ADR 0022, ADR 0047, ADR 0055). Known cleanup and legacy settlement belong exclusively to
 * the authenticated original owner; retirement of empty completed records is an operator action
 * whose permission the caller checks. Every mutation preserves before-state evidence first and
 * shares one set of rate limits regardless of entry point. Server thread only.
 */
public final class RecoveryActions {
  private static final Map<MinecraftServer, Map<UUID, Integer>> REQUESTS = new WeakHashMap<>();
  private static final Map<MinecraftServer, Integer> RETIRE_TIMES = new WeakHashMap<>();

  /**
   * {@code reason} is a {@code command.factorycore.recovery.*} key suffix: {@code queued} or {@code
   * retired} on success, otherwise the refusal. {@code evidence} names the preserved audit file
   * when one was written; {@code admission} is the scheduler's admission for queued work.
   */
  public record Outcome(String reason, String evidence, OperationResult.Reason admission) {
    static Outcome refused(String reason) {
      return new Outcome(reason, "", OperationResult.Reason.OK);
    }
  }

  private RecoveryActions() {}

  static void stopped(MinecraftServer server) {
    REQUESTS.remove(server);
    RETIRE_TIMES.remove(server);
  }

  /** Safe-next-step class of one record: uncertain, complete, legacy or known. */
  public static String classify(TransferRegistry.Stored record) {
    var move = record.transfer();
    if (move.pending() != StagedTransfer.PendingCall.NONE
        || move.state() == StagedTransfer.State.QUARANTINED) return "uncertain";
    if (move.state() == StagedTransfer.State.COMPLETE) return "complete";
    return record.context() == null ? "legacy" : "known";
  }

  /**
   * Original-owner known cleanup ({@code settle == false}) or legacy settlement. The caller must
   * already have established that {@code player} is the actual connected player making this request
   * (not a console, {@code execute as} or a fake player).
   */
  public static Outcome request(
      ServerPlayer player, UUID id, boolean settle, Consumer<WorldStorageTransfers.Result> report) {
    var server = player.server;
    if (server.getPlayerList().getPlayer(player.getUUID()) != player || !player.isAlive())
      return Outcome.refused("owner");
    try {
      var saved = DeviceSavedData.get(server);
      var records = saved.transfers();
      if (!records.contains(id) || !records.get(id).owner().equals(player.getUUID()))
        return Outcome.refused("owner");
      var move = records.get(id);
      if (move.pending() != StagedTransfer.PendingCall.NONE
          || move.state() == StagedTransfer.State.QUARANTINED) return Outcome.refused("uncertain");
      if (move.state() == StagedTransfer.State.COMPLETE) return Outcome.refused("complete");
      if ((records.context(id) == null) != settle)
        return Outcome.refused(settle ? "settle_scoped" : "legacy");
      var times = REQUESTS.computeIfAbsent(server, s -> new HashMap<>());
      int tick = server.getTickCount();
      var previous = times.get(player.getUUID());
      if (previous != null && tick - previous < 20) return Outcome.refused("rate");
      times.entrySet().removeIf(entry -> tick - entry.getValue() >= 20);
      if (previous == null && times.size() >= 256) return Outcome.refused("limit");
      times.put(player.getUUID(), tick);
      var path =
          RecoveryAudit.preserve(
              server.getWorldPath(LevelResource.ROOT),
              saved.registry().world(),
              new TransferRegistry.Stored(id, move, records.context(id)));
      var actor = AccessAdministration.actor(player);
      var transfers = WorldNetworks.get(server).transfers();
      var result =
          settle
              ? transfers.settleLegacy(id, actor, report)
              : transfers.progress(id, actor, true, report);
      return new Outcome("queued", path.getFileName().toString(), result.rejection());
    } catch (IOException evidenceFailure) {
      return Outcome.refused("audit");
    } catch (IllegalStateException quarantined) {
      return Outcome.refused("unavailable");
    }
  }

  /** Operator retirement of a complete, empty record after preserving its evidence. */
  public static Outcome retire(MinecraftServer server, UUID id) {
    try {
      var saved = DeviceSavedData.get(server);
      var records = saved.transfers();
      if (!records.contains(id)) return Outcome.refused("missing");
      var move = records.get(id);
      if (move.state() != StagedTransfer.State.COMPLETE
          || move.pending() != StagedTransfer.PendingCall.NONE
          || move.staged() != 0) return Outcome.refused("not_complete");
      int tick = server.getTickCount();
      var previous = RETIRE_TIMES.get(server);
      if (previous != null && tick - previous < 20) return Outcome.refused("rate");
      RETIRE_TIMES.put(server, tick);
      var path =
          RecoveryAudit.preserve(
              server.getWorldPath(LevelResource.ROOT),
              saved.registry().world(),
              new TransferRegistry.Stored(id, move, records.context(id)));
      records.retire(id);
      return new Outcome("retired", path.getFileName().toString(), OperationResult.Reason.OK);
    } catch (IOException evidenceFailure) {
      return Outcome.refused("audit");
    } catch (IllegalStateException unavailable) {
      return Outcome.refused("unavailable");
    }
  }
}
