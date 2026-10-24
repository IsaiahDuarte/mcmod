package dev.izzy.factorycore.clienttesting;

import static dev.izzy.factorycore.clienttesting.RestartCheckpoint.require;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.ProcessingContents;
import dev.izzy.factorycore.platform.crafting.ProcessingJournal;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Abrupt-kill and independent-save interruption of a real processing job (ADR 0057), across
 * separate dedicated JVMs. The seed smelts 4 raw iron into a 2-ingot blast-furnace job. It saves
 * once after the first insertion (S1, copied to {@code *.s1}) and again after the first collection
 * (S2), runs on and then SIGKILLs itself, so nothing after S2 is saved. The build then derives
 * three worlds from the seed:
 *
 * <ul>
 *   <li>{@code kill}: S2 as written; the job must resume and finish with exact conservation.
 *   <li>{@code data-old}: S1 SavedData with S2 chunks; {@code chunks-old}: S2 SavedData with S1
 *       chunks. The machine's journal disagrees with the job record, so the job must report {@code
 *       save_mismatch} and make no machine or ledger change until an operator resolves; resolution
 *       writes evidence and the job never holds more output than the pattern allows.
 * </ul>
 */
final class InterruptionVerification {
  private static final BlockPos ROOT = new BlockPos(9, 65, 9),
      DRIVE = ROOT.east(),
      INTERFACE = ROOT.south(),
      FURNACE = ROOT.south(2);
  private static final int SEEDED = 4, REQUESTED = 2;
  private final String variant = System.getProperty("factorycore.interruption.variant", "seed");
  private final Path report = Path.of(System.getProperty("factorycore.interruption.report"));
  private final Path observation = Path.of(System.getProperty("factorycore.interruption.expected"));
  private Properties expected = new Properties();
  private ServerPlayer principal;
  private int started, phase, mark, taken;
  private DeviceRegistry.Handle cell;
  private ResourceKey ore, ingot;
  private UUID job;
  private String frozen;

