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

  /**
   * {@code WorldSchema} in an audit names the world schema whose table encodings the envelope
   * embeds, so its sections decode with that schema's codecs; it is not the writing world's
   * version. Most envelopes embed tables unchanged since world schema 12 (ADR 0031). They are
   * pinned here, not tied to {@link DeviceSavedData#SCHEMA}, so a world schema bump cannot silently
   * change audit bytes ({@code AuditGoldenTest}).
   */
  static final int TABLE_SCHEMA = 12;

  /**
   * Port adoption (schema 7) and processing journal (schema 8) envelopes embed schema-13 tables.
   */
  static final int JOB_TABLE_SCHEMA = 13;

  static Path preserve(Path world, UUID namespace, DeviceRegistry.Stored owner, Tag stack)
      throws IOException {
    return Envelope.lease(1, namespace, null, owner, true)
        .put("Stack", stack.copy())
        .write(world, owner.ledger().id());
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
    return Envelope.lease(3, namespace, "CELL_ADOPTION", owner, false)
        .target(target)
        .put("Stack", stack.copy())
        .write(world, owner.ledger().id());
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
    return Envelope.lease(4, namespace, "ENERGY_BANK_ADOPTION", owner, false)
        .target(target)
        .holder(blockState, blockEntity)
        .write(world, owner.ledger().id());
  }

  static Path preserveBank(
      Path world,
      UUID namespace,
      DeviceRegistry.Stored owner,
      CompoundTag blockState,
      CompoundTag blockEntity)
      throws IOException {
    return Envelope.lease(2, namespace, "ENERGY_BANK", owner, true)
        .holder(blockState, blockEntity)
        .write(world, owner.ledger().id());
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
    return new Envelope(6, TABLE_SCHEMA, namespace, "BUFFERED_INTERFACE_ADOPTION")
        .nodes(owner)
        .put(
            "Buffers",
            BufferStateCodec.encode(
                List.of(inventory), deployment == null ? List.of() : List.of(deployment)))
        .target(target)
        .holder(blockState, blockEntity)
        .write(world, owner.id());
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
    return new Envelope(3, TABLE_SCHEMA, namespace, "BUFFERED_INTERFACE")
        .nodes(owner)
        .put(
            "Buffers",
            BufferStateCodec.encode(
                List.of(inventory), deployment == null ? List.of() : List.of(deployment)))
        .holder(blockState, blockEntity)
        .write(world, owner.id());
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
    return new Envelope(5, TABLE_SCHEMA, namespace, "NETWORK_NODE_ADOPTION")
        .nodes(owner)
        .put(
            "Networks",
            NetworkStateCodec.encode(permissions == null ? List.of() : List.of(permissions)))
        .target(target)
        .holder(blockState, blockEntity)
        .write(world, owner.id());
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
    return new Envelope(4, TABLE_SCHEMA, namespace, "NETWORK_NODE")
        .nodes(owner)
        .put(
            "Networks",
            NetworkStateCodec.encode(permissions == null ? List.of() : List.of(permissions)))
        .holder(blockState, blockEntity)
        .write(world, owner.id());
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
    return new Envelope(7, JOB_TABLE_SCHEMA, namespace, "FOREIGN_PORT_ADOPTION")
        .nodes(owner)
        .put(
            "MachinePresets",
            MachinePresetCodec.encode(
                deployment == null ? List.of() : List.of(deployment),
                machine && fence > 0 ? Map.of(owner.id(), fence) : Map.of()))
        .target(target)
        .holder(blockState, blockEntity)
        .write(world, owner.id());
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
    var envelope =
        new Envelope(5, TABLE_SCHEMA, namespace, "FOREIGN_PORT")
            .nodes(owner)
            .put(
                "MachinePresets",
                MachinePresetCodec.encode(
                    deployment == null ? List.of() : List.of(deployment),
                    machine ? Map.of(owner.id(), fence) : Map.of()))
            .holder(blockState, blockEntity);
    if (registration != null) {
      var binding = new CompoundTag();
      binding.put("Node", NodeHandleData.write(registration.connector()));
      binding.putLong("Configuration", registration.generation());
      binding.putUUID("Provider", registration.provider());
      binding.putUUID("Backing", registration.backing());
      binding.putString("Kind", registration.kind().name());
      envelope.put("Registration", binding);
    }
    return envelope.write(world, owner.id());
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
    return new Envelope(8, JOB_TABLE_SCHEMA, namespace, "PROCESSING_JOURNAL")
        .put("Journal", journal.copy())
        .put("CraftingJobs", CraftingJobCodec.encode(job == null ? List.of() : List.of(job)))
        .target(target)
        .holder(blockState, blockEntity)
        .write(world, journalJob);
  }

  /**
   * One audit envelope: the shared header (audit schema, embedded table schema, world namespace and
   * holder kind), then the kind's canonical sections, target and raw holder data. Written as
   * bounded binary NBT under a content-addressed name, so identical evidence is never duplicated.
   */
  private static final class Envelope {
    private final CompoundTag tag = new CompoundTag();

    /** {@code holderKind} is null only for the original cell envelope (schema 1). */
    Envelope(int schema, int tableSchema, UUID namespace, String holderKind) {
      tag.putInt("Schema", schema);
      tag.putInt("WorldSchema", tableSchema);
      tag.putUUID("World", namespace);
      if (holderKind != null) tag.putString("HolderKind", holderKind);
    }

    /**
     * A cell or bank lease envelope: generation, conflict flag, canonical location and the full
     * ledger. {@code requireConflict} is false for adoption, which also accepts unconflicted
     * leases.
     */
    static Envelope lease(
        int schema,
        UUID namespace,
        String holderKind,
        DeviceRegistry.Stored owner,
        boolean requireConflict) {
      if (requireConflict && !owner.conflict() || owner.location() == null)
        throw new IllegalArgumentException("Expected placed conflict evidence");
      var envelope = new Envelope(schema, TABLE_SCHEMA, namespace, holderKind);
      envelope.tag.putLong("Generation", owner.generation());
      envelope.tag.putBoolean("Conflict", owner.conflict());
      var location = new CompoundTag();
      location.putString("Dimension", owner.location().dimension());
      location.putLong("Position", owner.location().position());
      location.putInt("Slot", owner.location().slot());
      envelope.tag.put("Location", location);
      envelope.tag.putByteArray("Ledger", LedgerStateCodec.encode(owner.ledger()));
      return envelope;
    }

    Envelope put(String key, Tag value) {
      tag.put(key, value);
      return this;
    }

    /** The canonical node record before the change. */
    Envelope nodes(NodeRegistry.Stored owner) {
      return put("Nodes", NodeStateCodec.encode(List.of(owner)));
    }

    /** The adopted or affected block position. */
    Envelope target(NodeRegistry.Position target) {
      var place = new CompoundTag();
      place.putString("Dimension", target.dimension());
      place.putLong("Position", target.block());
      return put("Target", place);
    }

    /** The adopted cell or bank slot location. */
    Envelope target(DeviceRegistry.Location target) {
      var place = new CompoundTag();
      place.putString("Dimension", target.dimension());
      place.putLong("Position", target.position());
      place.putInt("Slot", target.slot());
      return put("Target", place);
    }

    /** The holder's complete native block state and block entity, copied. */
    Envelope holder(CompoundTag blockState, CompoundTag blockEntity) {
      put("BlockState", blockState.copy());
      return put("BlockEntity", blockEntity.copy());
    }

    Path write(Path world, UUID id) throws IOException {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(new BoundedOutput(bytes))) {
        NbtIo.write(tag, output);
      }
      return RecoveryAudit.preserve(
          world.resolve("factorycore-device-recovery"),
          id,
          bytes.toByteArray(),
          MAX_FILE_BYTES,
          ".nbt");
    }
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
