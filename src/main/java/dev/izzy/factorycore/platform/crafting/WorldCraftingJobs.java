package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.crafting.CraftingPlanner;
import dev.izzy.factorycore.core.crafting.JobReason;
import dev.izzy.factorycore.core.crafting.JobStatus;
import dev.izzy.factorycore.core.crafting.KeepStockRule;
import dev.izzy.factorycore.core.crafting.Pattern;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.WirelessBudget;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.ServerScoped;
import dev.izzy.factorycore.platform.ServerThread;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Server-thread autocrafting runtime over canonical {@code CraftingJobs}. Submission plans against
 * the requester's CRAFT view of digital cells and reserves exactly the plan's consumed stock with
 * the job as owner. Each runnable job queues at most one shared-scheduler task at a time; a task
 * performs one craft or one delivery pass. Work never voids resources: units leave storage only
 * into job staging, and staging returns only by accepted insertion.
 */
public final class WorldCraftingJobs {
  public static final int TASK_COST = 16;
  public static final int RETRY_TICKS = 20;
  public static final int MAX_NOTICES = 8;
  private static final ServerScoped<WorldCraftingJobs> SERVERS =
      ServerScoped.of(WorldCraftingJobs::new);

  /** Advisory preview; {@code reason} is empty when the plan could be submitted now. */
  public record Preview(CraftingPlanner.Plan plan, JobStatus reason) {}

  /** Submission outcome; {@code job} is null when {@code reason} explains a refusal. */
  public record Submission(UUID job, CraftingPlanner.Plan plan, JobStatus reason) {}

  /** Recently finished job for a requester's display; not persisted. */
  public record Notice(UUID job, ResourceKey request, long amount, boolean cancelled) {}

  private record Cell(DeviceRegistry.Handle handle, long available) {}

  private final MinecraftServer server;
  private final MachineIo io;
  private final Map<UUID, Long> retryAt = new HashMap<>();
  private final Map<UUID, RecipeHolder<CraftingRecipe>> recipes = new HashMap<>();
  private final Map<UUID, ArrayDeque<Notice>> notices = new HashMap<>();
  private final Map<UUID, Integer> cleanups = new HashMap<>();
  private final Map<UUID, JobStatus> ruleStatus = new HashMap<>();
  private final Map<UUID, Long> ruleDue = new HashMap<>();
  public static final int RULE_TICKS = 100;
  public static final long MAX_RULE_REQUEST = 4_096;
  public static final int MAX_CLEANUP_PASSES = 60;

  private WorldCraftingJobs(MinecraftServer server) {
    this.server = server;
    io = new MachineIo(server, job -> anchor(job, null));
  }

  public static WorldCraftingJobs get(MinecraftServer server) {
    ServerThread.require(server, "Crafting");
    return SERVERS.get(server);
  }

  public static void postTick(ServerTickEvent.Post event) {
    // Quarantined storage keeps every job record untouched; storage reports the diagnostic.
    if (DeviceSavedData.get(event.getServer()).diagnostic().isEmpty())
      get(event.getServer()).tick();
  }

  private DeviceSavedData saved() {
    return DeviceSavedData.get(server);
  }

  private WorldNetworks networks() {
    return WorldNetworks.get(server);
  }

  private static NetworkPermissions.Actor actor(UUID principal) {
    return new NetworkPermissions.Actor(principal, false);
  }

  /** Digital cells in the CRAFT view of {@code origin} with available units, in backing order. */
  private List<Cell> cells(
      NetworkPermissions.Actor actor, NodeRegistry.Position origin, ResourceKey key) {
    var result = new ArrayList<Cell>();
    var registry = saved().registry();
    var transfers = networks().transfers();
    UUID after = null;
    do {
      var page = registry.handles(after, 16);
      for (var handle : page.handles()) {
        if (!transfers.digitalAllowed(
            actor, origin, handle, key, NetworkPermissions.Permission.CRAFT)) continue;
        long available = registry.stock(handle, key).available();
        result.add(new Cell(handle, available));
      }
      after = page.next();
    } while (after != null);
    return result;
  }

  private JobStatus denial(NetworkPermissions.Actor actor, NodeRegistry.Position origin) {
    var member = networks().membership(origin);
    if (member.status() != NetworkTopology.Status.VALID) return JobStatus.OFFLINE;
    return networks().allows(actor, origin, NetworkPermissions.Permission.CRAFT)
        ? null
        : JobStatus.DENIED;
  }

  private CraftingPlanner.Plan plan(
      NetworkPermissions.Actor actor, NodeRegistry.Position origin, ResourceKey key, long amount) {
    var scope = networks().membership(origin).scope();
    var index = WorldCrafting.get(server).index(scope);
    var stock = new HashMap<ResourceKey, Long>();
    return new CraftingPlanner(index::producers)
        .plan(
            key,
            amount,
            resource ->
                stock.computeIfAbsent(
                    resource,
                    k -> {
                      long total = 0;
                      for (var cell : cells(actor, origin, k))
                        total = Math.addExact(total, cell.available());
                      return total;
                    }));
  }

  public Preview preview(
      NetworkPermissions.Actor actor, NodeRegistry.Position origin, ResourceKey key, long amount) {
    checkThread();
    var denied = denial(actor, origin);
    if (denied != null) return new Preview(null, denied);
    if (key.kind() == ResourceKind.ENERGY) return new Preview(null, JobStatus.UNSUPPORTED);
    var plan = plan(actor, origin, key, amount);
    return new Preview(plan, reason(plan));
  }

