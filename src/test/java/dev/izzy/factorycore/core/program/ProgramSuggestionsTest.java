package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.izzy.factorycore.core.program.ProgramSuggestions.Slot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Q123: suggestions while a sentence program is typed. */
class ProgramSuggestionsTest {
  private static final List<String> LABELS = List.of("smelter", "furnace", "my barrel");

  /** Suggestions with the cursor at the end of {@code typed}. */
  private static List<String> at(String typed) {
    var context = ProgramSuggestions.at(typed, typed.length());
    return ProgramSuggestions.options(context, GeneratedVocabulary.get(), LABELS, 8);
  }

  private static ProgramSuggestions.Context context(String typed) {
    return ProgramSuggestions.at(typed, typed.length());
  }

  @Test
  void aPartialItemNameCompletesWithAGhost() {
    var typed = "every second:\n    move 8 iron in";
    var context = context(typed);
    assertEquals(Slot.ITEM, context.slot());
    assertEquals("iron in", context.prefix());
    assertEquals(typed.indexOf("iron"), context.start());
    var options = at(typed);
    assertEquals("iron ingot", options.getFirst());
    assertEquals("got", ProgramSuggestions.ghost(context.prefix(), options));
  }

  @Test
  void itemsAreSuggestedWhereverANameGoes() {
    assertEquals("coal", at("every second:\n    move co").getFirst());
    assertEquals("raw iron", at("    keep 64 raw ir").getFirst());
    assertEquals("oak planks", at("    craft 64 oak pl").getFirst());
    assertEquals("dirt", at("    move everything except dir").getFirst());
    assertEquals("coal", at("    if furnace has less than 8 co").getFirst());
    assertEquals("water", at("    move 4000 fluid wa").getFirst());
    assertEquals(Slot.FLUID, context("    move fluid la").slot());
    assertTrue(at("    move raw mat").contains("raw materials"), "Common tags too");
  }

  @Test
  void aTypoStillFindsTheItem() {
    assertTrue(
        at("    move 8 iron ingit").contains("iron ingot"), at("    move 8 iron ingit").toString());
  }

  @Test
  void labelsStorageFacesAndNextWords() {
    assertEquals(List.of("smelter", "storage"), at("    move coal from s"));
    assertEquals(List.of("furnace"), at("    keep 8 coal in fu"));
    assertEquals("\"my barrel\"", at("    move coal from storage to my").getFirst());
    var after = at("    move coal from smelter ");
    assertEquals(Slot.AFTER_PLACE, context("    move coal from smelter ").slot());
    assertTrue(after.contains("top") && after.contains("to"), after.toString());
    assertEquals(List.of("bottom"), at("    move coal from storage to furnace bo"));
    assertTrue(at("    if furnace ").contains("has"));
    assertEquals("less than", at("    if furnace has le").getFirst());
    assertEquals(List.of("smelter"), at("    turn on sm"));
  }

  @Test
  void sentenceStartsAndUnits() {
    assertEquals(List.of("move"), at("every second:\n    mo"));
    assertEquals("every", at("ev").getFirst());
    assertEquals(List.of("second", "seconds"), at("every 5 sec"));
    assertEquals(Slot.UNIT, context("every ").slot());
  }

  @Test
  void commentsAndFinishedLinesSuggestNothingOdd() {
    assertEquals(Slot.START, context("# move co").slot());
    assertTrue(at("    say \"hi\" ").isEmpty() || !at("    say \"hi\" ").contains("coal"));
  }
}
