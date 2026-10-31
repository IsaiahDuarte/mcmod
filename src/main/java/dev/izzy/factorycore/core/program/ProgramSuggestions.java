package dev.izzy.factorycore.core.program;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the editor suggests while a sentence program is typed (ADR 0068, Q123): the slot the cursor
 * is in (an item after {@code move 8}, a machine label after {@code from}, a face after a label,
 * the next word), the text being typed there, and the closest matches. Pure; the editor supplies
 * the vocabulary and the labels the player may see.
 */
public final class ProgramSuggestions {
  /** What belongs at the cursor. */
  public enum Slot {
    /** The first word of a line. */
    START,
    /** After {@code every} and its number. */
    UNIT,
    /** An item, tag or {@code everything}. */
    ITEM,
    /** A fluid name after {@code fluid}. */
    FLUID,
    /** A machine label, or {@code storage} where it fits. */
    PLACE,
    /** After a machine label: a face, slots or the next word. */
    AFTER_PLACE,
    /** After a comparison word in a check: {@code than}, {@code least}, {@code most}. */
    NONE
  }

  /**
   * The typed text being completed, from {@code start} (inclusive) to {@code end}, the part before
   * the cursor ({@code prefix}), and what belongs there. {@code storage} says whether storage fits
   * this place; {@code after} is the word that introduced it.
   */
  public record Context(
      int start, int end, String prefix, Slot slot, boolean storage, String after) {}

  private static final List<String> STARTS =
      List.of(
          "every",
          "when",
          "move",
          "keep",
          "craft",
          "if",
          "otherwise",
          "for each",
          "turn on",
          "turn off",
          "set",
          "say");
  private static final List<String> UNITS =
      List.of("second", "seconds", "ticks", "minute", "minutes");
  private static final List<String> FACES =
      List.of("top", "bottom", "side", "north", "south", "east", "west", "slot", "slots");
  private static final List<String> COMPARES =
      List.of("less than", "more than", "at least", "at most", "exactly", "no", "any");
  private static final Set<String> STOP = Set.of("from", "to", "in", "except", "and", "or");

  private ProgramSuggestions() {}

  private record Word(int start, int end, String text) {
    String lower() {
      return text.toLowerCase(Locale.ROOT);
    }
  }

  public static Context at(String source, int cursor) {
    if (cursor < 0 || cursor > source.length())
      throw new IllegalArgumentException("Cursor outside draft");
    int lineStart = source.lastIndexOf('\n', cursor - 1) + 1;
    int end = cursor;
    while (end < source.length() && part(source.charAt(end))) end++;
    var words = words(source, lineStart, cursor);
    // The word under the cursor is still being typed unless a space follows the last word.
    boolean typing = cursor > lineStart && part(source.charAt(cursor - 1));
    var done = typing ? words.subList(0, words.size() - 1) : words;
    String partial = typing ? words.getLast().text : "";
    int partialStart = typing ? words.getLast().start : cursor;
    if (done.stream().anyMatch(word -> word.text.equals(":") || word.text.startsWith("#")))
      return context(source, partialStart, end, cursor, Slot.START, false, "");
    if (done.isEmpty()) return context(source, partialStart, end, cursor, Slot.START, false, "");
    var first = done.getFirst().lower();
    var last = done.getLast().lower();
    // Item phrases may be several words: complete from where the name starts.
    int phrase = phraseStart(done);
    if (phrase >= 0) {
      boolean fluid = done.get(phrase - 1).lower().equals("fluid");
      int start = phrase < done.size() ? done.get(phrase).start : partialStart;
      return context(
          source,
          start,
          end,
          cursor,
          fluid ? Slot.FLUID : Slot.ITEM,
          false,
          done.get(phrase - 1).lower());
    }
    switch (last) {
      case "every" -> {
        return context(source, partialStart, end, cursor, Slot.UNIT, false, last);
      }
      case "from", "to" -> {
        return context(source, partialStart, end, cursor, Slot.PLACE, true, last);
      }
      case "in", "on", "off", "set", "when" -> {
        return context(source, partialStart, end, cursor, Slot.PLACE, false, last);
      }
      case "if", "and", "or", "not" -> {
        return context(source, partialStart, end, cursor, Slot.PLACE, true, last);
      }
      case "has" -> {
        return context(source, partialStart, end, cursor, Slot.NONE, false, last);
      }
      default -> {}
    }
    if (first.equals("every") && done.size() == 2 && number(done.get(1).text))
      return context(source, partialStart, end, cursor, Slot.UNIT, false, "every");
    // A label just written after from/to/in/if: a face, slots or the next word.
    if (done.size() >= 2) {
      var before = done.get(done.size() - 2).lower();
      if (Set.of("from", "to", "in", "if", "and", "or", "not", "when").contains(before)
          && !last.equals("storage"))
        return context(source, partialStart, end, cursor, Slot.AFTER_PLACE, false, before);
    }
    return context(source, partialStart, end, cursor, Slot.NONE, false, last);
  }

