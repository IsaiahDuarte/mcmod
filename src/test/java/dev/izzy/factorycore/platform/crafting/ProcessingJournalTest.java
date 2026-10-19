package dev.izzy.factorycore.platform.crafting;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProcessingJournalTest {
  private static final ResourceKey ORE =
      new ResourceKey(ResourceKind.ITEM, "minecraft:raw_iron", new byte[0]);
  private static final ResourceKey INGOT =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);
  private static final ResourceKey WATER =
      new ResourceKey(ResourceKind.FLUID, "minecraft:water", new byte[] {1});
  private static final NodeRegistry.Position FACE =
      new NodeRegistry.Position("minecraft:overworld", BlockPos.asLong(1, 2, 3));
  private static final NodeRegistry.Position TARGET =
      new NodeRegistry.Position("minecraft:overworld", BlockPos.asLong(2, 2, 3));
  private static final UUID WORLD = UUID.fromString("0f6d7d5e-2c33-4c62-9d84-6f0e5f7c4a11");

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  private static CraftingJob.Dispatch dispatch(
      Map<ResourceKey, Long> inserted, Map<ResourceKey, Long> collected) {
    return new CraftingJob.Dispatch(FACE, TARGET, Map.of(), inserted, collected);
  }

  private static FurnaceBlockEntity machine() {
    return new FurnaceBlockEntity(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
  }

  @Test
  void digestIsExactAndIndependentOfInsertionOrder() {
    var forward = new LinkedHashMap<ResourceKey, Long>();
    forward.put(ORE, 2L);
    forward.put(WATER, 250L);
    var backward = new LinkedHashMap<ResourceKey, Long>();
    backward.put(WATER, 250L);
    backward.put(ORE, 2L);
    assertEquals(
        ProcessingJournal.digest(forward, Map.of()), ProcessingJournal.digest(backward, Map.of()));
    assertNotEquals(
        ProcessingJournal.digest(Map.of(ORE, 2L), Map.of()),
        ProcessingJournal.digest(Map.of(ORE, 1L), Map.of()));
    assertNotEquals(
        ProcessingJournal.digest(Map.of(ORE, 1L), Map.of()),
        ProcessingJournal.digest(Map.of(), Map.of(ORE, 1L)),
        "Inserted and collected units are distinct facts");
    assertNotEquals(
        ProcessingJournal.digest(Map.of(WATER, 1L), Map.of()),
        ProcessingJournal.digest(
            Map.of(new ResourceKey(ResourceKind.FLUID, "minecraft:water", new byte[0]), 1L),
            Map.of()),
        "Component bytes are part of the identity");
    assertEquals(ProcessingJournal.EMPTY, ProcessingJournal.digest(Map.of(), Map.of()));
  }

  @Test
  void journalRoundTripsInTheMachinesOwnSavedRecord() {
    var furnace = machine();
    var job = UUID.randomUUID();
    var progress = dispatch(Map.of(ORE, 2L), Map.of(INGOT, 1L));
    assertNull(ProcessingJournal.read(furnace, WORLD));
    ProcessingJournal.write(furnace, WORLD, job, progress, true);
    var saved = furnace.saveWithoutMetadata(net.minecraft.core.RegistryAccess.EMPTY);
    var reloaded = machine();
    reloaded.loadWithComponents(saved, net.minecraft.core.RegistryAccess.EMPTY);
    var entry = ProcessingJournal.read(reloaded, WORLD);
    assertEquals(job, entry.job());
    assertTrue(entry.pending());
    assertEquals(2, entry.inserted());
    assertEquals(1, entry.collected());
    assertEquals("uncertain", ProcessingJournal.check(entry, job, progress));
    ProcessingJournal.write(reloaded, WORLD, job, progress, false);
    assertNull(
        ProcessingJournal.check(ProcessingJournal.read(reloaded, WORLD), job, progress),
        "A settled journal proves exactly this progress");
    ProcessingJournal.clear(reloaded);
    assertNull(ProcessingJournal.read(reloaded, WORLD));
  }

  @Test
  void anyDisagreementIsAMismatchAndAbsenceProvesOnlyAnUnmovedDispatch() {
    var job = UUID.randomUUID();
    var empty = dispatch(Map.of(), Map.of());
    var inserted = dispatch(Map.of(ORE, 2L), Map.of());
    assertNull(ProcessingJournal.check(null, job, empty));
    assertEquals("save_mismatch", ProcessingJournal.check(null, job, inserted));
    var furnace = machine();
    ProcessingJournal.write(furnace, WORLD, job, empty, false);
    var older = ProcessingJournal.read(furnace, WORLD);
    assertTrue(older.empty());
    assertEquals(
        "save_mismatch",
        ProcessingJournal.check(older, job, inserted),
        "Job table newer than the machine");
    ProcessingJournal.write(furnace, WORLD, job, inserted, false);
    var newer = ProcessingJournal.read(furnace, WORLD);
    assertFalse(newer.empty());
    assertEquals(
        "save_mismatch",
        ProcessingJournal.check(newer, job, empty),
        "Machine newer than the job table");
    assertEquals(
        "save_mismatch",
        ProcessingJournal.check(newer, UUID.randomUUID(), inserted),
        "Another job's journal never proves this job");
  }

  @Test
  void malformedFutureOrForeignJournalsAreRejectedUnchanged() {
    var furnace = machine();
    ProcessingJournal.write(furnace, WORLD, UUID.randomUUID(), dispatch(Map.of(), Map.of()), false);
    assertThrows(
        IllegalArgumentException.class, () -> ProcessingJournal.read(furnace, UUID.randomUUID()));
    var tag = (CompoundTag) furnace.getPersistentData().get(ProcessingJournal.FIELD);
    tag.putInt("Schema", 2);
    assertThrows(IllegalArgumentException.class, () -> ProcessingJournal.read(furnace, WORLD));
    tag.putInt("Schema", 1);
    tag.putString("Extra", "x");
    assertThrows(IllegalArgumentException.class, () -> ProcessingJournal.read(furnace, WORLD));
    assertEquals(
        "x",
        ((CompoundTag) ProcessingJournal.raw(furnace)).getString("Extra"),
        "Rejected data is preserved for evidence");
  }
}
