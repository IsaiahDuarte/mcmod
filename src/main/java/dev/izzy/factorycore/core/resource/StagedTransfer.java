package dev.izzy.factorycore.core.resource;

import static dev.izzy.factorycore.core.resource.OperationResult.Reason.*;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

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

  private final UUID owner;
  private final ResourceKey key;
  private final ResourcePort source;
  private final ResourcePort destination;
  private final BooleanSupplier authorized;
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
      BooleanSupplier authorized) {
    this.owner = Objects.requireNonNull(owner);
    this.key = Objects.requireNonNull(key);
    this.source = Objects.requireNonNull(source);
    this.destination = Objects.requireNonNull(destination);
    this.authorized = Objects.requireNonNull(authorized);
  }

  public static StagedTransfer start(
      UUID owner,
      ResourceKey key,
      ResourcePort source,
      ResourcePort destination,
      long requested,
      long stagingCapacity,
      BooleanSupplier authorized) {
    OperationResult.requireQuantity(requested);
    OperationResult.requireQuantity(stagingCapacity);
    var move = new StagedTransfer(owner, key, source, destination, authorized);
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
      long extracted = source.extract(key, accepted);
      validate(extracted, accepted);
      move.staged = extracted;
      move.state = extracted == 0 ? State.COMPLETE : State.STAGED;
      if (extracted == 0) move.reason = SHORTAGE;
      else move.deliver();
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
      long accepted = port.insert(key, staged);
      validate(accepted, staged);
      staged -= accepted;
      if (returning) returned = Math.addExact(returned, accepted);
      else delivered = Math.addExact(delivered, accepted);
      state = staged == 0 ? State.COMPLETE : State.STAGED;
      reason = staged == 0 ? OK : FULL;
      return new OperationResult(accepted, reason);
    } catch (RuntimeException error) {
      quarantine(error);
      return new OperationResult(0, UNCERTAIN);
    } finally {
      executing = false;
    }
  }

  private static void validate(long actual, long offered) {
    if (actual < 0 || actual > offered) {
      throw new IllegalStateException("Handler returned " + actual + " for offered " + offered);
    }
  }

  private void quarantine(RuntimeException error) {
    state = State.QUARANTINED;
    reason = UNCERTAIN;
    diagnostic = error.getClass().getSimpleName() + ": " + error.getMessage();
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
