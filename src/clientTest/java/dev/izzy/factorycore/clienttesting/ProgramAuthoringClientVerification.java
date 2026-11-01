package dev.izzy.factorycore.clienttesting;

import dev.izzy.factorycore.platform.client.ProgramScreen;
import dev.izzy.factorycore.platform.client.ProgramTextEditor;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import org.lwjgl.glfw.GLFW;

/**
 * Real widget interactions appended to the isolated program-editor tour, without guest execution.
 */
final class ProgramAuthoringClientVerification {
  private static final String SOURCE =
      "every 0.5 seconds called preview:\n    move 4 iron ingot from smelter to storage\n";
  private int step, entered;

  boolean tick(Minecraft client, ProgramScreen screen, int ticks, Consumer<String> capture) {
    if (ticks - entered < 8) return false;
    var editor = editor(screen);
    switch (step) {
      case 0 -> {
        editor.load("");
        screen.setFocused(editor);
        editor.setFocused(true);
        for (char character : SOURCE.toCharArray()) {
          if (character == '\n') screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
          else screen.charTyped(character, 0);
        }
        require(editor.getValue().equals(SOURCE), "Typed multiline source preserved exactly");
        // Q135: while typing, screen-key hooks (JEI's R and U) never see the key.
        require(
            net.neoforged.neoforge.client.ClientHooks.onScreenKeyPressedPre(
                    screen, GLFW.GLFW_KEY_R, 0, 0)
                && editor.getValue().equals(SOURCE),
            "A focused editor takes R before other mods' key hooks");
        screen.setFocused(null);
        require(
            !net.neoforged.neoforge.client.ClientHooks.onScreenKeyPressedPre(
                screen, GLFW.GLFW_KEY_R, 0, 0),
            "Without editor focus the key reaches other mods again");
        screen.setFocused(editor);
        editor.setFocused(true);
        editor.insert(" bogus");
        advance(ticks);
      }
      case 1 -> {
        capture.accept("program-inline-error-scale-2.png");
        screen.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
        require(editor.getValue().equals(SOURCE), "Undo removes one atomic edit");
        screen.keyPressed(GLFW.GLFW_KEY_Y, 0, GLFW.GLFW_MOD_CONTROL);
        require(editor.getValue().equals(SOURCE + " bogus"), "Redo restores exact edit");
        screen.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
        click(screen, "Insert / complete");
        search(screen, "minecraft:coal");
        advance(ticks);
      }
      case 2 -> {
        capture.accept("program-native-items-scale-2.png");
        click(screen, "minecraft:coal");
        require(
            editor.getValue().equals(SOURCE + "coal"),
            "The item picker inserts the item as a program names it: " + editor.getValue());
        screen.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
        require(editor.getValue().equals(SOURCE), "Item picker insertion is one undo step");
        click(screen, "Insert / complete");
        clickCategory(screen);
        clickCategory(screen);
        search(screen, "smelter");
        advance(ticks);
      }
      case 3 -> {
        if (screen.getMenu().authorView() == null
            || !screen.getMenu().authorView().labels().contains("smelter")) return false;
        capture.accept("program-native-labels-scale-2.png");
        click(screen, "smelter");
        require(
            editor.getValue().equals(SOURCE + "smelter"),
            "The label picker inserts only an authorized local label");
        screen.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
        require(editor.getValue().equals(SOURCE), "Label insertion is one undo step");
        // Q123: type the move line again and let the suggestions finish the names.
        int body = SOURCE.indexOf('\n') + 1;
        editor.load(SOURCE.substring(0, body));
        screen.setFocused(editor);
        editor.setFocused(true);
        screen.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0);
        type(screen, "    move 4 iron ing");
        require(
            !editor.suggestions().isEmpty() && editor.suggestions().getFirst().equals("iron ingot"),
            "Typing an item name suggests it: " + editor.suggestions());
        advance(ticks);
      }
      case 4 -> {
        capture.accept("program-suggestion-scale-2.png");
        screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        type(screen, "from sm");
        require(
            editor.suggestions().contains("smelter"),
            "After 'from' the visible labels are suggested: " + editor.suggestions());
        screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        type(screen, "to storage");
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        require(
            editor.getValue().equals(SOURCE),
            "Tab accepted both suggestions into the same program: " + editor.getValue());
        // A misspelt item shows the problem with its hint under the editor.
        editor.setValue(SOURCE.replace("iron ingot", "iron ingit"));
        var problem = editor.diagnostics();
        require(
            !problem.isEmpty() && "Did you mean iron ingot?".equals(problem.getFirst().hint()),
            "A misspelt item suggests the right name: " + problem);
        advance(ticks);
      }
      case 5 -> {
        capture.accept("program-error-hint-scale-2.png");
        editor.setValue(SOURCE);
        advance(ticks);
      }
      case 6 -> {
        capture.accept("program-highlighted-scale-2.png");
        client.options.guiScale().set(4);
        client.resizeDisplay();
        advance(ticks);
      }
      case 7 -> {
        require(editor.getValue().equals(SOURCE), "Draft and history survive scale4 resize");
        capture.accept("program-highlighted-scale-4.png");
        click(screen, "Preview next rule");
        advance(ticks);
      }
      case 8 -> {
        var author = screen.getMenu().authorView();
        if (author == null || author.preview().isEmpty()) return false;
        require(
            author.preview().stream()
                .anyMatch(
                    line ->
                        line.contains("Move <= 4 item minecraft:iron_ingot")
                            && line.contains("\"smelter\" #1 -> storage")),
            "Server preview resolves the draft's real local target");
        require(!screen.getMenu().view().deployed(), "Preview does not deploy the program");
        capture.accept("program-rule-preview-scale-4.png");
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        client.options.guiScale().set(2);
        client.resizeDisplay();
        advance(ticks);
      }
      case 9 -> {
        return true;
      }
      default -> throw new IllegalStateException("Unexpected authoring stage");
    }
    return false;
  }

  private void advance(int ticks) {
    entered = ticks;
    step++;
  }

  private static ProgramTextEditor editor(ProgramScreen screen) {
    return screen.children().stream()
        .filter(child -> child instanceof ProgramTextEditor)
        .map(child -> (ProgramTextEditor) child)
        .findFirst()
        .orElseThrow();
  }

  /** Types as a player would, with Enter for a new line. */
  private static void type(ProgramScreen screen, String text) {
    for (char character : text.toCharArray()) {
      if (character == '\n') screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
      else screen.charTyped(character, 0);
    }
  }

  private static void clickCategory(ProgramScreen screen) {
    var category =
        screen.children().stream()
            .filter(
                child ->
                    child instanceof Button button
                        && button.visible
                        && button.getMessage().getString().contains("change category"))
            .map(child -> (Button) child)
            .findFirst()
            .orElseThrow();
    screen.mouseClicked(category.getX() + 4, category.getY() + 4, 0);
  }

  private static void search(ProgramScreen screen, String value) {
    var field =
        screen.children().stream()
            .filter(child -> child instanceof EditBox box && box.visible)
            .map(child -> (EditBox) child)
            .findFirst()
            .orElseThrow();
    field.setValue("");
    screen.setFocused(field);
    for (char character : value.toCharArray()) screen.charTyped(character, 0);
  }

  private static void click(ProgramScreen screen, String label) {
    var button =
        screen.children().stream()
            .filter(
                child ->
                    child instanceof Button value
                        && value.visible
                        && value.active
                        && value.getMessage().getString().equals(label))
            .map(child -> (Button) child)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Authoring action missing: " + label));
    screen.mouseClicked(button.getX() + 4, button.getY() + button.getHeight() / 2.0, 0);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
