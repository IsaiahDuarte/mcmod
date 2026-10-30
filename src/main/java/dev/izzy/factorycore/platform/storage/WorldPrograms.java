package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.core.Failures;
import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.program.FriendlyCompiler;
import dev.izzy.factorycore.core.program.HostBudget;
import dev.izzy.factorycore.core.program.ProgramCommand;
import dev.izzy.factorycore.core.program.ProgramEvaluator;
import dev.izzy.factorycore.core.program.ProgramIr;
import dev.izzy.factorycore.core.program.ProgramPreview;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.core.wasm.WasmProgram;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.ServerScoped;
import dev.izzy.factorycore.platform.ServerThread;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.program.ProgramMenu;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server-thread Program Controller runtime. Each due rule is one shared-scheduler task under the
 * deployment; it re-checks the deployer's DEPLOY grant and FE, evaluates the rule against a
 * read-only host, and publishes the staged commands to a bounded per-controller queue (a newer
 * invocation of the same rule replaces its unstarted commands). Commands run one at a time through
 * the same catalog, connector, route and crafting paths as presets and terminals.
 */
public final class WorldPrograms {
  /**
   * How a running controller's last rule went, shown as {@code
   * gui.factorycore.program.status.<key>}.
   */
  public enum RuntimeStatus {
    RUNNING,
    /** Ran, but at least {@code DELAY_TICKS} late. */
    DELAYED,
    THROTTLED,
    NO_POWER,
    OFFLINE;

    public String key() {
      return EnumKeys.key(this);
    }
  }

  public static final int TASK_COST = 16;
  public static final long BASE_COST = 4;
  public static final int STEPS_PER_FE = 256;
  public static final int MAX_QUEUE = 64;
  public static final int MAX_STOCK_PAGES = 64;

  /** A rule invocation this many ticks after its due tick reports {@code delayed}. */
  public static final int DELAY_TICKS = 20;

  /** Same page bound as preset discovery; pages also visit devices outside this scope. */
  public static final int MAX_CATALOG_PAGES = WorldStorageSearch.MAX_PAGES;

  private static final ServerScoped<WorldPrograms> SERVERS = ServerScoped.of(WorldPrograms::new);

  private record Pending(String rule, ProgramCommand command) {}

  /** A scheduled rule of either deployment kind; {@code index} addresses a Wasm callback. */
  private record RuleRef(String key, int interval, int index, ProgramIr.Rule friendly) {}

  private static List<RuleRef> rules(ProgramControllerEntity controller) {
    var refs = new ArrayList<RuleRef>();
    if (controller.wasmDeployment()) {
      var wasm = controller.wasm();
      if (wasm == null) return refs;
      var specs = wasm.rules();
      for (int index = 0; index < specs.size(); index++)
        refs.add(new RuleRef(specs.get(index).key(), specs.get(index).interval(), index, null));
    } else if (controller.program() != null)
      for (var rule : controller.program().rules())
        refs.add(new RuleRef(rule.key(), rule.interval(), -1, rule));
    return refs;
  }

  /** Transient runtime state of one loaded controller; persisted state lives in the entity. */
  private static final class Runtime {
    final Map<String, Long> due = new HashMap<>();
    final ArrayDeque<Pending> queue = new ArrayDeque<>();

    /** Tick each rule's queued invocation became due; used to expose scheduling latency. */
    final Map<String, Long> scheduled = new HashMap<>();

    UUID deployment, request;
    RuntimeStatus status;

    /** A storage search saw matching stock that crafting reservations fully held. */
    boolean reservedSeen;

    long lag;
  }

  private final MinecraftServer server;
  private final Map<ProgramControllerEntity, Runtime> controllers = new LinkedHashMap<>();

  private WorldPrograms(MinecraftServer server) {
    this.server = server;
  }

  public static WorldPrograms get(MinecraftServer server) {
    ServerThread.require(server, "Programs");
    return SERVERS.get(server);
  }

  public static boolean exists(MinecraftServer server) {
    return SERVERS.peek(server) != null;
  }

  public static void postTick(ServerTickEvent.Post event) {
    if (DeviceSavedData.get(event.getServer()).diagnostic().isEmpty())
      get(event.getServer()).tick();
  }

  public void loaded(ProgramControllerEntity controller) {
    controllers.putIfAbsent(controller, new Runtime());
  }

  public void removed(ProgramControllerEntity controller) {
    controllers.remove(controller);
  }

  /** Opens the editor for an authorized viewer; false when no screen applies. */
  public boolean open(ServerPlayer player, ProgramControllerEntity controller) {
    return ProgramMenu.open(player, controller);
  }

  private WorldNetworks networks() {
    return WorldNetworks.get(server);
  }

  private DeviceSavedData saved() {
    return DeviceSavedData.get(server);
  }

  private static NetworkPermissions.Actor deployer(ProgramControllerEntity controller) {
    return new NetworkPermissions.Actor(controller.deployer(), false);
  }

