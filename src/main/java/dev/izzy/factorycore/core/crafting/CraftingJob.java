package dev.izzy.factorycore.core.crafting;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Persisted autocrafting job owned by one requester. The job is the reservation owner in every
 * ledger it reserved from; {@code reserved} records the remaining reserved units per backing so
 * cancellation releases exactly what it holds. {@code staged} holds job-owned units that left
 * storage (inputs pulled for a craft, intermediates, outputs, remainders) until delivered.
 *
 * <p>Transitions: RUNNING ⇄ WAITING (with a reason) → DELIVERING once every step is done → removed
 * when staging and reservations are empty. Cancel moves RUNNING/WAITING to CANCELLING, which
 * releases reservations and then delivers staging; consumed ingredients are not restored.
 *
 * <p>A processing step runs through one {@link Dispatch} at a machine interface: staged inputs
 * leave the job when inserted into the machine, and expected outputs re-enter staging only when
 * collected from that leased machine. The dispatch is the persisted port lease. After cancel the
 * dispatch remains as a bounded cleanup record until its outputs are collected or it is abandoned.
 */
public final class CraftingJob {
  public static final int MAX_STEPS = CraftingPlanner.MAX_STEPS;
  public static final int MAX_STAGED_KEYS = 64;
  public static final int MAX_RESERVATIONS = 256;

  public enum State {
    RUNNING,
    WAITING,
    DELIVERING,
    CANCELLING
  }

  /**
   * One aggregated plan step with exact progress. {@code uses} (E1) is what the remaining crafts
   * may still use for each substituting input: concrete key to units, spent as crafts consume them.
   */
  public record Step(
      UUID pattern,
      long version,
      ResourceKey output,
      long crafts,
      long done,
      Map<ResourceKey, Map<ResourceKey, Long>> uses) {
    public Step(UUID pattern, long version, ResourceKey output, long crafts, long done) {
      this(pattern, version, output, crafts, done, Map.of());
    }

    public Step {
      Objects.requireNonNull(pattern);
      Objects.requireNonNull(output);
      if (version < 1 || crafts < 1 || done < 0 || done > crafts)
        throw new IllegalArgumentException("Invalid job step");
      var copy = new LinkedHashMap<ResourceKey, Map<ResourceKey, Long>>();
      var seen = new HashSet<ResourceKey>();
      for (var entry : uses.entrySet()) {
        var amounts = new LinkedHashMap<ResourceKey, Long>();
        for (var use : entry.getValue().entrySet()) {
          if (use.getValue() == null || use.getValue() < 1 || !seen.add(use.getKey()))
            throw new IllegalArgumentException("Invalid substitute allocation");
          amounts.put(use.getKey(), use.getValue());
        }
        copy.put(Objects.requireNonNull(entry.getKey()), Collections.unmodifiableMap(amounts));
      }
      uses = Collections.unmodifiableMap(copy);
    }

    /** This step with one more craft done and {@code spent} concrete units removed from uses. */
    Step advanced(Map<ResourceKey, Long> spent) {
      var next = new LinkedHashMap<ResourceKey, Map<ResourceKey, Long>>();
      for (var entry : uses.entrySet()) {
        var left = new LinkedHashMap<>(entry.getValue());
        for (var use : spent.entrySet()) {
          var held = left.get(use.getKey());
          if (held == null) continue;
          if (use.getValue() > held)
            throw new IllegalArgumentException("Craft uses more than its allocation");
          if (held.equals(use.getValue())) left.remove(use.getKey());
          else left.put(use.getKey(), held - use.getValue());
        }
        if (!left.isEmpty()) next.put(entry.getKey(), left);
      }
      return new Step(pattern, version, output, crafts, done + 1, next);
    }
  }

