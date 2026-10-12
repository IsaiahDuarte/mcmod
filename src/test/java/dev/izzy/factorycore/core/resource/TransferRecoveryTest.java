package dev.izzy.factorycore.core.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TransferRecoveryTest {
  private static final ResourceKey KEY =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);

  private static final class Port implements ResourcePort {
    private final UUID id = UUID.randomUUID();
    long stock;
    long acceptance = Long.MAX_VALUE;
    StagedTransfer.Snapshot interrupted;
    java.util.function.Supplier<StagedTransfer.Snapshot> saved;
    boolean interrupt;

    Port(long stock) {
      this.stock = stock;
    }

    @Override
    public UUID backingId() {
      return id;
    }

    @Override
    public long simulateInsert(ResourceKey key, long amount) {
      return amount;
    }

    @Override
    public long extract(ResourceKey key, long amount) {
      long actual = Math.min(amount, stock);
      stock -= actual;
      if (interrupt) {
        interrupted = saved.get();
        throw new IllegalStateException("After external extraction");
      }
      return actual;
    }

    @Override
    public long insert(ResourceKey key, long amount) {
      long actual = Math.min(amount, acceptance);
      stock += actual;
      if (interrupt) {
        interrupted = saved.get();
        throw new IllegalStateException("After external insertion");
      }
      return actual;
    }
  }

  @Test
  void knownPartialRemainderRestoresWithoutExtractingAgainAndRechecksAuthority() {
    var source = new Port(100);
    var destination = new Port(0);
    destination.acceptance = 17;
    var history = new ArrayList<StagedTransfer.Snapshot>();
    var authorized = new AtomicBoolean(true);
    var move =
        StagedTransfer.start(
            UUID.randomUUID(), KEY, source, destination, 60, 60, authorized::get, history::add);
    assertEquals(40, source.stock);
    assertEquals(17, destination.stock);
    assertEquals(43, move.staged());
    assertEquals(StagedTransfer.PendingCall.EXTRACT, history.get(0).pending());
    assertEquals(60, history.get(0).pendingAmount());
    var restored =
        StagedTransfer.restore(move.snapshot(), source, destination, authorized::get, history::add);
    authorized.set(false);
    assertEquals(OperationResult.Reason.DENIED, restored.deliver().reason());
    assertEquals(40, source.stock);
    assertEquals(17, destination.stock);
    assertEquals(43, restored.cancel().amount());
    assertEquals(83, source.stock);
    assertEquals(17, destination.stock);
    assertEquals(17, restored.delivered());
    assertEquals(43, restored.returned());
    assertEquals(StagedTransfer.State.COMPLETE, restored.state());
    var done =
        StagedTransfer.restore(restored.snapshot(), source, destination, () -> true, history::add);
    assertEquals(0, done.deliver().amount());
    assertEquals(0, done.cancel().amount());
    assertEquals(100, source.stock + destination.stock);
  }

  @Test
  void interruptedExtractionAndDeliveryNeverReplayOrInventRefunds() {
    for (boolean extraction : new boolean[] {true, false}) {
      var source = new Port(80);
      var destination = new Port(0);
      var history = new ArrayList<StagedTransfer.Snapshot>();
      var target = extraction ? source : destination;
      target.interrupt = true;
      target.saved = () -> history.getLast();
      var move =
          StagedTransfer.start(
              UUID.randomUUID(), KEY, source, destination, 50, 50, () -> true, history::add);
      assertEquals(StagedTransfer.State.QUARANTINED, move.state());
      var beforeSource = source.stock;
      var beforeDestination = destination.stock;
      var interrupted = target.interrupted;
      assertEquals(
          extraction ? StagedTransfer.PendingCall.EXTRACT : StagedTransfer.PendingCall.DELIVER,
          interrupted.pending());
      assertEquals(50, interrupted.pendingAmount());
      var restored =
          StagedTransfer.restore(interrupted, source, destination, () -> true, history::add);
      assertEquals(StagedTransfer.State.QUARANTINED, restored.state());
      assertEquals(OperationResult.Reason.UNCERTAIN, restored.deliver().reason());
      assertEquals(OperationResult.Reason.UNCERTAIN, restored.cancel().reason());
      assertEquals(beforeSource, source.stock);
      assertEquals(beforeDestination, destination.stock);
      assertTrue(restored.diagnostic().contains("Interrupted external"));
    }
  }

  @Test
  void interruptedReturnRetainsQuarantineAndBackingMismatchRejectsBeforeCalls() {
    var source = new Port(100);
    var destination = new Port(0);
    destination.acceptance = 0;
    var history = new ArrayList<StagedTransfer.Snapshot>();
    var move =
        StagedTransfer.start(
            UUID.randomUUID(), KEY, source, destination, 30, 30, () -> true, history::add);
    source.interrupt = true;
    source.saved = () -> history.getLast();
    assertEquals(OperationResult.Reason.UNCERTAIN, move.cancel().reason());
    assertEquals(StagedTransfer.PendingCall.RETURN, source.interrupted.pending());
    var restored =
        StagedTransfer.restore(source.interrupted, source, destination, () -> true, history::add);
    assertEquals(100, source.stock);
    assertEquals(OperationResult.Reason.UNCERTAIN, restored.cancel().reason());
    assertEquals(100, source.stock);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            StagedTransfer.restore(
                move.snapshot(), new Port(0), destination, () -> true, history::add));
  }

  @Test
  void journalFailureBeforeMutationStopsExtractionAndDiagnosticsStayBounded() {
    var source = new Port(100);
    var destination = new Port(0);
    var move =
        StagedTransfer.start(
            UUID.randomUUID(),
            KEY,
            source,
            destination,
            20,
            20,
            () -> true,
            saved -> {
              throw new IllegalStateException("x".repeat(2000));
            });
    assertEquals(100, source.stock);
    assertEquals(0, destination.stock);
    assertEquals(StagedTransfer.State.QUARANTINED, move.state());
    assertEquals(512, move.diagnostic().length());
    assertEquals(20, move.snapshot().pendingAmount());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StagedTransfer.Snapshot(
                UUID.randomUUID(),
                KEY,
                source.id,
                destination.id,
                0,
                0,
                0,
                StagedTransfer.State.STAGED,
                OperationResult.Reason.OK,
                "",
                StagedTransfer.PendingCall.NONE,
                0));
    assertThrows(
        ArithmeticException.class,
        () ->
            new StagedTransfer.Snapshot(
                UUID.randomUUID(),
                KEY,
                source.id,
                destination.id,
                Long.MAX_VALUE,
                1,
                0,
                StagedTransfer.State.STAGED,
                OperationResult.Reason.OK,
                "",
                StagedTransfer.PendingCall.NONE,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StagedTransfer.Snapshot(
                UUID.randomUUID(),
                KEY,
                source.id,
                destination.id,
                1,
                0,
                0,
                StagedTransfer.State.STAGED,
                OperationResult.Reason.UNCERTAIN,
                "",
                StagedTransfer.PendingCall.NONE,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StagedTransfer.Snapshot(
                UUID.randomUUID(),
                KEY,
                source.id,
                destination.id,
                1,
                0,
                0,
                StagedTransfer.State.STAGED,
                OperationResult.Reason.OK,
                "",
                StagedTransfer.PendingCall.DELIVER,
                2));
  }
}