  private boolean allowed(NetworkPermissions.Actor actor, ProgramControllerEntity controller) {
    return controller.current()
        && networks().membership(controller.origin()).status() == NetworkTopology.Status.VALID
        && networks().allows(actor, controller.origin(), NetworkPermissions.Permission.DEPLOY);
  }

  /** Compiles the draft and deploys it under {@code actor}; returns diagnostics on failure. */
  public List<FriendlyCompiler.Diagnostic> deploy(
      NetworkPermissions.Actor actor, ProgramControllerEntity controller) {
    if (!allowed(actor, controller))
      return List.of(new FriendlyCompiler.Diagnostic(1, 1, "You need Deploy permission here"));
    var result = FriendlyCompiler.compile(controller.draft());
    if (!result.ok()) return result.diagnostics();
    releaseSignals(controller);
    controller.deploy(controller.draft(), result.program(), actor.id());
    var runtime = controllers.computeIfAbsent(controller, c -> new Runtime());
    runtime.due.clear();
    runtime.scheduled.clear();
    runtime.queue.clear();
    runtime.deployment = controller.deployment();
    return List.of();
  }

  /**
   * Validates, instantiates and initializes a Wasm artifact and deploys it under {@code actor};
   * returns a diagnostic and changes nothing on failure.
   */
  public List<FriendlyCompiler.Diagnostic> deployWasm(
      NetworkPermissions.Actor actor, ProgramControllerEntity controller, byte[] artifact) {
    if (!allowed(actor, controller))
      return List.of(new FriendlyCompiler.Diagnostic(1, 1, "You need Deploy permission here"));
    WasmProgram instance;
    try {
      instance = new WasmProgram(artifact, System::nanoTime);
    } catch (RuntimeException failure) {
      var message = Failures.message(failure);
      return List.of(new FriendlyCompiler.Diagnostic(1, 1, "Wasm rejected: " + message));
    }
    releaseSignals(controller);
    controller.deployWasm(artifact, instance, actor.id());
    var runtime = controllers.computeIfAbsent(controller, c -> new Runtime());
    runtime.due.clear();
    runtime.scheduled.clear();
    runtime.queue.clear();
    runtime.deployment = controller.deployment();
    return List.of();
  }

  /** Pause, resume or stop; requires DEPLOY at the controller. Started transfers finish. */
  public boolean control(
      NetworkPermissions.Actor actor,
      ProgramControllerEntity controller,
      ProgramControllerEntity.State next) {
    if (!allowed(actor, controller) || !controller.deployed()) return false;
    var runtime = controllers.computeIfAbsent(controller, c -> new Runtime());
    runtime.queue.clear();
    if (next != ProgramControllerEntity.State.RUNNING) releaseSignals(controller);
    if (next == ProgramControllerEntity.State.STOPPED) controller.undeploy();
    else if (next == ProgramControllerEntity.State.RUNNING
        && controller.wasmDeployment()
        && (controller.wasm() == null || controller.wasm().failed())) {
      // A trapped guest never resumes mid-stack: resuming starts a fresh, re-initialized instance.
      var failure = controller.freshWasm();
      controller.state(failure.isEmpty() ? next : ProgramControllerEntity.State.TRAPPED, failure);
      if (!failure.isEmpty()) return false;
    } else controller.state(next, "");
    if (next == ProgramControllerEntity.State.RUNNING) runtime.due.clear();
    runtime.scheduled.clear();
    return true;
  }

  /** The controller's runtime status, or null before any rule has run. */
  public RuntimeStatus status(ProgramControllerEntity controller) {
    var runtime = controllers.get(controller);
    return runtime == null ? null : runtime.status;
  }

  public void tick() {
    ServerThread.require(server, "Programs");
    long now = server.getTickCount();
    var scheduler = networks().scheduler();
    for (var entry : new ArrayList<>(controllers.entrySet())) {
      var controller = entry.getKey();
      var runtime = entry.getValue();
      if (!controller.current()) {
        controllers.remove(controller);
        continue;
      }
      if (controller.state() != ProgramControllerEntity.State.RUNNING || !controller.deployed())
        continue;
      if (!controller.deployment().equals(runtime.deployment)) {
        runtime.deployment = controller.deployment();
        runtime.due.clear();
        runtime.scheduled.clear();
        runtime.queue.clear();
      }
      var member = networks().membership(controller.origin());
      if (member.status() != NetworkTopology.Status.VALID) {
        runtime.status = RuntimeStatus.OFFLINE;
        continue;
      }
      for (var rule : rules(controller)) {
        if (runtime.due.getOrDefault(rule.key(), Long.MIN_VALUE) > now) continue;
        var key = "rule:" + rule.key();
        // An overdue rule runs once: missed invocations coalesce into the next due time.
        runtime.due.put(rule.key(), now + rule.interval());
        if (scheduler.isQueued(member.scope().network(), controller.deployment(), key)) continue;
        var deployment = controller.deployment();
        var admission =
            scheduler.submit(
                member.scope().network(),
                deployment,
                key,
                TASK_COST,
                () -> invoke(controller, deployment, rule));
        if (admission == FairScheduler.Admission.ACCEPTED
            || admission == FairScheduler.Admission.REPLACED)
          runtime.scheduled.putIfAbsent(rule.key(), now);
        else throttled(controller, runtime, rule.key(), admission);
      }
      if (runtime.request == null && !runtime.queue.isEmpty())
        execute(controller, runtime, runtime.queue.poll());
    }
  }