  /** Active processing craft at one interface: exact units inserted and collected so far. */
  public record Dispatch(
      NodeRegistry.Position machine,
      NodeRegistry.Position target,
      Map<ResourceKind, UUID> providers,
      Map<ResourceKey, Long> inserted,
      Map<ResourceKey, Long> collected) {
    /** Legacy dispatched saves lack a proven physical target and remain fenced until cancelled. */
    public Dispatch(
        NodeRegistry.Position machine,
        Map<ResourceKey, Long> inserted,
        Map<ResourceKey, Long> collected) {
      this(machine, null, Map.of(), inserted, collected);
    }

    /** Target-only schema-four dispatches still lack proven provider identity. */
    public Dispatch(
        NodeRegistry.Position machine,
        NodeRegistry.Position target,
        Map<ResourceKey, Long> inserted,
        Map<ResourceKey, Long> collected) {
      this(machine, target, Map.of(), inserted, collected);
    }

    public Dispatch {
      Objects.requireNonNull(machine);
      if (target != null
          && (!machine.dimension().equals(target.dimension()) || machine.equals(target)))
        throw new IllegalArgumentException("Invalid processing target");
      providers = Map.copyOf(providers);
      if (!providers.isEmpty()
          && (target == null || !providers.keySet().equals(Set.of(ResourceKind.values()))))
        throw new IllegalArgumentException("Incomplete processing provider identity");
      inserted = Snapshot.positive(inserted, "inserted");
      collected = Snapshot.positive(collected, "collected");
      if (inserted.size() > Pattern.MAX_INPUT_KEYS
          || collected.size() > Pattern.MAX_BYPRODUCT_KEYS + 1)
        throw new IllegalArgumentException("Too many dispatch keys");
    }
  }

  /** Immutable persisted form; validated on construction. */
  public record Snapshot(
      UUID id,
      UUID principal,
      UUID network,
      UUID segment,
      NodeRegistry.Position origin,
      ResourceKey request,
      long amount,
      List<Step> steps,
      Map<UUID, Map<ResourceKey, Long>> reserved,
      Map<ResourceKey, Long> staged,
      State state,
      String reason,
      Dispatch dispatch,
      UUID rule) {
    /** A manual job snapshot with no active processing dispatch. */
    public Snapshot(
        UUID id,
        UUID principal,
        UUID network,
        UUID segment,
        NodeRegistry.Position origin,
        ResourceKey request,
        long amount,
        List<Step> steps,
        Map<UUID, Map<ResourceKey, Long>> reserved,
        Map<ResourceKey, Long> staged,
        State state,
        String reason) {
      this(
          id, principal, network, segment, origin, request, amount, steps, reserved, staged, state,
          reason, null, null);
    }

    public Snapshot {
      Objects.requireNonNull(id);
      Objects.requireNonNull(principal);
      Objects.requireNonNull(network);
      Objects.requireNonNull(segment);
      Objects.requireNonNull(origin);
      Objects.requireNonNull(request);
      Objects.requireNonNull(state);
      Objects.requireNonNull(reason);
      if (amount < 1 || reason.length() > 64) throw new IllegalArgumentException("Invalid job");
      if (steps.isEmpty() || steps.size() > MAX_STEPS)
        throw new IllegalArgumentException("Invalid job step count");
      steps = List.copyOf(steps);
      var cells = new TreeMap<UUID, Map<ResourceKey, Long>>();
      int entries = 0;
      for (var cell : reserved.entrySet()) {
        var keys = positive(cell.getValue(), "reservation");
        entries += keys.size();
        if (!keys.isEmpty()) cells.put(Objects.requireNonNull(cell.getKey()), keys);
      }
      if (entries > MAX_RESERVATIONS) throw new IllegalArgumentException("Too many reservations");
      reserved = Collections.unmodifiableMap(cells);
      staged = positive(staged, "staged");
      if (staged.size() > MAX_STAGED_KEYS)
        throw new IllegalArgumentException("Too many staged keys");
    }

    private static Map<ResourceKey, Long> positive(Map<ResourceKey, Long> values, String what) {
      var copy = new LinkedHashMap<ResourceKey, Long>();
      for (var entry : values.entrySet()) {
        if (entry.getValue() == null || entry.getValue() < 1)
          throw new IllegalArgumentException("Job " + what + " amounts must be positive");
        copy.put(Objects.requireNonNull(entry.getKey()), entry.getValue());
      }
      return Collections.unmodifiableMap(copy);
    }
  }

