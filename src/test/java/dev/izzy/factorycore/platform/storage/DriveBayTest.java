package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DriveBayTest {
  @Test
  void lampFollowsFillAndNetwork() {
    assertEquals(DriveBay.EMPTY, DriveBay.of(false, true, 0, 1000, false));
    assertEquals(DriveBay.OFFLINE, DriveBay.of(true, false, 0, 1000, false));
    assertEquals(DriveBay.ONLINE, DriveBay.of(true, true, 749, 1000, false));
    assertEquals(DriveBay.NEAR_FULL, DriveBay.of(true, true, 750, 1000, false));
    assertEquals(DriveBay.NEAR_FULL, DriveBay.of(true, true, 999, 1000, false));
    assertEquals(DriveBay.FULL, DriveBay.of(true, true, 1000, 1000, false));
    assertEquals(DriveBay.ONLINE, DriveBay.of(true, true, Long.MAX_VALUE, -1, true));
    assertEquals(
        DriveBay.NEAR_FULL, DriveBay.of(true, true, Long.MAX_VALUE - 1, Long.MAX_VALUE, false));
    assertEquals("near_full", DriveBay.NEAR_FULL.getSerializedName());
  }
}