  /**
   * The shared scheduler refused this invocation (world overload or a quota); it is coalesced into
   * the rule's next due tick. The transition is logged once, not on every refusal.
   */
  private static void throttled(
      ProgramControllerEntity controller,
      Runtime runtime,
      String rule,
      FairScheduler.Admission admission) {
    if (runtime.status != RuntimeStatus.THROTTLED)
      controller.log(
          "rule "
              + rule
              + " throttled: server work "
              + admission.name().toLowerCase(Locale.ROOT)
              + "; retrying at its next interval");
    runtime.status = RuntimeStatus.THROTTLED;
  }

  /** Latest scheduling latency of this controller's rule invocations, in ticks. */
  public long lag(ProgramControllerEntity controller) {
    var runtime = controllers.get(controller);
    return runtime == null ? 0 : runtime.lag;
  }

  /** One rule invocation: authority, power, bounded evaluation, then publication. */
  void invoke(ProgramControllerEntity controller, UUID deployment, RuleRef rule) {
    var runtime = controllers.get(controller);
    if (runtime == null
        || controller.state() != ProgramControllerEntity.State.RUNNING
        || !deployment.equals(controller.deployment())) return;
    Long dueAt = runtime.scheduled.remove(rule.key());
    long lag = dueAt == null ? 0 : Math.max(0, server.getTickCount() - dueAt);
    var actor = deployer(controller);
    // A network that is offline or rebuilding (for example after a nearby chunk loads) says
    // nothing about the deployer's grants: skip this run and retry at the next interval.
    if (!controller.current()
        || networks().membership(controller.origin()).status() != NetworkTopology.Status.VALID) {
      runtime.status = RuntimeStatus.OFFLINE;
      return;
    }
    if (!allowed(actor, controller)) {
      controller.state(ProgramControllerEntity.State.PAUSED, "Deployer lost Deploy permission");
      releaseSignals(controller);
      runtime.queue.clear();
      return;
    }
    boolean guest = rule.friendly() == null;
    long worst =
        BASE_COST
            + (guest ? WasmProgram.RULE_INSTRUCTIONS : ProgramEvaluator.MAX_STEPS) / STEPS_PER_FE;
    if (controller.energy() < worst) {
      runtime.status = RuntimeStatus.NO_POWER;
      return;
    }
    List<ProgramCommand> commands;
    int work;
    try {
      if (guest) {
        var instance = controller.wasm();
        if (instance == null || instance.failed())
          throw new ProgramEvaluator.Trap("guest instance unavailable");
        var outcome = instance.invoke(rule.index(), host(controller, actor, new HostBudget()));
        commands = outcome.commands();
        work = outcome.instructions();
        if (outcome.stateChanged()) controller.guestState(instance.state());
      } else {
        var outcome =
            ProgramEvaluator.evaluate(rule.friendly(), host(controller, actor, new HostBudget()));
        commands = outcome.commands();
        work = outcome.steps();
      }
    } catch (ProgramEvaluator.Trap trap) {
      controller.spend(BASE_COST);
      controller.state(
          ProgramControllerEntity.State.TRAPPED, "Rule " + rule.key() + ": " + trap.getMessage());
      controller.log("trap " + rule.key() + ": " + trap.getMessage());
      releaseSignals(controller);
      runtime.queue.clear();
      return;
    }
    controller.spend(BASE_COST + (work + STEPS_PER_FE - 1) / STEPS_PER_FE);
    runtime.lag = lag;
    if (lag >= DELAY_TICKS) {
      if (runtime.status != RuntimeStatus.DELAYED)
        controller.log("rule " + rule.key() + " ran " + lag + " ticks late: server busy");
      runtime.status = RuntimeStatus.DELAYED;
    } else runtime.status = RuntimeStatus.RUNNING;
    runtime.queue.removeIf(pending -> pending.rule().equals(rule.key()));
    var lines = new ArrayList<String>();
    for (var command : commands)
      if (command instanceof ProgramCommand.Log log) lines.add(log.text());
    controller.logInvocation(rule.key(), lines);
    for (var command : commands) {
      if (command instanceof ProgramCommand.Log) continue;
      if (runtime.queue.size() == MAX_QUEUE) {
        controller.log(rule.key() + ": command queue full; later commands dropped");
        break;
      }
      runtime.queue.add(new Pending(rule.key(), command));
    }
  }