  private final UUID id, principal, network, segment;
  private final NodeRegistry.Position origin;
  private final ResourceKey request;
  private final long amount;
  private final List<Step> steps;
  private final Map<UUID, Map<ResourceKey, Long>> reserved = new TreeMap<>();
  private final Map<ResourceKey, Long> staged = new LinkedHashMap<>();
  private State state;
  private String reason;
  private Dispatch dispatch;
  private final UUID rule;

  public CraftingJob(Snapshot saved) {
    id = saved.id();
    principal = saved.principal();
    network = saved.network();
    segment = saved.segment();
    origin = saved.origin();
    request = saved.request();
    amount = saved.amount();
    steps = new ArrayList<>(saved.steps());
    for (var cell : saved.reserved().entrySet())
      reserved.put(cell.getKey(), new LinkedHashMap<>(cell.getValue()));
    staged.putAll(saved.staged());
    state = saved.state();
    reason = saved.reason();
    dispatch = saved.dispatch();
    rule = saved.rule();
  }

  /** New running job from an OK plan whose consumed stock was reserved as {@code reserved}. */
  public static CraftingJob accepted(
      UUID id,
      UUID principal,
      UUID network,
      UUID segment,
      NodeRegistry.Position origin,
      CraftingPlanner.Plan plan,
      Map<UUID, Map<ResourceKey, Long>> reserved) {
    return accepted(id, principal, network, segment, origin, plan, reserved, null);
  }

  /** As above, started by keep-stock {@code rule} (null for a manual request). */
  public static CraftingJob accepted(
      UUID id,
      UUID principal,
      UUID network,
      UUID segment,
      NodeRegistry.Position origin,
      CraftingPlanner.Plan plan,
      Map<UUID, Map<ResourceKey, Long>> reserved,
      UUID rule) {
    if (plan.status() != CraftingPlanner.Status.OK)
      throw new IllegalArgumentException("Only complete plans become jobs");
    var steps = new ArrayList<Step>();
    for (var step : plan.steps())
      steps.add(
          new Step(step.pattern(), step.version(), step.output(), step.crafts(), 0, step.uses()));
    var totals = new LinkedHashMap<ResourceKey, Long>();
    for (var cell : reserved.values())
      for (var entry : cell.entrySet())
        totals.merge(entry.getKey(), entry.getValue(), Math::addExact);
    if (!totals.equals(plan.consumed()))
      throw new IllegalArgumentException("Reservations must equal the plan's consumed stock");
    return new CraftingJob(
        new Snapshot(
            id,
            principal,
            network,
            segment,
            origin,
            plan.request(),
            plan.amount(),
            steps,
            reserved,
            Map.of(),
            State.RUNNING,
            "",
            null,
            rule));
  }

  public Snapshot snapshot() {
    return new Snapshot(
        id, principal, network, segment, origin, request, amount, steps, reserved, staged, state,
        reason, dispatch, rule);
  }

  /** The keep-stock rule that started this job, or null for a manual request. */
  public UUID rule() {
    return rule;
  }

  public UUID id() {
    return id;
  }

  public UUID principal() {
    return principal;
  }

  public UUID network() {
    return network;
  }

  public UUID segment() {
    return segment;
  }

  public NodeRegistry.Position origin() {
    return origin;
  }

  public ResourceKey request() {
    return request;
  }

  public long amount() {
    return amount;
  }

  public State state() {
    return state;
  }

  public String reason() {
    return reason;
  }

