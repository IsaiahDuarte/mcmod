package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.crafting.CraftingJobs;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Nested crafting-job schema inside world schema thirteen. Schema two adds the optional processing
 * {@code Dispatch}; schema one (no dispatch) still loads. Unknown fields reject.
 */
public final class CraftingJobCodec {
  static final long MAX_BYTES = 4L * 1024 * 1024;

  private CraftingJobCodec() {}

  private static CompoundTag key(ResourceKey key) {
    var tag = new CompoundTag();
    tag.putString("Kind", key.kind().name());
    tag.putString("Registry", key.registryId());
    tag.putByteArray("Components", key.components());
    return tag;
  }

  private static ResourceKey key(CompoundTag tag) {
    NetworkStateCodec.fields(tag, Set.of("Kind", "Registry", "Components"));
    if (!tag.contains("Kind", Tag.TAG_STRING)
        || !tag.contains("Registry", Tag.TAG_STRING)
        || !tag.contains("Components", Tag.TAG_BYTE_ARRAY))
      throw new IllegalArgumentException("Malformed job resource key");
    return new ResourceKey(
        ResourceKind.valueOf(tag.getString("Kind")),
        tag.getString("Registry"),
        tag.getByteArray("Components"));
  }

  private static ListTag amounts(Map<ResourceKey, Long> values) {
    var list = new ListTag();
    for (var entry : values.entrySet()) {
      var tag = key(entry.getKey());
      tag.putLong("Amount", entry.getValue());
      list.add(tag);
    }
    return list;
  }

  private static Map<ResourceKey, Long> amounts(ListTag list) {
    var values = new LinkedHashMap<ResourceKey, Long>();
    for (int index = 0; index < list.size(); index++) {
      var tag = list.getCompound(index).copy();
      if (!tag.contains("Amount", Tag.TAG_LONG))
        throw new IllegalArgumentException("Malformed job amount");
      long amount = tag.getLong("Amount");
      tag.remove("Amount");
      if (values.put(key(tag), amount) != null)
        throw new IllegalArgumentException("Duplicate job key");
    }
    return values;
  }

  private static ListTag list(CompoundTag tag, String field) {
    if (!tag.contains(field, Tag.TAG_LIST))
      throw new IllegalArgumentException("Missing job field " + field);
    var list = tag.getList(field, Tag.TAG_COMPOUND);
    if (list.size() != ((ListTag) tag.get(field)).size())
      throw new IllegalArgumentException("Wrong job list type " + field);
    return list;
  }

  public static CompoundTag encode(List<CraftingJob.Snapshot> jobs) {
    var root = new CompoundTag();
    root.putInt("Schema", 2);
    var list = new ListTag();
    for (var job : jobs) {
      var entry = new CompoundTag();
      entry.putUUID("Id", job.id());
      entry.putUUID("Principal", job.principal());
      entry.putUUID("Network", job.network());
      entry.putUUID("Segment", job.segment());
      entry.putString("Dimension", job.origin().dimension());
      entry.putLong("Position", job.origin().block());
      entry.put("Request", key(job.request()));
      entry.putLong("Amount", job.amount());
      var steps = new ListTag();
      for (var step : job.steps()) {
        var tag = new CompoundTag();
        tag.putUUID("Pattern", step.pattern());
        tag.putLong("Version", step.version());
        tag.put("Output", key(step.output()));
        tag.putLong("Crafts", step.crafts());
        tag.putLong("Done", step.done());
        steps.add(tag);
      }
      entry.put("Steps", steps);
      var reserved = new ListTag();
      for (var cell : job.reserved().entrySet()) {
        var tag = new CompoundTag();
        tag.putUUID("Backing", cell.getKey());
        tag.put("Keys", amounts(cell.getValue()));
        reserved.add(tag);
      }
      entry.put("Reserved", reserved);
      entry.put("Staged", amounts(job.staged()));
      entry.putString("State", job.state().name());
      entry.putString("Reason", job.reason());
      if (job.dispatch() != null) {
        var dispatch = new CompoundTag();
        dispatch.putString("Dimension", job.dispatch().machine().dimension());
        dispatch.putLong("Position", job.dispatch().machine().block());
        dispatch.put("Inserted", amounts(job.dispatch().inserted()));
        dispatch.put("Collected", amounts(job.dispatch().collected()));
        entry.put("Dispatch", dispatch);
      }
      list.add(entry);
    }
    root.put("Jobs", list);
    return root;
  }

