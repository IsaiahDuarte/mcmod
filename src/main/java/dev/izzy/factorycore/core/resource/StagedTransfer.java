package dev.izzy.factorycore.core.resource;

import static dev.izzy.factorycore.core.resource.OperationResult.Reason.*;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One bounded move with explicit staging ownership. Never replays extraction. Uncertain external
 * writes freeze the record for recovery; known partial rejection can retry or return staging.
 */
public final class StagedTransfer {
  public enum State {
    STAGED,
    COMPLETE,
    QUARANTINED
  }

  public enum PendingCall {
    NONE,
    EXTRACT,
    DELIVER,
    RETURN
  }

  /** Immutable owner-thread save record; a pending external call always restores quarantined. */
  public record Snapshot(
      UUID owner,
      ResourceKey key,
      UUID source,
      UUID destination,
      long staged,
      long delivered,
      long returned,
      State state,
      OperationResult.Reason reason,
      String diagnostic,
      PendingCall pending,
      long pendingAmount) {
    public Snapshot {
      Objects.requireNonNull(owner);
      Objects.requireNonNull(key);
      Objects.requireNonNull(source);
      Objects.requireNonNull(destination);
      Objects.requireNonNull(state);
      Objects.requireNonNull(reason);
      Objects.requireNonNull(diagnostic);
      Objects.requireNonNull(pending);
      OperationResult.requireQuantity(staged);
      OperationResult.requireQuantity(delivered);
      OperationResult.requireQuantity(returned);
      OperationResult.requireQuantity(pendingAmount);
      Math.addExact(staged, Math.addExact(delivered, returned));
      if ((pending == PendingCall.NONE) != (pendingAmount == 0)
          || ((staged > 0 || pending != PendingCall.NONE) && source.equals(destination))
          || diagnostic.length() > 512
          || (state == State.COMPLETE && staged != 0)
          || (state == State.STAGED && staged == 0)
          || (state == State.QUARANTINED && reason != UNCERTAIN)
          || (state != State.QUARANTINED && reason == UNCERTAIN)
          || (pending == PendingCall.EXTRACT && (staged != 0 || delivered != 0 || returned != 0))
          || ((pending == PendingCall.DELIVER || pending == PendingCall.RETURN)
              && (staged == 0 || pendingAmount != staged)))
        throw new IllegalArgumentException("Invalid transfer snapshot");
    }
  }

  private final UUID owner;
  private final ResourceKey key;
  private final ResourcePort source;
  private final ResourcePort destination;
  private final BooleanSupplier authorized;
  private final Consumer<Snapshot> journal;
  private PendingCall pending = PendingCall.NONE;
  private long pendingAmount;
  private final Thread thread = Thread.currentThread();
  private long staged;
  private long delivered;
  private long returned;
  private State state = State.COMPLETE;
  private OperationResult.Reason reason = OK;
  private String diagnostic = "";
  private boolean executing;

  private StagedTransfer(
      UUID owner,
      ResourceKey key,
      ResourcePort source,
      ResourcePort destination,
      BooleanSupplier authorized,
      Consumer<Snapshot> journal) {
    this.owner = Objects.requireNonNull(owner);
    this.key = Objects.requireNonNull(key);
    this.source = Objects.requireNonNull(source);
    this.destination = Objects.requireNonNull(destination);
    this.authorized = Objects.requireNonNull(authorized);
    this.journal = Objects.requireNonNull(journal);
  }

  public static StagedTransfer start(
      UUID owner,
      ResourceKey key,
      ResourcePort source,
      ResourcePort destination,
      long requested,
      long stagingCapacity,
      BooleanSupplier authorized) {
    return start(
        owner, key, source, destination, requested, stagingCapacity, authorized, snapshot -> {});
  }

  /** Journal records intent before each actual external mutation and known accounting afterward. */
  public static StagedTransfer start(
      UUID owner,
      ResourceKey key,
      ResourcePort source,
      ResourcePort destination,
      long requested,
      long stagingCapacity,
      BooleanSupplier authorized,
      Consumer<Snapshot> journal) {
    OperationResult.requireQuantity(requested);
    OperationResult.requireQuantity(stagingCapacity);
    var move = new StagedTransfer(owner, key, source, destination, authorized, journal);
    if (source.backingId().equals(destination.backingId())) {
      move.reason = WRONG_RESOURCE;
      move.diagnostic = "Source and destination refer to the same backing store";
      return move;
    }
    if (!authorized.getAsBoolean()) {
      move.reason = DENIED;
      return move;
    }
    long offer = Math.min(requested, stagingCapacity);
    if (offer == 0) return move;
    try {
      long accepted = destination.simulateInsert(key, offer);
      validate(accepted, offer);
      if (accepted == 0) {
        move.reason = FULL;
        return move;
      }
      if (!authorized.getAsBoolean()) {
        move.reason = DENIED;
        return move;
      }
      move.pending = PendingCall.EXTRACT;
      move.pendingAmount = accepted;
      move.journal.accept(move.snapshot());
      long extracted = source.extract(key, accepted);
      validate(extracted, accepted);
      move.staged = extracted;
      move.state = extracted == 0 ? State.COMPLETE : State.STAGED;
      move.pending = PendingCall.NONE;
      move.pendingAmount = 0;
      if (extracted == 0) move.reason = SHORTAGE;
      move.journal.accept(move.snapshot());
      if (extracted > 0) move.deliver();
    } catch (RuntimeException error) {
      move.quarantine(error);
    }
    return move;
  }