  public List<Step> steps() {
    return List.copyOf(steps);
  }

  public Map<ResourceKey, Long> staged() {
    return Collections.unmodifiableMap(staged);
  }

  public Map<UUID, Map<ResourceKey, Long>> reserved() {
    var copy = new TreeMap<UUID, Map<ResourceKey, Long>>();
    for (var cell : reserved.entrySet())
      copy.put(cell.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(cell.getValue())));
    return Collections.unmodifiableMap(copy);
  }

  /** Index of the first unfinished step, or -1 when every step is done. */
  public int current() {
    for (int index = 0; index < steps.size(); index++)
      if (steps.get(index).done() < steps.get(index).crafts()) return index;
    return -1;
  }

  public long craftsDone() {
    long done = 0;
    for (var step : steps) done += step.done();
    return done;
  }

  public long craftsTotal() {
    long total = 0;
    for (var step : steps) total += step.crafts();
    return total;
  }

  public long stagedAmount(ResourceKey key) {
    return staged.getOrDefault(key, 0L);
  }

  /** Units of {@code key} still reserved for this job across all backings. */
  public long reservedAmount(ResourceKey key) {
    long total = 0;
    for (var cell : reserved.values()) total += cell.getOrDefault(key, 0L);
    return total;
  }

  /** Records {@code amount} reserved units of {@code key} at {@code backing} moved into staging. */
  public void pulled(UUID backing, ResourceKey key, long amount) {
    var cell = reserved.get(backing);
    long held = cell == null ? 0 : cell.getOrDefault(key, 0L);
    if (amount < 1 || amount > held) throw new IllegalArgumentException("Pull exceeds reservation");
    if (held == amount) cell.remove(key);
    else cell.put(key, held - amount);
    if (cell.isEmpty()) reserved.remove(backing);
    stage(key, amount);
  }

  /** Records that a backing's reservation for {@code key} was released. */
  public void released(UUID backing, ResourceKey key) {
    var cell = reserved.get(backing);
    if (cell == null || cell.remove(key) == null)
      throw new IllegalArgumentException("No such reservation");
    if (cell.isEmpty()) reserved.remove(backing);
  }

  /** Completes one craft of the current step: consumes staged inputs and stages outputs. */
  public void crafted(Map<ResourceKey, Long> inputs, Map<ResourceKey, Long> outputs) {
    if (state != State.RUNNING || dispatch != null)
      throw new IllegalStateException("Job is not running a direct craft");
    int index = current();
    if (index < 0) throw new IllegalStateException("No unfinished step");
    for (var input : inputs.entrySet())
      if (stagedAmount(input.getKey()) < input.getValue())
        throw new IllegalArgumentException("Inputs are not staged");
    var keys = new HashSet<>(staged.keySet());
    keys.addAll(outputs.keySet());
    for (var input : inputs.entrySet())
      if (stagedAmount(input.getKey()) == input.getValue()) keys.remove(input.getKey());
    if (keys.size() > MAX_STAGED_KEYS) throw new IllegalStateException("Too many staged keys");
    for (var input : inputs.entrySet()) unstage(input.getKey(), input.getValue());
    for (var output : outputs.entrySet()) stage(output.getKey(), output.getValue());
    steps.set(index, steps.get(index).advanced(inputs));
    if (current() < 0) {
      state = State.DELIVERING;
      reason = "";
    }
  }

  public Dispatch dispatch() {
    return dispatch;
  }

  /** Leases a machine for the current processing craft; only one dispatch exists at a time. */
  public void dispatch(NodeRegistry.Position machine) {
    dispatch(machine, null);
  }

  /** Persist the exact physical target before inserting the first input. */
  public void dispatch(NodeRegistry.Position machine, NodeRegistry.Position target) {
    dispatch(machine, target, Map.of());
  }

  public void dispatch(
      NodeRegistry.Position machine,
      NodeRegistry.Position target,
      Map<ResourceKind, UUID> providers) {
    if (state != State.RUNNING || dispatch != null || current() < 0)
      throw new IllegalStateException("Cannot dispatch now");
    dispatch = new Dispatch(machine, target, providers, Map.of(), Map.of());
  }

  /** Staged units accepted by the dispatched machine; they now belong to the external process. */
  public void inserted(ResourceKey key, long amount) {
    if (dispatch == null) throw new IllegalStateException("No dispatch");
    unstage(key, amount);
    var map = new LinkedHashMap<>(dispatch.inserted());
    map.merge(key, amount, Math::addExact);
    dispatch =
        new Dispatch(
            dispatch.machine(), dispatch.target(), dispatch.providers(), map, dispatch.collected());
  }

  /** Units extracted from the dispatched machine as expected outputs; they become job staging. */
  public void collected(ResourceKey key, long amount) {
    if (dispatch == null) throw new IllegalStateException("No dispatch");
    stage(key, amount);
    var map = new LinkedHashMap<>(dispatch.collected());
    map.merge(key, amount, Math::addExact);
    dispatch =
        new Dispatch(
            dispatch.machine(), dispatch.target(), dispatch.providers(), dispatch.inserted(), map);
  }

  /** Completes the dispatched craft once every input is inserted and every output collected. */
  public void processed(Map<ResourceKey, Long> inputs, Map<ResourceKey, Long> outputs) {
    if (state != State.RUNNING || dispatch == null) throw new IllegalStateException("No dispatch");
    for (var input : inputs.entrySet())
      if (dispatch.inserted().getOrDefault(input.getKey(), 0L) < input.getValue())
        throw new IllegalArgumentException("Inputs not inserted");
    for (var output : outputs.entrySet())
      if (dispatch.collected().getOrDefault(output.getKey(), 0L) < output.getValue())
        throw new IllegalArgumentException("Outputs not collected");
    dispatch = null;
    int index = current();
    steps.set(index, steps.get(index).advanced(Map.of()));
    if (current() < 0) {
      state = State.DELIVERING;
      reason = "";
    }
  }

  /**
   * Ends a cancelled job's cleanup record. Outputs not yet collected stay in the machine; the job
   * makes no claim on them.
   */
  public void abandonDispatch() {
    if (state != State.CANCELLING) throw new IllegalStateException("Only after cancel");
    dispatch = null;
  }

  /** Records {@code amount} staged units of {@code key} delivered into storage. */
  public void delivered(ResourceKey key, long amount) {
    if (state != State.DELIVERING && state != State.CANCELLING)
      throw new IllegalStateException("Job is not delivering");
    unstage(key, amount);
  }

  public void waiting(String why) {
    if (state == State.RUNNING || state == State.WAITING) {
      state = State.WAITING;
      reason = Objects.requireNonNull(why);
    } else reason = Objects.requireNonNull(why);
  }

  public void resume() {
    if (state == State.WAITING) state = State.RUNNING;
    reason = "";
  }

  public void cancel() {
    if (state == State.RUNNING || state == State.WAITING) {
      state = State.CANCELLING;
      reason = "";
    }
  }

  /** True once nothing remains reserved or staged after delivery or cancellation. */
  public boolean finished() {
    return (state == State.DELIVERING || state == State.CANCELLING)
        && staged.isEmpty()
        && reserved.isEmpty()
        && dispatch == null;
  }

  private void stage(ResourceKey key, long amount) {
    if (!staged.containsKey(key) && staged.size() >= MAX_STAGED_KEYS)
      throw new IllegalStateException("Too many staged keys");
    staged.merge(key, amount, Math::addExact);
  }

  private void unstage(ResourceKey key, long amount) {
    long held = stagedAmount(key);
    if (amount < 1 || amount > held) throw new IllegalArgumentException("Unstage exceeds staging");
    if (held == amount) staged.remove(key);
    else staged.put(key, held - amount);
  }
}
