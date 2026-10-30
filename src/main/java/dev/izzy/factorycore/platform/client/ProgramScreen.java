package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.program.DraftHistory;
import dev.izzy.factorycore.core.program.ProgramTemplates;
import dev.izzy.factorycore.core.program.ResourceExpressions;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.program.ProgramMenu;
import dev.izzy.factorycore.platform.program.ProgramPackets;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Program editor: draft text, inline diagnostics, deployment controls, templates and logs. On the
 * panel kit since playtest H3c; wider than other panels because the editor needs the width. The
 * buttons are grouped: saving and deploying, running, and writing (templates, insert, preview,
 * Wasm). Templates open in the same chooser as Insert, by name.
 */
public final class ProgramScreen extends AbstractContainerScreen<ProgramMenu> {
  private ProgramTextEditor editor;
  private DraftHistory history;
  private EditBox search;
  private Button category;
  private final List<Button> choices = new ArrayList<>();
  private boolean refocusEditor;
  private Button previousPage, nextPage;
  private boolean selecting, completing, previewing;
  private int kind, page, previewRule, previewPage;

  /** The chooser's categories: items, fluids, local labels, keywords and templates. */
  private static final int KINDS = 5, TEMPLATES = 4;

  private Button scope;
  private List<String> options = List.of();
  private ProgramPackets.AuthorView observedAuthor;
  private String labelAfter = "";
  private Button pause;
  private boolean loaded;

  public ProgramScreen(ProgramMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 344;
    imageHeight = 240;
  }

