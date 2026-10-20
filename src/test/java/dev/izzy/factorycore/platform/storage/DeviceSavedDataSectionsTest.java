package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;

/**
 * The {@link DeviceSavedData.Section} table matches history: each committed world fixture (schemas
 * 1-8 and the current golden) has exactly the top-level keys the table allows for its schema, so a
 * section's {@code since} is the first schema whose real save contains it.
 */
class DeviceSavedDataSectionsTest {
  private static CompoundTag fixture(int schema) throws Exception {
    try (var input =
        DeviceSavedDataSectionsTest.class.getResourceAsStream(
            "/persistence/registry-v" + schema + ".snbt")) {
      assertNotNull(input, "fixture v" + schema);
      return TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void everyFixtureHasExactlyItsSchemasSections() throws Exception {
    for (int schema = 1; schema <= 8; schema++) {
      var tag = fixture(schema);
      assertEquals(schema, tag.getInt("Schema"));
      assertEquals(DeviceSavedData.worldFields(schema), tag.getAllKeys(), "schema " + schema);
    }
    try (var input = getClass().getResourceAsStream("/persistence/world-v13.dat")) {
      var current = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()).getCompound("data");
      assertEquals(
          DeviceSavedData.worldFields(DeviceSavedData.SCHEMA), current.getAllKeys(), "current");
    }
  }

  @Test
  void sectionsAreIntroducedInOrderWithinTheSupportedRange() {
    int previous = 1;
    for (var section : DeviceSavedData.Section.values()) {
      assertTrue(section.since > previous, section + " after " + previous);
      assertTrue(section.since <= DeviceSavedData.SCHEMA, section.toString());
      previous = section.since;
    }
  }
}
