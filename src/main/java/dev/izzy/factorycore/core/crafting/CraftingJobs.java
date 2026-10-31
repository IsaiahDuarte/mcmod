package dev.izzy.factorycore.core.crafting;

import dev.izzy.factorycore.core.resource.ResourceKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Canonical owner of every persisted crafting job, single-threaded. Mutations of a job obtained
 * from {@link #get} must be followed by {@link #changed} so the owning save is marked dirty.
 */
public final class CraftingJobs {
  public static final int MAX_JOBS = 64;
  public static final int MAX_PER_PRINCIPAL = 8;
  public static final int MAX_RULES = 64;
  public static final int MAX_RULES_PER_PRINCIPAL = 16;

  private final Map<UUID, CraftingJob> jobs = new LinkedHashMap<>();
  private final Map<UUID, KeepStockRule> rules = new LinkedHashMap<>();

  /** Q65: jobs whose requester asked not to be told when they finish. */
  private final java.util.Set<UUID> quiet = new java.util.LinkedHashSet<>();

  private final Runnable dirty;
  private Thread owner;

  public CraftingJobs(List<CraftingJob.Snapshot> saved, Runnable dirty) {
    this(saved, List.of(), dirty);
  }

  public CraftingJobs(
      List<CraftingJob.Snapshot> saved, List<KeepStockRule> savedRules, Runnable dirty) {
    this(saved, savedRules, List.of(), dirty);
  }

  public CraftingJobs(
      List<CraftingJob.Snapshot> saved,
      List<KeepStockRule> savedRules,
      List<UUID> savedQuiet,
      Runnable dirty) {
    this.dirty = Objects.requireNonNull(dirty);
    if (savedRules.size() > MAX_RULES) throw new IllegalArgumentException("Rule limit exceeded");
    var owners = new LinkedHashMap<UUID, Integer>();
    for (var rule : savedRules) {
      if (rules.putIfAbsent(rule.id(), rule) != null)
        throw new IllegalArgumentException("Duplicate rule");
      if (owners.merge(rule.principal(), 1, Integer::sum) > MAX_RULES_PER_PRINCIPAL)
        throw new IllegalArgumentException("Per-principal rule limit exceeded");
    }
    if (saved.size() > MAX_JOBS) throw new IllegalArgumentException("Job limit exceeded");
    var principals = new LinkedHashMap<UUID, Integer>();
    for (var snapshot : saved) {
      if (jobs.containsKey(snapshot.id())) throw new IllegalArgumentException("Duplicate job");
      if (principals.merge(snapshot.principal(), 1, Integer::sum) > MAX_PER_PRINCIPAL)
        throw new IllegalArgumentException("Per-principal job limit exceeded");
      jobs.put(snapshot.id(), new CraftingJob(snapshot));
    }
    for (var id : savedQuiet) {
      if (!jobs.containsKey(id)) throw new IllegalArgumentException("Quiet flag for unknown job");
      quiet.add(id);
    }
  }

  /** Q65: the job finishes without telling its requester. */
  public void quiet(UUID id) {
    checkThread();
    if (!jobs.containsKey(id)) throw new IllegalArgumentException("Unknown job");
    if (quiet.add(id)) dirty.run();
  }

  public boolean isQuiet(UUID id) {
    checkThread();
    return quiet.contains(id);
  }

  public List<UUID> quietJobs() {
    checkThread();
    return List.copyOf(quiet);
  }

  private void checkThread() {
    if (owner == null) owner = Thread.currentThread();
    else if (owner != Thread.currentThread())
      throw new IllegalStateException("Crafting jobs require their owner thread");
  }

  /** Why a new job cannot be admitted, or null when it can. */
  public JobStatus admission(UUID principal) {
    checkThread();
    if (jobs.size() >= MAX_JOBS) return JobStatus.JOB_LIMIT;
    long mine = jobs.values().stream().filter(job -> job.principal().equals(principal)).count();
    return mine >= MAX_PER_PRINCIPAL ? JobStatus.PRINCIPAL_LIMIT : null;
  }

  public void add(CraftingJob job) {
    checkThread();
    if (admission(job.principal()) != null) throw new IllegalStateException("Job not admitted");
    if (jobs.putIfAbsent(job.id(), job) != null)
      throw new IllegalArgumentException("Duplicate job");
    dirty.run();
  }

  public CraftingJob get(UUID id) {
    checkThread();
    return jobs.get(id);
  }

  public List<CraftingJob> all() {
    checkThread();
    return List.copyOf(jobs.values());
  }

  public void changed() {
    checkThread();
    dirty.run();
  }

  /** Removes a job only after it has neither staging nor reservations left. */
  public void retire(UUID id) {
    checkThread();
    var job = jobs.get(id);
    if (job == null || !job.finished()) throw new IllegalStateException("Job is not finished");
    jobs.remove(id);
    quiet.remove(id);
    dirty.run();
  }

  /**
   * Adds or replaces the principal's rule for the same network, segment and key. A replacement
   * keeps the existing rule's ID, so jobs it started still count as its own. Returns null on
   * success, else why the rule was refused.
   */
  public JobStatus setRule(KeepStockRule rule) {
    checkThread();
    var existing = ruleFor(rule.principal(), rule.network(), rule.segment(), rule.key());
    if (existing == null && rules.size() >= MAX_RULES) return JobStatus.RULE_LIMIT;
    if (existing == null
        && rules().stream().filter(r -> r.principal().equals(rule.principal())).count()
            >= MAX_RULES_PER_PRINCIPAL) return JobStatus.PRINCIPAL_RULE_LIMIT;
    var stored = existing == null ? rule : rule.withId(existing.id());
    rules.put(stored.id(), stored);
    dirty.run();
    return null;
  }

  public KeepStockRule ruleFor(UUID principal, UUID network, UUID segment, ResourceKey key) {
    checkThread();
    for (var rule : rules.values())
      if (rule.principal().equals(principal)
          && rule.network().equals(network)
          && rule.segment().equals(segment)
          && rule.key().equals(key)) return rule;
    return null;
  }

  public boolean removeRule(UUID id) {
    checkThread();
    if (rules.remove(id) == null) return false;
    dirty.run();
    return true;
  }

  public List<KeepStockRule> rules() {
    checkThread();
    return List.copyOf(rules.values());
  }

  public List<CraftingJob.Snapshot> snapshot() {
    checkThread();
    var result = new ArrayList<CraftingJob.Snapshot>(jobs.size());
    for (var job : jobs.values()) result.add(job.snapshot());
    return result;
  }
}
