package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.RouteCursors;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Fixed-size nested schema one; cursor IDs never imply a current endpoint or grant. */
final class RouteCursorCodec {
  private RouteCursorCodec() {}

  static CompoundTag encode(List<RouteCursors.Cursor> cursors) {
    var root = new CompoundTag();
    root.putInt("Schema", 1);
    var entries = new ListTag();
    for (var cursor : cursors) {
      var entry = new CompoundTag();
      var scope = cursor.scope();
      entry.putUUID("Network", scope.network());
      entry.putUUID("Segment", scope.segment());
      entry.putUUID("Principal", scope.principal());
      entry.putUUID("Deployment", scope.deployment());
      entry.putUUID("Fixed", scope.fixed());
      entry.putString("Kind", scope.kind().name());
      entry.putBoolean("ToStorage", scope.toStorage());
      entry.putUUID("Last", cursor.last());
      entries.add(entry);
    }
    root.put("Cursors", entries);
    return root;
  }

  static List<RouteCursors.Cursor> decode(CompoundTag root) {
    NetworkStateCodec.fields(root, Set.of("Schema", "Cursors"));
    if (!root.contains("Schema", Tag.TAG_INT)
        || root.getInt("Schema") != 1
        || !root.contains("Cursors", Tag.TAG_LIST))
      throw new IllegalArgumentException("Unsupported route cursor schema");
    var list = root.getList("Cursors", Tag.TAG_COMPOUND);
    if (list.size() != ((ListTag) root.get("Cursors")).size()
        || list.size() > RouteCursors.MAX_CURSORS)
      throw new IllegalArgumentException("Invalid route cursor list");
    var cursors = new ArrayList<RouteCursors.Cursor>();
    for (int index = 0; index < list.size(); index++) {
      var entry = list.getCompound(index);
      NetworkStateCodec.fields(
          entry,
          Set.of(
              "Network",
              "Segment",
              "Principal",
              "Deployment",
              "Fixed",
              "Kind",
              "ToStorage",
              "Last"));
      if (!entry.hasUUID("Network")
          || !entry.hasUUID("Segment")
          || !entry.hasUUID("Principal")
          || !entry.hasUUID("Deployment")
          || !entry.hasUUID("Fixed")
          || !entry.hasUUID("Last")
          || !entry.contains("Kind", Tag.TAG_STRING)
          || !entry.contains("ToStorage", Tag.TAG_BYTE)
          || entry.getByte("ToStorage") < 0
          || entry.getByte("ToStorage") > 1)
        throw new IllegalArgumentException("Malformed route cursor");
      cursors.add(
          new RouteCursors.Cursor(
              new RouteCursors.Scope(
                  entry.getUUID("Network"),
                  entry.getUUID("Segment"),
                  entry.getUUID("Principal"),
                  entry.getUUID("Deployment"),
                  entry.getUUID("Fixed"),
                  ResourceKind.valueOf(entry.getString("Kind")),
                  entry.getBoolean("ToStorage")),
              entry.getUUID("Last")));
    }
    return List.copyOf(cursors);
  }
}
