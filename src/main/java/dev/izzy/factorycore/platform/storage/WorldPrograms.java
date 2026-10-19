package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.program.FriendlyCompiler;
import dev.izzy.factorycore.core.program.ProgramCommand;
import dev.izzy.factorycore.core.program.ProgramEvaluator;
import dev.izzy.factorycore.core.program.ProgramIr;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server-thread Program Controller runtime. Each due rule is one shared-scheduler task under the
 * deployment; it re-checks the deployer's DEPLOY grant and FE, evaluates the rule against a
 * read-only host, and publishes the staged commands to a bounded per-controller queue (a newer
 * invocation of the same rule replaces its unstarted commands). Commands run one at a time through
 * the same catalog, connector, route and crafting paths as presets and terminals.
 */
public final class WorldPrograms {
  public static final int TASK_COST = 16;
  public static final long BASE_COST = 4;
  public static final int STEPS_PER_FE = 256;
  public static final int MAX_QUEUE = 64;
  public static final int MAX_STOCK_PAGES = 64;

  /** Same page bound as preset discovery; pages also visit devices outside this scope. */
  public static final int MAX_CATALOG_PAGES = WorldStorageSearch.MAX_PAGES;

  private static final Map<MinecraftServer, WorldPrograms> SERVERS = new HashMap<>();

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
    final Map<String, UUID> crafts = new HashMap<>();
    UUID deployment, request;
    String status = "";
  }

  private final MinecraftServer server;
  private final Map<ProgramControllerEntity, Runtime> controllers = new LinkedHashMap<>();

  private WorldPrograms(MinecraftServer server) {
    this.server = server;
  }

  public static WorldPrograms get(MinecraftServer server) {
    if (!server.isSameThread()) throw new IllegalStateException("Programs require server thread");
    return SERVERS.computeIfAbsent(server, WorldPrograms::new);
  }

  public static boolean exists(MinecraftServer server) {
    return SERVERS.containsKey(server);
  }

  public static void stopped(ServerStoppedEvent event) {
    SERVERS.remove(event.getServer());
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
    return dev.izzy.factorycore.platform.program.ProgramMenu.open(player, controller);
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
        && networks().authorize(actor, controller.origin(), NetworkPermissions.Permission.DEPLOY)
            == NetworkAuthority.Decision.ALLOWED;
  }

  /** Compiles the draft and deploys it under {@code actor}; returns diagnostics on failure. */
  public List<FriendlyCompiler.Diagnostic> deploy(
      NetworkPermissions.Actor actor, ProgramControllerEntity controller) {
    if (!allowed(actor, controller))
      return List.of(new FriendlyCompiler.Diagnostic(1, 1, "You need Deploy permission here"));
    var result = FriendlyCompiler.compile(controller.draft());
    if (!result.ok()) return result.diagnostics();
    controller.deploy(controller.draft(), result.program(), actor.id());
    var runtime = controllers.computeIfAbsent(controller, c -> new Runtime());
    runtime.due.clear();
    runtime.queue.clear();
    runtime.crafts.clear();
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
    dev.izzy.factorycore.core.wasm.WasmProgram instance;
    try {
      instance = new dev.izzy.factorycore.core.wasm.WasmProgram(artifact, System::nanoTime);
    } catch (RuntimeException failure) {
      var message =
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
      return List.of(new FriendlyCompiler.Diagnostic(1, 1, "Wasm rejected: " + message));
    }
    controller.deployWasm(artifact, instance, actor.id());
    var runtime = controllers.computeIfAbsent(controller, c -> new Runtime());
    runtime.due.clear();
    runtime.queue.clear();
    runtime.crafts.clear();
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
    return true;
  }

  public String status(ProgramControllerEntity controller) {
    var runtime = controllers.get(controller);
    return runtime == null ? "" : runtime.status;
  }

  public void tick() {
    if (!server.isSameThread()) throw new IllegalStateException("Programs require server thread");
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
        runtime.queue.clear();
      }
      var member = networks().membership(controller.origin());
      if (member.status() != NetworkTopology.Status.VALID) {
        runtime.status = "offline";
        continue;
      }
      for (var rule : rules(controller)) {
        if (runtime.due.getOrDefault(rule.key(), Long.MIN_VALUE) > now) continue;
        var key = "rule:" + rule.key();
        // An overdue rule runs once: missed invocations coalesce into the next due time.
        runtime.due.put(rule.key(), now + rule.interval());
        if (scheduler.isQueued(member.scope().network(), controller.deployment(), key)) continue;
        var deployment = controller.deployment();
        scheduler.submit(
            member.scope().network(),
            deployment,
            key,
            TASK_COST,
            () -> invoke(controller, deployment, rule));
      }
      if (runtime.request == null && !runtime.queue.isEmpty())
        execute(controller, runtime, runtime.queue.poll());
    }
  }

  /** One rule invocation: authority, power, bounded evaluation, then publication. */
  void invoke(ProgramControllerEntity controller, UUID deployment, RuleRef rule) {
    var runtime = controllers.get(controller);
    if (runtime == null
        || controller.state() != ProgramControllerEntity.State.RUNNING
        || !deployment.equals(controller.deployment())) return;
    var actor = deployer(controller);
    if (!allowed(actor, controller)) {
      controller.state(ProgramControllerEntity.State.PAUSED, "Deployer lost Deploy permission");
      runtime.queue.clear();
      return;
    }
    boolean guest = rule.friendly() == null;
    long worst =
        BASE_COST
            + (guest
                    ? dev.izzy.factorycore.core.wasm.WasmProgram.RULE_INSTRUCTIONS
                    : ProgramEvaluator.MAX_STEPS)
                / STEPS_PER_FE;
    if (controller.energy() < worst) {
      runtime.status = "no_power";
      return;
    }
    List<ProgramCommand> commands;
    int work;
    try {
      if (guest) {
        var instance = controller.wasm();
        if (instance == null || instance.failed())
          throw new ProgramEvaluator.Trap("guest instance unavailable");
        var outcome = instance.invoke(rule.index(), host(controller, actor));
        commands = outcome.commands();
        work = outcome.instructions();
        if (outcome.stateChanged()) controller.guestState(instance.state());
      } else {
        var outcome = ProgramEvaluator.evaluate(rule.friendly(), host(controller, actor));
        commands = outcome.commands();
        work = outcome.steps();
      }
    } catch (ProgramEvaluator.Trap trap) {
      controller.spend(BASE_COST);
      controller.state(
          ProgramControllerEntity.State.TRAPPED, "Rule " + rule.key() + ": " + trap.getMessage());
      controller.log("trap " + rule.key() + ": " + trap.getMessage());
      runtime.queue.clear();
      return;
    }
    controller.spend(BASE_COST + (work + STEPS_PER_FE - 1) / STEPS_PER_FE);
    runtime.status = "running";
    runtime.queue.removeIf(pending -> pending.rule().equals(rule.key()));
    for (var command : commands) {
      if (command instanceof ProgramCommand.Log log) {
        controller.log(rule.key() + ": " + log.text());
        continue;
      }
      if (runtime.queue.size() == MAX_QUEUE) {
        controller.log(rule.key() + ": command queue full; later commands dropped");
        break;
      }
      runtime.queue.add(new Pending(rule.key(), command));
    }
  }

  /** Evaluates one saved native draft rule against the viewer's current read-only scope. */
  public dev.izzy.factorycore.core.program.ProgramPreview.Result preview(
      NetworkPermissions.Actor actor, ProgramControllerEntity controller, int rule) {
    if (networks().authorize(actor, controller.origin(), NetworkPermissions.Permission.VIEW)
        != NetworkAuthority.Decision.ALLOWED)
      return new dev.izzy.factorycore.core.program.ProgramPreview.Result(
          0, 0, List.of("View permission required"));
    var compiled = dev.izzy.factorycore.core.program.FriendlyCompiler.compile(controller.draft());
    if (!compiled.ok())
      return new dev.izzy.factorycore.core.program.ProgramPreview.Result(
          0, 0, List.of("Fix compiler diagnostics before previewing the saved draft"));
    return dev.izzy.factorycore.core.program.ProgramPreview.evaluate(
        compiled.program(), rule, host(controller, actor));
  }

  private ProgramEvaluator.Host host(
      ProgramControllerEntity controller, NetworkPermissions.Actor actor) {
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
        if (query.decision() != NetworkAuthority.Decision.ALLOWED) return List.of();
        if (query.more()) throw new IllegalStateException("more than 64 machines labeled " + label);
        return query.machines().stream().map(NetworkTopology.Scope::node).toList();
      }

      @Override
      public long stock(ResourceFilter filter) {
        if (networks().authorize(actor, controller.origin(), NetworkPermissions.Permission.VIEW)
            != NetworkAuthority.Decision.ALLOWED)
          throw new IllegalStateException("reading storage needs View permission");
        return storageStock(controller, actor, filter);
      }
    };
  }

  /** Available matching units in digital cells of the controller's VIEW storage, bounded. */
  private long storageStock(
      ProgramControllerEntity controller, NetworkPermissions.Actor actor, ResourceFilter filter) {
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
        if (registry.validate(handle) != dev.izzy.factorycore.core.storage.DeviceRegistry.Failure.OK
            || registry.definition(handle).location() == null
            || registry.definition(handle).kind() != filter.kind()
            || !transfers.digitalAllowed(
                actor, controller.origin(), handle, probe, NetworkPermissions.Permission.VIEW))
          continue;
        ResourceKey cursor = null;
        do {
          if (++pages > MAX_STOCK_PAGES)
            throw new IllegalStateException("storage too large to count");
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

  private boolean tagged(
      dev.izzy.factorycore.core.resource.ResourceKind kind, String tag, ResourceKey key) {
    if (kind != dev.izzy.factorycore.core.resource.ResourceKind.ITEM) return false;
    try {
      var stack = new PlatformResourceCodec(server.registryAccess()).itemStack(key, 1);
      return stack.is(
          net.minecraft.tags.TagKey.create(
              net.minecraft.core.registries.Registries.ITEM, ResourceLocation.parse(tag)));
    } catch (IllegalArgumentException unsupported) {
      return false;
    }
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed levels.
  private MachineInterfaceEntity machine(UUID node, ProgramControllerEntity controller) {
    var evidence = saved().nodes().evidence(node);
    if (evidence == null || evidence.position() == null) return null;
    var position = evidence.position();
    for (var level : server.getAllLevels()) {
      if (!level.dimension().location().toString().equals(position.dimension())) continue;
      var pos = BlockPos.of(position.block());
      if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return null;
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
    }
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
    fromStorage(
        controller, runtime, actor, owner, registration.handle(), keep.filter(), missing, null, 0);
  }

  private static String name(OperationResult.Reason reason) {
    return reason.name().toLowerCase(java.util.Locale.ROOT);
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
    long limit = Math.min(move.limit(), owner.settings().rate());
    if (move.to() != null) {
      fromStorage(controller, runtime, actor, owner, handle, move.filter(), limit, null, 0);
      return;
    }
    var observation = networks().connectors().observe(actor, owner.position(), handle, 0);
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
            entry.key(),
            Math.min(limit, entry.stored()),
            WorldStorageRoutes.Direction.TO_STORAGE);
        return;
      }
  }

  /** Finds the first matching stored key over a bounded number of catalog pages, then routes. */
  private void fromStorage(
      ProgramControllerEntity controller,
      Runtime runtime,
      NetworkPermissions.Actor actor,
      MachineInterfaceEntity owner,
      WorldConnectors.Handle handle,
      ResourceFilter filter,
      long amount,
      WorldStorageCatalog.Cursor cursor,
      int pages) {
    if (pages >= MAX_CATALOG_PAGES) {
      controller.log("storage: nothing matching in the first " + MAX_CATALOG_PAGES + " pages");
      return;
    }
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
                  for (var row : page.rows())
                    if (row.total() - row.reserved() > 0
                        && filter.matches(row.key(), this::tagged)) {
                      route(
                          controller,
                          runtime,
                          actor,
                          owner,
                          handle,
                          row.key(),
                          Math.min(amount, row.total() - row.reserved()),
                          WorldStorageRoutes.Direction.FROM_STORAGE);
                      return;
                    }
                  if (page.next() != null)
                    fromStorage(
                        controller,
                        runtime,
                        actor,
                        owner,
                        handle,
                        filter,
                        amount,
                        page.next(),
                        pages + 1);
                  else controller.log("storage: shortage");
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
                new WorldStorageTransfers.External(handle),
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
    var dedupe = rule + "|" + craft.item();
    var jobs = WorldCraftingJobs.get(server);
    var previous = runtime.crafts.get(dedupe);
    if (previous != null && saved().craftingJobs().get(previous) != null) return;
    var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(craft.item()));
    if (item.isEmpty()) {
      controller.log(rule + ": unknown item " + craft.item());
      return;
    }
    var key = new PlatformResourceCodec(server.registryAccess()).itemKey(new ItemStack(item.get()));
    var submission = jobs.submit(actor, controller.origin(), key, craft.amount());
    if (submission.job() != null) runtime.crafts.put(dedupe, submission.job());
    else controller.log(rule + ": craft " + craft.item() + " " + submission.reason());
  }
}