  /** Evaluates one saved native draft rule against the viewer's current read-only scope. */
  public ProgramPreview.Result preview(
      NetworkPermissions.Actor actor, ProgramControllerEntity controller, int rule) {
    if (!networks().allows(actor, controller.origin(), NetworkPermissions.Permission.VIEW))
      return new ProgramPreview.Result(0, 0, List.of("View permission required"));
    var compiled = FriendlyCompiler.compile(controller.draft());
    if (!compiled.ok())
      return new ProgramPreview.Result(
          0, 0, List.of("Fix compiler diagnostics before previewing the saved draft"));
    return ProgramPreview.evaluate(
        compiled.program(), rule, host(controller, actor, new HostBudget()));
  }

  /** Read-only host whose queries are charged to {@code budget} (one per invocation). */
  private ProgramEvaluator.Host host(
      ProgramControllerEntity controller, NetworkPermissions.Actor actor, HostBudget budget) {
    return new ProgramEvaluator.Host() {
      @Override
      public List<UUID> machines(String label) {
        var query =
            networks()
                .machines(
                    actor,
                    controller.origin(),
                    label,
                    null,
                    ProgramEvaluator.MAX_MACHINES_PER_LABEL);
        budget.charge(1 + query.machines().size() / 16, "machines(\"" + label + "\")");
        if (query.decision() != NetworkAuthority.Decision.ALLOWED) return List.of();
        if (query.more()) throw new IllegalStateException("more than 64 machines labeled " + label);
        return query.machines().stream().map(NetworkTopology.Scope::node).toList();
      }

      @Override
      public long stock(ResourceFilter filter) {
        if (!networks().allows(actor, controller.origin(), NetworkPermissions.Permission.VIEW))
          throw new IllegalStateException("reading storage needs View permission");
        return storageStock(controller, actor, filter, budget);
      }

      /** D6: whether a loaded Machine Interface of this segment receives redstone power. */
      @Override
      public boolean powered(UUID node) {
        var owner = machine(node, controller);
        if (owner == null)
          throw new IllegalStateException("a machine is unloaded or outside this segment");
        budget.charge(1, "redstone on " + owner.label());
        return owner.serverLevel().hasNeighborSignal(owner.getBlockPos());
      }

      /** D4: one loaded machine of this segment, through its binding, at most 16 pages. */
      @Override
      public ProgramEvaluator.Contents contents(UUID node, ResourceFilter filter) {
        var owner = machine(node, controller);
        if (owner == null)
          throw new IllegalStateException("a machine is unloaded or outside this segment");
        var registration = networks().connectors().bind(owner);
        if (registration.handle() == null)
          throw new IllegalStateException(owner.label() + ": " + unbound(registration));
        if (registration.handle().kind() != filter.kind())
          return new ProgramEvaluator.Contents(0, 1);
        long total = 0;
        int pages = 0, cursor = 0;
        do {
          budget.charge(1, "has on " + owner.label());
          var observation =
              networks()
                  .connectors()
                  .observe(actor, owner.position(), registration.handle(), cursor);
          if (observation.reason() != OperationResult.Reason.OK)
            throw new IllegalStateException(owner.label() + ": " + name(observation.reason()));
          for (var entry : observation.page().entries())
            if (filter.matches(entry.key(), WorldPrograms.this::tagged))
              total = Math.min(Long.MAX_VALUE - entry.stored(), total) + entry.stored();
          cursor = observation.page().next();
          pages++;
        } while (cursor >= 0 && pages < MAX_CONTENT_PAGES);
        if (cursor >= 0)
          throw new IllegalStateException(
              owner.label() + " has more than " + MAX_CONTENT_PAGES + " pages to count");
        return new ProgramEvaluator.Contents(total, pages);
      }
    };
  }

  /** Observation pages one {@code has} read may take from a single machine. */
  private static final int MAX_CONTENT_PAGES = 16;

  /** Available matching units in digital cells of the controller's VIEW storage, bounded. */
  private long storageStock(
      ProgramControllerEntity controller,
      NetworkPermissions.Actor actor,
      ResourceFilter filter,
      HostBudget budget) {
    var registry = saved().registry();
    var transfers = networks().transfers();
    var probe = new ResourceKey(filter.kind(), "factorycore:catalog", new byte[0]);
    long total = 0;
    int pages = 0;
    UUID after = null;
    do {
      var page = registry.handles(after, 16);
      for (var handle : page.handles()) {
        // Conflicted, stale or portable ledgers are not countable storage.
        if (registry.validate(handle) != DeviceRegistry.Failure.OK
            || registry.definition(handle).location() == null
            || registry.definition(handle).kind() != filter.kind()
            || !transfers.digitalAllowed(
                actor, controller.origin(), handle, probe, NetworkPermissions.Permission.VIEW))
          continue;
        ResourceKey cursor = null;
        do {
          if (++pages > MAX_STOCK_PAGES)
            throw new IllegalStateException("storage too large to count");
          budget.charge(1, "stock queries");
          var entries = registry.catalog(handle, cursor, 16);
          for (var entry : entries.entries())
            if (filter.matches(entry.key(), this::tagged))
              total = Math.addExact(total, entry.stock().available());
          cursor = entries.next();
        } while (cursor != null);
      }
      after = page.next();
    } while (after != null);
    return total;
  }

