package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Golden current-schema world: {@code world-v13.dat} is a real Factory Core save written by the
 * GameTest server, with every section populated (networks, nodes, devices, transfers, external
 * claims, route cursors, buffers, machine presets, crafting jobs and keep rules). Decoding and
 * re-encoding it must reproduce the identical tag, so a codec refactor cannot change what is
 * written for the current schema. Regenerate only with a schema bump, alongside a migration.
 */
class WorldGoldenTest {
  private static HolderLookup.Provider registries;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  private static CompoundTag golden() throws Exception {
    try (var input = WorldGoldenTest.class.getResourceAsStream("/persistence/world-v13.dat")) {
      assertNotNull(input, "golden world on the test classpath");
      return NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()).getCompound("data");
    }
  }

  @Test
  void goldenWorldPopulatesEverySection() throws Exception {
    var data = golden();
    assertEquals(DeviceSavedData.SCHEMA, data.getInt("Schema"));
    for (var list : List.of("Networks", "Nodes", "Devices", "Transfers"))
      assertFalse(data.getList(list, 10).isEmpty(), list);
    assertFalse(data.getCompound("CraftingJobs").getList("Jobs", 10).isEmpty());
    assertFalse(data.getCompound("CraftingJobs").getList("Rules", 10).isEmpty());
    assertFalse(data.getCompound("ExternalClaims").getList("Claims", 10).isEmpty());
    assertFalse(data.getCompound("RouteCursors").getList("Cursors", 10).isEmpty());
    assertFalse(data.getCompound("Buffers").getList("Buffers", 10).isEmpty());
    assertFalse(data.getCompound("MachinePresets").getList("Deployments", 10).isEmpty());
  }

  @Test
  void currentSchemaRoundTripsExactly() throws Exception {
    var data = golden();
    var loaded = DeviceSavedData.load(data.copy(), registries);
    assertEquals("", loaded.diagnostic(), "golden world loads without quarantine");
    assertEquals(data, loaded.save(new CompoundTag(), registries));
  }
}
