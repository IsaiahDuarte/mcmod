package dev.izzy.factorycore.core.crafting;

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

  private final Map<UUID, CraftingJob> jobs = new LinkedHashMap<>();
  private final Runnable dirty;
  private Thread owner;

  public CraftingJobs(List<CraftingJob.Snapshot> saved, Runnable dirty) {
    this.dirty = Objects.requireNonNull(dirty);
    if (saved.size() > MAX_JOBS) throw new IllegalArgumentException("Job limit exceeded");
    var principals = new LinkedHashMap<UUID, Integer>();
    for (var snapshot : saved) {
      if (jobs.containsKey(snapshot.id())) throw new IllegalArgumentException("Duplicate job");
      if (principals.merge(snapshot.principal(), 1, Integer::sum) > MAX_PER_PRINCIPAL)
        throw new IllegalArgumentException("Per-principal job limit exceeded");
      jobs.put(snapshot.id(), new CraftingJob(snapshot));
    }
  }

  private void checkThread() {
    if (owner == null) owner = Thread.currentThread();
    else if (owner != Thread.currentThread())
      throw new IllegalStateException("Crafting jobs require their owner thread");
  }

  /** Why a new job cannot be admitted, or null when it can. */
  public String admission(UUID principal) {
    checkThread();
    if (jobs.size() >= MAX_JOBS) return "job_limit";
    long mine = jobs.values().stream().filter(job -> job.principal().equals(principal)).count();
    return mine >= MAX_PER_PRINCIPAL ? "principal_limit" : null;
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
    dirty.run();
  }

  public List<CraftingJob.Snapshot> snapshot() {
    checkThread();
    var result = new ArrayList<CraftingJob.Snapshot>(jobs.size());
    for (var job : jobs.values()) result.add(job.snapshot());
    return result;
  }
}