  private boolean tagged(ResourceKind kind, String tag, ResourceKey key) {
    if (kind != ResourceKind.ITEM) return false;
    try {
      var stack = new PlatformResourceCodec(server.registryAccess()).itemStack(key, 1);
      return stack.is(TagKey.create(Registries.ITEM, ResourceLocation.parse(tag)));
    } catch (IllegalArgumentException unsupported) {
      return false;
    }
  }

  private MachineInterfaceEntity machine(UUID node, ProgramControllerEntity controller) {
    var evidence = saved().nodes().evidence(node);
    if (evidence == null || evidence.position() == null) return null;
    var position = evidence.position();
    for (ServerLevel level : server.getAllLevels()) {
      if (!level.dimension().location().toString().equals(position.dimension())) continue;
      var pos = BlockPos.of(position.block());
      if (Loaded.chunk(level, pos) == null) return null;
      if (!(level.getBlockEntity(pos) instanceof MachineInterfaceEntity owner)) return null;
      var target = networks().membership(owner.position());
      var origin = networks().membership(controller.origin());
      return target.status() == NetworkTopology.Status.VALID
              && origin.status() == NetworkTopology.Status.VALID
              && target.scope().segment().equals(origin.scope().segment())
          ? owner
          : null;
    }
    return null;
  }

  private void execute(ProgramControllerEntity controller, Runtime runtime, Pending pending) {
    var actor = deployer(controller);
    switch (pending.command()) {
      case ProgramCommand.Keep keep -> keep(controller, runtime, actor, keep);
      case ProgramCommand.Move move -> move(controller, runtime, actor, move);
      case ProgramCommand.Craft craft -> craft(controller, runtime, actor, pending.rule(), craft);
      case ProgramCommand.Log log -> controller.log(pending.rule() + ": " + log.text());
      case ProgramCommand.Emit emit -> emit(controller, pending.rule(), emit);
    }
  }

  /**
   * D6 (ADR 0062): drives Signal-mode Interfaces labelled {@code emit.label()} in the controller's
   * segment. The output lasts two rule intervals (plus a second) past each run, so a program that
   * stops running for any reason lets go; stopping, pausing or a trap lets go at once.
   */
  private void emit(ProgramControllerEntity controller, String rule, ProgramCommand.Emit emit) {
    int interval = 40;
    var program = controller.program();
    if (program != null)
      for (var each : program.rules()) if (each.key().equals(rule)) interval = each.interval();
    int driven =
        dev.izzy.factorycore.platform.signal.WorldSignals.get(server)
            .emit(
                controller.deployment(),
                controller.origin(),
                emit.label(),
                emit.strength(),
                server.getTickCount() + 2L * interval + 20);
    if (driven == 0)
      controller.log(rule + ": no Signal-mode Interface labeled " + emit.label() + " here");
  }

  private void releaseSignals(ProgramControllerEntity controller) {
    if (controller.deployment() != null)
      dev.izzy.factorycore.platform.signal.WorldSignals.get(server)
          .release(controller.deployment());
  }

  private void keep(
      ProgramControllerEntity controller,
      Runtime runtime,
      NetworkPermissions.Actor actor,
      ProgramCommand.Keep keep) {
    var owner = machine(keep.machine(), controller);
    if (owner == null) {
      controller.log("keep: machine unloaded or outside this segment");
      return;
    }
    var registration = networks().connectors().bind(owner);
    if (registration.handle() == null || registration.handle().kind() != keep.filter().kind()) {
      controller.log("keep " + owner.label() + ": " + unbound(registration));
      return;
    }
    var stock =
        networks()
            .connectors()
            .matchingStock(actor, owner.position(), registration.handle(), keep.filter());
    if (stock.reason() != OperationResult.Reason.OK) {
      controller.log("keep " + owner.label() + ": " + name(stock.reason()));
      return;
    }
    long missing = Math.min(keep.amount() - stock.amount(), owner.settings().rate());
    if (missing <= 0) return;
    if (resume(controller, runtime, actor, owner, registration.handle())) return;
    fromStorage(
        controller,
        runtime,
        actor,
        owner,
        registration.handle(),
        null,
        keep.filter(),
        missing,
        null,
        0);
  }

  /** Log text for a refused operation; contention reasons say what holds the resource (A08). */
  private static String name(OperationResult.Reason reason) {
    return switch (reason) {
      case BUSY -> "busy: a crafting job is using this machine; retrying next interval";
      case RATE_LIMIT -> "rate limit reached this tick; retrying next interval";
      default -> reason.name().toLowerCase(Locale.ROOT);
    };
  }