  public static List<CraftingJob.Snapshot> decode(CompoundTag root) {
    NetworkStateCodec.fields(root, Set.of("Schema", "Jobs"));
    if (!root.contains("Schema", Tag.TAG_INT)
        || root.getInt("Schema") < 1
        || root.getInt("Schema") > 2)
      throw new IllegalArgumentException("Unsupported crafting job schema");
    int schema = root.getInt("Schema");
    var list = list(root, "Jobs");
    if (list.size() > CraftingJobs.MAX_JOBS)
      throw new IllegalArgumentException("Crafting job limit");
    long bytes = 0;
    var jobs = new ArrayList<CraftingJob.Snapshot>();
    for (int index = 0; index < list.size(); index++) {
      var entry = list.getCompound(index);
      bytes += entry.sizeInBytes();
      if (bytes > MAX_BYTES) throw new IllegalArgumentException("Crafting job byte limit");
      var fields =
          new java.util.HashSet<>(
              Set.of(
                  "Id",
                  "Principal",
                  "Network",
                  "Segment",
                  "Dimension",
                  "Position",
                  "Request",
                  "Amount",
                  "Steps",
                  "Reserved",
                  "Staged",
                  "State",
                  "Reason"));
      if (schema >= 2) fields.add("Dispatch");
      NetworkStateCodec.fields(entry, fields);
      CraftingJob.Dispatch dispatch = null;
      if (entry.contains("Dispatch")) {
        if (!entry.contains("Dispatch", Tag.TAG_COMPOUND))
          throw new IllegalArgumentException("Malformed dispatch");
        var saved = entry.getCompound("Dispatch");
        NetworkStateCodec.fields(saved, Set.of("Dimension", "Position", "Inserted", "Collected"));
        if (!saved.contains("Dimension", Tag.TAG_STRING)
            || !saved.contains("Position", Tag.TAG_LONG))
          throw new IllegalArgumentException("Malformed dispatch");
        dispatch =
            new CraftingJob.Dispatch(
                new NodeRegistry.Position(saved.getString("Dimension"), saved.getLong("Position")),
                amounts(list(saved, "Inserted")),
                amounts(list(saved, "Collected")));
      }
      for (var field : List.of("Id", "Principal", "Network", "Segment"))
        if (!entry.hasUUID(field)) throw new IllegalArgumentException("Missing job " + field);
      if (!entry.contains("Dimension", Tag.TAG_STRING)
          || !entry.contains("Position", Tag.TAG_LONG)
          || !entry.contains("Request", Tag.TAG_COMPOUND)
          || !entry.contains("Amount", Tag.TAG_LONG)
          || !entry.contains("State", Tag.TAG_STRING)
          || !entry.contains("Reason", Tag.TAG_STRING))
        throw new IllegalArgumentException("Malformed crafting job");
      var steps = new ArrayList<CraftingJob.Step>();
      var stepList = list(entry, "Steps");
      if (stepList.size() > CraftingJob.MAX_STEPS)
        throw new IllegalArgumentException("Job step limit");
      for (int s = 0; s < stepList.size(); s++) {
        var step = stepList.getCompound(s);
        NetworkStateCodec.fields(step, Set.of("Pattern", "Version", "Output", "Crafts", "Done"));
        if (!step.hasUUID("Pattern")
            || !step.contains("Version", Tag.TAG_LONG)
            || !step.contains("Output", Tag.TAG_COMPOUND)
            || !step.contains("Crafts", Tag.TAG_LONG)
            || !step.contains("Done", Tag.TAG_LONG))
          throw new IllegalArgumentException("Malformed job step");
        steps.add(
            new CraftingJob.Step(
                step.getUUID("Pattern"),
                step.getLong("Version"),
                key(step.getCompound("Output")),
                step.getLong("Crafts"),
                step.getLong("Done")));
      }
      var reserved = new LinkedHashMap<UUID, Map<ResourceKey, Long>>();
      var cells = list(entry, "Reserved");
      for (int c = 0; c < cells.size(); c++) {
        var cell = cells.getCompound(c);
        NetworkStateCodec.fields(cell, Set.of("Backing", "Keys"));
        if (!cell.hasUUID("Backing")) throw new IllegalArgumentException("Malformed reservation");
        if (reserved.put(cell.getUUID("Backing"), amounts(list(cell, "Keys"))) != null)
          throw new IllegalArgumentException("Duplicate reservation backing");
      }
      jobs.add(
          new CraftingJob.Snapshot(
              entry.getUUID("Id"),
              entry.getUUID("Principal"),
              entry.getUUID("Network"),
              entry.getUUID("Segment"),
              new NodeRegistry.Position(entry.getString("Dimension"), entry.getLong("Position")),
              key(entry.getCompound("Request")),
              entry.getLong("Amount"),
              steps,
              reserved,
              amounts(list(entry, "Staged")),
              CraftingJob.State.valueOf(entry.getString("State")),
              entry.getString("Reason"),
              dispatch));
    }
    return new CraftingJobs(jobs, () -> {}).snapshot();
  }
}