  private static JobStatus reason(CraftingPlanner.Plan plan) {
    return switch (plan.status()) {
      case OK -> null;
      case MISSING -> plan.cycles().isEmpty() ? JobStatus.MISSING : JobStatus.CYCLE;
      case LIMIT -> JobStatus.LIMIT;
    };
  }

  /** Plans again now and reserves the plan's consumed stock exactly once, or reserves nothing. */
  public Submission submit(
      NetworkPermissions.Actor actor, NodeRegistry.Position origin, ResourceKey key, long amount) {
    return submit(actor, origin, key, amount, null);
  }

  private Submission submit(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      ResourceKey key,
      long amount,
      UUID rule) {
    checkThread();
    var denied = denial(actor, origin);
    if (denied != null) return new Submission(null, null, denied);
    if (key.kind() == ResourceKind.ENERGY) return new Submission(null, null, JobStatus.UNSUPPORTED);
    var jobs = saved().craftingJobs();
    var admission = jobs.admission(actor.id());
    if (admission != null) return new Submission(null, null, admission);
    var plan = plan(actor, origin, key, amount);
    if (plan.status() != CraftingPlanner.Status.OK) return new Submission(null, plan, reason(plan));
    var id = UUID.randomUUID();
    var registry = saved().registry();
    var reserved = new LinkedHashMap<UUID, Map<ResourceKey, Long>>();
    boolean complete = true;
    for (var entry : plan.consumed().entrySet()) {
      long need = entry.getValue();
      for (var cell : cells(actor, origin, entry.getKey())) {
        if (need == 0) break;
        long take = Math.min(need, cell.available());
        if (take == 0) continue;
        var result = registry.reserve(cell.handle(), id, entry.getKey(), take);
        if (result.amount() > 0) {
          reserved
              .computeIfAbsent(cell.handle().backing(), b -> new LinkedHashMap<>())
              .merge(entry.getKey(), result.amount(), Math::addExact);
          need -= result.amount();
        }
      }
      if (need > 0) {
        complete = false;
        break;
      }
    }
    var scope = networks().membership(origin).scope();
    if (!complete
        || reserved.values().stream().mapToInt(Map::size).sum() > CraftingJob.MAX_RESERVATIONS) {
      for (var cell : reserved.entrySet())
        for (var resource : cell.getValue().keySet()) release(cell.getKey(), id, resource);
      return new Submission(null, plan, complete ? JobStatus.LIMIT : JobStatus.CHANGED);
    }
    var job =
        CraftingJob.accepted(
            id, actor.id(), scope.network(), scope.segment(), origin, plan, reserved, rule);
    jobs.add(job);
    return new Submission(id, plan, null);
  }

  /**
   * Submits a job carrying a persistent request link (a program's rule/item command). The link is
   * saved with the job, so a restarted server or reloaded controller never submits a second job for
   * the same link while the first exists; such a request returns reason {@code in_flight}.
   */
  public Submission submitLinked(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      ResourceKey key,
      long amount,
      UUID link) {
    checkThread();
    Objects.requireNonNull(link);
    if (linked(link)) return new Submission(null, null, JobStatus.IN_FLIGHT);
    return submit(actor, origin, key, amount, link);
  }

  /** True while a saved job carries {@code link} as its rule or program request link. */
  public boolean linked(UUID link) {
    checkThread();
    for (var job : saved().craftingJobs().all()) if (link.equals(job.rule())) return true;
    return false;
  }

  private void release(UUID backing, UUID owner, ResourceKey key) {
    var registry = saved().registry();
    var ownership = registry.ownership(backing);
    if (ownership == null) return;
    registry.release(ownership.handle(), owner, key);
  }

  /** The requester or an operator may cancel; staged units are then delivered. */
  public boolean cancel(NetworkPermissions.Actor actor, UUID id) {
    checkThread();
    var job = saved().craftingJobs().get(id);
    if (job == null || (!job.principal().equals(actor.id()) && !actor.operator())) return false;
    var before = job.state();
    job.cancel();
    if (job.state() == before) return false;
    retryAt.remove(id);
    saved().craftingJobs().changed();
    return true;
  }

  /**
   * Creates or replaces the requester's keep-stock rule for {@code key} in the origin's segment.
   * Returns an empty reason on success. Rules act later under the principal's current grants.
   */
  public JobStatus keep(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      ResourceKey key,
      long low,
      long high) {
    checkThread();
    var denied = denial(actor, origin);
    if (denied != null) return denied;
    if (key.kind() == ResourceKind.ENERGY) return JobStatus.UNSUPPORTED;
    var scope = networks().membership(origin).scope();
    try {
      var refused =
          saved()
              .craftingJobs()
              .setRule(
                  new KeepStockRule(
                      UUID.randomUUID(),
                      actor.id(),
                      scope.network(),
                      scope.segment(),
                      origin,
                      key,
                      low,
                      high));
      return refused;
    } catch (IllegalArgumentException invalid) {
      return JobStatus.LIMIT;
    }
  }

  /** The requester's rule for {@code key} in the origin's segment, or null. */
  public KeepStockRule rule(
      NetworkPermissions.Actor actor, NodeRegistry.Position origin, ResourceKey key) {
    checkThread();
    var member = networks().membership(origin);
    if (member.status() != NetworkTopology.Status.VALID) return null;
    return saved()
        .craftingJobs()
        .ruleFor(actor.id(), member.scope().network(), member.scope().segment(), key);
  }

