package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.CatalogCounts;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.SearchQuery;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;

/** Server language names, exact identities and loaded item/fluid registry tags. */
public final class TerminalSearchSupport {
  /**
   * A sort is a mode ({@link #NAME} A–Z, {@link #QUANTITY} highest first, {@link #MOD} by namespace
   * then name), optionally combined with {@link #REVERSED}, which flips that mode's direction. Old
   * saved values 0 and 1 keep their meaning. {@link #TALL} rides in the same saved preference: it
   * changes only the client's grid height (H2), never the order. So does {@link #SYNC} (Q17): the
   * client mirrors the terminal search with a recipe viewer's search box. {@link #FOCUS} (Q18) puts
   * the cursor in the search box when the terminal opens.
   */
  public static final int NAME = 0, QUANTITY = 1, MOD = 2, REVERSED = 4, TALL = 8, SYNC = 16;

  public static final int FOCUS = 32;

  private static final int FLAGS = REVERSED | TALL | SYNC | FOCUS;

  public static boolean validSort(int sort) {
    return sort >= 0 && (sort & ~FLAGS) <= MOD;
  }

  public static int mode(int sort) {
    return sort & ~FLAGS;
  }

  public static boolean sync(int sort) {
    return (sort & SYNC) != 0;
  }

  public static boolean focus(int sort) {
    return (sort & FOCUS) != 0;
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

  /** Q61: whether {@code key} can match {@code text} (count terms are not known here). */
  public static boolean matches(PlatformResourceCodec codec, ResourceKey key, String text) {
    return SearchQuery.parse(text).mayMatch(subject(codec, key));
  }

  /** Most tags and tooltip lines read from one resource; more is refused as unsupported. */
  private static final int MAX_TAGS = 1024, MAX_TOOLTIP = 64;

  /**
   * The search subject for {@code key}: server-language name, registry ID, loaded tags and the
   * item's tooltip, each computed only when a term asks. Throws {@link IllegalArgumentException}
   * for an unsupported key or more than 1,024 tags.
   */
  public static SearchQuery.Subject subject(PlatformResourceCodec codec, ResourceKey key) {
    return new SearchQuery.Subject() {
      private String name;

      @Override
      public String name() {
        if (name == null) name = TerminalSearchSupport.name(codec, key);
        return name;
      }

      @Override
      public String id() {
        return key.registryId();
      }

      @Override
      public Iterable<String> tags() {
        var tags = new java.util.ArrayList<String>();
        Stream<String> stream =
            switch (key.kind()) {
              case ITEM -> codec.itemStack(key, 1).getTags().map(tag -> tag.location().toString());
              case FLUID ->
                  codec.fluidStack(key, 1).getTags().map(tag -> tag.location().toString());
              case ENERGY -> Stream.empty();
            };
        try (stream) {
          var iterator = stream.iterator();
          while (iterator.hasNext()) {
            if (tags.size() >= MAX_TAGS)
              throw new IllegalArgumentException("Too many resource tags");
            tags.add(iterator.next());
          }
        }
        return tags;
      }

      @Override
      public Iterable<String> tooltip() {
        if (key.kind() != ResourceKind.ITEM) return java.util.List.of(name());
        var lines =
            codec
                .itemStack(key, 1)
                .getTooltipLines(
                    Item.TooltipContext.of(codec.registries()), null, TooltipFlag.Default.NORMAL);
        var text = new java.util.ArrayList<String>();
        for (var line : lines) {
          if (text.size() >= MAX_TOOLTIP) break;
          text.add(line.getString().replaceAll("[\\p{Cntrl}§]", ""));
        }
        return text;
      }
    };
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
