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
  /**
   * A sort is a mode ({@link #NAME} A–Z, {@link #QUANTITY} highest first, {@link #MOD} by namespace
   * then name), optionally combined with {@link #REVERSED}, which flips that mode's direction. Old
   * saved values 0 and 1 keep their meaning. {@link #TALL} rides in the same saved preference: it
   * changes only the client's grid height (H2), never the order. So does {@link #SYNC} (Q17): the
   * client mirrors the terminal search with a recipe viewer's search box.
   */
  public static final int NAME = 0, QUANTITY = 1, MOD = 2, REVERSED = 4, TALL = 8, SYNC = 16;

  private static final int FLAGS = REVERSED | TALL | SYNC;

  public static boolean validSort(int sort) {
    return sort >= 0 && (sort & ~FLAGS) <= MOD;
  }

  public static int mode(int sort) {
    return sort & ~FLAGS;
  }

  public static boolean sync(int sort) {
    return (sort & SYNC) != 0;
  }

  public static boolean reversed(int sort) {
    return (sort & REVERSED) != 0;
  }

  public static boolean tall(int sort) {
    return (sort & TALL) != 0;
  }

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
    if (!validSort(sort)) throw new IllegalArgumentException("Invalid terminal sort");
    Comparator<CatalogCounts.Row> names =
        Comparator.comparing(row -> name.apply(row.key()).toLowerCase(Locale.ROOT));
    Comparator<CatalogCounts.Row> ordered =
        (switch (mode(sort)) {
              case QUANTITY ->
                  Comparator.comparing(CatalogCounts.Row::total).reversed().thenComparing(names);
              case MOD ->
                  Comparator.<CatalogCounts.Row, String>comparing(
                          row ->
                              row.key()
                                  .registryId()
                                  .substring(0, Math.max(0, row.key().registryId().indexOf(':'))))
                      .thenComparing(names);
              default -> names;
            })
            .thenComparing(CatalogCounts.Row::key);
    return reversed(sort) ? ordered.reversed() : ordered;
  }
}
