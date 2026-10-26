package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.crafting.JobReason;
import dev.izzy.factorycore.core.crafting.KeepStockRule;
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
import net.minecraft.nbt.ListTag;
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
    return world(reserve, recorded, Map.of());
  }

  private static CompoundTag world(
      boolean reserve, long recorded, Map<ResourceKey, Map<ResourceKey, Long>> uses) {
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
            List.of(new CraftingJob.Step(new UUID(0, 5), 2, STICK, 2, 1, uses)),
            Map.of(handle.backing(), Map.of(LOG, recorded)),
            Map.of(STICK, 4L),
            CraftingJob.State.WAITING,
            JobReason.OFFLINE);
    tag.put("CraftingJobs", CraftingJobCodec.encode(List.of(snapshot)));
    return tag;
  }

  private static CompoundTag emptyWorld() {
    var tag = new CompoundTag();
    tag.putInt("Schema", 1);
    tag.putUUID("World", new UUID(0, 77));
    tag.put("Devices", new ListTag());
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
    assertEquals(JobReason.OFFLINE, job.reason());
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

  /** Every job reason, including movement refusals such as rate_limit, is saved by its key. */
  @Test
  void everyJobReasonLoadsFromItsSavedKey() {
    for (var reason : JobReason.values()) {
      var tag = world(true, 3);
      tag.getCompound("CraftingJobs")
          .getList("Jobs", Tag.TAG_COMPOUND)
          .getCompound(0)
          .putString("Reason", reason.key());
      var loaded = DeviceSavedData.load(tag, registries);
      assertEquals("", loaded.diagnostic(), reason.key());
      assertEquals(reason, loaded.craftingJobs().all().getFirst().reason());
      assertEquals(tag, loaded.save(new CompoundTag(), registries), reason.key());
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
    for (int i = 0; i < 5; i++) {
      var tag = world(true, 3);
      var entry = tag.getCompound("CraftingJobs").getList("Jobs", Tag.TAG_COMPOUND).getCompound(0);
      switch (i) {
        // Schema 6 is now defined (E1 allocations); the first unknown one is 7.
        case 0 -> tag.getCompound("CraftingJobs").putInt("Schema", 7);
        case 1 -> entry.putString("State", "FUTURE");
        case 2 -> entry.putString("Unknown", "x");
        case 3 -> entry.getList("Steps", Tag.TAG_COMPOUND).getCompound(0).putLong("Done", 9);
        // M52: a reason no code writes is a malformed save, not a reason to show raw.
        case 4 -> entry.putString("Reason", "machine_on_fire");
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
        new KeepStockRule(
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

  @Test
  void exactTargetPersistsWhileLegacyDispatchesKeepUnknownTargetAndWrongTypesReject() {
    var loaded = DeviceSavedData.load(world(true, 3), registries);
    var job = loaded.craftingJobs().all().getFirst();
    job.resume();
    var machine = new NodeRegistry.Position("minecraft:overworld", 99);
    var target = new NodeRegistry.Position("minecraft:overworld", 100);
    job.dispatch(machine, target);
    job.inserted(STICK, 2);
    var saved = loaded.save(new CompoundTag(), registries);
    assertEquals(5, saved.getCompound("CraftingJobs").getInt("Schema"));
    assertEquals(
        target,
        DeviceSavedData.load(saved, registries)
            .craftingJobs()
            .all()
            .getFirst()
            .dispatch()
            .target());
    var legacy = saved.copy();
    legacy.getCompound("CraftingJobs").putInt("Schema", 3);
    legacy
        .getCompound("CraftingJobs")
        .getList("Jobs", Tag.TAG_COMPOUND)
        .getCompound(0)
        .getCompound("Dispatch")
        .remove("Target");
    legacy
        .getCompound("CraftingJobs")
        .getList("Jobs", Tag.TAG_COMPOUND)
        .getCompound(0)
        .getCompound("Dispatch")
        .remove("Providers");
    var migrated = DeviceSavedData.load(legacy, registries);
    assertEquals("", migrated.diagnostic());
    assertNull(migrated.craftingJobs().all().getFirst().dispatch().target());
    var rewritten = migrated.save(new CompoundTag(), registries);
    assertEquals(5, rewritten.getCompound("CraftingJobs").getInt("Schema"));
    assertNull(
        DeviceSavedData.load(rewritten, registries)
            .craftingJobs()
            .all()
            .getFirst()
            .dispatch()
            .target());
    var malformed = saved.copy();
    malformed
        .getCompound("CraftingJobs")
        .getList("Jobs", Tag.TAG_COMPOUND)
        .getCompound(0)
        .getCompound("Dispatch")
        .putString("Target", "100");
    var rejected = DeviceSavedData.load(malformed, registries);
    assertFalse(rejected.diagnostic().isEmpty());
    assertEquals(malformed, rejected.save(new CompoundTag(), registries));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CraftingJob.Dispatch(
                machine,
                new NodeRegistry.Position("minecraft:the_nether", 100),
                Map.of(),
                Map.of()));
  }

  @Test
  void providerIdentitiesRoundTripAndSchemaFourMigratesWithoutGuessing() {
    var loaded = DeviceSavedData.load(world(true, 3), registries);
    var job = loaded.craftingJobs().all().getFirst();
    job.resume();
    var providers =
        Map.of(
            ResourceKind.ITEM,
            UUID.randomUUID(),
            ResourceKind.FLUID,
            UUID.randomUUID(),
            ResourceKind.ENERGY,
            UUID.randomUUID());
    job.dispatch(
        new NodeRegistry.Position("minecraft:overworld", 99),
        new NodeRegistry.Position("minecraft:overworld", 100),
        providers);
    job.inserted(STICK, 2);
    var saved = loaded.save(new CompoundTag(), registries);
    assertEquals(
        providers,
        DeviceSavedData.load(saved, registries)
            .craftingJobs()
            .all()
            .getFirst()
            .dispatch()
            .providers());
    var legacy = saved.copy();
    legacy.getCompound("CraftingJobs").putInt("Schema", 4);
    legacy
        .getCompound("CraftingJobs")
        .getList("Jobs", Tag.TAG_COMPOUND)
        .getCompound(0)
        .getCompound("Dispatch")
        .remove("Providers");
    var migrated = DeviceSavedData.load(legacy, registries);
    assertEquals("", migrated.diagnostic());
    assertTrue(migrated.isDirty());
    assertEquals(
        job.dispatch().target(), migrated.craftingJobs().all().getFirst().dispatch().target());
    assertTrue(migrated.craftingJobs().all().getFirst().dispatch().providers().isEmpty());
    for (int fault = 0; fault < 3; fault++) {
      var malformed = saved.copy();
      var entry =
          malformed
              .getCompound("CraftingJobs")
              .getList("Jobs", Tag.TAG_COMPOUND)
              .getCompound(0)
              .getCompound("Dispatch")
              .getCompound("Providers");
      switch (fault) {
        case 0 -> entry.putString("ITEM", "not UUID");
        case 1 -> entry.remove("FLUID");
        case 2 -> entry.putUUID("FUTURE", UUID.randomUUID());
        default -> throw new AssertionError();
      }
      var rejected = DeviceSavedData.load(malformed, registries);
      assertFalse(rejected.diagnostic().isEmpty(), "Fault " + fault);
      assertEquals(malformed, rejected.save(new CompoundTag(), registries));
    }
  }

  @Test
  void substituteAllocationsRoundTripInSchemaSix() {
    var tag = world(true, 3, Map.of(LOG, Map.of(LOG, 2L)));
    assertEquals(6, tag.getCompound("CraftingJobs").getInt("Schema"), "Allocations need schema 6");
    var loaded = DeviceSavedData.load(tag, registries);
    assertEquals("", loaded.diagnostic());
    assertEquals(
        Map.of(LOG, Map.of(LOG, 2L)),
        loaded.craftingJobs().all().getFirst().steps().getFirst().uses());
  }
}
