package dev.izzy.factorycore.clienttesting;

import static dev.izzy.factorycore.clienttesting.RestartCheckpoint.require;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Actual accepted scheduled operations; expectations never synthesize or restore staging. */
final class StagedRecoveryVerification {
  private static final BlockPos ROOT = new BlockPos(9, 65, 9), DRIVE = ROOT.east();
  private static final List<BlockPos> MACHINES = List.of(ROOT.south(), DRIVE.east(), DRIVE.south());

  private static BlockPos target(int i) {
    return i == 1 ? MACHINES.get(i).east() : MACHINES.get(i).south();
  }

  private static ResourceKey key(ServerPlayer player, int i) {
    var item = new ItemStack(i == 0 ? Items.DIAMOND : i == 1 ? Items.LAPIS_LAZULI : Items.EMERALD);
    item.set(DataComponents.CUSTOM_NAME, Component.literal("Staging fixture " + i));
    return new PlatformResourceCodec(player.registryAccess()).itemKey(item);
  }

  static boolean ready(ServerPlayer player) {
    var entity = player.serverLevel().getBlockEntity(MACHINES.getFirst());
    return entity instanceof MachineInterfaceEntity machine
        && WorldNetworks.get(player.server).membership(machine.position()).status()
            == NetworkTopology.Status.VALID;
  }

  static final class Seed {
    private final UUID[] ids = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
    private final WorldStorageTransfers.Result[] results = new WorldStorageTransfers.Result[3];
    private DeviceRegistry.Handle cell;
    private int phase, next;
    private boolean submitted;

    boolean tick(ServerPlayer player, Properties expected) {
      var level = player.serverLevel();
      var saved = DeviceSavedData.get(player.server);
      var runtime = WorldNetworks.get(player.server);
      var actor = new NetworkPermissions.Actor(player.getUUID(), false);
      if (phase == 0) {
        level.setBlock(ROOT, NetworkContent.CONTROLLER.get().defaultBlockState(), 3);
        ((NetworkNodeEntity) level.getBlockEntity(ROOT)).commission(player.getUUID());
        level.setBlock(DRIVE, StorageContent.DRIVE.get().defaultBlockState(), 3);
        var drive = (StorageDriveEntity) level.getBlockEntity(DRIVE);
        drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
        cell = CellHandleData.read(drive.cell(0));
        for (int i = 0; i < 3; i++) {
          level.setBlock(
              MACHINES.get(i),
              MachineInterfaceContent.BLOCK
                  .get()
                  .defaultBlockState()
                  .setValue(BlockStateProperties.FACING, i == 1 ? Direction.EAST : Direction.SOUTH),
              3);
          ((MachineInterfaceEntity) level.getBlockEntity(MACHINES.get(i)))
              .place(new ItemStack(MachineInterfaceContent.ITEM.get()));
          level.setBlock(target(i), RecoveryProvider.BLOCK.get().defaultBlockState(), 3);
        }
        ((RecoveryProvider.Provider) level.getBlockEntity(target(2))).failAfterInsert = true;
        phase = 1;
        return false;
      }
      if (phase == 1) {
        var machine = (MachineInterfaceEntity) level.getBlockEntity(MACHINES.getFirst());
        if (runtime.membership(machine.position()).status() != NetworkTopology.Status.VALID)
          return false;
        for (int i = 0; i < 3; i++) {
          require(
              saved.registry().insert(cell, key(player, i), 20).amount() == 20,
              "Seed20 actual units per staged fixture");
          var current = (MachineInterfaceEntity) level.getBlockEntity(MACHINES.get(i));
          current.configure(
              actor,
              new ConnectorSettings(
                  ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 1, 8));
        }
        phase = 2;
      }
      if (next < 3) {
        final int index = next;
        if (!submitted) {
          var machine = (MachineInterfaceEntity) level.getBlockEntity(MACHINES.get(index));
          var bound = runtime.connectors().bind(machine);
          if (bound.handle() == null) return false;
          var submission =
              runtime
                  .transfers()
                  .submit(
                      ids[index],
                      actor,
                      machine.position(),
                      new WorldStorageTransfers.Digital(cell),
                      new WorldStorageTransfers.External(bound.handle()),
                      key(player, index),
                      8,
                      result -> results[index] = result);
          require(
              submission.rejection() == OperationResult.Reason.OK,
              "Accept native transfer before extraction");
          submitted = true;
          return false;
        }
        if (results[index] == null) return false;
        var result = results[index];
        var move = saved.transfers().get(ids[index]);
        require(
            saved.registry().stock(cell, key(player, index)).total() == 12
                && ((RecoveryProvider.Provider) level.getBlockEntity(target(index)))
                        .items
                        .getStackInSlot(0)
                        .getCount()
                    == 3,
            "Native scheduled call debits8 and physically inserts3");
        require(
            index == 2
                ? result.reason() == OperationResult.Reason.UNCERTAIN
                    && move.state() == StagedTransfer.State.QUARANTINED
                    && move.pending() == StagedTransfer.PendingCall.DELIVER
                    && move.pendingAmount() == 8
                    && move.staged() == 8
                    && move.delivered() == 0
                : move.staged() == 5
                    && move.delivered() == 3
                    && move.pending() == StagedTransfer.PendingCall.NONE,
            "Persist actual known partial or uncertain post-insert result");
        next++;
        submitted = false;
        return false;
      }
      var known = (MachineInterfaceEntity) level.getBlockEntity(MACHINES.getFirst());
      if (runtime.membership(known.position()).status() != NetworkTopology.Status.VALID)
        return false;
      for (int i = 0; i < 3; i++) {
        expected.setProperty("staged." + i, ids[i].toString());
        expected.setProperty(
            "stagedCalls." + i,
            Integer.toString(((RecoveryProvider.Provider) level.getBlockEntity(target(i))).calls));
      }
      System.out.println("FACTORYCORE_STAGING_SEEDED known=5 return=5 uncertain=DELIVER8");
      return true;
    }
  }

