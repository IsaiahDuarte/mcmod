package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;

class EnergyBankHandleDataTest {
  @Test
  void committedSchemaOneFixtureKeepsExactOwnerAndGeneration() throws Exception {
    try (var input = getClass().getResourceAsStream("/persistence/energy-bank-lease-v1.snbt")) {
      assertNotNull(input);
      var fixture = TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      var expected = new DeviceRegistry.Handle(new UUID(0, 11), new UUID(0, 1), 2);
      assertEquals(expected, EnergyBankHandleData.read(fixture));
      assertEquals(fixture, EnergyBankHandleData.write(expected));
    }
  }

  @Test
  void futureMalformedAndExtraFieldsRejectWithoutRewritingInput() throws Exception {
    var source =
        EnergyBankHandleData.write(new DeviceRegistry.Handle(new UUID(0, 11), new UUID(0, 1), 2));
    for (String mutation :
        new String[] {
          "{Schema:2}", "{Generation:0L}", "{Generation:2}", "{World:1}", "{Unknown:1}"
        }) {
      var bad = source.copy().merge(TagParser.parseTag(mutation));
      var original = bad.copy();
      assertThrows(IllegalArgumentException.class, () -> EnergyBankHandleData.read(bad));
      assertEquals(original, bad);
    }
    assertNull(EnergyBankHandleData.read(null));
    assertThrows(
        IllegalArgumentException.class, () -> EnergyBankHandleData.read(StringTag.valueOf("bad")));
  }
}
