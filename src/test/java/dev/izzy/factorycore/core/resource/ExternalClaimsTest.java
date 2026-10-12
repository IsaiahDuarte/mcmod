package dev.izzy.factorycore.core.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExternalClaimsTest {
  private static final ResourceKey KEY =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);

  @Test
  void competingClaimsRespectShortageAndBackgroundAccess() {
    var book = new ExternalClaims();
    var backing = UUID.randomUUID();
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    assertEquals(60, book.reserve(backing, first, KEY, 60, 100).amount());
    assertEquals(40, book.reserve(backing, second, KEY, 40, 100).amount());
    assertEquals(
        OperationResult.Reason.SHORTAGE, book.reserve(backing, second, KEY, 1, 100).reason());
    assertEquals(0, book.available(backing, null, KEY, 100));
    assertEquals(10, book.available(backing, first, KEY, 50));
    assertEquals(0, book.available(backing, second, KEY, 50));
    book.consume(backing, first, KEY, 10);
    assertEquals(50, book.held(backing, first, KEY));
    assertEquals(40, book.release(backing, second, KEY));
    assertEquals(20, book.available(backing, null, KEY, 70));
  }

  @Test
  void reloadPreservesOrphansAndRejectsDuplicateOverflowAndMetadataExcess() {
    var backing = UUID.randomUUID();
    var owner = UUID.randomUUID();
    var entry = new ExternalClaims.Claim(backing, owner, KEY, Long.MAX_VALUE);
    var book = new ExternalClaims(List.of(entry));
    assertEquals(0, book.available(backing, owner, KEY, 0));
    assertEquals(List.of(entry), book.snapshot());
    assertThrows(IllegalArgumentException.class, () -> new ExternalClaims(List.of(entry, entry)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExternalClaims(
                List.of(entry, new ExternalClaims.Claim(backing, UUID.randomUUID(), KEY, 1))));
    var large =
        new ResourceKey(
            ResourceKind.ITEM, "minecraft:iron_ingot", new byte[ResourceKey.MAX_COMPONENT_BYTES]);
    var bounded = new ExternalClaims();
    int accepted = 0;
    for (int index = 0; index < 100; index++) {
      if (bounded.reserve(backing, UUID.randomUUID(), large, 1, 100).amount() == 1) accepted++;
    }
    assertTrue(accepted > 0 && accepted < 100);
    assertTrue(bounded.metadataBytes() <= ExternalClaims.MAX_BYTES);
    assertEquals(accepted, bounded.snapshot().size());
  }

  @Test
  void actualPartialExtractionDebitsOnlyKnownUnitsAndFaultKeepsHold() {
    var book = new ExternalClaims();
    var backing = UUID.randomUUID();
    var owner = UUID.randomUUID();
    book.reserve(backing, owner, KEY, 60, 100);
    long[] stock = {100};
    boolean[] fault = {false};
    ResourcePort provider =
        new ResourcePort() {
          @Override
          public UUID backingId() {
            return backing;
          }

          @Override
          public long simulateInsert(ResourceKey key, long amount) {
            return amount;
          }

          @Override
          public long insert(ResourceKey key, long amount) {
            stock[0] += amount;
            return amount;
          }

          @Override
          public long extract(ResourceKey key, long amount) {
            long actual = Math.min(7, amount);
            stock[0] -= actual;
            if (fault[0]) throw new IllegalStateException("Uncertain extraction");
            return actual;
          }
        };
    var port = new ExternalClaimPort(provider, book, owner, key -> stock[0]);
    assertEquals(7, port.extract(KEY, 40));
    assertEquals(53, book.held(backing, owner, KEY));
    assertEquals(93, stock[0]);
    fault[0] = true;
    assertThrows(IllegalStateException.class, () -> port.extract(KEY, 40));
    assertEquals(53, book.held(backing, owner, KEY));
    assertEquals(86, stock[0]);
    stock[0] = 20;
    fault[0] = false;
    assertEquals(7, port.extract(KEY, 40));
    assertEquals(46, book.held(backing, owner, KEY));
    assertEquals(13, stock[0]);
  }

  @Test
  void generatedCompetingOperationsPreserveExactClaims() {
    long seed = 0xEC1A1L;
    var random = new Random(seed);
    var book = new ExternalClaims();
    var backing = UUID.randomUUID();
    var owners = new UUID[] {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
    long[] expected = new long[3];
    for (int step = 0; step < 10000; step++) {
      int index = random.nextInt(3);
      long amount = random.nextInt(50);
      switch (random.nextInt(3)) {
        case 0 -> {
          var result = book.reserve(backing, owners[index], KEY, amount, 100);
          if (result.amount() > 0) expected[index] += amount;
        }
        case 1 -> {
          long consumed = Math.min(amount, expected[index]);
          book.consume(backing, owners[index], KEY, consumed);
          expected[index] -= consumed;
        }
        default -> {
          book.release(backing, owners[index], KEY);
          expected[index] = 0;
        }
      }
      long sum = 0;
      for (int owner = 0; owner < 3; owner++) {
        assertEquals(
            expected[owner],
            book.held(backing, owners[owner], KEY),
            "seed=" + seed + " step=" + step);
        sum += expected[owner];
      }
      assertEquals(sum, book.reserved(backing, KEY));
      assertEquals(100 - sum, book.available(backing, null, KEY, 100));
    }
  }

  @Test
  void allWrappersShareReentrancyGuardAndProtectHeldStock() {
    var book = new ExternalClaims();
    var backing = UUID.randomUUID();
    var owner = UUID.randomUUID();
    book.reserve(backing, owner, KEY, 50, 100);
    ResourcePort provider =
        new ResourcePort() {
          @Override
          public UUID backingId() {
            return backing;
          }

          @Override
          public long simulateInsert(ResourceKey key, long amount) {
            return amount;
          }

          @Override
          public long insert(ResourceKey key, long amount) {
            return amount;
          }

          @Override
          public long extract(ResourceKey key, long amount) {
            assertThrows(IllegalStateException.class, () -> book.release(backing, owner, key));
            return amount;
          }
        };
    var background = new ExternalClaimPort(provider, book, null, key -> 100);
    var owned =
        new ExternalClaimPort(
            provider,
            book,
            owner,
            key -> {
              assertThrows(IllegalStateException.class, () -> background.extract(key, 1));
              return 100;
            });
    assertEquals(20, owned.extract(KEY, 20));
    assertEquals(30, book.held(backing, owner, KEY));
    assertEquals(70, background.extract(KEY, 100));
    assertEquals(30, book.held(backing, owner, KEY));
  }

  @Test
  void countLimitAndBackingIdentityRemainIndependent() {
    var book = new ExternalClaims();
    var owner = UUID.randomUUID();
    var backing = UUID.randomUUID();
    for (int index = 0; index < ExternalClaims.MAX_CLAIMS; index++)
      assertEquals(1, book.reserve(new UUID(0, index), owner, KEY, 1, 1).amount());
    assertEquals(
        OperationResult.Reason.RESERVATION_LIMIT, book.reserve(backing, owner, KEY, 1, 1).reason());
    assertEquals(1, book.release(new UUID(0, 0), owner, KEY));
    assertEquals(1, book.reserve(backing, owner, KEY, 1, 1).amount());
    assertEquals(1, book.held(backing, owner, KEY));
    assertEquals(0, book.held(backing, UUID.randomUUID(), KEY));
    assertEquals(ExternalClaims.MAX_CLAIMS, book.snapshot().size());
  }
}
