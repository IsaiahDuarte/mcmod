package dev.izzy.factorycore.clienttesting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/**
 * Shared layout and step barrier for the three-JVM multiplayer check. Only the barrier uses files:
 * the dedicated server publishes the current step and each client acknowledges a step once its
 * role's in-game actions for it have completed over the real connection. Every game action and
 * observation itself travels as ordinary Minecraft/Factory Core packets.
 */
final class MultiplayerSync {
  static final String OWNER = "FactoryOwner", STRANGER = "FactoryGuest";
  static final UUID OWNER_ID = UUIDUtil.createOfflinePlayerUUID(OWNER),
      STRANGER_ID = UUIDUtil.createOfflinePlayerUUID(STRANGER);
  static final BlockPos ROOT = new BlockPos(1, 65, 1),
      DRIVE = new BlockPos(2, 65, 1),
      TERMINAL = new BlockPos(1, 65, 2),
      GATEWAY = new BlockPos(0, 65, 1),
      BRANCH = new BlockPos(-1, 65, 1);
  static final int SEEDED_IRON = 64;
  static final String TERMINAL_ARGS =
      TERMINAL.getX() + " " + TERMINAL.getY() + " " + TERMINAL.getZ();

  /** Steps in order; see the server fixture for what each verifies. */
  static final int STRANGER_DENIED = 1,
      OWNER_POLICY = 2,
      STRANGER_POLICY = 3,
      GRANT_VIEW = 4,
      VIEW_ONLY = 5,
      GRANT_MOVE = 6,
      BOTH_OPEN = 7,
      STRANGER_TAKES = 8,
      OWNER_TAKES = 9,
      STRANGER_DEPOSITS = 10,
      BOTH_TAKE = 11,
      REVOKE = 12,
      OPERATOR_GRANT = 13,
      FINISH = 14;

  private final Path directory;

  MultiplayerSync(Path directory) {
    this.directory = directory;
  }

  Path directory() {
    return directory;
  }

  int step() {
    return read("step");
  }

  int acknowledged(String role) {
    return read(role + ".ack");
  }

  void publish(int step) {
    write("step", step);
  }

  void acknowledge(String role, int step) {
    write(role + ".ack", step);
  }

  private int read(String name) {
    try {
      var text = Files.readString(directory.resolve(name), StandardCharsets.UTF_8).trim();
      return text.isEmpty() ? 0 : Integer.parseInt(text);
    } catch (NoSuchFileException absent) {
      return 0;
    } catch (IOException | NumberFormatException error) {
      throw new IllegalStateException("Unreadable multiplayer barrier " + name, error);
    }
  }

  /** Atomic replace, so a reader never sees a partial value. */
  private void write(String name, int value) {
    try {
      Files.createDirectories(directory);
      var temporary = directory.resolve(name + ".tmp");
      Files.writeString(temporary, Integer.toString(value), StandardCharsets.UTF_8);
      Files.move(
          temporary,
          directory.resolve(name),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException error) {
      throw new IllegalStateException("Cannot write multiplayer barrier " + name, error);
    }
  }
}
