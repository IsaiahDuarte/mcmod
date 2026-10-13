package dev.izzy.factorycore.core.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResourceFilterTest {
  private static final ResourceKey FIRST =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {1});
  private static final ResourceKey SECOND =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {2});
  private static final ResourceKey GOLD =
      new ResourceKey(ResourceKind.ITEM, "minecraft:gold_ingot", new byte[] {1});
  private static final ResourceFilter.Tags NO_TAGS = (kind, tag, key) -> false;

  @Test
  void registryDefaultIsBroadWhileExactSelectionRetainsComponents() {
    var broad =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(new ResourceFilter.Registry(FIRST.registryId())));
    var exact =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(new ResourceFilter.Exact(FIRST)));
    assertTrue(broad.matches(FIRST, NO_TAGS));
    assertTrue(broad.matches(SECOND, NO_TAGS));
    assertFalse(broad.matches(GOLD, NO_TAGS));
    assertTrue(exact.matches(FIRST, NO_TAGS));
    assertFalse(exact.matches(SECOND, NO_TAGS));
    assertFalse(
        exact.matches(
            new ResourceKey(ResourceKind.FLUID, FIRST.registryId(), new byte[] {1}), NO_TAGS));
    assertEquals(new byte[] {2}[0], SECOND.components()[0]);
  }

  @Test
  void inclusionExclusionEmptyListsAndCurrentTagsHaveUnambiguousSemantics() {
    assertTrue(ResourceFilter.all(ResourceKind.ITEM).matches(FIRST, NO_TAGS));
    assertFalse(
        new ResourceFilter(ResourceKind.ITEM, ResourceFilter.Mode.INCLUDE, List.of())
            .matches(FIRST, NO_TAGS));
    assertTrue(
        new ResourceFilter(ResourceKind.ITEM, ResourceFilter.Mode.EXCLUDE, List.of())
            .matches(FIRST, NO_TAGS));
    var tags = new HashSet<String>();
    var exclude =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.EXCLUDE,
            List.of(new ResourceFilter.Tag("c:ingots/iron")));
    ResourceFilter.Tags live =
        (kind, tag, key) -> tags.contains(tag) && key.registryId().equals(FIRST.registryId());
    assertTrue(exclude.matches(FIRST, live));
    tags.add("c:ingots/iron");
    assertFalse(exclude.matches(FIRST, live));
    assertFalse(exclude.matches(SECOND, live));
    assertTrue(exclude.matches(GOLD, live));
    tags.clear();
    assertTrue(exclude.matches(FIRST, live));
  }

  @Test
  void admissionIsBoundedImmutableAndFailuresNeverBroadenSelection() {
    var terms = new ArrayList<ResourceFilter.Term>();
    terms.add(new ResourceFilter.Registry(FIRST.registryId()));
    var filter = new ResourceFilter(ResourceKind.ITEM, ResourceFilter.Mode.INCLUDE, terms);
    terms.clear();
    assertTrue(filter.matches(FIRST, NO_TAGS));
    assertThrows(UnsupportedOperationException.class, () -> filter.terms().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceFilter(ResourceKind.ITEM, ResourceFilter.Mode.ALL, filter.terms()));
    assertThrows(
        IllegalArgumentException.class, () -> new ResourceFilter.Registry("missing namespace"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceFilter(
                ResourceKind.ENERGY,
                ResourceFilter.Mode.INCLUDE,
                List.of(new ResourceFilter.Tag("c:energy"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceFilter(
                ResourceKind.ITEM,
                ResourceFilter.Mode.INCLUDE,
                List.of(new ResourceFilter.Exact(ResourceKey.ENERGY))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceFilter(
                ResourceKind.ITEM,
                ResourceFilter.Mode.INCLUDE,
                Collections.nCopies(17, new ResourceFilter.Tag("c:ingots"))));
    var heavy =
        new ResourceKey(
            ResourceKind.ITEM, FIRST.registryId(), new byte[ResourceKey.MAX_COMPONENT_BYTES]);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceFilter(
                ResourceKind.ITEM,
                ResourceFilter.Mode.INCLUDE,
                Collections.nCopies(4, new ResourceFilter.Exact(heavy))));
    var calls = new AtomicInteger();
    var tagged =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            Collections.nCopies(16, new ResourceFilter.Tag("c:ingots")));
    assertFalse(
        tagged.matches(
            FIRST,
            (kind, tag, key) -> {
              calls.incrementAndGet();
              return false;
            }));
    assertEquals(16, calls.get());
    assertFalse(
        tagged.matches(
            ResourceKey.ENERGY,
            (kind, tag, key) -> {
              throw new AssertionError("Wrong kind must not probe tags");
            }));
    assertThrows(
        IllegalStateException.class,
        () ->
            tagged.matches(
                FIRST,
                (kind, tag, key) -> {
                  throw new IllegalStateException("Reload failure");
                }));
  }

  @Test
  void generatedCandidateSequencesPreserveIncludeExcludeComplementWithoutIdentityMutation() {
    var random = new Random(0xF117E221);
    var source = new ResourceKey[128];
    for (int i = 0; i < source.length; i++)
      source[i] = new ResourceKey(ResourceKind.ITEM, "test:item_" + (i % 8), new byte[] {(byte) i});
    var terms =
        List.<ResourceFilter.Term>of(
            new ResourceFilter.Registry("test:item_3"),
            new ResourceFilter.Exact(source[5]),
            new ResourceFilter.Tag("test:even"));
    var include = new ResourceFilter(ResourceKind.ITEM, ResourceFilter.Mode.INCLUDE, terms);
    var exclude = new ResourceFilter(ResourceKind.ITEM, ResourceFilter.Mode.EXCLUDE, terms);
    for (int i = 0; i < 5000; i++) {
      var key = source[random.nextInt(source.length)];
      byte[] before = key.components();
      ResourceFilter.Tags even = (kind, tag, candidate) -> (candidate.components()[0] & 1) == 0;
      assertNotEquals(include.matches(key, even), exclude.matches(key, even));
      assertArrayEquals(before, key.components());
    }
  }
}
