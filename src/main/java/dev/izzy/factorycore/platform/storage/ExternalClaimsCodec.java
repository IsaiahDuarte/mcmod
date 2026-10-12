package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ExternalClaims;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Nested schema one contains holds only; absent foreign stock never invalidates orphan holds. */
final class ExternalClaimsCodec {
  private ExternalClaimsCodec() {}

  static CompoundTag encode(List<ExternalClaims.Claim> claims) {
    var root = new CompoundTag();
    root.putInt("Schema", 1);
    var list = new ListTag();
    claims.stream()
        .sorted(
            Comparator.comparing(ExternalClaims.Claim::backing)
                .thenComparing(ExternalClaims.Claim::owner)
                .thenComparing(ExternalClaims.Claim::key))
        .forEach(
            claim -> {
              var entry = new CompoundTag();
              entry.putUUID("Backing", claim.backing());
              entry.putUUID("Owner", claim.owner());
              entry.putString("Kind", claim.key().kind().name());
              entry.putString("Registry", claim.key().registryId());
              entry.putByteArray("Components", claim.key().components());
              entry.putLong("Amount", claim.amount());
              list.add(entry);
            });
    root.put("Claims", list);
    return root;
  }

  static List<ExternalClaims.Claim> decode(CompoundTag root) {
    NetworkStateCodec.fields(root, Set.of("Schema", "Claims"));
    if (!root.contains("Schema", Tag.TAG_INT)
        || root.getInt("Schema") != 1
        || !root.contains("Claims", Tag.TAG_LIST))
      throw new IllegalArgumentException("Unsupported external claim schema");
    var list = root.getList("Claims", Tag.TAG_COMPOUND);
    if (list.size() != ((ListTag) root.get("Claims")).size()
        || list.size() > ExternalClaims.MAX_CLAIMS)
      throw new IllegalArgumentException("Invalid external claim list");
    var claims = new ArrayList<ExternalClaims.Claim>();
    long bytes = 0;
    for (int index = 0; index < list.size(); index++) {
      var entry = list.getCompound(index);
      NetworkStateCodec.fields(
          entry, Set.of("Backing", "Owner", "Kind", "Registry", "Components", "Amount"));
      if (!entry.hasUUID("Backing")
          || !entry.hasUUID("Owner")
          || !entry.contains("Kind", Tag.TAG_STRING)
          || !entry.contains("Registry", Tag.TAG_STRING)
          || !entry.contains("Components", Tag.TAG_BYTE_ARRAY)
          || !entry.contains("Amount", Tag.TAG_LONG))
        throw new IllegalArgumentException("Malformed external claim");
      bytes +=
          256L
              + 3L * entry.getString("Registry").length()
              + entry.getByteArray("Components").length;
      if (bytes > ExternalClaims.MAX_BYTES)
        throw new IllegalArgumentException("External claim byte limit");
      claims.add(
          new ExternalClaims.Claim(
              entry.getUUID("Backing"),
              entry.getUUID("Owner"),
              new ResourceKey(
                  ResourceKind.valueOf(entry.getString("Kind")),
                  entry.getString("Registry"),
                  entry.getByteArray("Components")),
              entry.getLong("Amount")));
    }
    return new ExternalClaims(claims).snapshot();
  }
}
