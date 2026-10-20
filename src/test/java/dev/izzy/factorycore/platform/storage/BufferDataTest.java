package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class BufferDataTest {
  @Test
  void upgradedReferenceRetainsLeaseAndRejectsUnknownTierWithoutTouchingEvidence()
      throws Exception {
    var old = BufferData.read(fixture());
    for (var tier :
        java.util.List.of(
            dev.izzy.factorycore.core.resource.ThroughputTier.ADVANCED,
            dev.izzy.factorycore.core.resource.ThroughputTier.ENDGAME)) {
      var state = new BufferData.State(old.lease(), old.principal(), old.configuration(), tier);
      var tag = BufferData.write(state);
      assertEquals(2, tag.getInt("Schema"));
      assertEquals(state, BufferData.read(tag));
      for (String invalid : java.util.List.of("BASE", "FUTURE")) {
        var malformed = tag.copy();
        malformed.putString("Throughput", invalid);
        var before = malformed.copy();
        assertThrows(IllegalArgumentException.class, () -> BufferData.read(malformed));
        assertEquals(before, malformed);
      }
    }
  }

  private CompoundTag fixture() throws Exception {
    try (var input = getClass().getResourceAsStream("/persistence/buffer-reference-v1.snbt")) {
      assertNotNull(input);
      return TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void fixedReferenceKeepsGenerationPrincipalAndConfigurationWithoutStock() throws Exception {
    var tag = fixture();
    var reference = BufferData.read(tag);
    assertEquals(new UUID(0, 601), reference.lease().id());
    assertEquals(3, reference.lease().generation());
    assertEquals(2, reference.configuration());
    assertEquals(new UUID(0, 201), reference.principal());
    assertEquals(tag, BufferData.write(reference));
  }

  @Test
  void malformedFutureFieldsAndInvalidConfigurationRejectWithoutChangingRawData() throws Exception {
    for (int mode = 0; mode < 6; mode++) {
      var tag = fixture();
      if (mode == 0) tag.putInt("Schema", 2);
      if (mode == 1) tag.putString("Future", "retain");
      if (mode == 2) tag.putInt("Generation", 3);
      if (mode == 3) tag.putLong("Configuration", 0);
      if (mode == 4) tag.remove("Principal");
      if (mode == 5) tag.putInt("Schema", 3);
      var original = tag.copy();
      assertThrows(IllegalArgumentException.class, () -> BufferData.read(tag));
      assertEquals(original, tag);
    }
  }
}
