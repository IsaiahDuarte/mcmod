package dev.izzy.factorycore.clienttesting;

import static dev.izzy.factorycore.clienttesting.RestartCheckpoint.require;

import com.mojang.authlib.GameProfile;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Two real dedicated JVMs; a trusted synthetic program principal supplies no player endpoint. */
final class StagingServerVerification {
  private final boolean restoring = Boolean.getBoolean("factorycore.staging.restore");
  private final Path report = Path.of(System.getProperty("factorycore.staging.report"));
  private final Path observation = Path.of(System.getProperty("factorycore.staging.expected"));
  private final StagedRecoveryVerification.Seed seed = new StagedRecoveryVerification.Seed();
  private final StagedRecoveryVerification recovery = new StagedRecoveryVerification();
  private Properties expected = new Properties();
  private ServerPlayer principal;
  private int started;
  private boolean done, compared;

  void register() {
    NeoForge.EVENT_BUS.addListener(this::started);
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  @SuppressWarnings("PMD.CloseResource") // Server owns the level and fake-player service.
  private void started(ServerStartedEvent event) {
    try {
      Files.createDirectories(report.getParent());
      var server = event.getServer();
      var level = server.overworld();
      level.setChunkForced(0, 0, true);
      principal =
          FakePlayerFactory.get(
              level,
              new GameProfile(
                  UUID.fromString("2b5c9e37-63f2-4d6b-8400-aba0b1386c9e"), "staging_probe"));
      started = server.getTickCount();
      if (restoring) {
        expected = RestartCheckpoint.read(observation);
        long previous = Long.parseLong(expected.getProperty("pid"));
        require(
            previous != ProcessHandle.current().pid()
                && ProcessHandle.of(previous).map(p -> !p.isAlive()).orElse(true),
            "Seed dedicated JVM exited before restore");
      }
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private void tick(ServerTickEvent.Post event) {
    if (principal == null || done) return;
    try {
      require(event.getServer().getTickCount() - started < 600, "Dedicated staging probe deadline");
      var saved = DeviceSavedData.get(event.getServer());
      if (restoring) {
        if (!StagedRecoveryVerification.ready(principal)) return;
        if (!compared) {
          var actual = saved.save(new CompoundTag(), principal.registryAccess());
          require(
              actual.equals(TagParser.parseTag(expected.getProperty("canonical"))),
              "Entire canonical staging save restored before progress");
          Files.writeString(report.getParent().resolve("restored-canonical.snbt"), actual + "\n");
          compared = true;
        }
        if (!recovery.tick(principal, expected)) return;
      } else {
        if (!seed.tick(principal, expected)) return;
        expected.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        expected.setProperty(
            "canonical", saved.save(new CompoundTag(), principal.registryAccess()).toString());
        try (var output = Files.newOutputStream(observation)) {
          expected.store(
              output, "Expected native observations; never installed into world authority");
        }
      }
      Files.writeString(
          report,
          "PASS\nmode="
              + (restoring ? "restore" : "seed")
              + " pid="
              + ProcessHandle.current().pid()
              + "\nThree accepted native operations: known delivery, known return, post-insert uncertainty.\n"
              + (restoring
                  ? "Canonical restore equal; delivery12+8=20; return17+3=20; uncertain delivery/refund rejected with unchanged native calls.\n"
                  : "Each source debited8; providers accepted3; known staging5 and uncertain pendingDELIVER8 saved.\n")
              + "Trusted synthetic program principal; no connected-player/session/UI claim.\n");
      done = true;
      System.out.println(
          "FACTORYCORE_STAGING_SERVER_PASS mode=" + (restoring ? "restore" : "seed"));
      event.getServer().halt(false);
    } catch (Exception error) {
      throw new IllegalStateException("Dedicated staged recovery verification failed", error);
    }
  }
}
