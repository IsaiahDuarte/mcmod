package dev.izzy.factorycore.core.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ResourceAccountingTest {
  private static final ResourceKey IRON = key("minecraft:iron_ingot");
  private static final ResourceKey GOLD = key("minecraft:gold_ingot");

  private static ResourceKey key(String id) {
    return new ResourceKey(ResourceKind.ITEM, id, new byte[0]);
  }

  private static ResourceLedger ledger(long capacity, boolean infinite) {
    return new ResourceLedger(UUID.randomUUID(), ResourceKind.ITEM, capacity, infinite, 10, 10);
  }

  @Test
  void infiniteTierAndAggregatesAreExactAtSignedLongBoundary() {
    var first = ledger(1, true);
    var second = ledger(1, true);
    assertEquals(Long.MAX_VALUE, first.insert(IRON, Long.MAX_VALUE).amount());
    assertEquals(OperationResult.Reason.TECHNICAL_LIMIT, first.insert(IRON, 1).reason());
    assertEquals(Long.MAX_VALUE, second.insert(IRON, Long.MAX_VALUE).amount());
    assertEquals(
        BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO),
        StorageTotals.total(List.of(first, first, second), IRON));
    long exact = 9_007_199_254_740_993L;
    assertEquals(
        Long.MAX_VALUE - exact, first.extract(IRON, Long.MAX_VALUE - exact, null).amount());
    assertEquals(exact, first.stock(IRON).total());
    assertThrows(IllegalArgumentException.class, () -> first.insert(IRON, -1));
    var impostor = new ResourceLedger(first.id(), ResourceKind.ITEM, 1, false, 10, 10);
    assertThrows(
        IllegalArgumentException.class, () -> StorageTotals.total(List.of(first, impostor), IRON));
  }

  @Test
  void reservationsCompeteWithoutRemovingStockAndSurviveUpgradesAndUnload() {
    var store = ledger(100, false);
    var owner = UUID.randomUUID();
    var competitor = UUID.randomUUID();
    store.insert(IRON, 100);
    assertEquals(80, store.reserve(owner, IRON, 80).amount());
    assertEquals(OperationResult.Reason.SHORTAGE, store.reserve(competitor, IRON, 21).reason());
    assertEquals(20, store.extract(IRON, 100, null).amount());
    assertEquals(new ResourceLedger.Stock(80, 80, 0, true), store.stock(IRON));
    assertEquals(OperationResult.Reason.OK, store.resize(0, true).reason());
    assertEquals(OperationResult.Reason.FULL, store.resize(79, false).reason());
    store.setLoaded(false);
    assertEquals(OperationResult.Reason.OFFLINE, store.extract(IRON, 80, owner).reason());
    assertEquals(80, store.stock(IRON).reserved());
    store.setLoaded(true);
    assertEquals(50, store.extract(IRON, 50, owner).amount());
    assertEquals(30, store.release(owner, IRON));
    assertEquals(new ResourceLedger.Stock(30, 0, 30, true), store.stock(IRON));
  }

  @Test
  void componentBytesAreImmutableAndAdministrativeLimitsAreSeparate() {
    byte[] bytes = {1, 2};
    var original = new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", bytes);
    bytes[0] = 99;
    original.components()[0] = 88;
    assertEquals(
        new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {1, 2}), original);
    var store = new ResourceLedger(UUID.randomUUID(), ResourceKind.ITEM, 1, true, 1, 1);
    store.insert(IRON, 100);
    assertEquals(OperationResult.Reason.CATALOG_LIMIT, store.insert(GOLD, 1).reason());
    store.reserve(UUID.randomUUID(), IRON, 1);
    assertEquals(
        OperationResult.Reason.RESERVATION_LIMIT,
        store.reserve(UUID.randomUUID(), IRON, 1).reason());
    assertEquals(100, store.total());
    assertThrows(
        IllegalArgumentException.class, () -> new ResourceKey(ResourceKind.ITEM, "bad id", bytes));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceKey(ResourceKind.ITEM, "minecraft:stone", new byte[65_537]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceKey(ResourceKind.ENERGY, "minecraft:lava", new byte[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceLedger(UUID.randomUUID(), ResourceKind.FLUID, 1, true, 1, 1));
    assertThrows(UnsupportedOperationException.class, () -> store.snapshot().put(GOLD, 1L));
  }

  @Test
  void partialAcceptanceRetainsStagingAndRevocationAllowsOnlyCleanup() {
    var source = ledger(100, false);
    var destination = ledger(100, false);
    source.insert(IRON, 64);
    var authorized = new AtomicBoolean(true);
    var target = limited(new LedgerPort(destination, null), 5);
    var move =
        StagedTransfer.start(
            UUID.randomUUID(), IRON, new LedgerPort(source, null), target, 64, 64, authorized::get);
    assertEquals(5, move.delivered());
    assertEquals(59, move.staged());
    assertEquals(64, source.total() + destination.total() + move.staged());
    authorized.set(false);
    assertEquals(OperationResult.Reason.DENIED, move.deliver().reason());
    assertEquals(59, move.cancel().amount());
    assertEquals(StagedTransfer.State.COMPLETE, move.state());
    assertEquals(64, source.total() + destination.total());
    assertEquals(0, move.cancel().amount());
  }

  @Test
  void fullDestinationDoesNotExtractAndUncertainWritesCannotBeReplayed() {
    var source = ledger(100, false);
    var destination = ledger(0, false);
    source.insert(IRON, 50);
    var full =
        StagedTransfer.start(
            UUID.randomUUID(),
            IRON,
            new LedgerPort(source, null),
            new LedgerPort(destination, null),
            50,
            50,
            () -> true);
    assertEquals(OperationResult.Reason.FULL, full.reason());
    assertEquals(50, source.total());
    destination.resize(100, false);
    ResourcePort faulty =
        new ResourcePort() {
          @Override
          public UUID backingId() {
            return destination.id();
          }

          @Override
          public long simulateInsert(ResourceKey key, long amount) {
            return amount;
          }

          @Override
          public long extract(ResourceKey key, long amount) {
            return 0;
          }

          @Override
          public long insert(ResourceKey key, long amount) {
            destination.insert(key, 3);
            throw new IllegalStateException("External write happened before failure");
          }
        };
    var uncertain =
        StagedTransfer.start(
            UUID.randomUUID(), IRON, new LedgerPort(source, null), faulty, 20, 20, () -> true);
    assertEquals(StagedTransfer.State.QUARANTINED, uncertain.state());
    assertEquals(30, source.total());
    assertEquals(3, destination.total());
    assertEquals(OperationResult.Reason.UNCERTAIN, uncertain.deliver().reason());
    assertEquals(OperationResult.Reason.UNCERTAIN, uncertain.cancel().reason());
    assertEquals(30, source.total());
    assertEquals(3, destination.total());
    assertTrue(uncertain.diagnostic().contains("External write"));
  }

  @Test
  void generatedOperationsConserveResourcesWithRecordedSeeds() {
    for (long seed : new long[] {0xFAC7001L, 0xFAC7002L, 0xFAC7003L}) {
      var random = new Random(seed);
      var stores = List.of(ledger(2000, false), ledger(2000, false), ledger(2000, false));
      var pending = new ArrayList<StagedTransfer>();
      var owner = new UUID(0, seed);
      long introduced = 0;
      long consumed = 0;
      for (int step = 0; step < 10_000; step++) {
        var store = stores.get(random.nextInt(stores.size()));
        var key = random.nextBoolean() ? IRON : GOLD;
        int amount = random.nextInt(100);
        switch (random.nextInt(8)) {
          case 0 -> introduced += store.insert(key, amount).amount();
          case 1 ->
              consumed += store.extract(key, amount, random.nextBoolean() ? owner : null).amount();
          case 2 -> store.reserve(owner, key, amount);
          case 3 -> store.release(owner, key);
          case 4 -> store.setLoaded(random.nextBoolean());
          case 5 -> store.resize(random.nextInt(3000), false);
          case 6 -> {
            var target = stores.get(random.nextInt(stores.size()));
            var move =
                StagedTransfer.start(
                    owner,
                    key,
                    new LedgerPort(store, null),
                    limited(new LedgerPort(target, null), random.nextInt(30)),
                    amount,
                    100,
                    () -> true);
            if (move.staged() > 0) pending.add(move);
          }
          case 7 -> {
            if (!pending.isEmpty()) {
              int index = random.nextInt(pending.size());
              var move = pending.get(index);
              if (random.nextBoolean()) move.deliver();
              else move.cancel();
              if (move.state() == StagedTransfer.State.COMPLETE) pending.remove(index);
            }
          }
          default -> throw new AssertionError();
        }
        long owned =
            stores.stream().mapToLong(ResourceLedger::total).sum()
                + pending.stream().mapToLong(StagedTransfer::staged).sum();
        assertEquals(introduced - consumed, owned, "Replay seed=" + seed + " step=" + step);
        for (var checked : stores) {
          for (var resource : List.of(IRON, GOLD)) {
            var stock = checked.stock(resource);
            assertTrue(stock.reserved() >= 0 && stock.reserved() <= stock.total(), "seed=" + seed);
            assertEquals(stock.loaded() ? stock.total() - stock.reserved() : 0, stock.available());
          }
        }
      }
    }
  }

  private static ResourcePort limited(ResourcePort delegate, long limit) {
    return new ResourcePort() {
      @Override
      public UUID backingId() {
        return delegate.backingId();
      }

      @Override
      public long simulateInsert(ResourceKey key, long offered) {
        return delegate.simulateInsert(key, offered);
      }

      @Override
      public long extract(ResourceKey key, long requested) {
        return delegate.extract(key, requested);
      }

      @Override
      public long insert(ResourceKey key, long offered) {
        return delegate.insert(key, Math.min(offered, limit));
      }
    };
  }
}
