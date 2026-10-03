package dev.izzy.factorycore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;
import net.neoforged.fml.common.Mod;
import org.junit.jupiter.api.Test;

class ModMetadataTest {
  @Test
  void developmentJarContainsEntryPointAndMetadataButNoTestFixtures() throws IOException {
    try (var jar = new JarFile(System.getProperty("factorycore.test.jar"))) {
      assertNotNull(jar.getJarEntry("dev/izzy/factorycore/platform/FactoryCoreMod.class"));
      var descriptor = jar.getJarEntry("META-INF/neoforge.mods.toml");
      assertNotNull(descriptor);
      try (var stream = jar.getInputStream(descriptor)) {
        var metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(metadata.contains("version=\"0.1.0-dev\""));
        assertFalse(metadata.contains("${"));
      }
      assertFalse(jar.stream().anyMatch(entry -> entry.getName().contains("FakeOptionalApi")));
      assertFalse(jar.stream().anyMatch(entry -> entry.getName().endsWith("Test.class")));
      assertFalse(jar.stream().anyMatch(entry -> entry.getName().contains("/testing/")));
      assertFalse(jar.stream().anyMatch(entry -> entry.getName().contains("factorycore_tests")));
    }
  }

  @Test
  void processedMetadataMatchesLoaderEntryAndHasNoTemplateTokens() throws IOException {
    try (var stream = getClass().getResourceAsStream("/META-INF/neoforge.mods.toml")) {
      assertNotNull(stream, "Loader metadata must be packaged as a resource");
      var metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(metadata.contains("modId=\"" + FactoryCoreMod.MOD_ID + "\""));
      assertTrue(metadata.contains("version=\"0.1.0-dev\""));
      assertFalse(metadata.contains("${"), "Unexpanded metadata cannot be shipped");
      assertEquals(FactoryCoreMod.MOD_ID, FactoryCoreMod.class.getAnnotation(Mod.class).value());
    }
  }
}
