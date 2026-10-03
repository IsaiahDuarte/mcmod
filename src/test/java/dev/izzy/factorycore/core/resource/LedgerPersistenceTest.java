package dev.izzy.factorycore.core.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerPersistenceTest {
  private static final UUID BACKING = new UUID(0, 1);
  private static final UUID OWNER = new UUID(0, 2);
  private static final ResourceKey IRON =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {3, 1});

  @Test
  void exactHugeStockAndCompetingClaimsSurviveOfflineRecoveryAndSafeDowngrade() {
    var ledger = new ResourceLedger(BACKING, ResourceKind.ITEM, 1024, true, 8, 8);
    ledger.insert(IRON, Long.MAX_VALUE);
    ledger.reserve(OWNER, IRON, Long.MAX_VALUE - 1);
    ledger.reserve(new UUID(0, 3), IRON, 1);
    LedgerState captured = ledger.persistentState();
    var restored =
        ResourceLedger.restore(LedgerStateCodec.decode(LedgerStateCodec.encode(captured)));
    assertEquals(BACKING, restored.id());
    assertEquals(
        new ResourceLedger.Stock(Long.MAX_VALUE, Long.MAX_VALUE, 0, false), restored.stock(IRON));
    assertEquals(OperationResult.Reason.OFFLINE, restored.extract(IRON, 1, OWNER).reason());
    assertEquals(OperationResult.Reason.FULL, restored.resize(1024, false).reason());
    restored.setLoaded(true);
    assertEquals(Long.MAX_VALUE - 1, restored.extract(IRON, Long.MAX_VALUE, OWNER).amount());
    assertEquals(1, restored.stock(IRON).reserved());
    assertEquals(1, restored.release(new UUID(0, 3), IRON));
    assertEquals(1, restored.extract(IRON, 1, null).amount());
    ledger.release(OWNER, IRON);
    assertEquals(
        Long.MAX_VALUE - 1,
        captured.reservations().stream()
            .filter(claim -> claim.owner().equals(OWNER))
            .findFirst()
            .orElseThrow()
            .amount());
    assertThrows(UnsupportedOperationException.class, () -> captured.contents().clear());
    assertThrows(UnsupportedOperationException.class, () -> captured.reservations().clear());
  }

  @Test
  void emptyAndEveryResourceKindRoundTripWithoutFloatConversion() {
    for (ResourceKind kind : ResourceKind.values()) {
      var ledger = new ResourceLedger(BACKING, kind, Long.MAX_VALUE, false, 8, 8);
      assertEquals(
          ledger.persistentState(),
          LedgerStateCodec.decode(LedgerStateCodec.encode(ledger.persistentState())));
      var key =
          kind == ResourceKind.ENERGY
              ? ResourceKey.ENERGY
              : new ResourceKey(kind, "minecraft:water", new byte[] {1, 2, 3});
      ledger.insert(key, 9_007_199_254_740_993L);
      ledger.reserve(OWNER, key, 1000);
      var restored =
          ResourceLedger.restore(
              LedgerStateCodec.decode(LedgerStateCodec.encode(ledger.persistentState())));
      restored.setLoaded(true);
      assertEquals(1000, restored.extract(key, 1000, OWNER).amount());
      assertEquals(9_007_199_254_739_993L, restored.stock(key).total());
    }
  }

  @Test
  void committedVersionOneFixtureRemainsCompatibleAndEncodingIsDeterministic() throws Exception {
    byte[] fixture;
    try (var input = getClass().getResourceAsStream("/persistence/ledger-v1.hex")) {
      assertNotNull(input, "Committed schema fixture must be packaged in test resources");
      fixture =
          HexFormat.of()
              .parseHex(new String(input.readAllBytes(), StandardCharsets.US_ASCII).strip());
    }
    var decoded = LedgerStateCodec.decode(fixture);
    var restored = ResourceLedger.restore(decoded);
    assertEquals(new ResourceLedger.Stock(17, 7, 0, false), restored.stock(ResourceKey.ENERGY));
    assertArrayEquals(fixture, LedgerStateCodec.encode(decoded));
    var first =
        new LedgerState(
            BACKING,
            ResourceKind.ITEM,
            30,
            false,
            8,
            8,
            Map.of(
                IRON,
                10L,
                new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {3, 2}),
                20L),
            List.of(
                new LedgerState.Reservation(new UUID(0, 3), IRON, 2),
                new LedgerState.Reservation(OWNER, IRON, 3)));
    var second =
        new LedgerState(
            BACKING,
            ResourceKind.ITEM,
            30,
            false,
            8,
            8,
            first.contents(),
            first.reservations().reversed());
    assertArrayEquals(LedgerStateCodec.encode(first), LedgerStateCodec.encode(second));
  }

  @Test
  void corruptUnknownAndAmplifiedDeclarationsRejectWithoutChangingOriginalBytes() throws Exception {
    var ledger = new ResourceLedger(BACKING, ResourceKind.ITEM, 100, false, 8, 8);
    ledger.insert(IRON, 10);
    byte[] valid = LedgerStateCodec.encode(ledger.persistentState());
    for (int offset : new int[] {0, 10, valid.length - 1}) {
      byte[] corrupt = valid.clone();
      corrupt[offset] ^= 1;
      byte[] preserved = corrupt.clone();
      assertThrows(IllegalArgumentException.class, () -> LedgerStateCodec.decode(corrupt));
      assertArrayEquals(preserved, corrupt);
    }
    for (int version : new int[] {0, 2, Integer.MAX_VALUE}) {
      byte[] unknown = valid.clone();
      ByteBuffer.wrap(unknown).putInt(4, version);
      reseal(unknown);
      assertThrows(IllegalArgumentException.class, () -> LedgerStateCodec.decode(unknown));
    }
    byte[] bomb = valid.clone();
    ByteBuffer.wrap(bomb).putInt(42, Integer.MAX_VALUE); // Contents count, with valid checksum.
    reseal(bomb);
    assertThrows(IllegalArgumentException.class, () -> LedgerStateCodec.decode(bomb));
    int keyBytes = 2 + IRON.registryId().length() + 4 + IRON.componentByteCount();
    byte[] negative = valid.clone();
    ByteBuffer.wrap(negative).putLong(46 + keyBytes, -1);
    reseal(negative);
    assertThrows(IllegalArgumentException.class, () -> LedgerStateCodec.decode(negative));
    var secondKey = new ResourceKey(ResourceKind.ITEM, IRON.registryId(), new byte[] {3, 2});
    byte[] duplicate = LedgerStateCodec.encode(state(Map.of(IRON, 10L, secondKey, 20L), List.of()));
    System.arraycopy(duplicate, 46, duplicate, 46 + keyBytes + 8, keyBytes);
    reseal(duplicate);
    assertThrows(IllegalArgumentException.class, () -> LedgerStateCodec.decode(duplicate));
    for (int size : new int[] {0, 40, valid.length - 1}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> LedgerStateCodec.decode(Arrays.copyOf(valid, size)));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> LedgerStateCodec.decode(new byte[LedgerStateCodec.MAX_BYTES + 1]));
    assertEquals(10, ledger.stock(IRON).total());
  }

  @Test
  void invalidStateClaimsOverflowAndOversizedSerializationCannotBecomeAvailableStock() {
    assertThrows(
        IllegalArgumentException.class,
        () -> state(Map.of(IRON, 10L), List.of(new LedgerState.Reservation(OWNER, IRON, 11))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            state(
                Map.of(IRON, 10L),
                List.of(
                    new LedgerState.Reservation(OWNER, IRON, 1),
                    new LedgerState.Reservation(OWNER, IRON, 1))));
    assertThrows(IllegalArgumentException.class, () -> state(Map.of(IRON, -1L), List.of()));
    assertThrows(IllegalArgumentException.class, () -> state(Map.of(IRON, 0L), List.of()));
    var other = new ResourceKey(ResourceKind.ITEM, "minecraft:gold_ingot", new byte[0]);
    assertThrows(
        IllegalArgumentException.class,
        () -> state(Map.of(IRON, Long.MAX_VALUE, other, 1L), List.of()));
    var oversized = new ResourceLedger(BACKING, ResourceKind.ITEM, 256, false, 256, 8);
    for (int i = 0; i < 128; i++) {
      oversized.insert(new ResourceKey(ResourceKind.ITEM, "test:item_" + i, new byte[65536]), 1);
    }
    assertThrows(
        IllegalArgumentException.class, () -> LedgerStateCodec.encode(oversized.persistentState()));
    assertEquals(128, oversized.total());
  }

  @Test
  void repeatedRecoveryPreservesAvailableStockAndOwnedClaimsInGeneratedSequences() {
    long seed = 0x5A7E001L;
    var random = new Random(seed);
    var ledger = new ResourceLedger(BACKING, ResourceKind.ITEM, 1000, false, 8, 8);
    for (int step = 0; step < 2000; step++) {
      switch (random.nextInt(4)) {
        case 0 -> ledger.insert(IRON, random.nextInt(50));
        case 1 -> ledger.extract(IRON, random.nextInt(50), null);
        case 2 -> ledger.reserve(OWNER, IRON, random.nextInt(50));
        case 3 -> ledger.release(OWNER, IRON);
        default -> throw new AssertionError();
      }
      var before = ledger.stock(IRON);
      ledger =
          ResourceLedger.restore(
              LedgerStateCodec.decode(LedgerStateCodec.encode(ledger.persistentState())));
      assertFalse(ledger.stock(IRON).loaded(), "seed=" + seed + " step=" + step);
      ledger.setLoaded(true);
      assertEquals(before, ledger.stock(IRON), "seed=" + seed + " step=" + step);
    }
  }

  private static LedgerState state(
      Map<ResourceKey, Long> stock, List<LedgerState.Reservation> claims) {
    return new LedgerState(BACKING, ResourceKind.ITEM, Long.MAX_VALUE, true, 8, 8, stock, claims);
  }

  private static void reseal(byte[] encoded) throws Exception {
    int payload = encoded.length - 32;
    byte[] checksum = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(encoded, payload));
    System.arraycopy(checksum, 0, encoded, payload, 32);
  }
}
