package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.LedgerStateCodec;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.io.*;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.*;

/** Versioned binary before-state evidence; never a restore instruction or live authority. */
final class DeviceRecoveryAudit {
  static final int MAX_FILE_BYTES = LedgerStateCodec.MAX_BYTES + 1024 * 1024;

  private DeviceRecoveryAudit() {}

  static Path preserve(Path world, UUID namespace, DeviceRegistry.Stored owner, Tag stack)
      throws IOException {
    var envelope = envelope(namespace, owner, 1);
    envelope.put("Stack", stack.copy());
    return write(world, owner, envelope);
  }

  static Path preserveBank(
      Path world,
      UUID namespace,
      DeviceRegistry.Stored owner,
      CompoundTag blockState,
      CompoundTag blockEntity)
      throws IOException {
    var envelope = envelope(namespace, owner, 2);
    envelope.putString("HolderKind", "ENERGY_BANK");
    envelope.put("BlockState", blockState.copy());
    envelope.put("BlockEntity", blockEntity.copy());
    return write(world, owner, envelope);
  }

  private static CompoundTag envelope(UUID namespace, DeviceRegistry.Stored owner, int schema) {
    if (!owner.conflict() || owner.location() == null)
      throw new IllegalArgumentException("Expected placed conflict evidence");
    var envelope = new CompoundTag();
    envelope.putInt("Schema", schema);
    envelope.putInt("WorldSchema", 12);
    envelope.putUUID("World", namespace);
    envelope.putLong("Generation", owner.generation());
    envelope.putBoolean("Conflict", owner.conflict());
    var location = new CompoundTag();
    location.putString("Dimension", owner.location().dimension());
    location.putLong("Position", owner.location().position());
    location.putInt("Slot", owner.location().slot());
    envelope.put("Location", location);
    envelope.putByteArray("Ledger", LedgerStateCodec.encode(owner.ledger()));
    return envelope;
  }

  private static Path write(Path world, DeviceRegistry.Stored owner, CompoundTag envelope)
      throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var output = new DataOutputStream(new BoundedOutput(bytes))) {
      NbtIo.write(envelope, output);
    }
    return RecoveryAudit.preserve(
        world.resolve("factorycore-device-recovery"),
        owner.ledger().id(),
        bytes.toByteArray(),
        MAX_FILE_BYTES,
        ".nbt");
  }

  private static final class BoundedOutput extends OutputStream {
    private final ByteArrayOutputStream bytes;

    BoundedOutput(ByteArrayOutputStream bytes) {
      this.bytes = bytes;
    }

    @Override
    public void write(int value) throws IOException {
      requireSpace(1);
      bytes.write(value);
    }

    @Override
    public void write(byte[] values, int offset, int length) throws IOException {
      requireSpace(length);
      bytes.write(values, offset, length);
    }

    private void requireSpace(int length) throws IOException {
      if (length > MAX_FILE_BYTES - bytes.size()) throw new IOException("Device audit size limit");
    }
  }
}
