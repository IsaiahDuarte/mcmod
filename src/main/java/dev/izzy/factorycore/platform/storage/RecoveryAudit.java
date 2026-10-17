package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.TransferRegistry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;

/** Explicit recovery-command evidence; synchronous bounded bytes, not a crash transaction log. */
final class RecoveryAudit {
  static final int MAX_FILES = 512, MAX_FILE_BYTES = 512 * 1024;
  static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;

  private RecoveryAudit() {}

  static Path preserve(Path world, UUID namespace, TransferRegistry.Stored record)
      throws IOException {
    var envelope = new CompoundTag();
    envelope.putInt("Schema", 1);
    envelope.putInt("WorldSchema", 12);
    envelope.putUUID("World", namespace);
    envelope.put("Transfers", TransferStateCodec.encode(List.of(record)));
    byte[] bytes = (envelope + "\n").getBytes(StandardCharsets.UTF_8);
    return preserve(
        world.resolve("factorycore-recovery"), record.id(), bytes, MAX_FILE_BYTES, ".snbt");
  }

  static Path preserve(Path folder, UUID id, byte[] bytes, int fileLimit, String extension)
      throws IOException {
    if (bytes.length > fileLimit) throw new IOException("Recovery audit record size limit");
    String digest;
    try {
      digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
    Files.createDirectories(folder);
    if (Files.isSymbolicLink(folder)) throw new IOException("Recovery audit directory is a link");
    var target = folder.resolve(id + "-" + digest + extension);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      verify(target, bytes);
      return target;
    }
    long total = 0;
    try (var files = Files.list(folder)) {
      var paths = files.limit(MAX_FILES + 1L).toList();
      if (paths.size() >= MAX_FILES) throw new IOException("Recovery audit file quota");
      for (var path : paths) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("Unexpected recovery audit entry");
        long size = Files.size(path);
        if (size > fileLimit || size > MAX_TOTAL_BYTES - total)
          throw new IOException("Recovery audit byte quota");
        total += size;
      }
    }
    if (bytes.length > MAX_TOTAL_BYTES - total) throw new IOException("Recovery audit byte quota");
    try (var output =
        FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      var buffer = ByteBuffer.wrap(bytes);
      for (int writes = 0; buffer.hasRemaining() && writes < 64; writes++) output.write(buffer);
      if (buffer.hasRemaining()) throw new IOException("Recovery evidence short write");
      output.force(true);
    } catch (FileAlreadyExistsException existing) {
      verify(target, bytes);
    }
    return target;
  }

  private static void verify(Path file, byte[] expected) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.size(file) != expected.length
        || !Arrays.equals(Files.readAllBytes(file), expected))
      throw new IOException("Recovery evidence differs; preserve and investigate");
  }
}