  private static String unbound(WorldConnectors.Registration registration) {
    return registration.handle() == null
        ? name(registration.reason())
        : "handles another resource kind";
  }

  private void move(
      ProgramControllerEntity controller,
      Runtime runtime,
      NetworkPermissions.Actor actor,
      ProgramCommand.Move move) {
    if (move.from() != null && move.to() != null) {
      between(controller, runtime, actor, move);
      return;
    }
    var node = move.from() != null ? move.from() : move.to();
    var owner = machine(node, controller);
    if (owner == null) {
      controller.log("move: machine unloaded or outside this segment");
      return;
    }
    var registration = networks().connectors().bind(owner);
    if (registration.handle() == null || registration.handle().kind() != move.filter().kind()) {
      controller.log("move " + owner.label() + ": " + unbound(registration));
      return;
    }
    var handle = registration.handle();
    if (resume(controller, runtime, actor, owner, handle)) return;
    var view =
        view(controller, owner, handle, move.to() != null ? move.toAccess() : move.fromAccess());
    if (view == REFUSED) return;
    long limit = Math.min(move.limit(), owner.settings().rate());
    if (move.to() != null) {
      fromStorage(controller, runtime, actor, owner, handle, view, move.filter(), limit, null, 0);
      return;
    }
    var observation = networks().connectors().observe(actor, owner.position(), handle, view, 0);
    if (observation.reason() != OperationResult.Reason.OK) {
      controller.log("move " + owner.label() + ": " + name(observation.reason()));
      return;
    }
    for (var entry : observation.page().entries())
      if (entry.stored() > 0 && move.filter().matches(entry.key(), this::tagged)) {
        route(
            controller,
            runtime,
            actor,
            owner,
            handle,
            view,
            entry.key(),
            Math.min(limit, entry.stored()),
            WorldStorageRoutes.Direction.TO_STORAGE);
        return;
      }
  }

  /**
   * ADR 0062 (D2): one bounded move from the first matching entry of the source's first page
   * straight into the target, staged by the shared transfer path. Both machines must be loaded in
   * the program's segment; a processing lease on either refuses it as busy, and neither end may
   * hold an unresolved transfer when it starts.
   */
  private void between(
      ProgramControllerEntity controller,
      Runtime runtime,
      NetworkPermissions.Actor actor,
      ProgramCommand.Move move) {
    var source = machine(move.from(), controller);
    var target = machine(move.to(), controller);
    if (source == null || target == null) {
      controller.log("move: machine unloaded or outside this segment");
      return;
    }
    var from = networks().connectors().bind(source);
    var to = networks().connectors().bind(target);
    for (var pair : List.of(Map.entry(source, from), Map.entry(target, to)))
      if (pair.getValue().handle() == null
          || pair.getValue().handle().kind() != move.filter().kind()) {
        controller.log("move " + pair.getKey().label() + ": " + unbound(pair.getValue()));
        return;
      }
    for (var pair : List.of(Map.entry(source, from), Map.entry(target, to))) {
      var busy = networks().connectors().limit(pair.getValue().handle(), false, 0);
      if (busy.reason() == OperationResult.Reason.BUSY) {
        controller.log("move " + pair.getKey().label() + ": " + name(busy.reason()));
        return;
      }
    }
    if (resume(controller, runtime, actor, source, from.handle())
        || resume(controller, runtime, actor, target, to.handle())) return;
    var fromView = view(controller, source, from.handle(), move.fromAccess());
    var toView = view(controller, target, to.handle(), move.toAccess());
    if (fromView == REFUSED || toView == REFUSED) return;
    var observation =
        networks().connectors().observe(actor, source.position(), from.handle(), fromView, 0);
    if (observation.reason() != OperationResult.Reason.OK) {
      controller.log("move " + source.label() + ": " + name(observation.reason()));
      return;
    }
    for (var entry : observation.page().entries()) {
      if (entry.stored() <= 0 || !move.filter().matches(entry.key(), this::tagged)) continue;
      long amount =
          Math.min(
              Math.min(move.limit(), entry.stored()),
              Math.min(source.settings().rate(), target.settings().rate()));
      // A platform item call moves at most one stack.
      if (entry.key().kind() == ResourceKind.ITEM)
        amount =
            Math.min(
                amount,
                new PlatformResourceCodec(server.registryAccess())
                    .itemStack(entry.key(), 1)
                    .getMaxStackSize());
      var key = entry.key();
      var id = UUID.randomUUID();
      runtime.request = id;
      var submission =
          networks()
              .transfers()
              .submitGuarded(
                  id,
                  actor,
                  source.position(),
                  new WorldStorageTransfers.External(from.handle(), fromView),
                  new WorldStorageTransfers.External(to.handle(), toView),
                  key,
                  amount,
                  result -> {
                    var transfer = result.transfer();
                    if (transfer != null
                        && transfer.state() == StagedTransfer.State.COMPLETE
                        && transfer.pending() == StagedTransfer.PendingCall.NONE
                        && transfer.staged() == 0
                        && saved().transfers().contains(result.id()))
                      saved().transfers().retire(result.id());
                    if (id.equals(runtime.request)) runtime.request = null;
                    if (result.reason() != OperationResult.Reason.OK)
                      controller.log(
                          "move "
                              + key.registryId()
                              + " to "
                              + target.label()
                              + ": "
                              + name(result.reason()));
                  },
                  () -> true,
                  () ->
                      saved().transfers().unresolved(from.handle().backing()) == null
                          && saved().transfers().unresolved(to.handle().backing()) == null);
      if (submission.rejection() != OperationResult.Reason.OK) {
        runtime.request = null;
        controller.log("move " + key.registryId() + ": " + name(submission.rejection()));
      }
      return;
    }
  }

