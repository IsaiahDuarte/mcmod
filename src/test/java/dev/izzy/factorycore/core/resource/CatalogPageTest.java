package dev.izzy.factorycore.core.resource;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogPageTest {
  private static ResourceKey key(int index, int bytes) {
    var components = new byte[bytes];
    if (bytes > 0) components[bytes - 1] = (byte) index;
    return new ResourceKey(
        ResourceKind.ITEM,
        "factorycore:key_" + String.format(Locale.ROOT, "%04d", index),
        components);
  }

  @Test
  void keyPagesPreserveExactComponentsClaimsAndStableCursorWithoutSnapshotMutation() {
    var ledger = new ResourceLedger(UUID.randomUUID(), ResourceKind.ITEM, 10000, false, 4096, 8192);
    var keys = new ArrayList<ResourceKey>();
    var owner = UUID.randomUUID();
    for (int index = 69; index >= 0; index--) {
      var key = key(index, 4);
      keys.add(key);
      ledger.insert(key, index + 1);
      ledger.reserve(owner, key, index / 2);
    }
    var before = ledger.persistentState();
    var observed = new ArrayList<ResourceKey>();
    ResourceKey cursor = null;
    do {
      var page = ledger.catalog(cursor, 7);
      assertTrue(page.entries().size() <= 7);
      for (var entry : page.entries()) {
        observed.add(entry.key());
        assertEquals(ledger.stock(entry.key()), entry.stock());
      }
      cursor = page.next();
    } while (cursor != null);
    keys.sort(ResourceKey::compareTo);
    assertEquals(keys, observed);
    assertEquals(before, ledger.persistentState());
    ledger.setLoaded(false);
    assertTrue(
        ledger.catalog(null, 16).entries().stream()
            .allMatch(entry -> !entry.stock().loaded() && entry.stock().available() == 0));
    assertThrows(
        UnsupportedOperationException.class, () -> ledger.catalog(null, 1).entries().clear());
  }

  @Test
  void maximalMetadataPagesAlwaysAdvanceAndRemovedCursorRemainsAnExclusivePosition() {
    var ledger = new ResourceLedger(UUID.randomUUID(), ResourceKind.ITEM, 100, false, 100, 100);
    for (int index = 0; index < 20; index++)
      ledger.insert(key(index, ResourceKey.MAX_COMPONENT_BYTES), 1);
    var first = ledger.catalog(null, 16);
    assertEquals(1, first.entries().size());
    var cursor = first.next();
    ledger.extract(cursor, 1, null);
    ledger.insert(key(0, 1), 1);
    var seen = new HashSet<ResourceKey>();
    do {
      var page = ledger.catalog(cursor, 16);
      assertFalse(page.entries().isEmpty());
      assertTrue(page.entries().stream().allMatch(entry -> seen.add(entry.key())));
      cursor = page.next();
    } while (cursor != null);
    assertEquals(19, seen.size());
    assertThrows(IllegalArgumentException.class, () -> ledger.catalog(ResourceKey.ENERGY, 1));
    assertThrows(IllegalArgumentException.class, () -> ledger.catalog(null, 17));
  }

  @Test
  void incrementalCountsApplyReservationsOncePerBackingAndAggregateBeyondSignedLong() {
    var counts = new CatalogCounts();
    var key = key(1, 0);
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    assertEquals(OperationResult.Reason.OK, counts.observe(first, key, 20, 18, null));
    assertEquals(OperationResult.Reason.OK, counts.observe(first, key, 15, 18, null));
    assertEquals(OperationResult.Reason.OK, counts.observe(second, key, 10, 0, null));
    var row = counts.page(null, 32).rows().getFirst();
    assertEquals(BigInteger.valueOf(45), row.total());
    assertEquals(BigInteger.valueOf(18), row.reserved());
    assertEquals(BigInteger.valueOf(27), row.available());
    counts.observe(first, key, 5, 0, null);
    assertEquals(BigInteger.valueOf(50), counts.page(null, 32).rows().getFirst().available());
    var energy = new CatalogCounts();
    energy.observe(first, ResourceKey.ENERGY, Long.MAX_VALUE, 1, Long.MAX_VALUE);
    energy.observe(second, ResourceKey.ENERGY, Long.MAX_VALUE, 2, Long.MAX_VALUE);
    var e = energy.page(null, 32).rows().getFirst();
    assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), e.total());
    assertEquals(e.total(), e.capacity());
    assertEquals(e.total().subtract(BigInteger.valueOf(3)), e.available());
  }

  @Test
  void admissionFailureChangesNoObservedCountAndHeavyResultPagesRemainBounded() {
    var counts = new CatalogCounts();
    var backing = UUID.randomUUID();
    ResourceKey last = null;
    for (int index = 0; index < 1000; index++) {
      var key = key(index, ResourceKey.MAX_COMPONENT_BYTES);
      if (counts.observe(backing, key, 1, 0, null) != OperationResult.Reason.OK) {
        last = key;
        break;
      }
    }
    assertNotNull(last);
    long bytes = counts.bytes();
    assertEquals(OperationResult.Reason.TECHNICAL_LIMIT, counts.observe(backing, last, 1, 0, null));
    assertEquals(bytes, counts.bytes());
    assertEquals(1, counts.page(null, 32).rows().size());
    assertNotNull(counts.page(null, 32).next());
    var overflow = new CatalogCounts();
    var key = key(1, 0);
    overflow.observe(backing, key, Long.MAX_VALUE, 0, null);
    assertEquals(
        OperationResult.Reason.TECHNICAL_LIMIT, overflow.observe(backing, key, 1, 0, null));
    assertEquals(
        BigInteger.valueOf(Long.MAX_VALUE), overflow.page(null, 32).rows().getFirst().total());
    assertEquals(0, overflow.additionalBytes(backing, key));
  }

  @Test
  void registryCatalogPreservesOfflineCountsAndRejectsOldPortableLease() {
    var registry = new DeviceRegistry(UUID.randomUUID(), () -> {}, UUID::randomUUID);
    var portable = registry.create(ResourceKind.ITEM, 1000, false, 100, 100).handle();
    var location = new DeviceRegistry.Location("minecraft:overworld", 42, 0);
    var handle = registry.attach(portable, location).handle();
    registry.availability(handle, location, true);
    registry.insert(handle, key(1, 4), 10);
    registry.routing(position -> false);
    var row = registry.catalog(handle, null, 16).entries().getFirst();
    assertEquals(10, row.stock().total());
    assertEquals(0, row.stock().available());
    assertFalse(row.stock().loaded());
    registry.detach(handle, location);
    assertThrows(IllegalStateException.class, () -> registry.catalog(handle, null, 16));
  }

  @Test
  void generatedMutationAndRestoreSequencePreservesPagedStockAndClaims() {
    long seed = 0xCA7A106L;
    var random = new Random(seed);
    var ledger = new ResourceLedger(UUID.randomUUID(), ResourceKind.ITEM, 1000, false, 128, 256);
    var owner = UUID.randomUUID();
    for (int step = 0; step < 5000; step++) {
      var key = key(random.nextInt(128), 4);
      long amount = random.nextInt(32);
      switch (random.nextInt(5)) {
        case 0 -> ledger.insert(key, amount);
        case 1 -> ledger.extract(key, amount, null);
        case 2 -> ledger.reserve(owner, key, amount);
        case 3 -> ledger.release(owner, key);
        default -> {
          ledger = ResourceLedger.restore(ledger.persistentState());
          ledger.setLoaded(true);
        }
      }
      if (step % 100 == 0 || step == 4999) {
        var actual = new TreeMap<ResourceKey, Long>();
        ResourceKey cursor = null;
        do {
          var page = ledger.catalog(cursor, 16);
          for (var entry : page.entries()) {
            assertNull(
                actual.put(entry.key(), entry.stock().total()),
                "replay seed=" + seed + " step=" + step);
            assertEquals(ledger.stock(entry.key()), entry.stock());
          }
          cursor = page.next();
        } while (cursor != null);
        assertEquals(ledger.snapshot(), actual, "replay seed=" + seed + " step=" + step);
      }
    }
  }
}