  private int phase;
  private final WorldStorageTransfers.Result[] results = new WorldStorageTransfers.Result[2];

  boolean tick(ServerPlayer player, Properties expected) {
    var saved = DeviceSavedData.get(player.server);
    var runtime = WorldNetworks.get(player.server);
    var actor = new NetworkPermissions.Actor(player.getUUID(), false);
    var ids = new ArrayList<UUID>();
    for (int i = 0; i < 3; i++) ids.add(UUID.fromString(expected.getProperty("staged." + i)));
    var level = player.serverLevel();
    var drive = (StorageDriveEntity) level.getBlockEntity(DRIVE);
    var cell = CellHandleData.read(drive.cell(0));
    if (phase == 0) {
      var known = (MachineInterfaceEntity) level.getBlockEntity(MACHINES.getFirst());
      if (known == null
          || runtime.membership(known.position()).status() != NetworkTopology.Status.VALID)
        return false;
      for (int i = 0; i < 3; i++) {
        var provider = (RecoveryProvider.Provider) level.getBlockEntity(target(i));
        var move = saved.transfers().get(ids.get(i));
        require(
            provider.calls == Integer.parseInt(expected.getProperty("stagedCalls." + i))
                && provider.items.getStackInSlot(0).getCount() == 3
                && new PlatformResourceCodec(player.registryAccess())
                    .itemKey(provider.items.getStackInSlot(0))
                    .equals(move.key())
                && saved.registry().stock(cell, key(player, i)).total() == 12,
            "Restart retains native partial contents/counters and source debit");
        require(
            i == 2
                ? move.pending() == StagedTransfer.PendingCall.DELIVER
                    && move.state() == StagedTransfer.State.QUARANTINED
                : move.staged() == 5 && move.delivered() == 3,
            "Restart retains canonical staging without replay");
      }
      var uncertain = saved.transfers().get(ids.get(2));
      require(
          runtime
                      .transfers()
                      .progress(
                          ids.get(2),
                          actor,
                          false,
                          r -> {
                            throw new AssertionError("Uncertain callback");
                          })
                      .rejection()
                  == OperationResult.Reason.UNCERTAIN
              && runtime
                      .transfers()
                      .progress(
                          ids.get(2),
                          actor,
                          true,
                          r -> {
                            throw new AssertionError("Uncertain return callback");
                          })
                      .rejection()
                  == OperationResult.Reason.UNCERTAIN,
          "Uncertain delivery and refund reject before handler calls");
      boolean denied = false;
      try {
        saved.transfers().retire(ids.get(2));
      } catch (IllegalStateException required) {
        denied = true;
      }
      require(
          denied && uncertain.equals(saved.transfers().get(ids.get(2))),
          "Uncertainty evidence cannot retire or change");
      ((RecoveryProvider.Provider) level.getBlockEntity(target(0))).budget = 64;
      level.getBlockEntity(target(0)).setChanged();
      for (int i = 0; i < 2; i++) {
        final int index = i;
        require(
            runtime
                    .transfers()
                    .progress(ids.get(i), actor, i == 1, r -> results[index] = r)
                    .rejection()
                == OperationResult.Reason.OK,
            "Schedule only known staged progress");
      }
      phase = 1;
      return false;
    }
    if (results[0] == null || results[1] == null) return false;
    require(
        results[0].transfer().state() == StagedTransfer.State.COMPLETE
            && results[0].transfer().delivered() == 8
            && saved.registry().stock(cell, key(player, 0)).total() == 12
            && ((RecoveryProvider.Provider) level.getBlockEntity(target(0)))
                    .items
                    .getStackInSlot(0)
                    .getCount()
                == 8,
        "Known delivery conserves source12 + provider8 without new extraction");
    require(
        results[1].transfer().returned() == 5
            && results[1].transfer().staged() == 0
            && saved.registry().stock(cell, key(player, 1)).total() == 17
            && ((RecoveryProvider.Provider) level.getBlockEntity(target(1)))
                    .items
                    .getStackInSlot(0)
                    .getCount()
                == 3,
        "Known original-source return conserves source17 + provider3");
    for (int i = 1; i < 3; i++)
      require(
          ((RecoveryProvider.Provider) level.getBlockEntity(target(i))).calls
              == Integer.parseInt(expected.getProperty("stagedCalls." + i)),
          "Return and uncertainty never replay destination insertion");
    for (int i = 0; i < 2; i++) saved.transfers().retire(ids.get(i));
    require(
        saved.transfers().get(ids.get(2)).pending() == StagedTransfer.PendingCall.DELIVER,
        "Uncertain evidence stays frozen after other cleanup");
    System.out.println(
        "FACTORYCORE_STAGING_RECOVERED deliver=12+8 return=17+3 uncertainCallsUnchanged=true");
    return true;
  }
}
