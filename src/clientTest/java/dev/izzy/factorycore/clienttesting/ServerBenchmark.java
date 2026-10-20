package dev.izzy.factorycore.clienttesting;

import com.mojang.authlib.GameProfile;
import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.MachineInterfaceContent;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Dedicated-server workload benchmark (A15/D07). 64 factories, each one controller, one drive with
 * 4,096 cobblestone, one Program Controller and four labeled Machine Interfaces on chests. Phases:
 * idle (built, no programs), small (4 programs) and large (64 programs). Each program keeps its
 * machines stocked with 64 and moves 16 back every 10 ticks, so items churn continuously. Overload
 * then adds 64 more factories (128 networks) and runs an eight-rule program on every one, which
 * deliberately exceeds the scheduler's per-tick shares; admissions may be refused. Finally, with
 * all programs stopped, 896 extra idle networks (1,024 total, the server limit) must leave the
 * scheduler empty on every measured tick. Measured span: Factory Core's server post-tick listeners
 * (HIGHEST to LOWEST priority Post); whole server ticks are recorded alongside. Raw nanoseconds go
 * to CSV.
 */
final class ServerBenchmark {
  static final int FACTORIES = 64, OVERLOAD_FACTORIES = 128, SMALL = 4, WARMUP = 200;
  static final int MEASURED = 1_000;
  static final long STOCK = 4_096;
  static final String PROGRAM =
      """
      every 10 ticks as "churn" {
          for machine in machines("bench") {
              keep machine stocked with 64 of item("minecraft:cobblestone") from storage
          }
          move item("minecraft:cobblestone") from machines("bench") to storage limit 16
      }
      """;

  /** Eight staggered rules, each restocking every machine and moving up to 64 back. */
  static final String OVERLOAD_PROGRAM = overloadProgram();

  private static String overloadProgram() {
    var source = new StringBuilder();
    for (int rule = 0; rule < 8; rule++)
      source
          .append("every ")
          .append(10 + rule)
          .append(" ticks as \"o")
          .append(rule)
          .append(
              "\" {\n    for machine in machines(\"bench\") {\n        keep machine stocked with 64"
                  + " of item(\"minecraft:cobblestone\") from storage\n    }\n    move"
                  + " item(\"minecraft:cobblestone\") from machines(\"bench\") to storage limit"
                  + " 64\n}\n");
    return source.toString();
  }

  private ResourceKey cobble;
  private static final Direction[] FACES = {Direction.WEST, Direction.NORTH, Direction.UP};

  private record Factory(
      BlockPos root,
      DeviceRegistry.Handle cell,
      ProgramControllerEntity controller,
      List<MachineInterfaceEntity> machines,
      List<BlockPos> chests) {}

  private final Path output = Path.of(System.getProperty("factorycore.bench.output"));
  private final List<Factory> factories = new ArrayList<>();
  private final List<String> rows = new ArrayList<>();
  private final List<String> summary = new ArrayList<>();
  private NetworkPermissions.Actor owner;
  private ServerLevel level;
  private String phase = "build";
  private int counter;
  private long tickStart, postStart;
  private long[] mod = new long[MEASURED], whole = new long[MEASURED];
  private int[] backlog = new int[MEASURED];
  private boolean done, churned, conserved;
  private final List<NetworkNodeEntity> idleNetworks = new ArrayList<>();

