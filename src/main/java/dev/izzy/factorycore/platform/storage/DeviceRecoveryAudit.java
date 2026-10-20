package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.LedgerStateCodec;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.core.storage.ItemBuffer;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.network.NodeHandleData;
import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

  /**
   * Buffer adoption evidence (schema 6): canonical node (conflicted or not), exact slots,
   * deployment, target position and the adopting holder's native state.
   */
  static Path preserveBufferAdoption(
      Path world,
      UUID namespace,
      NodeRegistry.Stored owner,
      ItemBuffer.State inventory,
      BufferRegistry.Deployment deployment,
      NodeRegistry.Position target,
      CompoundTag blockState,
      CompoundTag blockEntity)
      throws IOException {
    if (owner.position() == null
        || owner.kind() != NodeRegistry.Kind.BUFFERED_INTERFACE
        || !owner.id().equals(inventory.backing())
        || deployment != null && !deployment.backing().equals(owner.id()))
      throw new IllegalArgumentException("Expected canonical buffer adoption evidence");
    var envelope = new CompoundTag();
    envelope.putInt("Schema", 6);
    envelope.putInt("WorldSchema", 12);
    envelope.putUUID("World", namespace);
    envelope.putString("HolderKind", "BUFFERED_INTERFACE_ADOPTION");
    envelope.put("Nodes", NodeStateCodec.encode(List.of(owner)));
    envelope.put(
        "Buffers",
        BufferStateCodec.encode(
            List.of(inventory), deployment == null ? List.of() : List.of(deployment)));
    var adopted = new CompoundTag();
    adopted.putString("Dimension", target.dimension());
    adopted.putLong("Position", target.block());
    envelope.put("Target", adopted);
    envelope.put("BlockState", blockState.copy());
    envelope.put("BlockEntity", blockEntity.copy());
    return write(world, owner.id(), envelope);
  }

  static Path preserveBuffer(
      Path world,
      UUID namespace,
      NodeRegistry.Stored owner,
      ItemBuffer.State inventory,
      BufferRegistry.Deployment deployment,
      CompoundTag blockState,
      CompoundTag blockEntity)
      throws IOException {
    if (!owner.conflict()
        || owner.position() == null
        || owner.kind() != NodeRegistry.Kind.BUFFERED_INTERFACE
        || !owner.id().equals(inventory.backing())
        || deployment != null && !deployment.backing().equals(owner.id()))
      throw new IllegalArgumentException("Expected canonical buffer conflict evidence");
    var envelope = new CompoundTag();
    envelope.putInt("Schema", 3);
    envelope.putInt("WorldSchema", 12);
    envelope.putUUID("World", namespace);
    envelope.putString("HolderKind", "BUFFERED_INTERFACE");
    envelope.put("Nodes", NodeStateCodec.encode(List.of(owner)));
    envelope.put(
        "Buffers",
        BufferStateCodec.encode(
            List.of(inventory), deployment == null ? List.of() : List.of(deployment)));
    envelope.put("BlockState", blockState.copy());
    envelope.put("BlockEntity", blockEntity.copy());
    return write(world, owner.id(), envelope);
  }

  /**
   * Internal server-thread evidence boundary for controller/gateway holders; never restores state.
   */
  /**
   * Network-node adoption evidence (schema 5): the canonical node before adoption (conflicted or
   * not), controller grants, the target position and the adopting holder's native state.
   */
  public static Path preserveNetworkNodeAdoption(
      Path world,
      UUID namespace,
      NodeRegistry.Stored owner,
      NetworkPermissions.State permissions,
      NodeRegistry.Position target,
      CompoundTag blockState,
      CompoundTag blockEntity)
      throws IOException {
    boolean controller = owner.kind() == NodeRegistry.Kind.CONTROLLER;
    if (owner.position() == null
        || !controller && owner.kind() != NodeRegistry.Kind.GATEWAY
        || controller != (permissions != null)
        || permissions != null && !permissions.network().equals(owner.id()))
      throw new IllegalArgumentException("Expected canonical network-node adoption evidence");
    var envelope = new CompoundTag();
    envelope.putInt("Schema", 5);
    envelope.putInt("WorldSchema", 12);
    envelope.putUUID("World", namespace);
    envelope.putString("HolderKind", "NETWORK_NODE_ADOPTION");
    envelope.put("Nodes", NodeStateCodec.encode(List.of(owner)));
    envelope.put(
        "Networks",
        NetworkStateCodec.encode(permissions == null ? List.of() : List.of(permissions)));
    var adopted = new CompoundTag();
    adopted.putString("Dimension", target.dimension());
    adopted.putLong("Position", target.block());
    envelope.put("Target", adopted);
    envelope.put("BlockState", blockState.copy());
    envelope.put("BlockEntity", blockEntity.copy());
    return write(world, owner.id(), envelope);
  }

  public static Path preserveNetworkNode(
      Path world,
      UUID namespace,
      NodeRegistry.Stored owner,
      NetworkPermissions.State permissions,
      CompoundTag blockState,
      CompoundTag blockEntity)
      throws IOException {
    boolean controller = owner.kind() == NodeRegistry.Kind.CONTROLLER;
    if (!owner.conflict()
        || owner.position() == null
        || !controller && owner.kind() != NodeRegistry.Kind.GATEWAY
        || controller != (permissions != null)
        || permissions != null && !permissions.network().equals(owner.id()))
      throw new IllegalArgumentException("Expected canonical network-node conflict evidence");
    var envelope = new CompoundTag();
    envelope.putInt("Schema", 4);
    envelope.putInt("WorldSchema", 12);
    envelope.putUUID("World", namespace);
    envelope.putString("HolderKind", "NETWORK_NODE");
    envelope.put("Nodes", NodeStateCodec.encode(List.of(owner)));
    envelope.put(
        "Networks",
        NetworkStateCodec.encode(permissions == null ? List.of() : List.of(permissions)));
    envelope.put("BlockState", blockState.copy());
    envelope.put("BlockEntity", blockEntity.copy());
    return write(world, owner.id(), envelope);
  }

  static Path preservePortAdoption(
      Path world,
      UUID namespace,
      NodeRegistry.Stored owner,
      MachinePresets.Deployment deployment,
      long fence,
      NodeRegistry.Position target,
      CompoundTag blockState,
      CompoundTag blockEntity)
      throws IOException {
    var state = ConnectorData.read(blockEntity.get(ConnectorData.FIELD));
    boolean machine = owner.kind() == NodeRegistry.Kind.MACHINE_INTERFACE;
    if (owner.position() == null
        || target.equals(owner.position())
        || !machine && owner.kind() != NodeRegistry.Kind.CONNECTOR
        || !state.lease().world().equals(namespace)
        || !state.lease().id().equals(owner.id())
        || state.lease().kind() != owner.kind()
        || state.lease().generation() == owner.generation()
        || !state.fault().isEmpty()
        || (machine ? fence < 0 : fence != 0 || deployment != null)
        || deployment != null
            && (!deployment.node().equals(owner.id())
                || deployment.configuration() > fence
                || deployment.leaseGeneration() > owner.generation()))
      throw new IllegalArgumentException("Expected stale foreign-port adoption evidence");
    var envelope = new CompoundTag();
    envelope.putInt("Schema", 7);
    envelope.putInt("WorldSchema", DeviceSavedData.SCHEMA);
    envelope.putUUID("World", namespace);
    envelope.putString("HolderKind", "FOREIGN_PORT_ADOPTION");
    envelope.put("Nodes", NodeStateCodec.encode(List.of(owner)));
    envelope.put(
        "MachinePresets",
        MachinePresetCodec.encode(
            deployment == null ? List.of() : List.of(deployment),
            machine && fence > 0 ? Map.of(owner.id(), fence) : Map.of()));
    var adopted = new CompoundTag();
    adopted.putString("Dimension", target.dimension());
    adopted.putLong("Position", target.block());
    envelope.put("Target", adopted);
    envelope.put("BlockState", blockState.copy());
    envelope.put("BlockEntity", blockEntity.copy());
    return write(world, owner.id(), envelope);
  }

  static Path preservePort(
      Path world,
      UUID namespace,
      NodeRegistry.Stored owner,
      MachinePresets.Deployment deployment,
      long fence,
      WorldConnectors.Handle registration,
      CompoundTag blockState,
      CompoundTag blockEntity)
      throws IOException {
    var state = ConnectorData.read(blockEntity.get(ConnectorData.FIELD));
    boolean machine = owner.kind() == NodeRegistry.Kind.MACHINE_INTERFACE;
    if (!owner.conflict()
        || owner.position() == null
        || !machine && owner.kind() != NodeRegistry.Kind.CONNECTOR
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
    envelope.put("Nodes", NodeStateCodec.encode(List.of(owner)));
    envelope.put(
        "MachinePresets",
        MachinePresetCodec.encode(
            deployment == null ? List.of() : List.of(deployment),
            machine ? Map.of(owner.id(), fence) : Map.of()));
    if (registration != null) {
      var binding = new CompoundTag();
      binding.put("Node", NodeHandleData.write(registration.connector()));
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

  /**
   * Before-state evidence for an operator processing-journal resolution (ADR 0057): audit schema 8,
   * {@code HolderKind=PROCESSING_JOURNAL}, the machine's raw journal ({@code {Absent:1b}} when the
   * machine held none), the named job's canonical record when it exists, the target and the
   * machine's native block state and block entity.
   */
  public static Path preserveProcessingJournal(
      Path world,
      UUID namespace,
      UUID journalJob,
      Tag journal,
      CraftingJob.Snapshot job,
      NodeRegistry.Position target,
      CompoundTag blockState,
      CompoundTag blockEntity)
      throws IOException {
    if (journal == null || job != null && !job.id().equals(journalJob))
      throw new IllegalArgumentException("Expected processing journal evidence");
    var envelope = new CompoundTag();
    envelope.putInt("Schema", 8);
    envelope.putInt("WorldSchema", DeviceSavedData.SCHEMA);
    envelope.putUUID("World", namespace);
    envelope.putString("HolderKind", "PROCESSING_JOURNAL");
    envelope.put("Journal", journal.copy());
    envelope.put("CraftingJobs", CraftingJobCodec.encode(job == null ? List.of() : List.of(job)));
    var machine = new CompoundTag();
    machine.putString("Dimension", target.dimension());
    machine.putLong("Position", target.block());
    envelope.put("Target", machine);
    envelope.put("BlockState", blockState.copy());
    envelope.put("BlockEntity", blockEntity.copy());
    return write(world, journalJob, envelope);
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
