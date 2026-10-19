package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.crafting.CraftingPlanner;
import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
  private static final Map<MinecraftServer, WorldCraftingJobs> SERVERS = new HashMap<>();

  /** Advisory preview; {@code reason} is empty when the plan could be submitted now. */
  public record Preview(CraftingPlanner.Plan plan, String reason) {}

  /** Submission outcome; {@code job} is null when {@code reason} explains a refusal. */
  public record Submission(UUID job, CraftingPlanner.Plan plan, String reason) {}

  /** Recently finished job for a requester's display; not persisted. */
  public record Notice(UUID job, ResourceKey request, long amount, boolean cancelled) {}

  private record Cell(DeviceRegistry.Handle handle, long available) {}

  private final MinecraftServer server;
  private final Map<UUID, Long> retryAt = new HashMap<>();
  private final Map<UUID, RecipeHolder<CraftingRecipe>> recipes = new HashMap<>();
  private final Map<UUID, ArrayDeque<Notice>> notices = new HashMap<>();
  private final Map<UUID, Integer> cleanups = new HashMap<>();
  private final Map<UUID, String> ruleStatus = new HashMap<>();
  private final Map<UUID, Long> ruleDue = new HashMap<>();
  public static final int RULE_TICKS = 100;
  public static final long MAX_RULE_REQUEST = 4_096;
  public static final int MAX_CLEANUP_PASSES = 60;

  private WorldCraftingJobs(MinecraftServer server) {
    this.server = server;
  }

  public static WorldCraftingJobs get(MinecraftServer server) {
    if (!server.isSameThread()) throw new IllegalStateException("Crafting requires server thread");
    return SERVERS.computeIfAbsent(server, WorldCraftingJobs::new);
  }

  public static void postTick(ServerTickEvent.Post event) {
    // Quarantined storage keeps every job record untouched; storage reports the diagnostic.
    if (DeviceSavedData.get(event.getServer()).diagnostic().isEmpty())
      get(event.getServer()).tick();
  }

  public static void stopped(ServerStoppedEvent event) {
    SERVERS.remove(event.getServer());
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

  private String denial(NetworkPermissions.Actor actor, NodeRegistry.Position origin) {
    var member = networks().membership(origin);
    if (member.status() != NetworkTopology.Status.VALID) return "offline";
    return networks().authorize(actor, origin, NetworkPermissions.Permission.CRAFT)
            == NetworkAuthority.Decision.ALLOWED
        ? null
        : "denied";
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
    if (key.kind() == ResourceKind.ENERGY) return new Preview(null, "unsupported");
    var plan = plan(actor, origin, key, amount);
    return new Preview(plan, reason(plan));
  }

  private static String reason(CraftingPlanner.Plan plan) {
    return switch (plan.status()) {
      case OK -> "";
      case MISSING -> plan.cycles().isEmpty() ? "missing" : "cycle";
      case LIMIT -> "limit";
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
    if (key.kind() == ResourceKind.ENERGY) return new Submission(null, null, "unsupported");
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
      return new Submission(null, plan, complete ? "limit" : "changed");
    }
    var job =
        CraftingJob.accepted(
            id, actor.id(), scope.network(), scope.segment(), origin, plan, reserved, rule);
    jobs.add(job);
    return new Submission(id, plan, "");
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
  public String keep(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      ResourceKey key,
      long low,
      long high) {
    checkThread();
    var denied = denial(actor, origin);
    if (denied != null) return denied;
    if (key.kind() == ResourceKind.ENERGY) return "unsupported";
    var scope = networks().membership(origin).scope();
    try {
      var refused =
          saved()
              .craftingJobs()
              .setRule(
                  new dev.izzy.factorycore.core.crafting.KeepStockRule(
                      UUID.randomUUID(),
                      actor.id(),
                      scope.network(),
                      scope.segment(),
                      origin,
                      key,
                      low,
                      high));
      return refused == null ? "" : refused;
    } catch (IllegalArgumentException invalid) {
      return "limit";
    }
  }

  /** The requester's rule for {@code key} in the origin's segment, or null. */
  public dev.izzy.factorycore.core.crafting.KeepStockRule rule(
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
    return jobs.removeRule(rule);
  }

  /** Last evaluation outcome for a rule: "" when satisfied or a job started, else a reason. */
  public String ruleStatus(UUID rule) {
    checkThread();
    return ruleStatus.getOrDefault(rule, "");
  }

  /** Evaluates one rule: start a job for its deficit unless one of its jobs is in flight. */
  void evaluate(UUID id) {
    checkThread();
    var jobs = saved().craftingJobs();
    var rule = jobs.rules().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    if (rule == null) return;
    for (var job : jobs.all())
      if (id.equals(job.rule())) {
        ruleStatus.put(id, "in_flight");
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
      ruleStatus.put(id, "satisfied");
      return;
    }
    var submission = submit(actor, rule.origin(), rule.key(), deficit, id);
    ruleStatus.put(id, submission.job() != null ? "started" : submission.reason());
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

  private void notice(CraftingJob job, boolean cancelled) {
    var list = notices.computeIfAbsent(job.principal(), p -> new ArrayDeque<>());
    if (list.size() == MAX_NOTICES) list.removeFirst();
    list.addLast(new Notice(job.id(), job.request(), job.amount(), cancelled));
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
      scheduler.submit(job.network(), job.principal(), key, TASK_COST, () -> run(id));
    }
    for (var rule : saved().craftingJobs().rules()) {
      var key = "keep:" + rule.id();
      if (ruleDue.getOrDefault(rule.id(), Long.MIN_VALUE) > now
          || scheduler.isQueued(rule.network(), rule.principal(), key)) continue;
      ruleDue.put(rule.id(), now + RULE_TICKS);
      var id = rule.id();
      scheduler.submit(rule.network(), rule.principal(), key, TASK_COST, () -> evaluate(id));
    }
  }

  /** Runs one unit of work for a job; reason changes keep it waiting until the next retry. */
  void run(UUID id) {
    checkThread();
    var jobs = saved().craftingJobs();
    var job = jobs.get(id);
    if (job == null) return;
    boolean cancelling = job.state() == CraftingJob.State.CANCELLING;
    if (cancelling && job.dispatch() != null) cleanup(job);
    if (job.state() == CraftingJob.State.DELIVERING || cancelling) {
      deliver(job);
      if (job.finished()) {
        jobs.retire(id);
        retryAt.remove(id);
        recipes.remove(id);
        notice(job, cancelling);
      }
      jobs.changed();
      return;
    }
    job.resume();
    String why = craft(job);
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
   */
  private void cleanup(CraftingJob job) {
    int passes = cleanups.merge(job.id(), 1, Integer::sum);
    var holder = interfaceAt(job.dispatch().machine());
    var offer = holder == null ? null : findDispatched(job);
    if (offer != null
        && job.dispatch().target() != null
        && !job.dispatch().providers().isEmpty()
        && job.dispatch().target().equals(machineTarget(holder))
        && job.dispatch()
            .providers()
            .equals(networks().connectors().processingIdentity(job.dispatch().target(), false))
        && reachable(holder, offer.pattern())) {
      var outputs = expected(offer.pattern());
      var codec = new PlatformResourceCodec(server.registryAccess());
      if (holder.machine() != null) collect(job, outputs, holder.machine(), codec);
      if (holder.fluidMachine() != null) collectFluids(job, outputs, holder.fluidMachine(), codec);
      boolean done =
          outputs.entrySet().stream()
              .allMatch(
                  e -> job.dispatch().collected().getOrDefault(e.getKey(), 0L) >= e.getValue());
      if (done) passes = MAX_CLEANUP_PASSES;
    }
    if (passes >= MAX_CLEANUP_PASSES) {
      job.abandonDispatch();
      cleanups.remove(job.id());
    } else retryAt.put(job.id(), (long) server.getTickCount() + RETRY_TICKS);
  }

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

  private String craft(CraftingJob job) {
    int index = job.current();
    if (index < 0) return null;
    var step = job.steps().get(index);
    var scopeAnchor = anchor(job, null);
    if (scopeAnchor == null) return "offline";
    var actor = actor(job.principal());
    if (networks().authorize(actor, scopeAnchor, NetworkPermissions.Permission.CRAFT)
        != NetworkAuthority.Decision.ALLOWED) return "denied";
    var crafting = WorldCrafting.get(server).index(networks().membership(scopeAnchor).scope());
    var offer = crafting.offer(step.pattern());
    if (offer == null || offer.pattern().version() != step.version()) return "pattern_missing";
    var inputs = offer.pattern().inputs();
    if (offer.processing() != null) return process(job, offer);
    for (var input : inputs.entrySet()) {
      long missing = input.getValue() - job.stagedAmount(input.getKey());
      if (missing > 0) pull(job, input.getKey(), missing);
      if (job.stagedAmount(input.getKey()) < input.getValue()) return "offline";
    }
    var outputs = assemble(job.id(), offer);
    if (outputs == null) return "recipe_changed";
    var permits = new ArrayList<dev.izzy.factorycore.core.resource.WirelessBudget.Permit>();
    for (var changes : List.of(inputs, outputs))
      for (var entry : changes.entrySet()) {
        var permit =
            networks()
                .movement(scopeAnchor, offer.unit().origin(), entry.getKey(), entry.getValue());
        permits.add(permit);
        if (permit.amount() != entry.getValue()) {
          for (var held : permits) held.finish(0);
          return permit.reason().name().toLowerCase(java.util.Locale.ROOT);
        }
      }
    job.crafted(inputs, outputs);
    for (var permit : permits) permit.finish(permit.amount());
    return null;
  }

  /** The loaded current interface at a dispatch position, or null. */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed level.
  private CraftingInterfaceEntity interfaceAt(NodeRegistry.Position position) {
    for (var level : server.getAllLevels()) {
      if (!level.dimension().location().toString().equals(position.dimension())) continue;
      var pos = net.minecraft.core.BlockPos.of(position.block());
      if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return null;
      return level.getBlockEntity(pos) instanceof CraftingInterfaceEntity holder && holder.current()
          ? holder
          : null;
    }
    return null;
  }

  private static NodeRegistry.Position machineTarget(CraftingInterfaceEntity holder) {
    return new NodeRegistry.Position(
        holder.origin().dimension(), holder.getBlockPos().relative(holder.facing()).asLong());
  }

  /**
   * Metadata-only lease query for the shared connector path. Dispatch saves the interface position,
   * and target; legacy dispatches without a proven target fence all six neighbors. Never loads a
   * chunk.
   */
  public boolean machineLeased(NodeRegistry.Position target) {
    if (!server.isSameThread()) throw new IllegalStateException("Crafting requires server thread");
    return leasedByOther(target, null);
  }

  private boolean leasedByOther(NodeRegistry.Position target, UUID job) {
    var block = net.minecraft.core.BlockPos.of(target.block());
    for (var other : saved().craftingJobs().all()) {
      if (other.id().equals(job) || other.dispatch() == null) continue;
      var dispatch = other.dispatch();
      if (dispatch.target() != null) {
        if (dispatch.target().equals(target)) return true;
      } else {
        var leased = dispatch.machine();
        if (!leased.dimension().equals(target.dimension())) continue;
        var port = net.minecraft.core.BlockPos.of(leased.block());
        if (port.equals(block) || port.distManhattan(block) == 1) return true;
      }
    }
    return false;
  }

  private static boolean contains(
      net.neoforged.neoforge.items.IItemHandler machine,
      ResourceKey key,
      PlatformResourceCodec codec) {
    for (int slot = 0; slot < machine.getSlots(); slot++) {
      var stack = machine.getStackInSlot(slot);
      if (!stack.isEmpty() && key.equals(keyOf(codec, stack))) return true;
    }
    return false;
  }

  private static ResourceKey keyOf(PlatformResourceCodec codec, ItemStack stack) {
    try {
      return codec.itemKey(stack);
    } catch (IllegalArgumentException unsupported) {
      return null;
    }
  }

  private static Map<ResourceKey, Long> expected(
      dev.izzy.factorycore.core.crafting.Pattern pattern) {
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
  private String process(CraftingJob job, WorldCrafting.Offer offer) {
    var pattern = offer.pattern();
    var codec = new PlatformResourceCodec(server.registryAccess());
    var outputs = expected(pattern);
    if (job.dispatch() == null) {
      var holder = (CraftingInterfaceEntity) offer.unit();
      if (!reachable(holder, pattern)) return "machine_missing";
      if (leasedByOther(machineTarget(holder), job.id())) return "busy";
      var admission = networks().connectors().processingAdmission(machineTarget(holder));
      if (admission == dev.izzy.factorycore.core.resource.OperationResult.Reason.BUSY)
        return "busy";
      if (admission != dev.izzy.factorycore.core.resource.OperationResult.Reason.OK)
        return "reserved";
      for (var key : outputs.keySet())
        if (key.kind() == ResourceKind.FLUID
            ? containsFluid(holder.fluidMachine(), key)
            : contains(holder.machine(), key, codec)) return "output_not_clean";
      var providers = networks().connectors().processingIdentity(machineTarget(holder), true);
      if (providers.isEmpty()) return "machine_identity";
      for (var input : pattern.inputs().entrySet()) {
        long missing = input.getValue() - job.stagedAmount(input.getKey());
        if (missing > 0) pull(job, input.getKey(), missing);
        if (job.stagedAmount(input.getKey()) < input.getValue()) return "offline";
      }
      job.dispatch(holder.origin(), machineTarget(holder), providers);
    }
    var holder = interfaceAt(job.dispatch().machine());
    if (holder == null) return "machine_missing";
    if (job.dispatch().target() == null) return "legacy_machine";
    if (job.dispatch().providers().isEmpty()) return "legacy_provider";
    if (!job.dispatch().target().equals(machineTarget(holder))) return "machine_changed";
    if (!job.dispatch()
        .providers()
        .equals(networks().connectors().processingIdentity(job.dispatch().target(), false)))
      return "machine_replaced";
    if (!reachable(holder, pattern)) return "machine_missing";
    if (holder.machine() != null) {
      insert(job, pattern.inputs(), holder.machine(), codec);
      collect(job, outputs, holder.machine(), codec);
    }
    if (holder.fluidMachine() != null) {
      insertFluids(job, pattern.inputs(), holder.fluidMachine(), codec);
      collectFluids(job, outputs, holder.fluidMachine(), codec);
    }
    var dispatch = job.dispatch();
    for (var input : pattern.inputs().entrySet())
      if (dispatch.inserted().getOrDefault(input.getKey(), 0L) < input.getValue())
        return "machine_full";
    for (var output : outputs.entrySet())
      if (dispatch.collected().getOrDefault(output.getKey(), 0L) < output.getValue())
        return "processing";
    job.processed(pattern.inputs(), outputs);
    return null;
  }

  /** True when the machine exposes every handler kind the pattern's keys need. */
  private static boolean reachable(
      CraftingInterfaceEntity holder, dev.izzy.factorycore.core.crafting.Pattern pattern) {
    var keys = new java.util.HashSet<>(pattern.inputs().keySet());
    keys.addAll(expected(pattern).keySet());
    boolean items = keys.stream().anyMatch(key -> key.kind() == ResourceKind.ITEM);
    boolean fluids = keys.stream().anyMatch(key -> key.kind() == ResourceKind.FLUID);
    if (keys.stream().anyMatch(key -> key.kind() == ResourceKind.ENERGY)) return false;
    return (!items || holder.machine() != null) && (!fluids || holder.fluidMachine() != null);
  }

  private static boolean containsFluid(
      net.neoforged.neoforge.fluids.capability.IFluidHandler tanks, ResourceKey key) {
    for (int tank = 0; tank < tanks.getTanks(); tank++) {
      var present = tanks.getFluidInTank(tank);
      if (!present.isEmpty()
          && key.registryId()
              .equals(
                  net.minecraft.core.registries.BuiltInRegistries.FLUID
                      .getKey(present.getFluid())
                      .toString())) return true;
    }
    return false;
  }

  /** Fills exactly what the machine accepts of each staged fluid input; the rest stays staged. */
  private void insertFluids(
      CraftingJob job,
      Map<ResourceKey, Long> inputs,
      net.neoforged.neoforge.fluids.capability.IFluidHandler tanks,
      PlatformResourceCodec codec) {
    tanks = meteredFluids(job, tanks, codec);
    if (tanks == null) return;
    for (var input : inputs.entrySet()) {
      if (input.getKey().kind() != ResourceKind.FLUID) continue;
      long remaining =
          Math.min(
              input.getValue() - job.dispatch().inserted().getOrDefault(input.getKey(), 0L),
              job.stagedAmount(input.getKey()));
      if (remaining <= 0) continue;
      var offer = codec.fluidStack(input.getKey(), (int) Math.min(remaining, Integer.MAX_VALUE));
      int accepted =
          tanks.fill(
              offer, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
      if (accepted > 0) job.inserted(input.getKey(), Math.min(accepted, remaining));
    }
  }

  /** Drains only the expected fluid outputs from the leased machine into job staging. */
  private void collectFluids(
      CraftingJob job,
      Map<ResourceKey, Long> outputs,
      net.neoforged.neoforge.fluids.capability.IFluidHandler tanks,
      PlatformResourceCodec codec) {
    tanks = meteredFluids(job, tanks, codec);
    if (tanks == null) return;
    for (var output : outputs.entrySet()) {
      if (output.getKey().kind() != ResourceKind.FLUID) continue;
      long remaining =
          output.getValue() - job.dispatch().collected().getOrDefault(output.getKey(), 0L);
      if (remaining <= 0) continue;
      var want = codec.fluidStack(output.getKey(), (int) Math.min(remaining, Integer.MAX_VALUE));
      var got =
          tanks.drain(
              want, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
      if (got.isEmpty()) continue;
      if (!codec.fluidKey(got).equals(output.getKey()) || got.getAmount() > remaining)
        throw new IllegalStateException("Machine drained an unexpected fluid");
      job.collected(output.getKey(), got.getAmount());
    }
  }

  private void insert(
      CraftingJob job,
      Map<ResourceKey, Long> inputs,
      net.neoforged.neoforge.items.IItemHandler machine,
      PlatformResourceCodec codec) {
    machine = meteredItems(job, machine, codec);
    if (machine == null) return;
    for (var input : inputs.entrySet()) {
      if (input.getKey().kind() != ResourceKind.ITEM) continue;
      long remaining =
          Math.min(
              input.getValue() - job.dispatch().inserted().getOrDefault(input.getKey(), 0L),
              job.stagedAmount(input.getKey()));
      while (remaining > 0) {
        var stack = codec.itemStack(input.getKey(), 1);
        int size = (int) Math.min(remaining, stack.getMaxStackSize());
        stack.setCount(size);
        var rest = net.neoforged.neoforge.items.ItemHandlerHelper.insertItem(machine, stack, false);
        int accepted = size - rest.getCount();
        if (accepted <= 0) break;
        job.inserted(input.getKey(), accepted);
        remaining -= accepted;
      }
    }
  }

  /** Extracts expected outputs from the leased machine; anything else extracted is staged too. */
  private void collect(
      CraftingJob job,
      Map<ResourceKey, Long> outputs,
      net.neoforged.neoforge.items.IItemHandler machine,
      PlatformResourceCodec codec) {
    machine = meteredItems(job, machine, codec);
    if (machine == null) return;
    for (var output : outputs.entrySet())
      for (int slot = 0;
          output.getKey().kind() == ResourceKind.ITEM && slot < machine.getSlots();
          slot++) {
        long remaining =
            output.getValue() - job.dispatch().collected().getOrDefault(output.getKey(), 0L);
        if (remaining <= 0) break;
        var present = machine.getStackInSlot(slot);
        if (present.isEmpty() || !output.getKey().equals(keyOf(codec, present))) continue;
        var got = machine.extractItem(slot, (int) Math.min(remaining, present.getCount()), false);
        if (got.isEmpty()) continue;
        var key = keyOf(codec, got);
        if (key == null)
          throw new IllegalStateException("Machine returned an unrepresentable stack");
        job.collected(key, got.getCount());
      }
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
   * Revalidates the server recipe at the offering unit and returns exact outputs plus remainders,
   * or null when the unit or recipe no longer reproduces the encoded pattern.
   */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the unit's borrowed level.
  private Map<ResourceKey, Long> assemble(UUID job, WorldCrafting.Offer offer) {
    if (!offer.unit().current()) return null;
    var level = offer.unit().getLevel();
    if (level == null) return null;
    var input = CraftingInput.of(3, 3, offer.contents().cells());
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
      job.waiting("offline");
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
      job.waiting("full");
      retryAt.put(job.id(), (long) server.getTickCount() + RETRY_TICKS);
    }
  }

  /** Job staging resides in its request segment; external machine dispatch is a separate leg. */
  private long meterMachine(
      CraftingJob job,
      ResourceKey key,
      long amount,
      boolean simulate,
      java.util.function.LongUnaryOperator operation) {
    var origin = anchor(job, null);
    if (origin == null || job.dispatch() == null) return 0;
    var machine = job.dispatch().machine();
    if (simulate) {
      long offer = networks().movementAvailable(origin, machine, key, amount);
      return offer == 0 ? 0 : operation.applyAsLong(offer);
    }
    var permit = networks().movement(origin, machine, key, amount);
    if (permit.amount() == 0) {
      permit.finish(0);
      return 0;
    }
    long actual = operation.applyAsLong(permit.amount());
    permit.finish(actual);
    return actual;
  }

  private net.neoforged.neoforge.items.IItemHandler meteredItems(
      CraftingJob job,
      net.neoforged.neoforge.items.IItemHandler delegate,
      PlatformResourceCodec codec) {
    if (anchor(job, null) == null || job.dispatch() == null) return null;
    return new net.neoforged.neoforge.items.IItemHandler() {
      @Override
      public int getSlots() {
        return delegate.getSlots();
      }

      @Override
      public ItemStack getStackInSlot(int slot) {
        return delegate.getStackInSlot(slot);
      }

      @Override
      public int getSlotLimit(int slot) {
        return delegate.getSlotLimit(slot);
      }

      @Override
      public boolean isItemValid(int slot, ItemStack stack) {
        return delegate.isItemValid(slot, stack);
      }

      @Override
      public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        long accepted =
            meterMachine(
                job,
                codec.itemKey(stack),
                stack.getCount(),
                simulate,
                offered -> {
                  var rest =
                      delegate.insertItem(slot, stack.copyWithCount((int) offered), simulate);
                  if (!rest.isEmpty() && !ItemStack.isSameItemSameComponents(rest, stack))
                    throw new IllegalStateException(
                        "Processing handler changed an offered identity");
                  return offered - rest.getCount();
                });
        return stack.copyWithCount(stack.getCount() - (int) accepted);
      }

      @Override
      public ItemStack extractItem(int slot, int amount, boolean simulate) {
        var present = delegate.getStackInSlot(slot);
        if (present.isEmpty()) return ItemStack.EMPTY;
        var extracted = new ItemStack[] {ItemStack.EMPTY};
        meterMachine(
            job,
            codec.itemKey(present),
            amount,
            simulate,
            offered -> {
              extracted[0] = delegate.extractItem(slot, (int) offered, simulate);
              return extracted[0].getCount();
            });
        return extracted[0];
      }
    };
  }

  private net.neoforged.neoforge.fluids.capability.IFluidHandler meteredFluids(
      CraftingJob job,
      net.neoforged.neoforge.fluids.capability.IFluidHandler delegate,
      PlatformResourceCodec codec) {
    if (anchor(job, null) == null || job.dispatch() == null) return null;
    return new net.neoforged.neoforge.fluids.capability.IFluidHandler() {
      @Override
      public int getTanks() {
        return delegate.getTanks();
      }

      @Override
      public net.neoforged.neoforge.fluids.FluidStack getFluidInTank(int tank) {
        return delegate.getFluidInTank(tank);
      }

      @Override
      public int getTankCapacity(int tank) {
        return delegate.getTankCapacity(tank);
      }

      @Override
      public boolean isFluidValid(int tank, net.neoforged.neoforge.fluids.FluidStack stack) {
        return delegate.isFluidValid(tank, stack);
      }

      @Override
      public int fill(net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
        if (stack.isEmpty()) return 0;
        return (int)
            meterMachine(
                job,
                codec.fluidKey(stack),
                stack.getAmount(),
                action.simulate(),
                offered -> delegate.fill(stack.copyWithAmount((int) offered), action));
      }

      @Override
      public net.neoforged.neoforge.fluids.FluidStack drain(
          net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
        if (stack.isEmpty()) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        var got =
            new net.neoforged.neoforge.fluids.FluidStack[] {
              net.neoforged.neoforge.fluids.FluidStack.EMPTY
            };
        meterMachine(
            job,
            codec.fluidKey(stack),
            stack.getAmount(),
            action.simulate(),
            offered -> {
              got[0] = delegate.drain(stack.copyWithAmount((int) offered), action);
              return got[0].getAmount();
            });
        return got[0];
      }

      @Override
      public net.neoforged.neoforge.fluids.FluidStack drain(int amount, FluidAction action) {
        var present = delegate.drain(amount, FluidAction.SIMULATE);
        return present.isEmpty() ? present : drain(present, action);
      }
    };
  }

  private void checkThread() {
    Objects.requireNonNull(server);
    if (!server.isSameThread()) throw new IllegalStateException("Crafting requires server thread");
  }
}
