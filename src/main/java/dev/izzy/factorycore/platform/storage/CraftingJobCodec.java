package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.crafting.CraftingJobs;
import dev.izzy.factorycore.core.crafting.KeepStockRule;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
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
 * {@code Dispatch}; schema three adds keep-stock {@code Rules} and an optional job {@code Rule}.
 * Schema four persists the exact physical target; schema five adds component/type provider
 * fingerprints. Older dispatches retain unknown target/provider identity without inference. Older
 * nested schemas still load. Unknown fields reject.
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
    Nbt.fields(tag, Set.of("Kind", "Registry", "Components"));
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

  /** Decoded nested table: jobs and keep-stock rules, already validated together. */
  public record Decoded(List<CraftingJob.Snapshot> jobs, List<KeepStockRule> rules) {}

  public static CompoundTag encode(List<CraftingJob.Snapshot> jobs) {
    return encode(jobs, List.of());
  }

  public static CompoundTag encode(List<CraftingJob.Snapshot> jobs, List<KeepStockRule> rules) {
    var root = new CompoundTag();
    root.putInt("Schema", 5);
    var ruleList = new ListTag();
    for (var rule : rules) {
      var entry = new CompoundTag();
      entry.putUUID("Id", rule.id());
      entry.putUUID("Principal", rule.principal());
      entry.putUUID("Network", rule.network());
      entry.putUUID("Segment", rule.segment());
      entry.putString("Dimension", rule.origin().dimension());
      entry.putLong("Position", rule.origin().block());
      entry.put("Key", key(rule.key()));
      entry.putLong("Low", rule.low());
      entry.putLong("High", rule.high());
      ruleList.add(entry);
    }
    root.put("Rules", ruleList);
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
        if (job.dispatch().target() != null)
          dispatch.putLong("Target", job.dispatch().target().block());
        var providers = new CompoundTag();
        job.dispatch()
            .providers()
            .forEach((kind, identity) -> providers.putUUID(kind.name(), identity));
        dispatch.put("Providers", providers);
        dispatch.put("Inserted", amounts(job.dispatch().inserted()));
        dispatch.put("Collected", amounts(job.dispatch().collected()));
        entry.put("Dispatch", dispatch);
      }
      if (job.rule() != null) entry.putUUID("Rule", job.rule());
      list.add(entry);
    }
    root.put("Jobs", list);
    return root;
  }

  public static List<CraftingJob.Snapshot> decode(CompoundTag root) {
    return decodeAll(root).jobs();
  }

  public static Decoded decodeAll(CompoundTag root) {
    if (!root.contains("Schema", Tag.TAG_INT)
        || root.getInt("Schema") < 1
        || root.getInt("Schema") > 5)
      throw new IllegalArgumentException("Unsupported crafting job schema");
    int schema = root.getInt("Schema");
    Nbt.fields(root, schema >= 3 ? Set.of("Schema", "Jobs", "Rules") : Set.of("Schema", "Jobs"));
    var rules = new ArrayList<KeepStockRule>();
    if (schema >= 3) {
      var ruleList = list(root, "Rules");
      if (ruleList.size() > CraftingJobs.MAX_RULES)
        throw new IllegalArgumentException("Rule limit");
      for (int index = 0; index < ruleList.size(); index++) {
        var entry = ruleList.getCompound(index);
        Nbt.fields(
            entry,
            Set.of(
                "Id",
                "Principal",
                "Network",
                "Segment",
                "Dimension",
                "Position",
                "Key",
                "Low",
                "High"));
        for (var field : List.of("Id", "Principal", "Network", "Segment"))
          if (!entry.hasUUID(field)) throw new IllegalArgumentException("Missing rule " + field);
        if (!entry.contains("Dimension", Tag.TAG_STRING)
            || !entry.contains("Position", Tag.TAG_LONG)
            || !entry.contains("Key", Tag.TAG_COMPOUND)
            || !entry.contains("Low", Tag.TAG_LONG)
            || !entry.contains("High", Tag.TAG_LONG))
          throw new IllegalArgumentException("Malformed keep-stock rule");
        rules.add(
            new KeepStockRule(
                entry.getUUID("Id"),
                entry.getUUID("Principal"),
                entry.getUUID("Network"),
                entry.getUUID("Segment"),
                new NodeRegistry.Position(entry.getString("Dimension"), entry.getLong("Position")),
                key(entry.getCompound("Key")),
                entry.getLong("Low"),
                entry.getLong("High")));
      }
    }
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
          new HashSet<>(
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
      if (schema >= 3) fields.add("Rule");
      if (entry.contains("Rule") && !entry.hasUUID("Rule"))
        throw new IllegalArgumentException("Malformed job rule");
      Nbt.fields(entry, fields);
      CraftingJob.Dispatch dispatch = null;
      if (entry.contains("Dispatch")) {
        if (!entry.contains("Dispatch", Tag.TAG_COMPOUND))
          throw new IllegalArgumentException("Malformed dispatch");
        var saved = entry.getCompound("Dispatch");
        Nbt.fields(
            saved,
            schema >= 5
                ? Set.of("Dimension", "Position", "Target", "Providers", "Inserted", "Collected")
                : schema >= 4
                    ? Set.of("Dimension", "Position", "Target", "Inserted", "Collected")
                    : Set.of("Dimension", "Position", "Inserted", "Collected"));
        if (saved.contains("Target") && !saved.contains("Target", Tag.TAG_LONG))
          throw new IllegalArgumentException("Malformed processing target");
        if (!saved.contains("Dimension", Tag.TAG_STRING)
            || !saved.contains("Position", Tag.TAG_LONG))
          throw new IllegalArgumentException("Malformed dispatch");
        var providerIds = new EnumMap<ResourceKind, UUID>(ResourceKind.class);
        if (schema >= 5) {
          if (!saved.contains("Providers", Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Malformed processing providers");
          var providers = saved.getCompound("Providers");
          for (var name : providers.getAllKeys()) {
            var kind = ResourceKind.valueOf(name);
            if (!providers.hasUUID(name))
              throw new IllegalArgumentException("Malformed processing provider identity");
            providerIds.put(kind, providers.getUUID(name));
          }
        }
        dispatch =
            new CraftingJob.Dispatch(
                new NodeRegistry.Position(saved.getString("Dimension"), saved.getLong("Position")),
                saved.contains("Target", Tag.TAG_LONG)
                    ? new NodeRegistry.Position(
                        saved.getString("Dimension"), saved.getLong("Target"))
                    : null,
                providerIds,
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
        Nbt.fields(step, Set.of("Pattern", "Version", "Output", "Crafts", "Done"));
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
        Nbt.fields(cell, Set.of("Backing", "Keys"));
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
              dispatch,
              entry.hasUUID("Rule") ? entry.getUUID("Rule") : null));
    }
    var validated = new CraftingJobs(jobs, rules, () -> {});
    return new Decoded(validated.snapshot(), validated.rules());
  }
}