  void register() {
    NeoForge.EVENT_BUS.addListener(this::started);
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private void started(ServerStartedEvent event) {
    try {
      Files.createDirectories(report.getParent());
      var level = event.getServer().overworld();
      level.setChunkForced(0, 0, true);
      principal =
          FakePlayerFactory.get(
              level,
              new GameProfile(
                  UUID.fromString("0b9e4a52-6c1d-4f7e-8a3b-2d5c7e9f1a04"), "interruption_probe"));
      var codec = new PlatformResourceCodec(level.registryAccess());
      ore = codec.itemKey(new ItemStack(Items.RAW_IRON));
      ingot = codec.itemKey(new ItemStack(Items.IRON_INGOT));
      started = event.getServer().getTickCount();
      if (!"seed".equals(variant)) {
        expected = RestartCheckpoint.read(observation);
        job = UUID.fromString(expected.getProperty("job"));
        cell =
            new DeviceRegistry.Handle(
                UUID.fromString(expected.getProperty("cellWorld")),
                UUID.fromString(expected.getProperty("cellBacking")),
                Long.parseLong(expected.getProperty("cellGeneration")));
      }
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static Path data(MinecraftServer server) {
    return server.getWorldPath(LevelResource.ROOT).resolve("data/" + DeviceSavedData.NAME + ".dat");
  }

  private static Path region(MinecraftServer server) {
    return server.getWorldPath(LevelResource.ROOT).resolve("region/r.0.0.mca");
  }

  private NodeRegistry.Position at(BlockPos position) {
    return Positions.of(principal.serverLevel(), position);
  }

  private boolean ready(MinecraftServer server) {
    var saved = DeviceSavedData.get(server);
    return WorldNetworks.get(server).membership(at(INTERFACE)).status()
            == NetworkTopology.Status.VALID
        && cell != null
        && saved.registry().stock(cell, ore).loaded();
  }

  private BlastFurnaceBlockEntity furnace() {
    return (BlastFurnaceBlockEntity) principal.serverLevel().getBlockEntity(FURNACE);
  }

  private long stock(MinecraftServer server, ResourceKey key) {
    return DeviceSavedData.get(server).registry().stock(cell, key).total();
  }

  private int held(Item item) {
    int total = 0;
    for (int slot = 0; slot < furnace().getContainerSize(); slot++)
      if (furnace().getItem(slot).is(item)) total += furnace().getItem(slot).getCount();
    return total;
  }

  private CraftingJob job(MinecraftServer server) {
    return DeviceSavedData.get(server).craftingJobs().get(job);
  }

  private ProcessingJournal.Entry journal(MinecraftServer server) {
    return ProcessingJournal.read(furnace(), DeviceSavedData.get(server).registry().world());
  }

  /** Units the job has moved into or out of the machine so far, from the saved job record. */
  private static long moved(Map<ResourceKey, Long> map, ResourceKey key) {
    return map == null ? 0 : map.getOrDefault(key, 0L);
  }

  private void tick(ServerTickEvent.Post event) {
    if (principal == null || phase < 0) return;
    var server = event.getServer();
    try {
      require(server.getTickCount() - started < 2400, "Interruption probe deadline " + variant);
      if ("seed".equals(variant)) seed(server);
      else if ("kill".equals(variant)) resumeAfterKill(server);
      else skewed(server);
    } catch (Exception error) {
      throw new IllegalStateException("Interruption verification failed: " + variant, error);
    }
  }

  private void seed(MinecraftServer server) throws IOException {
    ServerLevel level = principal.serverLevel();
    switch (phase) {
      case 0 -> {
        level.setBlock(ROOT, NetworkContent.CONTROLLER.get().defaultBlockState(), 3);
        ((NetworkNodeEntity) level.getBlockEntity(ROOT)).commission(principal.getUUID());
        level.setBlock(DRIVE, StorageContent.DRIVE.get().defaultBlockState(), 3);
        var drive = (StorageDriveEntity) level.getBlockEntity(DRIVE);
        drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
        cell = CellHandleData.read(drive.cell(0));
        level.setBlock(FURNACE, Blocks.BLAST_FURNACE.defaultBlockState(), 3);
        furnace().setItem(1, new ItemStack(Items.COAL, 8));
        level.setBlock(
            INTERFACE,
            CraftingContent.CRAFTER
                .get()
                .defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.SOUTH),
            3);
        var pattern = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
        pattern.set(
            CraftingContent.PROCESSING.get(),
            new ProcessingContents(
                UUID.randomUUID(),
                1,
                0,
                List.of(new ItemStack(Items.RAW_IRON)),
                List.of(new ItemStack(Items.IRON_INGOT))));
        ((CrafterEntity) level.getBlockEntity(INTERFACE)).patterns().insertItem(0, pattern, false);
        phase = 1;
      }
      case 1 -> {
        if (!ready(server)) return;
        var registry = DeviceSavedData.get(server).registry();
        require(registry.insert(cell, ore, SEEDED).amount() == SEEDED, "Seed raw iron");
        var submission =
            WorldCraftingJobs.get(server)
                .submit(
                    new NetworkPermissions.Actor(principal.getUUID(), false),
                    at(INTERFACE),
                    ingot,
                    REQUESTED);
        require(submission.job() != null, "Job submitted: " + submission.reason());
        job = submission.job();
        phase = 2;
      }
      case 2 -> {
        var current = job(server);
        var entry = journal(server);
        if (current == null
            || current.dispatch() == null
            || moved(current.dispatch().inserted(), ore) == 0
            || entry == null
            || entry.pending()
            || !current.dispatch().collected().isEmpty()) return;
        require(server.saveEverything(true, true, true), "S1 save");
        Files.copy(
            data(server),
            data(server).resolveSibling(DeviceSavedData.NAME + ".s1"),
            StandardCopyOption.REPLACE_EXISTING);
        Files.copy(
            region(server),
            region(server).resolveSibling("r.0.0.s1"),
            StandardCopyOption.REPLACE_EXISTING);
        expected.setProperty("s1Inserted", Long.toString(entry.inserted()));
        phase = 3;
      }
      case 3 -> {
        // Job 1 collects its outputs and finishes; S2 is the consistent finished state.
        if (job(server) != null) return;
        require(stock(server, ingot) == REQUESTED, "Job 1 delivered its ingots");
        require(journal(server) == null, "Finished dispatch cleared its journal");
        require(server.saveEverything(true, true, true), "S2 save");
        Files.copy(
            data(server),
            data(server).resolveSibling(DeviceSavedData.NAME + ".s2"),
            StandardCopyOption.REPLACE_EXISTING);
        Files.copy(
            region(server),
            region(server).resolveSibling("r.0.0.s2"),
            StandardCopyOption.REPLACE_EXISTING);
        expected.setProperty("job1", job.toString());
        var submission =
            WorldCraftingJobs.get(server)
                .submit(
                    new NetworkPermissions.Actor(principal.getUUID(), false),
                    at(INTERFACE),
                    ingot,
                    REQUESTED);
        require(submission.job() != null, "Job 2 submitted: " + submission.reason());
        job = submission.job();
        phase = 4;
      }
      case 4 -> {
        var current = job(server);
        var entry = journal(server);
        if (current == null
            || current.dispatch() == null
            || moved(current.dispatch().inserted(), ore) == 0
            || entry == null
            || entry.pending()) return;
        require(server.saveEverything(true, true, true), "S3 save with job 2 in flight");
        expected.setProperty("job", job.toString());
        expected.setProperty("cellWorld", cell.world().toString());
        expected.setProperty("cellGeneration", Long.toString(cell.generation()));
        expected.setProperty("cellBacking", cell.backing().toString());
        expected.setProperty("s3Inserted", Long.toString(entry.inserted()));
        try (var output = Files.newOutputStream(observation)) {
          expected.store(output, "Observations only; never installed into world authority");
        }
        mark = server.getTickCount();
        phase = 5;
      }
      case 5 -> {
        // Keep mutating past S3 so the kill discards real unsaved work.
        if (server.getTickCount() - mark < 20) return;
        Files.writeString(
            report,
            "PASS\nmode=seed pid="
                + ProcessHandle.current().pid()
                + "\nS1 after job 1 inserted "
                + expected.getProperty("s1Inserted")
                + "; S2 after job 1 finished; S3 with job 2 inserted "
                + expected.getProperty("s3Inserted")
                + "; SIGKILL without a final save.\n");
        System.out.println("FACTORYCORE_INTERRUPTION_SEED_KILL");
        phase = -1;
        new ProcessBuilder("kill", "-9", Long.toString(ProcessHandle.current().pid())).start();
      }
      default -> {}
    }
  }

  private void resumeAfterKill(MinecraftServer server) throws IOException {
    if (!ready(server)) return;
    var current = job(server);
    switch (phase) {
      case 0 -> {
        require(current != null, "Job 2, in flight at S3, survives the kill");
        require(
            moved(current.dispatch().inserted(), ore)
                == Long.parseLong(expected.getProperty("s3Inserted")),
            "Resume starts from the S3 dispatch, not unsaved progress");
        phase = 1;
      }
      case 1 -> {
        if (current != null) {
          require(
              !"save_mismatch".equals(current.reason()) && !"uncertain".equals(current.reason()),
              "A consistent S3 world is not quarantined: " + current.reason());
          return;
        }
        long cellIngots = stock(server, ingot), cellOre = stock(server, ore);
        require(
            cellIngots == 2L * REQUESTED, "Both jobs' ingots arrive exactly once: " + cellIngots);
        require(
            cellOre + held(Items.RAW_IRON) + cellIngots + held(Items.IRON_INGOT) == SEEDED,
            "Every seeded unit is accounted for once, raw or smelted");
        require(journal(server) == null, "Finished dispatch leaves no journal");
        finish(
            server,
            "Resumed job 2 from S3 after SIGKILL: it finished with "
                + cellIngots
                + " ingots and "
                + cellOre
                + " raw iron in storage; no unit duplicated or lost.");
      }
      default -> {}
    }
  }

  private String machineState() {
    var state = new StringBuilder();
    for (int slot = 0; slot < furnace().getContainerSize(); slot++)
      state.append(furnace().getItem(slot)).append(';');
    return state.toString();
  }

  private void skewed(MinecraftServer server) throws IOException, CommandSyntaxException {
    if (!ready(server)) return;
    if ("data-old".equals(variant)) olderData(server);
    else olderChunks(server);
  }

  private int resolve(MinecraftServer server) throws IOException, CommandSyntaxException {
    long before = Files.exists(evidenceDir(server)) ? countEvidence(server) : 0;
    int resolved =
        server
            .getCommands()
            .getDispatcher()
            .execute(
                "factorycore recovery processing resolve %d %d %d"
                    .formatted(FURNACE.getX(), FURNACE.getY(), FURNACE.getZ()),
                server.createCommandSourceStack());
    require(resolved == 1, "Operator resolves the processing journal");
    require(countEvidence(server) > before, "Resolution preserved before-state evidence");
    return resolved;
  }

  private static long countEvidence(MinecraftServer server) throws IOException {
    try (var files = Files.list(evidenceDir(server))) {
      return files.count();
    }
  }

  /**
   * S1 SavedData (job 1 inserted, nothing collected) with S2 chunks (job 1 finished: no journal, no
   * iron in the furnace). The job's progress has no proof at the machine.
   */
  private void olderData(MinecraftServer server) throws IOException, CommandSyntaxException {
    var first =
        DeviceSavedData.get(server)
            .craftingJobs()
            .get(UUID.fromString(expected.getProperty("job1")));
    require(first != null, "S1 SavedData still holds job 1");
    switch (phase) {
      case 0 -> {
        if (!"save_mismatch".equals(first.reason())) {
          require(server.getTickCount() - started < 400, "Skew diagnosed: " + first.reason());
          return;
        }
        require(journal(server) == null, "S2 chunk holds no journal for job 1");
        frozen = machineState() + stock(server, ore) + "/" + stock(server, ingot);
        mark = server.getTickCount();
        phase = 1;
      }
      case 1 -> {
        require("save_mismatch".equals(first.reason()), "Quarantine persists: " + first.reason());
        require(
            (machineState() + stock(server, ore) + "/" + stock(server, ingot)).equals(frozen),
            "No machine or ledger change while the machine disagrees with the job");
        if (server.getTickCount() - mark < 100) return;
        resolve(server);
        var entry = journal(server);
        require(
            entry != null
                && entry
                    .progress()
                    .equals(
                        ProcessingJournal.digest(
                            first.dispatch().inserted(), first.dispatch().collected())),
            "Journal now matches the saved job record");
        mark = server.getTickCount();
        phase = 2;
      }
      case 2 -> {
        require(stock(server, ingot) == 0, "No ingot appears that the machine never returned");
        if (server.getTickCount() - mark < 200) return;
        // The job re-evaluates on its next scheduled pass; by now it must have left quarantine.
        require(
            !"save_mismatch".equals(first.reason()),
            "Resolved job is no longer quarantined: " + first.reason());
        finish(
            server,
            "S1 SavedData with S2 chunks: job 1 quarantined as save_mismatch with no machine or"
                + " ledger change for 100 ticks; resolution preserved evidence and aligned the"
                + " journal; the job then waited with "
                + stock(server, ingot)
                + " ingots in storage. The "
                + REQUESTED
                + " ingots that the S2 chunk had already handed back are lost to the skew and"
                + " reported, never re-created ("
                + first.reason()
                + ").");
      }
      default -> {}
    }
  }

  /**
   * S2 SavedData (job 1 finished) with S1 chunks (the furnace still holds job 1's inserted raw iron
   * and its non-empty journal). The orphan journal must block new dispatch at the machine.
   */
  private void olderChunks(MinecraftServer server) throws IOException, CommandSyntaxException {
    var first = UUID.fromString(expected.getProperty("job1"));
    switch (phase) {
      case 0 -> {
        require(
            DeviceSavedData.get(server).craftingJobs().get(first) == null, "Job 1 finished in S2");
        var entry = journal(server);
        require(entry != null && entry.job().equals(first) && !entry.empty(), "S1 orphan journal");
        require(stock(server, ingot) == REQUESTED, "S2 ledger holds job 1's ingots");
        var submission =
            WorldCraftingJobs.get(server)
                .submit(
                    new NetworkPermissions.Actor(principal.getUUID(), false),
                    at(INTERFACE),
                    ingot,
                    1);
        require(submission.job() != null, "Job 3 submitted: " + submission.reason());
        job = submission.job();
        frozen = Long.toString(stock(server, ore));
        mark = server.getTickCount();
        phase = 1;
      }
      case 1 -> {
        var third = job(server);
        require(third != null, "Job 3 waits rather than finishing");
        require(
            Long.toString(stock(server, ore)).equals(frozen) || third.dispatch() == null,
            "No ingredient is dispatched to a machine with an unfinished journal");
        if (!"machine_journal".equals(third.reason())) {
          require(server.getTickCount() - mark < 200, "Orphan journal blocks: " + third.reason());
          return;
        }
        if (server.getTickCount() - mark < 100) return;
        require(third.dispatch() == null, "Job 3 never dispatched while blocked");
        resolve(server);
        require(journal(server) == null, "Resolution removes the orphan journal");
        mark = server.getTickCount();
        phase = 2;
      }
      case 2 -> {
        var third = job(server);
        require(stock(server, ingot) <= REQUESTED + 1, "Job 3 adds at most its one ingot");
        if (third != null && "output_not_clean".equals(third.reason())) {
          // The older chunk's ingots are not the job's; Factory Core refuses to absorb them, so a
          // player empties the output slot by hand, as the wait reason asks.
          require(third.dispatch() == null, "Unowned output blocks dispatch");
          taken += furnace().removeItem(2, 64).getCount();
          return;
        }
        if (third != null && server.getTickCount() - mark < 900) return;
        require(
            third == null,
            "Job 3 finishes after resolution: " + (third == null ? "" : third.reason()));
        require(stock(server, ingot) == REQUESTED + 1, "Job 3 delivered exactly its one ingot");
        finish(
            server,
            "S2 SavedData with S1 chunks: the orphan job-1 journal blocked job 3 as machine_journal"
                + " with no dispatch for 100 ticks; resolution preserved evidence and removed the"
                + " journal; job 3 then refused the "
                + taken
                + " rolled-back ingots as output_not_clean (taken out by hand, never absorbed) and"
                + " finished with exactly one ingot added ("
                + stock(server, ingot)
                + " in storage).");
      }
      default -> {}
    }
  }

  private static Path evidenceDir(MinecraftServer server) {
    return server.getWorldPath(LevelResource.ROOT).resolve("factorycore-device-recovery");
  }

  private void finish(MinecraftServer server, String detail) throws IOException {
    Files.writeString(
        report,
        "PASS\nmode=" + variant + " pid=" + ProcessHandle.current().pid() + "\n" + detail + "\n");
    System.out.println("FACTORYCORE_INTERRUPTION_PASS mode=" + variant);
    phase = -1;
    server.halt(false);
  }
}