  /** Removes the requester's (or, for an operator, any) rule; its in-flight job continues. */
  public boolean unkeep(NetworkPermissions.Actor actor, UUID rule) {
    checkThread();
    var jobs = saved().craftingJobs();
    var existing = jobs.rules().stream().filter(r -> r.id().equals(rule)).findFirst().orElse(null);
    if (existing == null || (!existing.principal().equals(actor.id()) && !actor.operator()))
      return false;
    ruleStatus.remove(rule);
    ruleDue.remove(rule);
    return jobs.removeRule(rule);
  }

  /** Runtime entries kept for rules (status and due tick), for leak checks; bounded by rules. */
  public int ruleStateEntries() {
    checkThread();
    return Math.max(ruleStatus.size(), ruleDue.size());
  }

  /** Last evaluation outcome for a rule, or null before its first evaluation. */
  public JobStatus ruleStatus(UUID rule) {
    checkThread();
    return ruleStatus.get(rule);
  }

  /**
   * Evaluates one rule: start a job for its deficit unless a job for its target is in flight. The
   * target match also covers a job left by an earlier rule that was removed and set again.
   */
  void evaluate(UUID id) {
    checkThread();
    var jobs = saved().craftingJobs();
    var rule = jobs.rules().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    if (rule == null) return;
    for (var job : jobs.all())
      if (id.equals(job.rule()) || rule.targets(job)) {
        ruleStatus.put(id, JobStatus.IN_FLIGHT);
        return;
      }
    var actor = actor(rule.principal());
    var denied = denial(actor, rule.origin());
    if (denied != null) {
      ruleStatus.put(id, denied);
      return;
    }
    long available = 0;
    for (var cell : cells(actor, rule.origin(), rule.key()))
      available = Math.addExact(available, cell.available());
    long deficit = Math.min(rule.deficit(available), MAX_RULE_REQUEST);
    if (deficit == 0) {
      ruleStatus.put(id, JobStatus.SATISFIED);
      return;
    }
    var submission = submit(actor, rule.origin(), rule.key(), deficit, id);
    ruleStatus.put(id, submission.job() != null ? JobStatus.STARTED : submission.reason());
  }

  public List<CraftingJob> jobs(UUID principal) {
    checkThread();
    return saved().craftingJobs().all().stream()
        .filter(job -> job.principal().equals(principal))
        .toList();
  }

  public List<Notice> notices(UUID principal) {
    checkThread();
    var list = notices.get(principal);
    return list == null ? List.of() : List.copyOf(list);
  }

