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

  public sealed interface Term permits Registry, Exact, Tag {}

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

  /** Resolve current tags at evaluation time; exceptions propagate before any resource mutation. */
  @FunctionalInterface
  public interface Tags {
    boolean contains(ResourceKind kind, String tag, ResourceKey candidate);
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
      } else {
        if (kind == ResourceKind.ENERGY)
          throw new IllegalArgumentException("Energy has no tag model");
        bytes += 256L + 3L * ((Tag) term).id().length();
      }
      if (bytes > MAX_BYTES) throw new IllegalArgumentException("Filter metadata exceeds 256 KiB");
    }
  }

  public static ResourceFilter all(ResourceKind kind) {
    return new ResourceFilter(kind, Mode.ALL, List.of());
  }

  public long bytes() {
    long bytes = 256;
    for (var term : terms)
      bytes +=
          term instanceof Exact exact
              ? 256L + 3L * exact.key().registryId().length() + exact.key().componentByteCount()
              : 256L
                  + 3L
                      * (term instanceof Registry registry ? registry.id() : ((Tag) term).id())
                          .length();
    return bytes;
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
      matched =
          term instanceof Exact exact
              ? exact.key().equals(candidate)
              : term instanceof Registry registry
                  ? registry.id().equals(candidate.registryId())
                  : tags.contains(kind, ((Tag) term).id(), candidate);
      if (matched) break;
    }
    return mode == Mode.INCLUDE ? matched : !matched;
  }

  private static void validateId(String id) {
    new ResourceKey(ResourceKind.ITEM, id, new byte[0]);
  }
}
