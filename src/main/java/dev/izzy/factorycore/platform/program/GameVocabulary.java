package dev.izzy.factorycore.platform.program;

import dev.izzy.factorycore.core.program.Vocabulary;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * The {@link Vocabulary} for sentence programs (ADR 0068): every registered item and fluid, the
 * loaded item tags, and English item names that differ from their ID paths. Built once and rebuilt
 * after tags reload. The server's language is English, which is what programs are written in.
 */
@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID)
public final class GameVocabulary {
  private static volatile Vocabulary cached;

  private GameVocabulary() {}

  public static Vocabulary get() {
    var vocabulary = cached;
    if (vocabulary == null) {
      var tags = new HashSet<String>();
      BuiltInRegistries.ITEM.getTagNames().forEach(tag -> tags.add(tag.location().toString()));
      cached =
          vocabulary =
              build(tags, item -> Language.getInstance().getOrDefault(item.getDescriptionId()));
    }
    return vocabulary;
  }

  @SubscribeEvent
  static void tagsUpdated(TagsUpdatedEvent event) {
    cached = null;
  }

  /**
   * Items, fluids (not the empty fluid), {@code tags} and the {@code name}s that differ from an
   * item's ID path. A name two items share is left out, so it never picks one silently.
   */
  public static Vocabulary build(Set<String> tags, Function<Item, String> name) {
    var items = new HashSet<String>();
    Map<String, String> names = new HashMap<>();
    var shared = new HashSet<String>();
    for (var item : BuiltInRegistries.ITEM) {
      var id = BuiltInRegistries.ITEM.getKey(item);
      if (item == net.minecraft.world.item.Items.AIR) continue;
      items.add(id.toString());
      var english = name.apply(item).strip().toLowerCase(Locale.ROOT);
      // An untranslated name is its key (block.mod.thing); leave it out.
      if (english.isEmpty()
          || english.equals(item.getDescriptionId())
          || english.contains("=")
          || english.contains("\n")
          || english.equals(id.getPath().replace('_', ' '))) continue;
      if (names.putIfAbsent(english, id.toString()) != null) shared.add(english);
    }
    shared.forEach(names::remove);
    var fluids = new HashSet<String>();
    for (var fluid : BuiltInRegistries.FLUID)
      if (fluid != Fluids.EMPTY) fluids.add(BuiltInRegistries.FLUID.getKey(fluid).toString());
    return new Vocabulary(items, fluids, tags, names);
  }
}
