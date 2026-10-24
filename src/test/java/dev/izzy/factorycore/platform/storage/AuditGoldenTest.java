package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.LedgerState;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import dev.izzy.factorycore.core.resource.TransferRegistry;
import dev.izzy.factorycore.core.storage.BufferPolicy;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.core.storage.ItemBuffer;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.network.NodeHandleData;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden audit envelopes: every recovery audit writer, fed fixed inputs, must write exactly the
 * committed {@code persistence/audit/<name>.snbt}. These files are evidence operators keep, so
 * their schema numbers, HolderKind strings and sections may only change deliberately. A missing
 * golden is written to {@code build/audit-goldens/} and fails, so adding one is a reviewed copy.
 */
class AuditGoldenTest {
  @TempDir Path folder;

  private static final UUID WORLD = new UUID(0, 100), ID = new UUID(0, 101);
  private static final ResourceKey KEY =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {1, 2, 3});
  private static final NodeRegistry.Position HERE =
      new NodeRegistry.Position("minecraft:overworld", 123);
  private static final NodeRegistry.Position TARGET =
      new NodeRegistry.Position("minecraft:overworld", 456);

  private static CompoundTag tag(String snbt) throws Exception {
    return TagParser.parseTag(snbt);
  }

  private static void assertGolden(String name, Path file) throws Exception {
    // Transfer audits are SNBT text; every other audit is binary NBT.
    CompoundTag actual;
    if (file.toString().endsWith(".snbt")) {
      actual = TagParser.parseTag(Files.readString(file, StandardCharsets.UTF_8).strip());
    } else {
      try (var input = new DataInputStream(Files.newInputStream(file))) {
        actual = NbtIo.read(input, NbtAccounter.unlimitedHeap());
      }
    }
    try (var golden =
        AuditGoldenTest.class.getResourceAsStream("/persistence/audit/" + name + ".snbt")) {
      if (golden == null) {
        var out = Path.of("build/audit-goldens/" + name + ".snbt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, actual + "\n");
        fail("No golden for " + name + "; review and copy " + out.toAbsolutePath());
      }
      var expected = TagParser.parseTag(new String(golden.readAllBytes(), StandardCharsets.UTF_8));
      assertEquals(expected, actual, name);
    }
  }

  private static DeviceRegistry.Stored cell(boolean conflict) {
    var water = new ResourceKey(ResourceKind.FLUID, "minecraft:water", new byte[] {1, 2});
    return new DeviceRegistry.Stored(
        new LedgerState(
            new UUID(0, 1),
            ResourceKind.FLUID,
            1000,
            false,
            8,
            8,
            Map.of(water, 800L),
            List.of(new LedgerState.Reservation(new UUID(0, 2), water, 300))),
        9,
        new DeviceRegistry.Location("minecraft:overworld", 123, 2),
        conflict);
  }

  private static DeviceRegistry.Stored bank(boolean conflict) {
    return new DeviceRegistry.Stored(
        new LedgerState(
            new UUID(0, 3),
            ResourceKind.ENERGY,
            100000,
            false,
            8,
            8,
            Map.of(ResourceKey.ENERGY, 900L),
            List.of(new LedgerState.Reservation(new UUID(0, 4), ResourceKey.ENERGY, 200))),
        2,
        new DeviceRegistry.Location("minecraft:overworld", 45, 0),
        conflict);
  }

  private static NodeRegistry.Stored node(NodeRegistry.Kind kind, boolean conflict) {
    var policy =
        kind == NodeRegistry.Kind.GATEWAY
            ? new GatewayPolicy(
                Set.of(NetworkPermissions.Permission.VIEW),
                Set.of(NetworkPermissions.Permission.DEPOSIT),
                Set.of(ResourceKind.FLUID))
            : null;
    return new NodeRegistry.Stored(ID, kind, 7, HERE, conflict, policy);
  }

  private static NetworkPermissions.State permissions() {
    return new NetworkPermissions.State(
        ID,
        new UUID(0, 102),
        19,
        Map.of(
            new UUID(0, 103),
            Set.of(NetworkPermissions.Permission.VIEW, NetworkPermissions.Permission.DEPOSIT)));
  }

  private static ItemBuffer.State inventory() {
    var slots = new ArrayList<>(Collections.nCopies(9, ItemBuffer.Slot.empty()));
    slots.set(8, new ItemBuffer.Slot(KEY, 19, 64));
    return new ItemBuffer.State(ID, slots);
  }

  private static BufferRegistry.Deployment bufferDeployment() {
    return new BufferRegistry.Deployment(
        ID,
        4,
        7,
        new UUID(0, 102),
        new UUID(0, 103),
        new UUID(0, 104),
        BufferRegistry.Mode.STOCK,
        new BufferPolicy(
            new ResourceFilter(
                ResourceKind.ITEM,
                ResourceFilter.Mode.INCLUDE,
                List.of(new ResourceFilter.Exact(KEY))),
            3,
            17));
  }

  private static MachinePresets.Deployment presetDeployment() {
    return new MachinePresets.Deployment(
        ID,
        2,
        7,
        5,
        MachinePresets.Face.EAST,
        new MachinePresets.Authority(new UUID(0, 102), new UUID(0, 103), new UUID(0, 104), 9),
        MachinePresets.Mode.STOCK,
        ResourceFilter.all(ResourceKind.ITEM),
        17);
  }

  private static CompoundTag holder(String field, net.minecraft.nbt.Tag reference) {
    var tag = new CompoundTag();
    tag.put(field, reference);
    tag.putString("retained", "complete physical evidence");
    return tag;
  }

  @Test
  void cellAndBankEnvelopes() throws Exception {
    var stack = tag("{id:'factorycore:starter_fluid_cell',count:1,components:{custom:'retained'}}");
    assertGolden("cell", DeviceRecoveryAudit.preserve(folder, WORLD, cell(true), stack));
    var target = new DeviceRegistry.Location("minecraft:overworld", 456, 1);
    assertGolden(
        "cell-adoption",
        DeviceRecoveryAudit.preserveAdoption(folder, WORLD, cell(false), target, stack));
    var state = tag("{Name:'factorycore:starter_energy_bank'}");
    var owner = bank(true);
    var handle = new DeviceRegistry.Handle(WORLD, owner.ledger().id(), owner.generation());
    var physical = holder(EnergyBankHandleData.FIELD, EnergyBankHandleData.write(handle));
    assertGolden("bank", DeviceRecoveryAudit.preserveBank(folder, WORLD, owner, state, physical));
    assertGolden(
        "bank-adoption",
        DeviceRecoveryAudit.preserveBankAdoption(
            folder,
            WORLD,
            bank(false),
            new DeviceRegistry.Location("minecraft:overworld", 789, 0),
            state,
            physical));
  }

  @Test
  void nodeAndBufferEnvelopes() throws Exception {
    var controller = node(NodeRegistry.Kind.CONTROLLER, true);
    var physical =
        holder(
            NodeHandleData.FIELD,
            NodeHandleData.write(new NodeRegistry.Handle(WORLD, ID, 7, controller.kind())));
    var state = tag("{Name:'factorycore:network_controller'}");
    assertGolden(
        "network-node",
        DeviceRecoveryAudit.preserveNetworkNode(
            folder, WORLD, controller, permissions(), state, physical));
    var gateway = node(NodeRegistry.Kind.GATEWAY, true);
    assertGolden(
        "network-node-gateway",
        DeviceRecoveryAudit.preserveNetworkNode(
            folder,
            WORLD,
            gateway,
            null,
            tag("{Name:'factorycore:gateway',Properties:{facing:'east'}}"),
            physical));
    assertGolden(
        "network-node-adoption",
        DeviceRecoveryAudit.preserveNetworkNodeAdoption(
            folder,
            WORLD,
            node(NodeRegistry.Kind.CONTROLLER, false),
            permissions(),
            TARGET,
            state,
            physical));
    var buffer = node(NodeRegistry.Kind.BUFFERED_INTERFACE, true);
    var reference =
        new BufferData.State(
            new NodeRegistry.Handle(WORLD, ID, 7, buffer.kind()), new UUID(0, 105), 4);
    var bufferHolder = holder(BufferData.FIELD, BufferData.write(reference));
    var bufferState = tag("{Name:'factorycore:interface'}");
    assertGolden(
        "buffer",
        DeviceRecoveryAudit.preserveBuffer(
            folder, WORLD, buffer, inventory(), bufferDeployment(), bufferState, bufferHolder));
    assertGolden(
        "buffer-adoption",
        DeviceRecoveryAudit.preserveBufferAdoption(
            folder,
            WORLD,
            node(NodeRegistry.Kind.BUFFERED_INTERFACE, false),
            inventory(),
            bufferDeployment(),
            TARGET,
            bufferState,
            bufferHolder));
  }

  @Test
  void portJournalAndTransferEnvelopes() throws Exception {
    var owner = node(NodeRegistry.Kind.MACHINE_INTERFACE, true);
    var reference =
        new ConnectorData.State(
            new NodeRegistry.Handle(WORLD, ID, 7, owner.kind()),
            new ConnectorSettings(
                ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 7, 1, 2, 3),
            5,
            "",
            "furnace");
    var physical = holder(ConnectorData.FIELD, ConnectorData.write(reference));
    var state = tag("{Name:'factorycore:interface'}");
    var binding =
        new WorldConnectors.Handle(
            reference.lease(), 5, new UUID(0, 106), new UUID(0, 107), ResourceKind.ITEM);
    assertGolden(
        "port",
        DeviceRecoveryAudit.preservePort(
            folder, WORLD, owner, presetDeployment(), 5, binding, state, physical));
    // Adoption is evidence about a stale holder: an older lease generation than the owner's.
    var stale =
        new ConnectorData.State(
            new NodeRegistry.Handle(WORLD, ID, 1, owner.kind()),
            reference.settings(),
            3,
            "",
            reference.label());
    var stalePhysical = holder(ConnectorData.FIELD, ConnectorData.write(stale));
    assertGolden(
        "port-adoption",
        DeviceRecoveryAudit.preservePortAdoption(
            folder,
            WORLD,
            node(NodeRegistry.Kind.MACHINE_INTERFACE, false),
            presetDeployment(),
            7,
            TARGET,
            state,
            stalePhysical));
    var job = new UUID(0, 900);
    var snapshot =
        new CraftingJob.Snapshot(
            job,
            new UUID(0, 1),
            new UUID(0, 2),
            new UUID(0, 2),
            HERE,
            KEY,
            8,
            List.of(new CraftingJob.Step(new UUID(0, 5), 2, KEY, 2, 1)),
            Map.of(new UUID(0, 6), Map.of(KEY, 3L)),
            Map.of(KEY, 4L),
            CraftingJob.State.WAITING,
            "offline");
    assertGolden(
        "processing-journal",
        DeviceRecoveryAudit.preserveProcessingJournal(
            folder, WORLD, job, tag("{Absent:1b}"), snapshot, TARGET, state, physical));
    var record =
        new TransferRegistry.Stored(
            new UUID(0, 10),
            new StagedTransfer.Snapshot(
                new UUID(0, 11),
                KEY,
                new UUID(0, 12),
                new UUID(0, 13),
                5,
                3,
                0,
                StagedTransfer.State.STAGED,
                OperationResult.Reason.FULL,
                "Known partial",
                StagedTransfer.PendingCall.NONE,
                0));
    assertGolden("transfer", RecoveryAudit.preserve(folder, WORLD, record));
  }
}
