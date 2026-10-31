package dev.izzy.factorycore.core.resource;

import java.util.List;
import java.util.Objects;

/** Bounded immutable selectors. Broad registry/tag matching never changes stored exact identity. */
public record ResourceFilter(ResourceKind kind, Mode mode, List<Term> terms) {
  public static final int MAX_TERMS = 16;
  public static final long MAX_BYTES = 256L * 1024;

  public enum Mode {
    ALL,
    INCLUDE,
    EXCLUDE
  }

  public sealed interface Term permits Registry, Exact, Tag, Worn, Glob, Except {}

  /**
   * D5 (ADR 0062): a registry ID with exactly one {@code *} in its path, matching zero or more of
   * {@code [a-z0-9_./-]}. Without a namespace it matches any; with one, that namespace exactly.
   * Registry IDs only, never components. Program filters only: never saved by a preset codec.
   */
  public record Glob(String pattern) implements Term {
    public Glob {
      Objects.requireNonNull(pattern);
      int colon = pattern.indexOf(':');
      var path = colon < 0 ? pattern : pattern.substring(colon + 1);
      if (pattern.length() > 128
          || pattern.chars().filter(c -> c == '*').count() != 1
          || path.indexOf('*') < 0
          || pattern.indexOf(':', colon + 1) >= 0
          || !pattern
              .chars()
              .allMatch(
                  c ->
                      c == '*'
                          || c == ':'
                          || c == '_'
                          || c == '.'
                          || c == '/'
                          || c == '-'
                          || (c >= 'a' && c <= 'z')
                          || (c >= '0' && c <= '9')))
        throw new IllegalArgumentException("A glob has one '*' in its ID path");
    }

    boolean matches(String id) {
      int colon = pattern.indexOf(':');
      int idColon = id.indexOf(':');
      var path = idColon < 0 ? id : id.substring(idColon + 1);
      if (colon >= 0
          && !pattern
              .substring(0, colon)
              .equals(idColon < 0 ? "minecraft" : id.substring(0, idColon))) return false;
      var glob = colon < 0 ? pattern : pattern.substring(colon + 1);
      int star = glob.indexOf('*');
      var prefix = glob.substring(0, star);
      var suffix = glob.substring(star + 1);
      return path.length() >= prefix.length() + suffix.length()
          && path.startsWith(prefix)
          && path.endsWith(suffix);
    }
  }

  /** D5 (ADR 0062): what {@code include} matches minus anything one of {@code excluded} does. */
  public record Except(Term include, List<Term> excluded) implements Term {
    public static final int MAX_EXCLUDED = 7;

    public Except {
      Objects.requireNonNull(include);
      excluded = List.copyOf(excluded);
      if (include instanceof Except
          || excluded.isEmpty()
          || excluded.size() > MAX_EXCLUDED
          || excluded.stream().anyMatch(term -> term instanceof Except))
        throw new IllegalArgumentException("Except takes one term and 1–7 others");
    }
  }

  /** True for program-only terms ({@link Glob}, {@link Except}) that no preset codec can save. */
  public static boolean programOnly(Term term) {
    return term instanceof Glob || term instanceof Except;
  }

  public record Registry(String id) implements Term {
    public Registry {
      validateId(id);
    }
  }

  public record Exact(ResourceKey key) implements Term {
    public Exact {
      Objects.requireNonNull(key);
    }
  }

  public record Tag(String id) implements Term {
    public Tag {
      validateId(id);
    }
  }

  /**
   * Q76: item {@code id} with less than {@code below} percent of its durability left, any other
   * components. Only damageable items match, as {@link Tags#worn} reports. Items only.
   */
  public record Worn(String id, int below) implements Term {
    /** The thresholds a filter slot cycles through, most worn last. */
    public static final List<Integer> STEPS = List.of(99, 75, 50, 25);

    public Worn {
      validateId(id);
      if (below < 1 || below > 99)
        throw new IllegalArgumentException("Worn threshold must be 1–99 percent");
    }
  }

  /** Resolve current tags at evaluation time; exceptions propagate before any resource mutation. */
  @FunctionalInterface
  public interface Tags {
    boolean contains(ResourceKind kind, String tag, ResourceKey candidate);

    /**
     * Q76: {@code candidate} is damageable and has less than {@code below} percent of its
     * durability left. A provider that cannot read durability matches nothing.
     */
    default boolean worn(ResourceKey candidate, int below) {
      return false;
    }
  }

