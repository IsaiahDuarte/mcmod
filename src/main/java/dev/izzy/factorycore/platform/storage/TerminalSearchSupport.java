package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.CatalogCounts;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;

/** Server language names, exact identities and loaded item/fluid registry tags. */
public final class TerminalSearchSupport {
  public static final int NAME = 0, QUANTITY = 1;

  private TerminalSearchSupport() {}

  public static String name(PlatformResourceCodec codec, ResourceKey key) {
    String name =
        switch (key.kind()) {
          case ITEM -> codec.itemStack(key, 1).getHoverName().getString();
          case FLUID -> codec.fluidStack(key, 1).getHoverName().getString();
          case ENERGY -> "Energy";
        };
    return name.replaceAll("[\\p{Cntrl}§]", "");
  }

  public static boolean matches(PlatformResourceCodec codec, ResourceKey key, String text) {
    String query = text.toLowerCase(Locale.ROOT).strip();
    if (query.startsWith("@"))
      return key.registryId()
          .substring(0, key.registryId().indexOf(':'))
          .contains(query.substring(1));
    if (query.startsWith("#")) {
      Stream<String> tags =
          switch (key.kind()) {
            case ITEM -> codec.itemStack(key, 1).getTags().map(tag -> tag.location().toString());
            case FLUID -> codec.fluidStack(key, 1).getTags().map(tag -> tag.location().toString());
            case ENERGY -> Stream.empty();
          };
      try (tags) {
        var iterator = tags.iterator();
        int count = 0;
        boolean found = false;
        while (iterator.hasNext()) {
          if (++count > 1024) throw new IllegalArgumentException("Too many resource tags");
          found |= iterator.next().toLowerCase(Locale.ROOT).contains(query.substring(1));
        }
        return found;
      }
    }
    return key.registryId().contains(query)
        || name(codec, key).toLowerCase(Locale.ROOT).contains(query);
  }

  public static Comparator<CatalogCounts.Row> order(int sort, Function<ResourceKey, String> name) {
    if (sort < NAME || sort > QUANTITY) throw new IllegalArgumentException("Invalid terminal sort");
    Comparator<CatalogCounts.Row> names =
        Comparator.comparing(row -> name.apply(row.key()).toLowerCase(Locale.ROOT));
    return (sort == QUANTITY
            ? Comparator.comparing(CatalogCounts.Row::total).reversed().thenComparing(names)
            : names)
        .thenComparing(CatalogCounts.Row::key);
  }
}