  /**
   * Index in {@code done} where an item name starts, when the cursor is inside one, else -1. A name
   * follows {@code except}, {@code fluid}, {@code no}/{@code any} after {@code has}, a bare {@code
   * move}, or an amount after {@code move}, {@code keep}, {@code craft} or a comparison. A word
   * that ends a name ({@code from}, {@code to}, {@code in}, {@code and}, {@code or}) means the
   * cursor is past it.
   */
  private static int phraseStart(List<Word> done) {
    for (int index = done.size() - 1; index >= 0; index--) {
      var word = done.get(index).lower();
      var before = index > 0 ? done.get(index - 1).lower() : "";
      if (word.equals("except") || word.equals("fluid")) return index + 1;
      if (STOP.contains(word) || word.equals(":")) return -1;
      if ((number(word) || word.equals("all"))
          && Set.of("move", "keep", "craft", "than", "least", "most", "exactly").contains(before))
        return index + 1;
      if ((word.equals("no") || word.equals("any")) && before.equals("has")) return index + 1;
      if (word.equals("move") && index == 0) return 1;
    }
    return -1;
  }

  private static Context context(
      String source, int start, int end, int cursor, Slot slot, boolean storage, String after) {
    return new Context(
        start, Math.max(end, cursor), source.substring(start, cursor), slot, storage, after);
  }

  private static boolean number(String text) {
    return !text.isEmpty() && Character.isDigit(text.charAt(0));
  }

  private static boolean part(char character) {
    return Character.isLetterOrDigit(character)
        || character == '_'
        || character == ':'
        || character == '*'
        || character == '/'
        || character == '.';
  }

  /** Words (and ':' and quoted labels) on the line before the cursor. */
  private static List<Word> words(String source, int from, int to) {
    var words = new ArrayList<Word>();
    int index = from;
    while (index < to) {
      char character = source.charAt(index);
      if (character == '#') {
        words.add(new Word(index, to, source.substring(index, to)));
        break;
      }
      if (character == '"') {
        int close = source.indexOf('"', index + 1);
        int stop = close < 0 || close >= to ? to : close + 1;
        words.add(new Word(index, stop, source.substring(index, stop)));
        index = stop;
      } else if (character == ':') {
        words.add(new Word(index, index + 1, ":"));
        index++;
      } else if (part(character)) {
        int start = index;
        while (index < to && part(source.charAt(index))) {
          // "furnace:" ends the word; "minecraft:coal" does not.
          if (source.charAt(index) == ':'
              && (index + 1 >= to || !Character.isLetter(source.charAt(index + 1)))) break;
          index++;
        }
        words.add(new Word(start, index, source.substring(start, index)));
      } else index++;
    }
    return words;
  }

  // ---- Options ----

  /**
   * Up to {@code limit} suggestions for {@code context}: names from {@code vocabulary} (written so
   * they read back exactly), {@code labels}, or the words of the language that fit. Matches by
   * prefix first, then by the start of any word, then by containing the text, then (only when
   * nothing else matches) by a small typo.
   */
  public static List<String> options(
      Context context, Vocabulary vocabulary, Collection<String> labels, int limit) {
    var candidates =
        switch (context.slot()) {
          case START -> STARTS;
          case UNIT -> UNITS;
          case ITEM -> names(vocabulary, ResourceKind.ITEM);
          case FLUID -> names(vocabulary, ResourceKind.FLUID);
          case PLACE -> {
            var places = new ArrayList<String>();
            if (context.storage()) places.add("storage");
            for (var label : labels) places.add(ProgramPrinter.label(label));
            yield places;
          }
          case AFTER_PLACE ->
              switch (context.after()) {
                case "from" -> concat(List.of("to"), FACES);
                case "if", "and", "or", "not" -> List.of("has", "is powered", "is unpowered");
                case "when" -> List.of("is powered", "is unpowered");
                default -> FACES;
              };
          case NONE -> context.after().equals("has") ? COMPARES : List.<String>of();
        };
    // Names and labels are listed alphabetically; the language's own words keep their order.
    boolean sort = Set.of(Slot.ITEM, Slot.FLUID, Slot.PLACE).contains(context.slot());
    return rank(context.prefix(), candidates, limit, sort);
  }