  public ResourceFilter {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(mode);
    Objects.requireNonNull(terms);
    if (terms.size() > MAX_TERMS || (mode == Mode.ALL && !terms.isEmpty()))
      throw new IllegalArgumentException("Filter term limit or ambiguous ALL filter");
    terms = List.copyOf(terms);
    long bytes = 256;
    for (var term : terms) {
      if (term instanceof Exact exact) {
        if (exact.key().kind() != kind)
          throw new IllegalArgumentException("Filter exact kind mismatch");
        bytes += 256L + 3L * exact.key().registryId().length() + exact.key().componentByteCount();
      } else if (term instanceof Registry registry) {
        if (kind == ResourceKind.ENERGY && !registry.id().equals(ResourceKey.ENERGY.registryId()))
          throw new IllegalArgumentException("Energy has one canonical registry identity");
        bytes += 256L + 3L * registry.id().length();
      } else if (term instanceof Tag tag) {
        if (kind == ResourceKind.ENERGY)
          throw new IllegalArgumentException("Energy has no tag model");
        bytes += 256L + 3L * tag.id().length();
      } else if (term instanceof Worn worn) {
        if (kind != ResourceKind.ITEM)
          throw new IllegalArgumentException("Only items have durability");
        bytes += termBytes(worn);
      } else {
        if (kind == ResourceKind.ENERGY)
          throw new IllegalArgumentException("Energy has no glob or except model");
        if (term instanceof Except except)
          for (var part : except.excluded())
            if (part instanceof Exact exact && exact.key().kind() != kind)
              throw new IllegalArgumentException("Filter exact kind mismatch");
        bytes += termBytes(term);
      }
      if (bytes > MAX_BYTES) throw new IllegalArgumentException("Filter metadata exceeds 256 KiB");
    }
  }

  public static ResourceFilter all(ResourceKind kind) {
    return new ResourceFilter(kind, Mode.ALL, List.of());
  }

  public long bytes() {
    long bytes = 256;
    for (var term : terms) bytes += termBytes(term);
    return bytes;
  }

  private static long termBytes(Term term) {
    return switch (term) {
      case Exact exact ->
          256L + 3L * exact.key().registryId().length() + exact.key().componentByteCount();
      case Registry registry -> 256L + 3L * registry.id().length();
      case Tag tag -> 256L + 3L * tag.id().length();
      case Worn worn -> 260L + 3L * worn.id().length();
      case Glob glob -> 256L + 3L * glob.pattern().length();
      case Except except -> {
        long bytes = termBytes(except.include());
        for (var part : except.excluded()) bytes += termBytes(part);
        yield bytes;
      }
    };
  }

  private boolean term(Term term, ResourceKey candidate, Tags tags) {
    return switch (term) {
      case Exact exact -> exact.key().equals(candidate);
      case Registry registry -> registry.id().equals(candidate.registryId());
      case Tag tag -> tags.contains(kind, tag.id(), candidate);
      case Worn worn ->
          worn.id().equals(candidate.registryId()) && tags.worn(candidate, worn.below());
      case Glob glob -> glob.matches(candidate.registryId());
      case Except except -> {
        if (!term(except.include(), candidate, tags)) yield false;
        for (var part : except.excluded()) if (term(part, candidate, tags)) yield false;
        yield true;
      }
    };
  }

  /**
   * Q73: the index of the first term {@code candidate} matches, ignoring the mode, or −1 (also for
   * another kind). At most sixteen probes.
   */
  public int firstMatch(ResourceKey candidate, Tags tags) {
    Objects.requireNonNull(candidate);
    Objects.requireNonNull(tags);
    if (candidate.kind() != kind) return -1;
    for (int index = 0; index < terms.size(); index++)
      if (term(terms.get(index), candidate, tags)) return index;
    return -1;
  }

  /**
   * At most sixteen probes. Empty INCLUDE matches nothing; empty EXCLUDE matches all of its kind.
   */
  public boolean matches(ResourceKey candidate, Tags tags) {
    Objects.requireNonNull(candidate);
    Objects.requireNonNull(tags);
    if (candidate.kind() != kind) return false;
    if (mode == Mode.ALL) return true;
    boolean matched = false;
    for (var term : terms) {
      matched = term(term, candidate, tags);
      if (matched) break;
    }
    return mode == Mode.INCLUDE ? matched : !matched;
  }

  private static void validateId(String id) {
    new ResourceKey(ResourceKind.ITEM, id, new byte[0]);
  }
}