  void register() {
    NeoForge.EVENT_BUS.addListener(this::started);
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::pre);
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::postFirst);
    NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::postLast);
  }

  private void started(ServerStartedEvent event) {
    level = event.getServer().overworld();
    var principal =
        FakePlayerFactory.get(
            level,
            new GameProfile(
                UUID.fromString("0c6b1d3e-9a51-4f0b-8e7e-5b2f8a1c3d40"), "bench_principal"));
    owner = new NetworkPermissions.Actor(principal.getUUID(), false);
    cobble =
        new PlatformResourceCodec(level.registryAccess()).itemKey(new ItemStack(Items.COBBLESTONE));
  }

  private static BlockPos base(int index) {
    return new BlockPos(8 + 16 * (index % 8), 70, 8 + 16 * (index / 8));
  }

  private void build(int from, int to) {
    for (int index = from; index < to; index++) {
      var root = base(index);
      level.setChunkForced(root.getX() >> 4, root.getZ() >> 4, true);
      level.setBlock(root, NetworkContent.CONTROLLER.get().defaultBlockState(), 3);
      ((NetworkNodeEntity) level.getBlockEntity(root)).commission(owner.id());
      var drivePos = root.east();
      level.setBlock(drivePos, StorageContent.DRIVE.get().defaultBlockState(), 3);
      var drive = (StorageDriveEntity) level.getBlockEntity(drivePos);
      drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
      level.setBlock(root.south(), ProgramContent.BLOCK.get().defaultBlockState(), 3);
      var machines = new ArrayList<MachineInterfaceEntity>();
      var chests = new ArrayList<BlockPos>();
      for (int m = 0; m < 4; m++) {
        var face = m < 3 ? FACES[m] : Direction.EAST;
        var at = m < 3 ? root.relative(face) : drivePos.east();
        level.setBlock(
            at,
            MachineInterfaceContent.BLOCK
                .get()
                .defaultBlockState()
                .setValue(BlockStateProperties.FACING, face),
            3);
        var machine = (MachineInterfaceEntity) level.getBlockEntity(at);
        machine.place(new ItemStack(MachineInterfaceContent.ITEM.get()));
        level.setBlock(at.relative(face), Blocks.CHEST.defaultBlockState(), 3);
        machines.add(machine);
        chests.add(at.relative(face));
      }
      factories.add(
          new Factory(
              root,
              CellHandleData.read(drive.cell(0)),
              (ProgramControllerEntity) level.getBlockEntity(root.south()),
              machines,
              chests));
    }
  }

  private boolean ready(MinecraftServer server) {
    var networks = WorldNetworks.get(server);
    var registry = DeviceSavedData.get(server).registry();
    for (var factory : factories) {
      for (var machine : factory.machines())
        if (networks.membership(machine.position()).status() != NetworkTopology.Status.VALID)
          return false;
      if (!registry.stock(factory.cell(), cobble).loaded()) return false;
    }
    return true;
  }

  private final Set<MachineInterfaceEntity> configured = new HashSet<>();

  private void seed(MinecraftServer server, int from) {
    var registry = DeviceSavedData.get(server).registry();
    for (var factory : factories.subList(from, factories.size()))
      if (registry.insert(factory.cell(), cobble, STOCK).amount() != STOCK)
        throw new IllegalStateException("Seed stock");
  }

  /**
   * Labels at most one machine per factory per tick: each change rebuilds that network, and
   * settings are refused (INVALID_SCOPE) until it validates again. Returns true when all are done.
   */
  private boolean configure(MinecraftServer server) {
    var networks = WorldNetworks.get(server);
    for (var factory : factories)
      for (var machine : factory.machines()) {
        if (configured.contains(machine)) continue;
        if (networks.membership(machine.position()).status() != NetworkTopology.Status.VALID) break;
        var decision =
            machine.applySettings(
                owner,
                new ConnectorSettings(
                    ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 27, 64),
                "bench");
        if (decision == NetworkAuthority.Decision.ALLOWED) configured.add(machine);
        else if (decision != NetworkAuthority.Decision.INVALID_SCOPE)
          throw new IllegalStateException("Machine settings refused: " + decision);
        break;
      }
    return configured.size() == factories.size() * 4;
  }

  private void deploy(MinecraftServer server, int from, int to, String source) {
    for (int index = from; index < to; index++) {
      var controller = factories.get(index).controller();
      controller.draft(source);
      var diagnostics = WorldPrograms.get(server).deploy(owner, controller);
      if (!diagnostics.isEmpty()) throw new IllegalStateException("Deploy: " + diagnostics);
    }
  }

  private long total(MinecraftServer server) {
    var registry = DeviceSavedData.get(server).registry();
    long sum = chests();
    for (var factory : factories) sum += registry.stock(factory.cell(), cobble).total();
    return sum;
  }

  /** Cobblestone currently in machine chests: evidence that programs actually moved items. */
  private long chests() {
    long sum = 0;
    for (var factory : factories) {
      for (var chest : factory.chests()) {
        var container = (Container) level.getBlockEntity(chest);
        for (int slot = 0; slot < container.getContainerSize(); slot++)
          if (container.getItem(slot).is(Items.COBBLESTONE))
            sum += container.getItem(slot).getCount();
      }
    }
    return sum;
  }

  private void pre(ServerTickEvent.Pre event) {
    tickStart = System.nanoTime();
    if (!factories.isEmpty())
      for (var factory : factories)
        factory.controller().energyStorage().receiveEnergy(1_000, false);
  }

  private void postFirst(ServerTickEvent.Post event) {
    postStart = System.nanoTime();
  }

  private void postLast(ServerTickEvent.Post event) {
    long end = System.nanoTime();
    if (level == null || done) return;
    var server = event.getServer();
    try {
      step(server, end - postStart, end - tickStart);
    } catch (IOException | RuntimeException failure) {
      done = true;
      throw new IllegalStateException("Server benchmark failed", failure);
    }
  }

  private void step(MinecraftServer server, long modNanos, long wholeNanos) throws IOException {
    counter++;
    switch (phase) {
      case "build" -> {
        build(0, FACTORIES);
        next("settle");
      }
      case "build-overload" -> {
        // Programs were stopped after the large phase; let in-flight work drain first.
        if (counter < 200) return;
        build(FACTORIES, OVERLOAD_FACTORIES);
        next("settle");
      }
      case "settle" -> {
        if (counter > 600) throw new IllegalStateException("Networks did not validate");
        if (!ready(server)) return;
        seed(server, factories.size() == FACTORIES ? 0 : FACTORIES);
        next("configure");
      }
      case "configure" -> {
        if (counter > 1_200) throw new IllegalStateException("Machines did not configure");
        if (!configure(server) || !ready(server)) return;
        if (factories.size() == FACTORIES) next("warm-idle");
        else {
          deploy(server, 0, OVERLOAD_FACTORIES, OVERLOAD_PROGRAM);
          next("warm-overload");
        }
      }
      case "warm-idle", "warm-small", "warm-large", "warm-overload" -> {
        if (counter >= WARMUP) next(phase.substring(5));
      }
      case "idle", "small", "large", "overload" -> {
        mod[counter - 1] = modNanos;
        whole[counter - 1] = wholeNanos;
        backlog[counter - 1] = WorldNetworks.get(server).scheduler().backlog();
        if (counter < MEASURED) return;
        record(phase);
        long inChests = chests();
        summary.add(phase + " items_in_machine_chests=" + inChests);
        if (phase.equals("large")) churned = inChests > 0;
        if (phase.equals("overload")) churned &= inChests > 0;
        var probe = factories.getFirst().controller();
        summary.add(
            phase
                + " probe state="
                + probe.state()
                + " message="
                + probe.message()
                + " energy="
                + probe.energy()
                + " logs="
                + probe.logs());
        switch (phase) {
          case "idle" -> {
            deploy(server, 0, SMALL, PROGRAM);
            next("warm-small");
          }
          case "small" -> {
            deploy(server, SMALL, FACTORIES, PROGRAM);
            next("warm-large");
          }
          case "large" -> {
            stopAll(server);
            next("build-overload");
          }
          default -> {
            stopAll(server);
            next("drain");
          }
        }
      }
      case "drain" -> {
        if (counter < 200) return;
        long expected = STOCK * factories.size();
        long actual = total(server);
        conserved = actual == expected;
        summary.add("conservation expected=" + expected + " actual=" + actual + " " + conserved);
        buildIdleNetworks();
        next("settle-wide");
      }
      case "settle-wide" -> {
        if (counter > 1_200) throw new IllegalStateException("Idle networks did not validate");
        var networks = WorldNetworks.get(server);
        for (var node : idleNetworks)
          if (networks.membership(node.position()).status() != NetworkTopology.Status.VALID) return;
        summary.add("idle-wide networks=" + (idleNetworks.size() + factories.size()));
        next("warm-idle-wide");
      }
      case "warm-idle-wide" -> {
        if (counter >= WARMUP) next("idle-wide");
      }
      case "idle-wide" -> {
        mod[counter - 1] = modNanos;
        whole[counter - 1] = wholeNanos;
        backlog[counter - 1] = WorldNetworks.get(server).scheduler().backlog();
        if (counter < MEASURED) return;
        boolean quiet = Arrays.stream(backlog).allMatch(value -> value == 0);
        record(phase);
        summary.add("idle-wide scheduler_quiet=" + quiet);
        finish(server, conserved && churned && quiet);
      }
      default -> throw new IllegalStateException(phase);
    }
  }

  /**
   * D07 idle scenario: 896 extra single-controller networks (seven per already-forced factory
   * chunk, two blocks apart so none connect) bring the server to its 1,024-network limit, with
   * every program stopped. No work is submitted.
   */
  private void buildIdleNetworks() {
    for (int index = 0; index < OVERLOAD_FACTORIES; index++) {
      int x0 = 16 * (index % 8), z0 = 16 * (index / 8);
      for (int k = 0; k < 7; k++) {
        var at = new BlockPos(x0 + 2 * k, 90, z0 + 1);
        level.setBlock(at, NetworkContent.CONTROLLER.get().defaultBlockState(), 3);
        var node = (NetworkNodeEntity) level.getBlockEntity(at);
        node.commission(owner.id());
        idleNetworks.add(node);
      }
    }
  }

  private void stopAll(MinecraftServer server) {
    for (var factory : factories)
      WorldPrograms.get(server)
          .control(owner, factory.controller(), ProgramControllerEntity.State.STOPPED);
  }

  private void next(String value) {
    phase = value;
    counter = 0;
  }

  private static double ms(long nanos) {
    return nanos / 1_000_000.0;
  }

  private static long percentile(long[] sorted, double p) {
    return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)];
  }

  private void record(String name) {
    for (int index = 0; index < MEASURED; index++)
      rows.add(name + "," + index + "," + mod[index] + "," + whole[index] + "," + backlog[index]);
    var sortedMod = mod.clone();
    var sortedWhole = whole.clone();
    Arrays.sort(sortedMod);
    Arrays.sort(sortedWhole);
    summary.add(
        String.format(
            Locale.ROOT,
            "%s mod_ms median=%.4f p95=%.4f p99=%.4f max=%.4f | server_tick_ms median=%.4f p99=%.4f max=%.4f",
            name,
            ms(percentile(sortedMod, 0.5)),
            ms(percentile(sortedMod, 0.95)),
            ms(percentile(sortedMod, 0.99)),
            ms(sortedMod[MEASURED - 1]),
            ms(percentile(sortedWhole, 0.5)),
            ms(percentile(sortedWhole, 0.99)),
            ms(sortedWhole[MEASURED - 1])));
    long over2 = Arrays.stream(mod).filter(n -> n > 2_000_000).count();
    long over5 = Arrays.stream(mod).filter(n -> n > 5_000_000).count();
    long over10 = Arrays.stream(mod).filter(n -> n > 10_000_000).count();
    summary.add(
        name
            + " mod_ticks_over 2ms="
            + over2
            + " 5ms="
            + over5
            + " 10ms="
            + over10
            + " of "
            + MEASURED);
    var sortedBacklog = backlog.clone();
    Arrays.sort(sortedBacklog);
    var runtime = Runtime.getRuntime();
    summary.add(
        name
            + " scheduler_backlog median="
            + sortedBacklog[MEASURED / 2]
            + " p99="
            + sortedBacklog[(int) Math.ceil(0.99 * MEASURED) - 1]
            + " max="
            + sortedBacklog[MEASURED - 1]
            + " used_heap="
            + (runtime.totalMemory() - runtime.freeMemory()));
    mod = new long[MEASURED];
    whole = new long[MEASURED];
    backlog = new int[MEASURED];
  }

  private void finish(MinecraftServer server, boolean pass) throws IOException {
    done = true;
    Files.createDirectories(output);
    var runtime = Runtime.getRuntime();
    summary.add(
        "java="
            + System.getProperty("java.version")
            + " os="
            + System.getProperty("os.name")
            + " "
            + System.getProperty("os.version")
            + " arch="
            + System.getProperty("os.arch")
            + " cpus="
            + runtime.availableProcessors()
            + " max_heap="
            + runtime.maxMemory()
            + " used_heap="
            + (runtime.totalMemory() - runtime.freeMemory()));
    Files.write(
        output.resolve("server-v1.csv"),
        prepend("phase,tick,mod_nanos,server_tick_nanos,scheduler_backlog", rows));
    Files.write(output.resolve("server-v1-summary.txt"), prepend(pass ? "PASS" : "FAIL", summary));
    System.out.println("FACTORYCORE_SERVER_BENCHMARK " + (pass ? "PASS" : "FAIL"));
    server.halt(false);
  }

  private static List<String> prepend(String first, List<String> rest) {
    var all = new ArrayList<String>(rest.size() + 1);
    all.add(first);
    all.addAll(rest);
    return all;
  }
}
