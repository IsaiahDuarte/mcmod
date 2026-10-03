package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Schema-two network metadata; fixed bits are independent of Java enum order. */
final class NetworkStateCodec {
  private NetworkStateCodec() {}

  static List<NetworkPermissions.State> decode(CompoundTag root) {
    require(root, "Networks", Tag.TAG_LIST);
    var list = root.getList("Networks", Tag.TAG_COMPOUND);
    if (list.size() != ((ListTag) root.get("Networks")).size()
        || list.size() > DeviceSavedData.MAX_NETWORKS)
      throw new IllegalArgumentException("Invalid network list");
    var states = new ArrayList<NetworkPermissions.State>(list.size());
    var identities = new HashSet<UUID>();
    int bindings = 0;
    for (int i = 0; i < list.size(); i++) {
      var entry = list.getCompound(i);
      fields(entry, Set.of("Id", "Owner", "Generation", "Grants"));
      if (!entry.hasUUID("Id") || !entry.hasUUID("Owner"))
        throw new IllegalArgumentException("Invalid network/owner UUID");
      UUID id = entry.getUUID("Id");
      if (!identities.add(id)) throw new IllegalArgumentException("Duplicate network identity");
      require(entry, "Generation", Tag.TAG_LONG);
      require(entry, "Grants", Tag.TAG_LIST);
      var grants = entry.getList("Grants", Tag.TAG_COMPOUND);
      if (grants.size() != ((ListTag) entry.get("Grants")).size()
          || grants.size() > NetworkPermissions.MAX_PRINCIPALS)
        throw new IllegalArgumentException("Invalid network principal list");
      bindings += grants.size();
      if (bindings > DeviceSavedData.MAX_NETWORK_GRANTS)
        throw new IllegalArgumentException("World principal binding limit");
      var permissions = new HashMap<UUID, Set<Permission>>();
      for (int j = 0; j < grants.size(); j++) {
        var grant = grants.getCompound(j);
        fields(grant, Set.of("Principal", "Permissions"));
        if (!grant.hasUUID("Principal"))
          throw new IllegalArgumentException("Invalid principal UUID");
        require(grant, "Permissions", Tag.TAG_INT);
        int mask = grant.getInt("Permissions");
        if (mask <= 0 || (mask & ~127) != 0)
          throw new IllegalArgumentException("Unknown/empty permission mask");
        var decoded = EnumSet.noneOf(Permission.class);
        for (var permission : Permission.values()) {
          if ((mask & bit(permission)) != 0) decoded.add(permission);
        }
        if (permissions.putIfAbsent(grant.getUUID("Principal"), decoded) != null)
          throw new IllegalArgumentException("Duplicate network principal");
      }
      states.add(
          new NetworkPermissions.State(
              id, entry.getUUID("Owner"), entry.getLong("Generation"), permissions));
    }
    return List.copyOf(states);
  }

  static ListTag encode(List<NetworkPermissions.State> states) {
    var list = new ListTag();
    for (var state : states) {
      var entry = new CompoundTag();
      entry.putUUID("Id", state.network());
      entry.putUUID("Owner", state.owner());
      entry.putLong("Generation", state.generation());
      var grants = new ListTag();
      for (var principal : state.grants().keySet().stream().sorted().toList()) {
        var grant = new CompoundTag();
        grant.putUUID("Principal", principal);
        int mask = 0;
        for (var permission : state.grants().get(principal)) mask |= bit(permission);
        grant.putInt("Permissions", mask);
        grants.add(grant);
      }
      entry.put("Grants", grants);
      list.add(entry);
    }
    return list;
  }

  private static int bit(Permission permission) {
    return switch (permission) {
      case VIEW -> 1;
      case DEPOSIT -> 2;
      case WITHDRAW -> 4;
      case CRAFT -> 8;
      case EDIT -> 16;
      case DEPLOY -> 32;
      case MANAGE -> 64;
    };
  }

  static void fields(CompoundTag tag, Set<String> allowed) {
    if (!allowed.containsAll(tag.getAllKeys()))
      throw new IllegalArgumentException("Unknown registry fields");
  }

  private static void require(CompoundTag tag, String field, int type) {
    if (!tag.contains(field, type))
      throw new IllegalArgumentException("Missing/wrong-type network field: " + field);
  }
}
