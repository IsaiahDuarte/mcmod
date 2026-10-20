package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import dev.izzy.factorycore.core.resource.TransferContext;
import dev.izzy.factorycore.core.resource.TransferRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Version-two canonical transfer records with explicit version-one migration; unknown fields and
 * enum names reject without repair.
 */
final class TransferStateCodec {
  private TransferStateCodec() {}

  static ListTag encode(List<TransferRegistry.Stored> records) {
    var list = new ListTag();
    for (var record : records) {
      var data = new CompoundTag();
      var move = record.transfer();
      data.putInt("Schema", 2);
      if (record.context() != null)
        data.put("Context", TransferContextCodec.encode(record.context()));
      data.putUUID("Id", record.id());
      data.putUUID("Owner", move.owner());
      data.putUUID("Source", move.source());
      data.putUUID("Destination", move.destination());
      data.putString("Kind", move.key().kind().name());
      data.putString("Registry", move.key().registryId());
      data.putByteArray("Components", move.key().components());
      data.putLong("Staged", move.staged());
      data.putLong("Delivered", move.delivered());
      data.putLong("Returned", move.returned());
      data.putString("State", move.state().name());
      data.putString("Reason", move.reason().name());
      data.putString("Diagnostic", move.diagnostic());
      data.putString("Pending", move.pending().name());
      data.putLong("PendingAmount", move.pendingAmount());
      list.add(data);
    }
    return list;
  }

  static List<TransferRegistry.Stored> decode(CompoundTag root) {
    if (!root.contains("Transfers", Tag.TAG_LIST))
      throw new IllegalArgumentException("Missing transfer records");
    var list = root.getList("Transfers", Tag.TAG_COMPOUND);
    if (list.size() != ((ListTag) root.get("Transfers")).size()
        || list.size() > TransferRegistry.MAX_RECORDS)
      throw new IllegalArgumentException("Invalid transfer list");
    var records = new ArrayList<TransferRegistry.Stored>();
    long bytes = 0;
    for (int i = 0; i < list.size(); i++) {
      var data = list.getCompound(i);
      NetworkStateCodec.fields(
          data,
          Set.of(
              "Schema",
              "Id",
              "Owner",
              "Source",
              "Destination",
              "Kind",
              "Registry",
              "Components",
              "Staged",
              "Delivered",
              "Returned",
              "State",
              "Reason",
              "Diagnostic",
              "Pending",
              "PendingAmount",
              "Context"));
      for (String field : new String[] {"Id", "Owner", "Source", "Destination"})
        if (!data.hasUUID(field)) throw new IllegalArgumentException("Malformed transfer identity");
      require(data, "Schema", Tag.TAG_INT);
      if (data.getInt("Schema") != (root.getInt("Schema") >= 5 ? 2 : 1)
          || data.getInt("Schema") == 1 && data.contains("Context"))
        throw new IllegalArgumentException("Unsupported transfer schema");
      for (String field :
          new String[] {"Kind", "Registry", "State", "Reason", "Diagnostic", "Pending"})
        require(data, field, Tag.TAG_STRING);
      for (String field : new String[] {"Staged", "Delivered", "Returned", "PendingAmount"})
        require(data, field, Tag.TAG_LONG);
      require(data, "Components", Tag.TAG_BYTE_ARRAY);
      bytes +=
          2048L
              + (data.contains("Context") ? 1024L : 0L)
              + 3L * data.getString("Registry").length()
              + data.getByteArray("Components").length;
      if (bytes > TransferRegistry.MAX_BYTES)
        throw new IllegalArgumentException("Transfer byte limit");
      var key =
          new ResourceKey(
              ResourceKind.valueOf(data.getString("Kind")),
              data.getString("Registry"),
              data.getByteArray("Components"));
      records.add(
          new TransferRegistry.Stored(
              data.getUUID("Id"),
              new StagedTransfer.Snapshot(
                  data.getUUID("Owner"),
                  key,
                  data.getUUID("Source"),
                  data.getUUID("Destination"),
                  data.getLong("Staged"),
                  data.getLong("Delivered"),
                  data.getLong("Returned"),
                  StagedTransfer.State.valueOf(data.getString("State")),
                  OperationResult.Reason.valueOf(data.getString("Reason")),
                  data.getString("Diagnostic"),
                  StagedTransfer.PendingCall.valueOf(data.getString("Pending")),
                  data.getLong("PendingAmount")),
              context(data)));
    }
    return records;
  }

  private static TransferContext context(CompoundTag data) {
    if (!data.contains("Context")) return null;
    require(data, "Context", Tag.TAG_COMPOUND);
    return TransferContextCodec.decode(data.getCompound("Context"));
  }

  private static void require(CompoundTag data, String field, int type) {
    if (!data.contains(field, type))
      throw new IllegalArgumentException("Missing/wrong-type transfer field: " + field);
  }
}