  public OperationResult deliver() {
    return insertInto(destination, false);
  }

  /** Cancellation returns only recoverable staging, never consumed or uncertain resources. */
  public OperationResult cancel() {
    return insertInto(source, true);
  }

  private OperationResult insertInto(ResourcePort port, boolean returning) {
    checkThread();
    if (executing) throw new IllegalStateException("Reentrant transfer mutation");
    if (state == State.QUARANTINED) return new OperationResult(0, UNCERTAIN);
    if (staged == 0) return new OperationResult(0, reason);
    if (!returning && !authorized.getAsBoolean()) {
      reason = DENIED;
      return new OperationResult(0, DENIED);
    }
    executing = true;
    try {
      pending = returning ? PendingCall.RETURN : PendingCall.DELIVER;
      pendingAmount = staged;
      journal.accept(snapshot());
      long accepted = port.insert(key, staged);
      validate(accepted, staged);
      staged -= accepted;
      if (returning) returned = Math.addExact(returned, accepted);
      else delivered = Math.addExact(delivered, accepted);
      state = staged == 0 ? State.COMPLETE : State.STAGED;
      reason = staged == 0 ? OK : FULL;
      pending = PendingCall.NONE;
      pendingAmount = 0;
      journal.accept(snapshot());
      return new OperationResult(accepted, reason);
    } catch (RuntimeException error) {
      quarantine(error);
      return new OperationResult(0, UNCERTAIN);
    } finally {
      executing = false;
    }
  }

  public Snapshot snapshot() {
    checkThread();
    return new Snapshot(
        owner,
        key,
        source.backingId(),
        destination.backingId(),
        staged,
        delivered,
        returned,
        state,
        reason,
        diagnostic,
        pending,
        pendingAmount);
  }

  /** Rebind only exact backing IDs; loading never performs a handler call or releases staging. */
  public static StagedTransfer restore(
      Snapshot saved,
      ResourcePort source,
      ResourcePort destination,
      BooleanSupplier authorized,
      Consumer<Snapshot> journal) {
    Objects.requireNonNull(saved);
    if (!saved.source().equals(source.backingId())
        || !saved.destination().equals(destination.backingId()))
      throw new IllegalArgumentException("Transfer recovery backing mismatch");
    var move =
        new StagedTransfer(saved.owner(), saved.key(), source, destination, authorized, journal);
    move.staged = saved.staged();
    move.delivered = saved.delivered();
    move.returned = saved.returned();
    move.state = saved.state();
    move.reason = saved.reason();
    move.diagnostic = saved.diagnostic();
    move.pending = saved.pending();
    move.pendingAmount = saved.pendingAmount();
    if (saved.pending() != PendingCall.NONE) {
      move.state = State.QUARANTINED;
      move.reason = UNCERTAIN;
      move.diagnostic =
          "Interrupted external " + saved.pending() + "; preserve evidence and reconcile manually";
    }
    return move;
  }

  private static void validate(long actual, long offered) {
    if (actual < 0 || actual > offered) {
      throw new IllegalStateException("Handler returned " + actual + " for offered " + offered);
    }
  }

  private void quarantine(RuntimeException error) {
    state = State.QUARANTINED;
    reason = UNCERTAIN;
    String message = error.getClass().getSimpleName() + ": " + error.getMessage();
    diagnostic = message.substring(0, Math.min(message.length(), 512));
  }

  private void checkThread() {
    if (Thread.currentThread() != thread) {
      throw new IllegalStateException("Transfer mutations require the owning thread");
    }
  }

  public UUID owner() {
    return owner;
  }

  public ResourceKey key() {
    return key;
  }

  public long staged() {
    return staged;
  }

  public long delivered() {
    return delivered;
  }

  public long returned() {
    return returned;
  }

  public State state() {
    return state;
  }

  public OperationResult.Reason reason() {
    return reason;
  }

  public String diagnostic() {
    return diagnostic;
  }
}