  /**
   * D3 (ADR 0062): the statement's face/slots on {@code owner}, or null for its own binding. Logs
   * and returns {@link #REFUSED} when the view cannot be used, rather than guessing another.
   */
  private WorldConnectors.View view(
      ProgramControllerEntity controller,
      MachineInterfaceEntity owner,
      WorldConnectors.Handle handle,
      ProgramCommand.Access access) {
    if (access == null) return null;
    var settings = owner.settings();
    var face =
        access.side() == null
            ? owner.targetDirection().getOpposite()
            : net.minecraft.core.Direction.valueOf(access.side().name());
    boolean own = face == owner.targetDirection().getOpposite();
    // Without slots: the bound window on the Interface's own face, or all of another face.
    int first = access.first() < 0 ? (own ? settings.firstSlot() : 0) : access.first();
    int count =
        access.first() >= 0
            ? access.last() - access.first() + 1
            : own || handle.kind() == ResourceKind.ENERGY
                ? settings.slotCount()
                : networks().connectors().faceSlots(handle, face);
    var view = new WorldConnectors.View(face, first, Math.max(1, count));
    var reason = networks().connectors().viewable(handle, view);
    if (reason == OperationResult.Reason.OK) return view;
    controller.log(
        "move "
            + owner.label()
            + ": "
            + switch (reason) {
              case WRONG_RESOURCE -> "those slots are outside what this Interface may reach";
              case RESERVED ->
                  "another side needs the Interface bound to every slot (its window is narrowed)";
              case UNSUPPORTED ->
                  "side or slots not available on this machine (none there, or it is partitioned)";
              default -> name(reason);
            });
    return REFUSED;
  }

  /** Sentinel for a refused view (never reaches a transfer). */
  private static final WorldConnectors.View REFUSED =
      new WorldConnectors.View(net.minecraft.core.Direction.DOWN, 0, 1);

  /**
   * Continues this deployer's own unresolved transfer on {@code handle} before any new move there,
   * as presets do; another owner's or an uncertain one is logged and waited on (Transfer Recovery).
   * True when the machine is busy with it.
   */
  private boolean resume(
      ProgramControllerEntity controller,
      Runtime runtime,
      NetworkPermissions.Actor actor,
      MachineInterfaceEntity owner,
      WorldConnectors.Handle handle) {
    var unresolved = saved().transfers().unresolved(handle.backing());
    if (unresolved == null) return false;
    var transfer = unresolved.transfer();
    if (transfer.pending() != StagedTransfer.PendingCall.NONE
        || transfer.state() == StagedTransfer.State.QUARANTINED) {
      controller.log(
          "move " + owner.label() + ": a staged move is uncertain; see Transfer Recovery");
      return true;
    }
    if (!transfer.owner().equals(actor.id())) {
      controller.log("move " + owner.label() + ": " + name(OperationResult.Reason.RESERVED));
      return true;
    }
    var id = unresolved.id();
    runtime.request = id;
    var submission =
        networks()
            .transfers()
            .progressGuarded(
                id,
                actor,
                false,
                result -> {
                  var after = result.transfer();
                  if (after != null
                      && after.state() == StagedTransfer.State.COMPLETE
                      && after.pending() == StagedTransfer.PendingCall.NONE
                      && after.staged() == 0
                      && saved().transfers().contains(result.id()))
                    saved().transfers().retire(result.id());
                  if (id.equals(runtime.request)) runtime.request = null;
                  if (result.reason() != OperationResult.Reason.OK)
                    controller.log(
                        "move " + owner.label() + ": staged move " + name(result.reason()));
                },
                () -> true);
    if (submission.rejection() != OperationResult.Reason.OK) {
      runtime.request = null;
      controller.log("move " + owner.label() + ": staged move " + name(submission.rejection()));
    }
    return true;
  }