  /** Records a finished job and tells its requester, if online, that it finished or cancelled. */
  private void notice(CraftingJob job, boolean cancelled) {
    var list = notices.computeIfAbsent(job.principal(), p -> new ArrayDeque<>());
    if (list.size() == MAX_NOTICES) list.removeFirst();
    list.addLast(new Notice(job.id(), job.request(), job.amount(), cancelled));
    var player = server.getPlayerList().getPlayer(job.principal());
    if (player == null) return;
    // A5: clients that know the payload show a toast over open screens, chat otherwise.
    if (player.connection.hasChannel(JobNotice.TYPE)) {
      net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
          player, new JobNotice(job.request().registryId(), job.amount(), cancelled));
      return;
    }
    Component name;
    try {
      name =
          new PlatformResourceCodec(server.registryAccess())
              .itemStack(job.request(), 1)
              .getHoverName();
    } catch (IllegalArgumentException notAnItem) {
      name = Component.literal(job.request().registryId());
    }
    player.displayClientMessage(
        Component.translatable(
            cancelled ? "message.factorycore.job.cancelled" : "message.factorycore.job.finished",
            job.amount(),
            name),
        false);
  }

  /** Queues one shared-scheduler task per runnable job that is not already queued. */
  public void tick() {
    checkThread();
    long now = server.getTickCount();
    var scheduler = networks().scheduler();
    for (var job : saved().craftingJobs().all()) {
      var key = "craft:" + job.id();
      if (scheduler.isQueued(job.network(), job.principal(), key)) continue;
      if (retryAt.getOrDefault(job.id(), Long.MIN_VALUE) > now) continue;
      var id = job.id();
      // E2: a run may make as many crafts as the Crafter's batch it last saw, and pays for them.
      int paid = batches.getOrDefault(id, 1);
      var admission =
          scheduler.submit(
              job.network(), job.principal(), key, TASK_COST * paid, () -> run(id, paid));
      if (admission != FairScheduler.Admission.ACCEPTED
          && admission != FairScheduler.Admission.REPLACED
          && paid > 1) batches.put(id, 1);
    }
    var rules = saved().craftingJobs().rules();
    // M51: runtime entries follow the live rules even if one is removed outside unkeep.
    if (ruleStatus.size() > rules.size() || ruleDue.size() > rules.size()) {
      var live = new HashSet<UUID>();
      for (var rule : rules) live.add(rule.id());
      ruleStatus.keySet().retainAll(live);
      ruleDue.keySet().retainAll(live);
    }
    for (var rule : rules) {
      var key = "keep:" + rule.id();
      if (ruleDue.getOrDefault(rule.id(), Long.MIN_VALUE) > now
          || scheduler.isQueued(rule.network(), rule.principal(), key)) continue;
      ruleDue.put(rule.id(), now + RULE_TICKS);
      var id = rule.id();
      scheduler.submit(rule.network(), rule.principal(), key, TASK_COST, () -> evaluate(id));
    }
  }

  /** Last Crafter batch seen per job (runtime only); the next submission pays for it. */
  private final Map<UUID, Integer> batches = new HashMap<>();

  /** Crafts each job's latest run made (runtime diagnostics for E2 measurements). */
  private final Map<UUID, Integer> lastRun = new HashMap<>();

  /** Scheduled runs each job has made so far (runtime diagnostics for E3 measurements). */
  private final Map<UUID, Integer> runs = new HashMap<>();

  /** How many scheduled runs {@code job} has made, or 0 before its first. */
  public int runs(UUID job) {
    checkThread();
    return runs.getOrDefault(job, 0);
  }

  /** How many crafts {@code job}'s latest scheduled run made, or 0 before its first. */
  public int lastRunCrafts(UUID job) {
    checkThread();
    return lastRun.getOrDefault(job, 0);
  }

  void run(UUID id) {
    run(id, 1);
  }

  /**
   * Runs up to {@code paid} crafts for a job; reason changes keep it waiting until the next retry.
   */
  void run(UUID id, int paid) {
    checkThread();
    var jobs = saved().craftingJobs();
    var job = jobs.get(id);
    if (job == null) return;
    boolean cancelling = job.state() == CraftingJob.State.CANCELLING;
    if (cancelling && job.dispatch() != null && !cleanup(job)) {
      // An unverifiable or disagreeing machine journal freezes every job resource (ADR 0057).
      jobs.changed();
      return;
    }
    if (job.state() == CraftingJob.State.DELIVERING || cancelling) {
      deliver(job);
      if (job.finished()) {
        jobs.retire(id);
        retryAt.remove(id);
        batches.remove(id);
        lastRun.remove(id);
        runs.remove(id);
        recipes.remove(id);
        notice(job, cancelling);
      }
      jobs.changed();
      return;
    }
    job.resume();
    int batch = batch(job);
    batches.put(id, batch);
    long before = job.craftsDone();
    JobReason why = craft(job, 0);
    // E2/E3: faster or parallel Crafters make more crafting-table crafts in the same run, never
    // more than paid; crafts rotate across the Crafters holding the pattern.
    for (int extra = 1;
        why == null && extra < Math.min(batch, paid) && job.state() == CraftingJob.State.RUNNING;
        extra++) why = craft(job, extra);
    lastRun.put(id, (int) (job.craftsDone() - before));
    runs.merge(id, 1, Integer::sum);
    if (why == null) job.resume();
    else {
      job.waiting(why);
      retryAt.put(id, (long) server.getTickCount() + RETRY_TICKS);
    }
    jobs.changed();
  }

  /**
   * Bounded cleanup record after cancel: keep collecting the dispatch's expected outputs from its
   * machine for at most {@link #MAX_CLEANUP_PASSES} passes, then abandon the remainder in place.
   * Returns false while the job must stay frozen: the leased machine's chunk is not loaded so its
   * journal cannot be checked, or the journal disagrees with the job (ADR 0057). A loaded target
   * that is no longer the original provider holds none of this job's journal and does not block.
   */
  private boolean cleanup(CraftingJob job) {
    var dispatch = job.dispatch();
    boolean legacy = dispatch.target() == null || dispatch.providers().isEmpty();
    BlockEntityRef machine = null;
    boolean original = false;
    if (!legacy) {
      machine = target(dispatch.target());
      if (!machine.loaded()) {
        job.waiting(JobReason.MACHINE_UNLOADED);
        retryAt.put(job.id(), (long) server.getTickCount() + RETRY_TICKS);
        return false;
      }
      original =
          machine.entity() != null
              && dispatch
                  .providers()
                  .equals(networks().connectors().processingIdentity(dispatch.target(), false));
      if (original) {
        var disagreement = journalCheck(job, machine.entity());
        if (disagreement != null) {
          job.waiting(disagreement);
          retryAt.put(job.id(), (long) server.getTickCount() + RETRY_TICKS);
          return false;
        }
      }
      if (job.reason() != null) job.clearReason();
    }
    int passes = cleanups.merge(job.id(), 1, Integer::sum);
    var holder = interfaceAt(dispatch.machine());
    var offer = holder == null ? null : findDispatched(job);
    var side = holder == null ? null : sideOf(holder, dispatch.target());
    if (offer != null && original && side != null && reachable(holder, side, offer.pattern())) {
      var outputs = expected(offer.pattern());
      var codec = new PlatformResourceCodec(server.registryAccess());
      var journal = new MachineIo.Journal(saved().registry().world(), job, machine.entity());
      try {
        if (holder.machine(side) != null)
          io.collect(job, outputs, holder.machine(side), codec, journal);
        if (holder.fluidMachine(side) != null)
          io.collectFluids(job, outputs, holder.fluidMachine(side), codec, journal);
      } catch (RuntimeException uncertain) {
        // The journal keeps its pending mark; nothing is retried or refunded.
        job.waiting(JobReason.UNCERTAIN);
        retryAt.put(job.id(), (long) server.getTickCount() + RETRY_TICKS);
        return false;
      }
      boolean done =
          outputs.entrySet().stream()
              .allMatch(
                  e -> job.dispatch().collected().getOrDefault(e.getKey(), 0L) >= e.getValue());
      if (done) passes = MAX_CLEANUP_PASSES;
    }
    if (passes >= MAX_CLEANUP_PASSES) {
      if (original) ProcessingJournal.clear(machine.entity());
      job.abandonDispatch();
      cleanups.remove(job.id());
    } else retryAt.put(job.id(), (long) server.getTickCount() + RETRY_TICKS);
    return true;
  }

  /** Loaded state of a dispatch target: {@code entity} is null when no block entity is there. */
  private record BlockEntityRef(boolean loaded, BlockEntity entity) {}

  /** Reads a target without loading its chunk. */
  private BlockEntityRef target(NodeRegistry.Position target) {
    for (ServerLevel level : server.getAllLevels()) {
      if (!level.dimension().location().toString().equals(target.dimension())) continue;
      var pos = BlockPos.of(target.block());
      var chunk = Loaded.chunk(level, pos);
      if (chunk == null) return new BlockEntityRef(false, null);
      var entity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
      return new BlockEntityRef(true, entity == null || entity.isRemoved() ? null : entity);
    }
    return new BlockEntityRef(false, null);
  }

  /** Null when the machine's journal proves the job's dispatch progress, else a wait reason. */
  private JobReason journalCheck(CraftingJob job, BlockEntity machine) {
    try {
      return ProcessingJournal.check(
          ProcessingJournal.read(machine, saved().registry().world()), job.id(), job.dispatch());
    } catch (IllegalArgumentException unreadable) {
      return JobReason.SAVE_MISMATCH;
    }
  }

  /**
   * Before a new dispatch: a journal left by an earlier dispatch blocks this machine unless it
   * proves that nothing moved and no call is outstanding, in which case it is cleared. Returns null
   * when dispatch may proceed.
   */
  private JobReason journalBlocks(CraftingJob job, NodeRegistry.Position position) {
    var machine = target(position);
    if (!machine.loaded() || machine.entity() == null) return null;
    ProcessingJournal.Entry entry;
    try {
      entry = ProcessingJournal.read(machine.entity(), saved().registry().world());
    } catch (IllegalArgumentException unreadable) {
      return JobReason.MACHINE_JOURNAL;
    }
    if (entry == null) return null;
    var owner = saved().craftingJobs().get(entry.job());
    if (!entry.empty() || owner != null && owner != job && owner.dispatch() != null)
      return JobReason.MACHINE_JOURNAL;
    ProcessingJournal.clear(machine.entity());
    return null;
  }

  /** Journals each real machine call: pending before it starts, settled after it returns. */
  private WorldCrafting.Offer findDispatched(CraftingJob job) {
    int index = job.current();
    if (index < 0) return null;
    var anchor = anchor(job, null);
    if (anchor == null) return null;
    var offer =
        WorldCrafting.get(server)
            .index(networks().membership(anchor).scope())
            .offer(job.steps().get(index).pattern());
    return offer != null && offer.processing() != null ? offer : null;
  }

  /** Finds a position in the job's segment for authorization, preferring the request origin. */
  private NodeRegistry.Position anchor(CraftingJob job, WorldCrafting.Offer offer) {
    var candidates = new ArrayList<NodeRegistry.Position>();
    candidates.add(job.origin());
    if (offer != null) candidates.add(offer.unit().origin());
    var root = saved().nodes().evidence(job.segment());
    if (root != null && root.position() != null) candidates.add(root.position());
    for (var position : candidates) {
      var member = networks().membership(position);
      if (member.status() == NetworkTopology.Status.VALID
          && member.scope().network().equals(job.network())
          && member.scope().segment().equals(job.segment())) return position;
    }
    return null;
  }

  /** The current step's Crafter batch: 1 for processing steps or when it cannot be resolved. */
  private int batch(CraftingJob job) {
    int index = job.current();
    if (index < 0) return 1;
    var anchor = anchor(job, null);
    if (anchor == null) return 1;
    var offer =
        WorldCrafting.get(server)
            .index(networks().membership(anchor).scope())
            .offer(job.steps().get(index).pattern());
    if (offer == null || offer.processing() != null) return 1;
    // E3: every Crafter holding this pattern adds its batch, still within the per-task cap.
    int total = 0;
    for (var holder : offer.holders())
      if (holder instanceof CrafterEntity crafter && crafter.current())
        total += CrafterEntity.batch(crafter.tier());
    return Math.max(
        1,
        Math.min(
            total, CrafterEntity.batch(dev.izzy.factorycore.core.resource.ThroughputTier.ENDGAME)));
  }

  /** Runs one craft of the current step; {@code turn} picks which holder of the pattern runs it. */
  private JobReason craft(CraftingJob job, int turn) {
    int index = job.current();
    if (index < 0) return null;
    var step = job.steps().get(index);
    var scopeAnchor = anchor(job, null);
    if (scopeAnchor == null) return JobReason.OFFLINE;
    var actor = actor(job.principal());
    if (!networks().allows(actor, scopeAnchor, NetworkPermissions.Permission.CRAFT))
      return JobReason.DENIED;
    var crafting = WorldCrafting.get(server).index(networks().membership(scopeAnchor).scope());
    var indexed = crafting.offer(step.pattern());
    if (indexed == null || indexed.pattern().version() != step.version())
      return JobReason.PATTERN_MISSING;
    var offer = indexed;
    if (indexed.processing() == null && !indexed.also().isEmpty()) {
      var holders = indexed.holders();
      var holder = holders.get(Math.floorMod(turn, holders.size()));
      offer =
          new WorldCrafting.Offer(
              indexed.pattern(),
              indexed.contents(),
              null,
              holder,
              indexed.slot(),
              indexed.published(),
              List.of());
    }
    if (offer.processing() != null) return process(job, offer);
    // E1: a substituting input draws this craft's units from the step's own remaining allocation,
    // in its (registry ID) order, never from units planned for another step.
    var inputs = new LinkedHashMap<ResourceKey, Long>();
    var chosen = new HashMap<ResourceKey, ArrayDeque<ResourceKey>>();
    for (var input : offer.pattern().inputs().entrySet()) {
      var uses = step.uses().get(input.getKey());
      if (uses == null) {
        inputs.merge(input.getKey(), input.getValue(), Math::addExact);
        continue;
      }
      long need = input.getValue();
      var units = new ArrayDeque<ResourceKey>();
      for (var use : uses.entrySet()) {
        long take = Math.min(need, use.getValue());
        for (long unit = 0; unit < take; unit++) units.add(use.getKey());
        if (take > 0) inputs.merge(use.getKey(), take, Math::addExact);
        need -= take;
        if (need == 0) break;
      }
      if (need > 0) return JobReason.SUBSTITUTES_SHORT;
      chosen.put(input.getKey(), units);
    }
    for (var input : inputs.entrySet()) {
      long missing = input.getValue() - job.stagedAmount(input.getKey());
      if (missing > 0) pull(job, input.getKey(), missing);
      if (job.stagedAmount(input.getKey()) < input.getValue()) return JobReason.OFFLINE;
    }
    var cells = offer.contents().cells();
    if (!chosen.isEmpty()) {
      var level = offer.unit().getLevel();
      if (level == null) return JobReason.OFFLINE;
      var codec = new PlatformResourceCodec(level.registryAccess());
      var grid = new ArrayList<ItemStack>(cells.size());
      for (var cell : cells) {
        var units = cell.isEmpty() ? null : chosen.get(codec.itemKey(cell));
        grid.add(units == null ? cell : codec.itemStack(units.poll(), 1));
      }
      cells = grid;
    }
    var outputs = assemble(job.id(), offer, cells);
    if (outputs == null) return JobReason.RECIPE_CHANGED;
    var permits = new ArrayList<WirelessBudget.Permit>();
    for (var changes : List.of(inputs, outputs))
      for (var entry : changes.entrySet()) {
        var permit =
            networks()
                .movement(scopeAnchor, offer.unit().origin(), entry.getKey(), entry.getValue());
        permits.add(permit);
        if (permit.amount() != entry.getValue()) {
          for (var held : permits) held.finish(0);
          return JobReason.of(permit.reason());
        }
      }
    job.crafted(inputs, outputs);
    for (var permit : permits) permit.finish(permit.amount());
    return null;
  }

  /** The loaded current interface at a dispatch position, or null. */
  private CrafterEntity interfaceAt(NodeRegistry.Position position) {
    for (ServerLevel level : server.getAllLevels()) {
      if (!level.dimension().location().toString().equals(position.dimension())) continue;
      var pos = BlockPos.of(position.block());
      if (Loaded.chunk(level, pos) == null) return null;
      return level.getBlockEntity(pos) instanceof CrafterEntity holder && holder.current()
          ? holder
          : null;
    }
    return null;
  }

  private static NodeRegistry.Position machineTarget(CrafterEntity holder, Direction side) {
    return new NodeRegistry.Position(
        holder.origin().dimension(), holder.getBlockPos().relative(side).asLong());
  }

  /** The Crafter's side facing a saved dispatch target, or null when it is no longer beside it. */
  private static Direction sideOf(CrafterEntity holder, NodeRegistry.Position target) {
    if (target == null || !target.dimension().equals(holder.origin().dimension())) return null;
    return holder.sideOf(BlockPos.of(target.block()));
  }

  /**
   * Metadata-only lease query for the shared connector path. Dispatch saves the interface position,
   * and target; legacy dispatches without a proven target fence all six neighbors. Never loads a
   * chunk.
   */
  public boolean machineLeased(NodeRegistry.Position target) {
    ServerThread.require(server, "Crafting");
    return leasedByOther(target, null);
  }

  private boolean leasedByOther(NodeRegistry.Position target, UUID job) {
    var block = BlockPos.of(target.block());
    for (var other : saved().craftingJobs().all()) {
      if (other.id().equals(job) || other.dispatch() == null) continue;
      var dispatch = other.dispatch();
      if (dispatch.target() != null) {
        if (dispatch.target().equals(target)) return true;
      } else {
        var leased = dispatch.machine();
        if (!leased.dimension().equals(target.dimension())) continue;
        var port = BlockPos.of(leased.block());
        if (port.equals(block) || port.distManhattan(block) == 1) return true;
      }
    }
    return false;
  }

  private static boolean contains(
      IItemHandler machine, ResourceKey key, PlatformResourceCodec codec) {
    for (int slot = 0; slot < machine.getSlots(); slot++) {
      var stack = machine.getStackInSlot(slot);
      if (!stack.isEmpty() && key.equals(keyOf(codec, stack))) return true;
    }
    return false;
  }

  static ResourceKey keyOf(PlatformResourceCodec codec, ItemStack stack) {
    try {
      return codec.itemKey(stack);
    } catch (IllegalArgumentException unsupported) {
      return null;
    }
  }

  private static Map<ResourceKey, Long> expected(Pattern pattern) {
    var outputs = new LinkedHashMap<ResourceKey, Long>();
    outputs.put(pattern.output(), pattern.outputAmount());
    pattern.byproducts().forEach((key, amount) -> outputs.merge(key, amount, Math::addExact));
    return outputs;
  }

  /**
   * One processing pass: lease a clean machine and stage every input, insert what the machine
   * accepts, collect expected outputs only from that machine, and complete the craft when all are
   * collected. Units the machine does not accept stay staged; nothing is voided.
   */
  private JobReason process(CraftingJob job, WorldCrafting.Offer offer) {
    var pattern = offer.pattern();
    var codec = new PlatformResourceCodec(server.registryAccess());
    var outputs = expected(pattern);
    if (job.dispatch() == null) {
      var holder = (CrafterEntity) offer.unit();
      // H8: try each machine beside the Crafter in turn; report why the last one was refused.
      JobReason refused = JobReason.MACHINE_MISSING;
      Direction chosen = null;
      Map<ResourceKind, UUID> providers = Map.of();
      for (var side : holder.machineSides()) {
        var reason = refusal(job, holder, side, pattern, outputs, codec);
        if (reason != null) {
          refused = reason;
          continue;
        }
        providers = networks().connectors().processingIdentity(machineTarget(holder, side), true);
        if (providers.isEmpty()) {
          refused = JobReason.MACHINE_IDENTITY;
          continue;
        }
        chosen = side;
        break;
      }
      if (chosen == null) return refused;
      for (var input : pattern.inputs().entrySet()) {
        long missing = input.getValue() - job.stagedAmount(input.getKey());
        if (missing > 0) pull(job, input.getKey(), missing);
        if (job.stagedAmount(input.getKey()) < input.getValue()) return JobReason.OFFLINE;
      }
      job.dispatch(holder.origin(), machineTarget(holder, chosen), providers);
      holder.used(chosen);
      var captured = target(machineTarget(holder, chosen));
      if (captured.entity() != null)
        ProcessingJournal.write(
            captured.entity(), saved().registry().world(), job.id(), job.dispatch(), false);
    }
    var holder = interfaceAt(job.dispatch().machine());
    if (holder == null) return JobReason.MACHINE_MISSING;
    if (job.dispatch().target() == null) return JobReason.LEGACY_MACHINE;
    if (job.dispatch().providers().isEmpty()) return JobReason.LEGACY_PROVIDER;
    var side = sideOf(holder, job.dispatch().target());
    if (side == null) return JobReason.MACHINE_CHANGED;
    if (!job.dispatch()
        .providers()
        .equals(networks().connectors().processingIdentity(job.dispatch().target(), false)))
      return JobReason.MACHINE_REPLACED;
    if (!reachable(holder, side, pattern)) return JobReason.MACHINE_MISSING;
    var machine = target(job.dispatch().target()).entity();
    if (machine == null) return JobReason.MACHINE_MISSING;
    var disagreement = journalCheck(job, machine);
    if (disagreement != null) return disagreement;
    var journal = new MachineIo.Journal(saved().registry().world(), job, machine);
    try {
      if (holder.machine(side) != null) {
        io.insert(job, pattern.inputs(), holder.machine(side), codec, journal);
        io.collect(job, outputs, holder.machine(side), codec, journal);
      }
      if (holder.fluidMachine(side) != null) {
        io.insertFluids(job, pattern.inputs(), holder.fluidMachine(side), codec, journal);
        io.collectFluids(job, outputs, holder.fluidMachine(side), codec, journal);
      }
    } catch (RuntimeException uncertain) {
      // The started call's outcome is unknown: its pending journal mark stays with the machine's
      // own saved contents, and no later pass, cancellation or restart replays or refunds it.
      return JobReason.UNCERTAIN;
    }
    var dispatch = job.dispatch();
    for (var input : pattern.inputs().entrySet())
      if (dispatch.inserted().getOrDefault(input.getKey(), 0L) < input.getValue())
        return JobReason.MACHINE_FULL;
    for (var output : outputs.entrySet())
      if (dispatch.collected().getOrDefault(output.getKey(), 0L) < output.getValue())
        return JobReason.PROCESSING;
    job.processed(pattern.inputs(), outputs);
    ProcessingJournal.clear(machine);
    return null;
  }

  /**
   * Why the machine on {@code side} cannot take a new dispatch of {@code pattern}, or null when it
   * can: missing handlers, another job's lease, foreign holds or staging, expected outputs already
   * inside, or a journal that disagrees.
   */
  private JobReason refusal(
      CraftingJob job,
      CrafterEntity holder,
      Direction side,
      Pattern pattern,
      Map<ResourceKey, Long> outputs,
      PlatformResourceCodec codec) {
    if (!reachable(holder, side, pattern)) return JobReason.MACHINE_MISSING;
    var target = machineTarget(holder, side);
    if (leasedByOther(target, job.id())) return JobReason.BUSY;
    var admission = networks().connectors().processingAdmission(target);
    if (admission == OperationResult.Reason.BUSY) return JobReason.BUSY;
    if (admission != OperationResult.Reason.OK) return JobReason.RESERVED;
    for (var key : outputs.keySet())
      if (key.kind() == ResourceKind.FLUID
          ? MachineIo.containsFluid(holder.fluidMachine(side), key)
          : contains(holder.machine(side), key, codec)) return JobReason.OUTPUT_NOT_CLEAN;
    return journalBlocks(job, target);
  }

  /** True when the machine on {@code side} exposes every handler kind the pattern's keys need. */
  private static boolean reachable(CrafterEntity holder, Direction side, Pattern pattern) {
    var keys = new HashSet<>(pattern.inputs().keySet());
    keys.addAll(expected(pattern).keySet());
    boolean items = keys.stream().anyMatch(key -> key.kind() == ResourceKind.ITEM);
    boolean fluids = keys.stream().anyMatch(key -> key.kind() == ResourceKind.FLUID);
    if (keys.stream().anyMatch(key -> key.kind() == ResourceKind.ENERGY)) return false;
    return (!items || holder.machine(side) != null)
        && (!fluids || holder.fluidMachine(side) != null);
  }

  /** Moves reserved units into job staging; stops at offline or moved ledgers. */
  private void pull(CraftingJob job, ResourceKey key, long amount) {
    var registry = saved().registry();
    for (var cell : job.reserved().entrySet()) {
      if (amount == 0) return;
      long held = cell.getValue().getOrDefault(key, 0L);
      if (held == 0) continue;
      var ownership = registry.ownership(cell.getKey());
      if (ownership == null || ownership.conflict()) continue;
      var origin = anchor(job, null);
      var location = registry.definition(ownership.handle()).location();
      if (origin == null || location == null) continue;
      var position = new NodeRegistry.Position(location.dimension(), location.position());
      var permit = networks().movement(position, origin, key, Math.min(held, amount));
      if (permit.amount() == 0) {
        permit.finish(0);
        continue;
      }
      var result = registry.extract(ownership.handle(), key, permit.amount(), job.id());
      permit.finish(result.amount());
      if (result.amount() > 0) {
        job.pulled(cell.getKey(), key, result.amount());
        amount -= result.amount();
      }
    }
  }

  /**
   * Revalidates the server recipe at the offering unit on {@code cells} and returns exact outputs
   * plus remainders, or null when the unit or recipe no longer reproduces the encoded pattern.
   */
  private Map<ResourceKey, Long> assemble(
      UUID job, WorldCrafting.Offer offer, List<ItemStack> cells) {
    if (!offer.unit().current()) return null;
    var level = offer.unit().getLevel();
    if (level == null) return null;
    // The real recipe is revalidated with the concrete (possibly substituted) items every craft.
    var input = CraftingInput.of(3, 3, cells);
    var holder = recipes.get(job);
    if (holder == null || !holder.value().matches(input, level)) {
      holder =
          level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
      if (holder == null) return null;
      recipes.put(job, holder);
    }
    var output = holder.value().assemble(input, level.registryAccess());
    if (!ItemStack.matches(output, offer.contents().output())) return null;
    var remainders = new ArrayList<ItemStack>();
    for (var remainder : holder.value().getRemainingItems(input))
      if (!remainder.isEmpty()) remainders.add(remainder);
    var expected = offer.contents().remainders();
    if (remainders.size() != expected.size()) return null;
    for (int i = 0; i < expected.size(); i++)
      if (!ItemStack.matches(remainders.get(i), expected.get(i))) return null;
    var codec = new PlatformResourceCodec(level.registryAccess());
    var produced = new LinkedHashMap<ResourceKey, Long>();
    produced.merge(codec.itemKey(output), (long) output.getCount(), Math::addExact);
    for (var remainder : remainders)
      produced.merge(codec.itemKey(remainder), (long) remainder.getCount(), Math::addExact);
    return produced;
  }

  /** Releases leftover reservations, then inserts staging into CRAFT-view cells. */
  private void deliver(CraftingJob job) {
    for (var cell : job.reserved().entrySet())
      for (var key : cell.getValue().keySet()) {
        release(cell.getKey(), job.id(), key);
        job.released(cell.getKey(), key);
      }
    if (job.staged().isEmpty()) return;
    var anchor = anchor(job, null);
    if (anchor == null) {
      job.waiting(JobReason.OFFLINE);
      retryAt.put(job.id(), (long) server.getTickCount() + RETRY_TICKS);
      return;
    }
    var actor = actor(job.principal());
    var registry = saved().registry();
    for (var entry : new LinkedHashMap<>(job.staged()).entrySet()) {
      long remaining = entry.getValue();
      for (var cell : cells(actor, anchor, entry.getKey())) {
        if (remaining == 0) break;
        var location = registry.definition(cell.handle()).location();
        if (location == null) continue;
        var position = new NodeRegistry.Position(location.dimension(), location.position());
        var permit = networks().movement(anchor, position, entry.getKey(), remaining);
        if (permit.amount() == 0) {
          permit.finish(0);
          continue;
        }
        var result = registry.insert(cell.handle(), entry.getKey(), permit.amount());
        permit.finish(result.amount());
        if (result.amount() > 0) {
          job.delivered(entry.getKey(), result.amount());
          remaining -= result.amount();
        }
      }
    }
    if (!job.staged().isEmpty()) {
      job.waiting(JobReason.FULL);
      retryAt.put(job.id(), (long) server.getTickCount() + RETRY_TICKS);
    }
  }

  private void checkThread() {
    Objects.requireNonNull(server);
    ServerThread.require(server, "Crafting");
  }
}
