package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.resource.ResourceKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Co-located processing dispatch journal (ADR 0057). Minecraft saves a block entity's contents and
 * its persistent data in one chunk record, so a journal written into the leased machine's own block
 * entity always describes exactly the dispatch progress that its saved contents reflect, even when
 * the canonical job table ({@code factorycore_devices.dat}) was saved at a different moment.
 *
 * <p>Schema 1: {@code Schema}, {@code World}, {@code Job}, {@code Progress} (digest of the job's
 * exact inserted/collected maps), {@code Inserted}/{@code Collected} (diagnostic unit totals) and
 * {@code Pending} (a real machine call was started and has not returned). Foreign contents never
 * enter this tag.
 */
public final class ProcessingJournal {
  public static final String FIELD = "factorycore:processing_journal";
  private static final Set<String> FIELDS =
      Set.of("Schema", "World", "Job", "Progress", "Inserted", "Collected", "Pending");

  /** Decoded journal; {@code null} from {@link #read} means the machine holds no journal. */
  public record Entry(
      UUID world, UUID job, UUID progress, long inserted, long collected, boolean pending) {
    public Entry {
      Objects.requireNonNull(world);
      Objects.requireNonNull(job);
      Objects.requireNonNull(progress);
      if (inserted < 0 || collected < 0)
        throw new IllegalArgumentException("Negative journal totals");
    }

    /** True when the recorded dispatch never moved a unit and no call is outstanding. */
    public boolean empty() {
      return !pending && inserted == 0 && collected == 0 && progress.equals(EMPTY);
    }
  }

  /** Digest of a dispatch with nothing inserted or collected. */
  public static final UUID EMPTY = digest(Map.of(), Map.of());

  private ProcessingJournal() {}

  /**
   * Reads the machine's journal, or null when it has none.
   *
   * @throws IllegalArgumentException for malformed, future or other-world data; callers treat it as
   *     unproven and never mutate the machine
   */
  public static Entry read(BlockEntity machine, UUID world) {
    var raw = machine.getPersistentData().get(FIELD);
    if (raw == null) return null;
    if (!(raw instanceof CompoundTag tag)
        || !FIELDS.equals(tag.getAllKeys())
        || !tag.contains("Schema", Tag.TAG_INT)
        || tag.getInt("Schema") != 1
        || !tag.hasUUID("World")
        || !tag.hasUUID("Job")
        || !tag.hasUUID("Progress")
        || !tag.contains("Inserted", Tag.TAG_LONG)
        || !tag.contains("Collected", Tag.TAG_LONG)
        || !tag.contains("Pending", Tag.TAG_BYTE))
      throw new IllegalArgumentException("Unsupported processing journal; preserve for recovery");
    if (!tag.getUUID("World").equals(world))
      throw new IllegalArgumentException("Processing journal belongs to another world");
    return new Entry(
        world,
        tag.getUUID("Job"),
        tag.getUUID("Progress"),
        tag.getLong("Inserted"),
        tag.getLong("Collected"),
        tag.getBoolean("Pending"));
  }

  /** Raw journal tag for audit evidence, or null. */
  public static Tag raw(BlockEntity machine) {
    var raw = machine.getPersistentData().get(FIELD);
    return raw == null ? null : raw.copy();
  }

  /** Records the job's current dispatch progress; {@code pending} precedes a real machine call. */
  public static void write(
      BlockEntity machine, UUID world, UUID job, CraftingJob.Dispatch dispatch, boolean pending) {
    var tag = new CompoundTag();
    tag.putInt("Schema", 1);
    tag.putUUID("World", world);
    tag.putUUID("Job", job);
    tag.putUUID("Progress", digest(dispatch.inserted(), dispatch.collected()));
    tag.putLong("Inserted", total(dispatch.inserted()));
    tag.putLong("Collected", total(dispatch.collected()));
    tag.putBoolean("Pending", pending);
    machine.getPersistentData().put(FIELD, tag);
    machine.setChanged();
  }

  public static void clear(BlockEntity machine) {
    if (machine.getPersistentData().contains(FIELD)) {
      machine.getPersistentData().remove(FIELD);
      machine.setChanged();
    }
  }

  /**
   * Compares the machine's journal with the job's dispatch. Returns null when they agree, else
   * {@code uncertain} (a started call never returned) or {@code save_mismatch}. A machine without a
   * journal agrees only with a dispatch that has moved nothing.
   */
  public static String check(Entry entry, UUID job, CraftingJob.Dispatch dispatch) {
    var expected = digest(dispatch.inserted(), dispatch.collected());
    if (entry == null) return expected.equals(EMPTY) ? null : "save_mismatch";
    if (!entry.job().equals(job)) return "save_mismatch";
    if (entry.pending()) return "uncertain";
    return entry.progress().equals(expected) ? null : "save_mismatch";
  }

  static long total(Map<ResourceKey, Long> amounts) {
    long sum = 0;
    for (long value : amounts.values()) sum = Math.addExact(sum, value);
    return sum;
  }

  /** Order-independent digest of exact inserted/collected maps. */
  public static UUID digest(Map<ResourceKey, Long> inserted, Map<ResourceKey, Long> collected) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        out.writeUTF("factorycore:processing-journal:v1");
        for (var map : List.of(inserted, collected)) {
          var sorted = new TreeMap<>(map);
          out.writeInt(sorted.size());
          for (var entry : sorted.entrySet()) {
            out.writeUTF(entry.getKey().kind().name());
            out.writeUTF(entry.getKey().registryId());
            var components = entry.getKey().components();
            out.writeInt(components.length);
            out.write(components);
            out.writeLong(entry.getValue());
          }
        }
      }
      return UUID.nameUUIDFromBytes(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
  }
}
