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
      "every 10 ticks as \"preview\" {\nmove item(\"minecraft:iron_ingot\") from machines(\"smelter\") to storage limit 4\n}";
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
            editor.getValue().equals(SOURCE + " item(\"minecraft:coal\")"),
            "Native item picker inserts a working escaped expression");
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
            editor.getValue().equals(SOURCE + " machines(\"smelter\")"),
            "Native picker inserts only an authorized local label expression");
        screen.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
        require(editor.getValue().equals(SOURCE), "Label insertion is one undo step");
        // Complete an actual identifier in a quoted item call, retaining its delimiters.
        editor.setValue(SOURCE.replace("minecraft:iron_ingot", "minecraft:ir"));
        screen.keyPressed(GLFW.GLFW_KEY_PAGE_UP, 0, 0);
        int cursor = editor.getValue().indexOf("minecraft:ir") + "minecraft:ir".length();
        for (int index = 0; index < cursor; index++) screen.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0);
        screen.keyPressed(GLFW.GLFW_KEY_SPACE, 0, GLFW.GLFW_MOD_CONTROL);
        advance(ticks);
      }
      case 4 -> {
        search(screen, "minecraft:iron_ingot");
        click(screen, "minecraft:iron_ingot");
        require(
            editor.getValue().equals(SOURCE),
            "Ctrl+Space completes a quoted resource without duplicate delimiters");
        advance(ticks);
      }
      case 5 -> {
        capture.accept("program-highlighted-scale-2.png");
        client.options.guiScale().set(4);
        client.resizeDisplay();
        advance(ticks);
      }
      case 6 -> {
        require(editor.getValue().equals(SOURCE), "Draft and history survive scale4 resize");
        capture.accept("program-highlighted-scale-4.png");
        click(screen, "Preview next rule");
        advance(ticks);
      }
      case 7 -> {
        var author = screen.getMenu().authorView();
        if (author == null || author.preview().isEmpty()) return false;
        require(
            author.preview().stream()
                .anyMatch(line -> line.contains("Move <= 4 item minecraft:iron_ingot")),
            "Server preview resolves the draft's real local target");
        require(!screen.getMenu().view().deployed(), "Preview does not deploy the program");
        capture.accept("program-rule-preview-scale-4.png");
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        client.options.guiScale().set(2);
        client.resizeDisplay();
        advance(ticks);
      }
      case 8 -> {
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
