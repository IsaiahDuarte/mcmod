package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.LedgerStateCodec;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.io.*;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.*;

/** Versioned binary before-state evidence; never a restore instruction or live authority. */
public final class DeviceRecoveryAudit {
  static final int MAX_FILE_BYTES = LedgerStateCodec.MAX_BYTES + 1024 * 1024;

  private DeviceRecoveryAudit() {}

  static Path preserve(Path world, UUID namespace, DeviceRegistry.Stored owner, Tag stack)
      throws IOException {
    var envelope = envelope(namespace, owner, 1);
    envelope.put("Stack", stack.copy());
    return write(world, owner.ledger().id(), envelope);
  }

  /**
   * Cell adoption evidence (schema 3): the canonical lease before adoption (conflicted or not), the
   * adopted target location and the complete adopted physical stack.
   */
  static Path preserveAdoption(
      Path world,
      UUID namespace,
      DeviceRegistry.Stored owner,
      DeviceRegistry.Location target,
      Tag stack)
      throws IOException {
    var envelope = envelope(namespace, owner, 3, false);
    envelope.putString("HolderKind", "CELL_ADOPTION");
    var adopted = new CompoundTag();
    adopted.putString("Dimension", target.dimension());
    adopted.putLong("Position", target.position());
    adopted.putInt("Slot", target.slot());
    envelope.put("Target", adopted);
    envelope.put("Stack", stack.copy());
    return write(world, owner.ledger().id(), envelope);
  }

  /**
   * Bank adoption evidence (schema 4): the canonical lease before adoption, the adopted target
   * position and the adopting bank's complete native state and block entity.
   */
  static Path preserveBankAdoption(
      Path world,
      UUID namespace,
      DeviceRegistry.Stored owner,
      DeviceRegistry.Location target,
      CompoundTag blockState,
      CompoundTag blockEntity)
      throws IOException {
    var envelope = envelope(namespace, owner, 4, false);
    envelope.putString("HolderKind", "ENERGY_BANK_ADOPTION");
    var adopted = new CompoundTag();
    adopted.putString("Dimension", target.dimension());
    adopted.putLong("Position", target.position());
    adopted.putInt("Slot", target.slot());
    envelope.put("Target", adopted);
    envelope.put("BlockState", blockState.copy());
    envelope.put("BlockEntity", blockEntity.copy());
    return write(world, owner.ledger().id(), envelope);
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
    return write(world, owner.ledger().id(), envelope);
  }

  static Path preserveBuffer(
      Path world,
      UUID namespace,
      dev.izzy.factorycore.core.network.NodeRegistry.Stored owner,
      dev.izzy.factorycore.core.storage.ItemBuffer.State inventory,
      dev.izzy.factorycore.core.storage.BufferRegistry.Deployment deployment,
      CompoundTag blockState,
      CompoundTag blockEntity)
      throws IOException {
    if (!owner.conflict()
        || owner.position() == null
        || owner.kind() != dev.izzy.factorycore.core.network.NodeRegistry.Kind.BUFFERED_INTERFACE
        || !owner.id().equals(inventory.backing())
        || deployment != null && !deployment.backing().equals(owner.id()))
      throw new IllegalArgumentException("Expected canonical buffer conflict evidence");
    var envelope = new CompoundTag();
    envelope.putInt("Schema", 3);
    envelope.putInt("WorldSchema", 12);
    envelope.putUUID("World", namespace);
    envelope.putString("HolderKind", "BUFFERED_INTERFACE");
    envelope.put("Nodes", NodeStateCodec.encode(java.util.List.of(owner)));
    envelope.put(
        "Buffers",
        BufferStateCodec.encode(
            java.util.List.of(inventory),
            deployment == null ? java.util.List.of() : java.util.List.of(deployment)));
    envelope.put("BlockState", blockState.copy());
    envelope.put("BlockEntity", blockEntity.copy());
    return write(world, owner.id(), envelope);
  }

