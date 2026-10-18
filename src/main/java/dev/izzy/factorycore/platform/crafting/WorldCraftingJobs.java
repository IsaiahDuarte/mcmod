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
    if (key.kind() != ResourceKind.ITEM) return new Preview(null, "unsupported");
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
    checkThread();
    var denied = denial(actor, origin);
    if (denied != null) return new Submission(null, null, denied);
    if (key.kind() != ResourceKind.ITEM) return new Submission(null, null, "unsupported");
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
            id, actor.id(), scope.network(), scope.segment(), origin, plan, reserved);
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
    var machine = holder == null ? null : holder.machine();
    var offer = machine == null ? null : findDispatched(job);
    if (offer != null) {
      var outputs = expected(offer.pattern());
      collect(job, outputs, machine, new PlatformResourceCodec(server.registryAccess()));
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
    job.crafted(inputs, outputs);
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

  private boolean leasedByOther(NodeRegistry.Position machine, UUID job) {
    for (var other : saved().craftingJobs().all())
      if (!other.id().equals(job)
          && other.dispatch() != null
          && other.dispatch().machine().equals(machine)) return true;
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
      var machine = holder.machine();
      if (machine == null) return "machine_missing";
      if (leasedByOther(holder.origin(), job.id())) return "busy";
      for (var key : outputs.keySet()) if (contains(machine, key, codec)) return "output_not_clean";
      for (var input : pattern.inputs().entrySet()) {
        long missing = input.getValue() - job.stagedAmount(input.getKey());
        if (missing > 0) pull(job, input.getKey(), missing);
        if (job.stagedAmount(input.getKey()) < input.getValue()) return "offline";
      }
      job.dispatch(holder.origin());
    }
    var holder = interfaceAt(job.dispatch().machine());
    var machine = holder == null ? null : holder.machine();
    if (machine == null) return "machine_missing";
    insert(job, pattern.inputs(), machine, codec);
    collect(job, outputs, machine, codec);
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

  private static void insert(
      CraftingJob job,
      Map<ResourceKey, Long> inputs,
      net.neoforged.neoforge.items.IItemHandler machine,
      PlatformResourceCodec codec) {
    for (var input : inputs.entrySet()) {
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
  private static void collect(
      CraftingJob job,
      Map<ResourceKey, Long> outputs,
      net.neoforged.neoforge.items.IItemHandler machine,
      PlatformResourceCodec codec) {
    for (var output : outputs.entrySet())
      for (int slot = 0; slot < machine.getSlots(); slot++) {
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
      var result = registry.extract(ownership.handle(), key, Math.min(held, amount), job.id());
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
        var result = registry.insert(cell.handle(), entry.getKey(), remaining);
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

  private void checkThread() {
    Objects.requireNonNull(server);
    if (!server.isSameThread()) throw new IllegalStateException("Crafting requires server thread");
  }
}