  /** Finds the first matching stored key over a bounded number of catalog pages, then routes. */
  private void fromStorage(
      ProgramControllerEntity controller,
      Runtime runtime,
      NetworkPermissions.Actor actor,
      MachineInterfaceEntity owner,
      WorldConnectors.Handle handle,
      WorldConnectors.View view,
      ResourceFilter filter,
      long amount,
      WorldStorageCatalog.Cursor cursor,
      int pages) {
    if (pages >= MAX_CATALOG_PAGES) {
      controller.log("storage: nothing matching in the first " + MAX_CATALOG_PAGES + " pages");
      return;
    }
    if (pages == 0) runtime.reservedSeen = false;
    var id = UUID.randomUUID();
    runtime.request = id;
    var submission =
        networks()
            .transfers()
            .allKeys()
            .page(
                id,
                actor,
                owner.position(),
                filter.kind(),
                cursor,
                page -> {
                  if (!id.equals(runtime.request)) return;
                  runtime.request = null;
                  if (page.reason() != OperationResult.Reason.OK) {
                    controller.log("storage: " + name(page.reason()));
                    return;
                  }
                  for (var row : page.rows()) {
                    if (row.total() > 0
                        && row.total() - row.reserved() <= 0
                        && filter.matches(row.key(), this::tagged)) runtime.reservedSeen = true;
                    if (row.total() - row.reserved() > 0
                        && filter.matches(row.key(), this::tagged)) {
                      route(
                          controller,
                          runtime,
                          actor,
                          owner,
                          handle,
                          view,
                          row.key(),
                          Math.min(amount, row.total() - row.reserved()),
                          WorldStorageRoutes.Direction.FROM_STORAGE);
                      return;
                    }
                  }
                  if (page.next() != null)
                    fromStorage(
                        controller,
                        runtime,
                        actor,
                        owner,
                        handle,
                        view,
                        filter,
                        amount,
                        page.next(),
                        pages + 1);
                  else
                    controller.log(
                        runtime.reservedSeen
                            ? "storage: matching stock is reserved by crafting jobs; retrying"
                            : "storage: shortage");
                });
    if (submission.rejection() != OperationResult.Reason.OK) {
      runtime.request = null;
      controller.log("storage: " + name(submission.rejection()));
    }
  }

  private void route(
      ProgramControllerEntity controller,
      Runtime runtime,
      NetworkPermissions.Actor actor,
      MachineInterfaceEntity owner,
      WorldConnectors.Handle handle,
      WorldConnectors.View view,
      ResourceKey key,
      long amount,
      WorldStorageRoutes.Direction direction) {
    if (amount <= 0) return;
    var id = UUID.randomUUID();
    runtime.request = id;
    var submission =
        networks()
            .transfers()
            .routes()
            .priorityGuarded(
                id,
                actor,
                owner.position(),
                new WorldStorageTransfers.External(handle, view),
                key,
                amount,
                direction,
                result -> {
                  var transfer = result.move().transfer();
                  if (transfer != null
                      && transfer.state() == StagedTransfer.State.COMPLETE
                      && transfer.pending() == StagedTransfer.PendingCall.NONE
                      && transfer.staged() == 0
                      && saved().transfers().contains(result.move().id()))
                    saved().transfers().retire(result.move().id());
                  if (id.equals(runtime.request)) runtime.request = null;
                  if (result.move().reason() != OperationResult.Reason.OK)
                    controller.log(
                        "move " + key.registryId() + ": " + name(result.move().reason()));
                },
                () -> true,
                () -> saved().transfers().unresolved(handle.backing()) == null);
    if (submission.rejection() != OperationResult.Reason.OK) {
      runtime.request = null;
      controller.log("move " + key.registryId() + ": " + name(submission.rejection()));
    }
  }

  private void craft(
      ProgramControllerEntity controller,
      Runtime runtime,
      NetworkPermissions.Actor actor,
      String rule,
      ProgramCommand.Craft craft) {
    // The dedupe link is derived from the persisted deployment and saved with the job, so neither a
    // server restart nor a controller chunk reload can submit a second job for the same request.
    var link = craftLink(controller.deployment(), rule, craft.item());
    var jobs = WorldCraftingJobs.get(server);
    if (jobs.linked(link)) return;
    var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(craft.item()));
    if (item.isEmpty()) {
      controller.log(rule + ": unknown item " + craft.item());
      return;
    }
    var key = new PlatformResourceCodec(server.registryAccess()).itemKey(new ItemStack(item.get()));
    var submission = jobs.submitLinked(actor, controller.origin(), key, craft.amount(), link);
    if (submission.job() == null)
      controller.log(rule + ": craft " + craft.item() + " " + submission.reason().key());
  }

  /** Name-based link for one deployment's rule/item craft request (never a random rule ID). */
  public static UUID craftLink(UUID deployment, String rule, String item) {
    return UUID.nameUUIDFromBytes(
        ("factorycore:program-craft:v1:" + deployment + "|" + rule + "|" + item)
            .getBytes(StandardCharsets.UTF_8));
  }
}