  /**
   * Internal server-thread evidence boundary for controller/gateway holders; never restores state.
   */
  public static Path preserveNetworkNode(
      Path world,
      UUID namespace,
      dev.izzy.factorycore.core.network.NodeRegistry.Stored owner,
      dev.izzy.factorycore.core.network.NetworkPermissions.State permissions,
      CompoundTag blockState,
      CompoundTag blockEntity)
      throws IOException {
    boolean controller =
        owner.kind() == dev.izzy.factorycore.core.network.NodeRegistry.Kind.CONTROLLER;
    if (!owner.conflict()
        || owner.position() == null
        || !controller
            && owner.kind() != dev.izzy.factorycore.core.network.NodeRegistry.Kind.GATEWAY
        || controller != (permissions != null)
        || permissions != null && !permissions.network().equals(owner.id()))
      throw new IllegalArgumentException("Expected canonical network-node conflict evidence");
    var envelope = new CompoundTag();
    envelope.putInt("Schema", 4);
    envelope.putInt("WorldSchema", 12);
    envelope.putUUID("World", namespace);
    envelope.putString("HolderKind", "NETWORK_NODE");
    envelope.put("Nodes", NodeStateCodec.encode(java.util.List.of(owner)));
    envelope.put(
        "Networks",
        NetworkStateCodec.encode(
            permissions == null ? java.util.List.of() : java.util.List.of(permissions)));
    envelope.put("BlockState", blockState.copy());
    envelope.put("BlockEntity", blockEntity.copy());
    return write(world, owner.id(), envelope);
  }

  static Path preservePort(
      Path world,
      UUID namespace,
      dev.izzy.factorycore.core.network.NodeRegistry.Stored owner,
      dev.izzy.factorycore.core.storage.MachinePresets.Deployment deployment,
      long fence,
      WorldConnectors.Handle registration,
      CompoundTag blockState,
      CompoundTag blockEntity)
      throws IOException {
    var state = ConnectorData.read(blockEntity.get(ConnectorData.FIELD));
    boolean machine =
        owner.kind() == dev.izzy.factorycore.core.network.NodeRegistry.Kind.MACHINE_INTERFACE;
    if (!owner.conflict()
        || owner.position() == null
        || !machine && owner.kind() != dev.izzy.factorycore.core.network.NodeRegistry.Kind.CONNECTOR
        || !state.lease().world().equals(namespace)
        || !state.lease().id().equals(owner.id())
        || state.lease().generation() != owner.generation()
        || state.lease().kind() != owner.kind()
        || !state.fault().isEmpty()
        || (machine ? fence <= 0 || fence != state.generation() : fence != 0 || deployment != null)
        || deployment != null
            && (!deployment.node().equals(owner.id())
                || deployment.configuration() > fence
                || deployment.leaseGeneration() > owner.generation())
        || registration != null
            && (!registration.connector().equals(state.lease())
                || registration.generation() != state.generation()
                || registration.kind() != state.settings().kind()))
      throw new IllegalArgumentException("Expected exact canonical port conflict evidence");
    var envelope = new CompoundTag();
    envelope.putInt("Schema", 5);
    envelope.putInt("WorldSchema", 12);
    envelope.putUUID("World", namespace);
    envelope.putString("HolderKind", "FOREIGN_PORT");
    envelope.put("Nodes", NodeStateCodec.encode(java.util.List.of(owner)));
    envelope.put(
        "MachinePresets",
        MachinePresetCodec.encode(
            deployment == null ? java.util.List.of() : java.util.List.of(deployment),
            machine ? java.util.Map.of(owner.id(), fence) : java.util.Map.of()));
    if (registration != null) {
      var binding = new CompoundTag();
      binding.put(
          "Node",
          dev.izzy.factorycore.platform.network.NodeHandleData.write(registration.connector()));
      binding.putLong("Configuration", registration.generation());
      binding.putUUID("Provider", registration.provider());
      binding.putUUID("Backing", registration.backing());
      binding.putString("Kind", registration.kind().name());
      envelope.put("Registration", binding);
    }
    envelope.put("BlockState", blockState.copy());
    envelope.put("BlockEntity", blockEntity.copy());
    return write(world, owner.id(), envelope);
  }

  private static CompoundTag envelope(UUID namespace, DeviceRegistry.Stored owner, int schema) {
    return envelope(namespace, owner, schema, true);
  }

  private static CompoundTag envelope(
      UUID namespace, DeviceRegistry.Stored owner, int schema, boolean conflict) {
    if (conflict && !owner.conflict() || owner.location() == null)
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

  private static Path write(Path world, UUID id, CompoundTag envelope) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var output = new DataOutputStream(new BoundedOutput(bytes))) {
      NbtIo.write(envelope, output);
    }
    return RecoveryAudit.preserve(
        world.resolve("factorycore-device-recovery"),
        id,
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