  private static List<String> concat(List<String> a, List<String> b) {
    var all = new ArrayList<>(a);
    all.addAll(b);
    return all;
  }

  /** Up to {@code limit} of {@code names} closest to {@code query}, ranked as while typing. */
  public static List<String> match(String query, Collection<String> names, int limit) {
    return rank(query, names, limit, true);
  }

  /** The best-ranked candidates for what was typed (case-insensitive). */
  static List<String> rank(String typed, Collection<String> candidates, int limit, boolean sort) {
    var needle = typed.toLowerCase(Locale.ROOT);
    var found = new LinkedHashSet<String>();
    var sorted =
        sort
            ? candidates.stream().distinct().sorted(Comparator.naturalOrder()).toList()
            : candidates.stream().distinct().toList();
    for (var candidate : sorted)
      if (candidate.toLowerCase(Locale.ROOT).startsWith(needle)) found.add(candidate);
    if (!needle.isEmpty()) {
      for (var candidate : sorted)
        if (found.size() < limit && wordStart(candidate.toLowerCase(Locale.ROOT), needle))
          found.add(candidate);
      for (var candidate : sorted)
        if (found.size() < limit && candidate.toLowerCase(Locale.ROOT).contains(needle))
          found.add(candidate);
      if (found.isEmpty() && needle.length() >= 3)
        for (var candidate : sorted) {
          var lower = candidate.toLowerCase(Locale.ROOT);
          var head = lower.length() > needle.length() ? lower.substring(0, needle.length()) : lower;
          if (found.size() < limit && distance(needle, head) <= 1 + needle.length() / 6)
            found.add(candidate);
        }
    }
    return found.stream().limit(limit).toList();
  }

  private static boolean wordStart(String candidate, String needle) {
    for (int index = candidate.indexOf(' '); index >= 0; index = candidate.indexOf(' ', index + 1))
      if (candidate.startsWith(needle, index + 1)) return true;
    return false;
  }

  private static int distance(String a, String b) {
    var previous = new int[b.length() + 1];
    var current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) previous[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++)
        current[j] =
            Math.min(
                Math.min(current[j - 1] + 1, previous[j] + 1),
                previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
      var swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }

  /** The suggestion's remainder to show faintly after the cursor, or "" when it does not extend. */
  public static String ghost(String prefix, List<String> options) {
    if (options.isEmpty() || prefix.isEmpty()) return "";
    var best = options.getFirst();
    return best.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))
        ? best.substring(prefix.length())
        : "";
  }

  private static Vocabulary cachedFor;
  private static List<String> cachedItems = List.of(), cachedFluids = List.of();

  /**
   * Every item (and common tag group) or fluid as a sentence would name it; cached per vocabulary.
   */
  public static synchronized List<String> names(Vocabulary vocabulary, ResourceKind kind) {
    if (cachedFor != vocabulary) {
      var items = new ArrayList<String>(List.of("everything", "fluid", "tag"));
      var printer = new ProgramPrinter(vocabulary);
      for (var id : vocabulary.items())
        items.add(printer.term(new ResourceFilter.Registry(id), ResourceKind.ITEM));
      for (var tag : vocabulary.itemTags())
        if (tag.startsWith("c:"))
          items.add(printer.term(new ResourceFilter.Tag(tag), ResourceKind.ITEM));
      items.addAll(vocabulary.names().keySet());
      var fluids = new ArrayList<String>();
      for (var id : vocabulary.fluids())
        fluids.add(
            printer
                .term(new ResourceFilter.Registry(id), ResourceKind.FLUID)
                .substring("fluid ".length()));
      cachedItems = List.copyOf(new LinkedHashSet<>(items));
      cachedFluids = List.copyOf(new LinkedHashSet<>(fluids));
      cachedFor = vocabulary;
    }
    return kind == ResourceKind.FLUID ? cachedFluids : cachedItems;
  }
}