  @Override
  protected void init() {
    String previous = editor == null ? menu.draft() : editor.getValue();
    imageWidth = Math.min(344, width);
    super.init();
    if (history == null) history = new DraftHistory(previous);
    editor =
        addRenderableWidget(
            new ProgramTextEditor(
                font, leftPos + 8, topPos + 18, editorWidth(), 148, previous, history));
    // Three groups, a gap apart: saving and deploying; running; writing.
    button("save", controlsX(), BUTTONS_Y, () -> upload(-1));
    button(
        "check",
        controlsX(),
        BUTTONS_Y + STEP,
        () -> {
          previewing = false;
          rebuildChoices();
          upload(ProgramPackets.CHECK);
        });
    button("deploy", controlsX(), BUTTONS_Y + 2 * STEP, () -> upload(ProgramPackets.DEPLOY));
    int running = BUTTONS_Y + 3 * STEP + GROUP;
    pause = button("pause", controlsX(), running, this::togglePause);
    button("stop", controlsX(), running + STEP, () -> action(ProgramPackets.STOP));
    int writing = running + 2 * STEP + GROUP;
    button("template", controlsX(), writing, this::openTemplates);
    button("insert", controlsX(), writing + STEP, () -> openSelector(false));
    button("preview", controlsX(), writing + 2 * STEP, this::preview);
    button("wasm", controlsX(), writing + 3 * STEP, this::uploadWasm);
    category =
        addRenderableWidget(
            Button.builder(
                    Component.empty(),
                    b -> {
                      kind = (kind + 1) % KINDS;
                      page = 0;
                      labelAfter = "";
                      if (kind == 2) requestAuthor(false, "");
                      rebuildChoices();
                    })
                .bounds(leftPos + 8, topPos + 18, editorWidth(), 18)
                .build());
    search =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + 8,
                topPos + 40,
                editorWidth(),
                18,
                Component.translatable("gui.factorycore.program.select.search")));
    search.setMaxLength(128);
    search.setResponder(
        text -> {
          page = 0;
          rebuildChoices();
        });
    choices.clear();
    for (int index = 0; index < 7; index++) {
      final int row = index;
      choices.add(
          addRenderableWidget(
              Button.builder(Component.empty(), b -> choose(row))
                  .bounds(leftPos + 8, topPos + 62 + index * 13, editorWidth(), 12)
                  .build()));
    }
    scope =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.program.scope"),
                    b -> {
                      requestAuthor(false, "");
                    })
                .bounds(leftPos + 8, topPos + 169, 90, 11)
                .build());
    previousPage =
        addRenderableWidget(
            Button.builder(
                    Component.literal("<"),
                    b -> {
                      if (previewing) {
                        previewPage = Math.max(0, previewPage - 1);
                      } else if (page > 0) page--;
                      else if (kind == 2) {
                        labelAfter = "";
                        requestAuthor(false, "");
                      }
                      rebuildChoices();
                    })
                .bounds(leftPos + 8, topPos + 156, 35, 18)
                .build());
    nextPage =
        addRenderableWidget(
            Button.builder(
                    Component.literal(">"),
                    b -> {
                      if (previewing) {
                        previewPage++;
                        rebuildChoices();
                      } else if ((page + 1) * choices.size() < options.size()) page++;
                      else if (kind == 2 && menu.authorView() != null && menu.authorView().more()) {
                        var labels = menu.authorView().labels();
                        labelAfter = labels.getLast();
                        page = 0;
                        requestAuthor(false, labelAfter);
                      }
                      rebuildChoices();
                    })
                .bounds(leftPos + 47, topPos + 156, 35, 18)
                .build());
    rebuildChoices();
  }

  /** The side buttons: 16 high, {@link #STEP} apart, with {@link #GROUP} between groups. */
  private static final int BUTTONS_Y = 18, STEP = 17, GROUP = 4;

  /** The message and log area under the editor. */
  private static final int LOG_Y = 182, LOG_HEIGHT = 50;

  private int editorWidth() {
    return imageWidth - 124;
  }

  private int controlsX() {
    return imageWidth - 110;
  }

  private void openSelector(boolean complete) {
    selecting = !selecting;
    completing = complete;
    previewing = false;
    var context = editor.completion();
    if (complete)
      kind =
          switch (context.call()) {
            case "item" -> 0;
            case "fluid" -> 1;
            case "machines" -> 2;
            default -> 3;
          };
    labelAfter = "";
    page = 0;
    search.setValue(complete ? context.prefix() : "");
    requestAuthor(false, "");
    rebuildChoices();
    setFocused(selecting ? search : editor);
  }

  /** Opens the chooser on the templates, listed by name. */
  private void openTemplates() {
    openSelector(false);
    if (!selecting) return;
    kind = TEMPLATES;
    rebuildChoices();
  }

  private void requestAuthor(boolean preview, String after) {
    PacketDistributor.sendToServer(
        new ProgramPackets.AuthorRequest(
            menu.containerId, menu.session(), preview, previewRule, after));
  }

  private void preview() {
    previewing = true;
    selecting = false;
    previewPage = 0;
    rebuildChoices();
    var author = menu.authorView();
    if (author != null && author.total() > 0) previewRule = (author.rule() + 1) % author.total();
    upload(-1);
    requestAuthor(true, "");
  }

  private void rebuildChoices() {
    if (category == null || search == null) return;
    category.setMessage(Component.translatable("gui.factorycore.program.select.kind." + kind));
    var needle = search.getValue().toLowerCase(Locale.ROOT);
    Stream<String> values =
        switch (kind) {
          case 0 -> BuiltInRegistries.ITEM.keySet().stream().map(Object::toString);
          case 1 -> BuiltInRegistries.FLUID.keySet().stream().map(Object::toString);
          case 2 ->
              menu.authorView() == null ? Stream.empty() : menu.authorView().labels().stream();
          case TEMPLATES -> ProgramTemplates.ALL.stream().map(ProgramTemplates.Template::name);
          default ->
              Stream.concat(
                  dev.izzy.factorycore.core.program.ProgramSyntax.KEYWORD_WORDS.stream(),
                  dev.izzy.factorycore.core.program.ProgramSyntax.CALL_WORDS.stream());
        };
    var matching = values.filter(value -> value.toLowerCase(Locale.ROOT).contains(needle));
    // Templates keep their teaching order; everything else is alphabetical.
    options = (kind == TEMPLATES ? matching : matching.sorted()).toList();
    editor.visible = !selecting && !previewing;
    if (scope != null) scope.visible = !selecting && !previewing;
    category.visible = selecting;
    search.visible = selecting;
    for (int index = 0; index < choices.size(); index++) {
      var button = choices.get(index);
      int option = page * choices.size() + index;
      button.visible = selecting;
      button.active = option < options.size();
      var text = option < options.size() ? options.get(option) : "";
      button.setMessage(Component.literal(font.plainSubstrByWidth(text, editorWidth() - 12)));
      button.setTooltip(Tooltip.create(Component.literal(text)));
    }
    if (previousPage != null) {
      previousPage.visible = selecting || previewing;
      nextPage.visible = selecting || previewing;
      previousPage.active = previewing ? previewPage > 0 : page > 0 || !labelAfter.isEmpty();
      nextPage.active =
          previewing
              ? (previewPage + 1) * 13 < previewLines().size()
              : (page + 1) * choices.size() < options.size()
                  || kind == 2 && menu.authorView() != null && menu.authorView().more();
    }
  }

  private void choose(int row) {
    int index = page * choices.size() + row;
    if (index >= options.size()) return;
    var value = options.get(index);
    if (kind == TEMPLATES) {
      for (var template : ProgramTemplates.ALL)
        if (template.name().equals(value)) editor.setValue(template.source());
      selecting = false;
      rebuildChoices();
      setFocused(editor);
      editor.setFocused(true);
      refocusEditor = true;
      return;
    }
    // Inside a quoted call, completion inserts its identifier; outside, insert a working
    // expression.
    String text =
        completing && editor.completion().quoted()
            ? ResourceExpressions.literal(value)
                .substring(1, ResourceExpressions.literal(value).length() - 1)
            : switch (kind) {
              case 0 -> ResourceExpressions.item(value);
              case 1 -> ResourceExpressions.fluid(value);
              case 2 -> "machines(" + ResourceExpressions.literal(value) + ")";
              default -> value;
            };
    if (completing) editor.complete(text);
    else insert(text);
    selecting = false;
    rebuildChoices();
    setFocused(editor);
    editor.setFocused(true);
    refocusEditor = true;
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    boolean handled = super.mouseClicked(x, y, button);
    // Vanilla focuses the clicked row after its action; keep typing and undo in the editor.
    if (refocusEditor) {
      refocusEditor = false;
      setFocused(editor);
      editor.setFocused(true);
    }
    return handled;
  }

  private Button button(String key, int x, int y, Runnable action) {
    return addRenderableWidget(
        ScreenButtons.labeled(
            "gui.factorycore.program.", key, leftPos + x, topPos + y, 102, 16, action));
  }

  /** Sends the editor text as bounded draft chunks, then the optional follow-up action. */
  private void upload(int then) {
    for (var chunk : ProgramPackets.chunks(menu.containerId, menu.session(), editor.getValue()))
      PacketDistributor.sendToServer(chunk);
    if (then >= 0) action(then);
  }

  /** Uploads and deploys the newest .wasm in {@code <game dir>/factorycore/programs}. */
  private void uploadWasm() {
    var folder = FMLPaths.GAMEDIR.get().resolve("factorycore/programs");
    try (var files = Files.list(folder)) {
      var newest =
          files
              .filter(path -> path.toString().endsWith(".wasm"))
              .max(Comparator.comparingLong(path -> path.toFile().lastModified()));
      if (newest.isEmpty()) {
        minecraft.player.displayClientMessage(
            Component.translatable("gui.factorycore.program.wasm.none", folder.toString()), false);
        return;
      }
      var bytes = Files.readAllBytes(newest.get());
      for (var chunk : ProgramPackets.Artifact.chunks(menu.containerId, menu.session(), bytes))
        PacketDistributor.sendToServer(chunk);
      minecraft.player.displayClientMessage(
          Component.translatable(
              "gui.factorycore.program.wasm.sent", newest.get().getFileName().toString()),
          false);
    } catch (IOException | IllegalArgumentException failure) {
      minecraft.player.displayClientMessage(
          Component.translatable("gui.factorycore.program.wasm.none", folder.toString()), false);
    }
  }

  /** Screen area of the draft editor, for recipe-viewer drop targets. */
  public Rect2i editorArea() {
    return new Rect2i(editor.getX(), editor.getY(), editor.getWidth(), editor.getHeight());
  }

  /**
   * Types {@code text} at the editor cursor, as if the player typed it. A space is typed first when
   * the draft does not already end in whitespace, so consecutive drops stay separate tokens.
   */
  public void insert(String text) {
    setFocused(editor);
    editor.setFocused(true);
    int cursor = editor.cursor();
    String prefix =
        cursor > 0 && !Character.isWhitespace(editor.getValue().charAt(cursor - 1)) ? " " : "";
    editor.insert(prefix + text);
  }

  private void action(int action) {
    PacketDistributor.sendToServer(
        new ProgramPackets.Command(menu.containerId, menu.session(), action, 0, 1, ""));
  }

  private void togglePause() {
    action(
        menu.view().state() == ProgramControllerEntity.State.RUNNING
            ? ProgramPackets.PAUSE
            : ProgramPackets.RESUME);
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    if (!loaded && menu.draftReceived()) {
      loaded = true;
      // A delayed opening snapshot must not replace text already entered by the player.
      if (editor.getValue().isEmpty()) editor.load(menu.draft());
    }
    if (menu.authorView() != observedAuthor) {
      observedAuthor = menu.authorView();
      rebuildChoices();
    }
    var view = menu.view();
    pause.active = view.deployed();
    pause.setMessage(
        Component.translatable(
            "gui.factorycore.program."
                + (view.state() == ProgramControllerEntity.State.RUNNING ? "pause" : "resume")));
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    Panels.panel(graphics, new Rect(leftPos, topPos, imageWidth, imageHeight));
    Panels.inset(graphics, new Rect(leftPos + 7, topPos + LOG_Y, imageWidth - 14, LOG_HEIGHT));
    if (previewing)
      Panels.inset(graphics, new Rect(leftPos + 7, topPos + 18, editorWidth() + 2, 137));
  }

  /** The strip follows the title on the free part of the title row. */
  private int stripX() {
    return 8 + font.width(title) + 10;
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    StatusStrip.tooltip(
        graphics,
        font,
        menu.deviceStatus(),
        leftPos + stripX(),
        topPos + 6,
        imageWidth - 8 - stripX(),
        mouseX,
        mouseY);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    Panels.title(graphics, font, title, 0, 0);
    var view = menu.view();
    StatusStrip.drawOnPanel(
        graphics, font, menu.deviceStatus(), stripX(), 6, imageWidth - 8 - stripX());
    if (menu.authorView() != null)
      scope.setTooltip(Tooltip.create(Component.literal(menu.authorView().scope())));
    if (previewing) {
      graphics.drawString(
          font,
          Component.translatable("gui.factorycore.program.snapshot"),
          12,
          21,
          Panels.WARN,
          false);
      var snapshot = previewLines();
      int end = Math.min(snapshot.size(), (previewPage + 1) * 13);
      for (int index = previewPage * 13; index < end; index++)
        graphics.drawString(
            font, snapshot.get(index), 12, 32 + (index % 13) * 9, Panels.TEXT, false);
    }
    int y = LOG_Y + 4;
    var lines = new ArrayList<String>();
    if (!view.message().isEmpty()) lines.add(view.message());
    lines.addAll(view.diagnostics());
    if (lines.isEmpty()) lines.addAll(view.logs());
    for (int i = Math.max(0, lines.size() - 4); i < lines.size(); i++) {
      graphics.drawString(
          font,
          font.plainSubstrByWidth(lines.get(i), imageWidth - 20),
          12,
          y,
          view.diagnostics().contains(lines.get(i)) ? Panels.BAD : Panels.TEXT,
          false);
      y += 11;
    }
  }

  private List<FormattedCharSequence> previewLines() {
    var lines = new ArrayList<FormattedCharSequence>();
    if (menu.authorView() != null)
      for (var line : menu.authorView().preview())
        lines.addAll(font.split(Component.literal(line), editorWidth() - 12));
    return lines;
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if ((hasControlDown() || (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0)
        && key == GLFW.GLFW_KEY_SPACE) {
      openSelector(true);
      return true;
    }
    if ((selecting || previewing) && key == GLFW.GLFW_KEY_ESCAPE) {
      selecting = false;
      previewing = false;
      rebuildChoices();
      setFocused(editor);
      return true;
    }
    // Typing in the editor must not close the screen on the inventory key.
    if (editor.isFocused() && key != GLFW.GLFW_KEY_ESCAPE)
      return editor.keyPressed(key, scan, modifiers) || true;
    return super.keyPressed(key, scan, modifiers);
  }
}
