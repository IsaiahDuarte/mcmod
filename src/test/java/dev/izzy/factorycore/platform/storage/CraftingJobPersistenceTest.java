package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CraftingJobPersistenceTest {
  private static RegistryAccess registries;
  private static final ResourceKey LOG =
      new ResourceKey(ResourceKind.ITEM, "minecraft:oak_log", new byte[] {4});
  private static final ResourceKey STICK =
      new ResourceKey(ResourceKind.ITEM, "minecraft:stick", new byte[0]);

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  private static CompoundTag world(boolean reserve, long recorded) {
    var saved = DeviceSavedData.load(emptyWorld(), registries);
    var registry = saved.registry();
    var location = new DeviceRegistry.Location("minecraft:overworld", 42, 0);
    var portable = registry.create(ResourceKind.ITEM, 1000, false, 8, 8).handle();
    var handle = registry.attach(portable, location).handle();
    registry.availability(handle, location, true);
    registry.insert(handle, LOG, 10);
    var job = new UUID(0, 900);
    if (reserve) registry.reserve(handle, job, LOG, 3);
    var network = saved.createNetwork(new UUID(0, 1));
    var tag = saved.save(new CompoundTag(), registries);
    var snapshot =
        new CraftingJob.Snapshot(
            job,
            new UUID(0, 1),
            network,
            network,
            new NodeRegistry.Position("minecraft:overworld", 7),
            STICK,
            8,
            List.of(new CraftingJob.Step(new UUID(0, 5), 2, STICK, 2, 1)),
            Map.of(handle.backing(), Map.of(LOG, recorded)),
            Map.of(STICK, 4L),
            CraftingJob.State.WAITING,
            "offline");
    tag.put("CraftingJobs", CraftingJobCodec.encode(List.of(snapshot)));
    return tag;
  }

  private static CompoundTag emptyWorld() {
    var tag = new CompoundTag();
    tag.putInt("Schema", 1);
    tag.putUUID("World", new UUID(0, 77));
    tag.put("Devices", new net.minecraft.nbt.ListTag());
    return tag;
  }

  /** A current-schema world with no jobs, produced by the real writer. */
  private static CompoundTag freshTag() {
    return DeviceSavedData.load(emptyWorld(), registries).save(new CompoundTag(), registries);
  }

  @Test
  void jobAndItsLedgerReservationRoundTripTogether() {
    var tag = world(true, 3);
    var loaded = DeviceSavedData.load(tag, registries);
    assertEquals("", loaded.diagnostic());
    var job = loaded.craftingJobs().all().getFirst();
    assertEquals(CraftingJob.State.WAITING, job.state());
    assertEquals("offline", job.reason());
    assertEquals(3, job.reservedAmount(LOG));
    assertEquals(4, job.stagedAmount(STICK));
    assertEquals(tag, loaded.save(new CompoundTag(), registries));
  }

  @Test
  void jobWhoseReservationDisagreesWithTheLedgerQuarantinesTheWholeWorld() {
    for (var tag : List.of(world(false, 3), world(true, 2))) {
      var rejected = DeviceSavedData.load(tag, registries);
      assertFalse(rejected.diagnostic().isEmpty());
      assertThrows(IllegalStateException.class, rejected::craftingJobs);
      assertEquals(tag, rejected.save(new CompoundTag(), registries));
    }
  }

  @Test
  void schemaTwelveMigratesToAnEmptyJobsTableAndMalformedJobsReject() {
    var current = freshTag();
    var legacy = current.copy();
    legacy.remove("CraftingJobs");
    legacy.putInt("Schema", 12);
    var migrated = DeviceSavedData.load(legacy, registries);
    assertEquals("", migrated.diagnostic());
    assertTrue(migrated.isDirty());
    assertTrue(migrated.craftingJobs().all().isEmpty());
    var saved = migrated.save(new CompoundTag(), registries);
    assertEquals(DeviceSavedData.SCHEMA, saved.getInt("Schema"));
    assertTrue(saved.getCompound("CraftingJobs").getList("Jobs", Tag.TAG_COMPOUND).isEmpty());
    var twelveWithJobs = current.copy();
    twelveWithJobs.putInt("Schema", 12);
    assertFalse(DeviceSavedData.load(twelveWithJobs, registries).diagnostic().isEmpty());
    for (int i = 0; i < 4; i++) {
      var tag = world(true, 3);
      var entry = tag.getCompound("CraftingJobs").getList("Jobs", Tag.TAG_COMPOUND).getCompound(0);
      switch (i) {
        case 0 -> tag.getCompound("CraftingJobs").putInt("Schema", 4);
        case 1 -> entry.putString("State", "FUTURE");
        case 2 -> entry.putString("Unknown", "x");
        case 3 -> entry.getList("Steps", Tag.TAG_COMPOUND).getCompound(0).putLong("Done", 9);
        default -> throw new IllegalStateException();
      }
      assertFalse(DeviceSavedData.load(tag, registries).diagnostic().isEmpty(), "Case " + i);
    }
  }

  @Test
  void dispatchRoundTripsAndNestedSchemaOneStillLoads() {
    var tag = world(true, 3);
    var loaded = DeviceSavedData.load(tag, registries);
    var job = loaded.craftingJobs().all().getFirst();
    job.resume();
    job.dispatch(new NodeRegistry.Position("minecraft:overworld", 99));
    job.inserted(STICK, 2);
    var saved = loaded.save(new CompoundTag(), registries);
    var dispatch =
        DeviceSavedData.load(saved, registries).craftingJobs().all().getFirst().dispatch();
    assertEquals(new NodeRegistry.Position("minecraft:overworld", 99), dispatch.machine());
    assertEquals(Map.of(STICK, 2L), dispatch.inserted());
    var legacy = world(true, 3);
    legacy.getCompound("CraftingJobs").putInt("Schema", 1);
    legacy.getCompound("CraftingJobs").remove("Rules"); // Introduced by nested schema three.
    assertEquals(
        "", DeviceSavedData.load(legacy, registries).diagnostic(), "Nested schema one loads");
    var withDispatch = saved.copy();
    withDispatch.getCompound("CraftingJobs").putInt("Schema", 1);
    assertFalse(
        DeviceSavedData.load(withDispatch, registries).diagnostic().isEmpty(),
        "Schema one cannot carry a dispatch");
  }

  @Test
  void keepStockRulesAndJobRuleLinksRoundTripAndNeedNestedSchemaThree() {
    var tag = world(true, 3);
    var loaded = DeviceSavedData.load(tag, registries);
    var network = loaded.craftingJobs().all().getFirst().network();
    var rule =
        new dev.izzy.factorycore.core.crafting.KeepStockRule(
            new UUID(0, 333),
            new UUID(0, 1),
            network,
            network,
            new NodeRegistry.Position("minecraft:overworld", 7),
            STICK,
            16,
            64);
    assertNull(loaded.craftingJobs().setRule(rule));
    var saved = loaded.save(new CompoundTag(), registries);
    var reloaded = DeviceSavedData.load(saved, registries);
    assertEquals(List.of(rule), reloaded.craftingJobs().rules());
    var legacy = saved.copy();
    legacy.getCompound("CraftingJobs").putInt("Schema", 2);
    assertFalse(
        DeviceSavedData.load(legacy, registries).diagnostic().isEmpty(),
        "Schema two cannot carry rules");
    var orphan = saved.copy();
    orphan
        .getCompound("CraftingJobs")
        .getList("Rules", Tag.TAG_COMPOUND)
        .getCompound(0)
        .putUUID("Network", new UUID(9, 9));
    assertFalse(
        DeviceSavedData.load(orphan, registries).diagnostic().isEmpty(),
        "A rule for an unknown network quarantines the world");
  }
}
