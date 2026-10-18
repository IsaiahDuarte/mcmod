package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.program.ProgramTemplates;
import dev.izzy.factorycore.platform.program.ProgramMenu;
import dev.izzy.factorycore.platform.program.ProgramPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/** Program editor: draft text, inline diagnostics, deployment controls, templates and logs. */
public final class ProgramScreen extends AbstractContainerScreen<ProgramMenu> {
  private MultiLineEditBox editor;
  private Button pause;
  private int template = -1;
  private boolean loaded;

  public ProgramScreen(ProgramMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 344;
    imageHeight = 240;
  }

  @Override
  protected void init() {
    String previous = editor == null ? menu.draft() : editor.getValue();
    super.init();
    editor =
        addRenderableWidget(
            new MultiLineEditBox(
                font,
                leftPos + 8,
                topPos + 18,
                220,
                160,
                Component.translatable("gui.factorycore.program.placeholder"),
                Component.translatable("gui.factorycore.program.editor")));
    editor.setCharacterLimit(
        dev.izzy.factorycore.platform.program.ProgramControllerEntity.MAX_DRAFT);
    editor.setValue(previous);
    button("save", 234, 18, () -> upload(-1));
    button("check", 234, 40, () -> upload(ProgramPackets.CHECK));
    button("deploy", 234, 62, () -> upload(ProgramPackets.DEPLOY));
    pause = button("pause", 234, 84, this::togglePause);
    button("stop", 234, 106, () -> action(ProgramPackets.STOP));
    button("template", 234, 128, this::nextTemplate);
    button("wasm", 234, 150, this::uploadWasm);
  }

  private Button button(String key, int x, int y, Runnable action) {
    return addRenderableWidget(
        Button.builder(Component.translatable("gui.factorycore.program." + key), b -> action.run())
            .bounds(leftPos + x, topPos + y, 102, 18)
            .tooltip(
                Tooltip.create(Component.translatable("gui.factorycore.program." + key + ".help")))
            .build());
  }

  /** Sends the editor text as bounded draft chunks, then the optional follow-up action. */
  private void upload(int then) {
    for (var chunk : ProgramPackets.chunks(menu.containerId, menu.session(), editor.getValue()))
      PacketDistributor.sendToServer(chunk);
    if (then >= 0) action(then);
  }

  /** Uploads and deploys the newest .wasm in {@code <game dir>/factorycore/programs}. */
  private void uploadWasm() {
    var folder = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("factorycore/programs");
    try (var files = java.nio.file.Files.list(folder)) {
      var newest =
          files
              .filter(path -> path.toString().endsWith(".wasm"))
              .max(java.util.Comparator.comparingLong(path -> path.toFile().lastModified()));
      if (newest.isEmpty()) {
        minecraft.player.displayClientMessage(
            Component.translatable("gui.factorycore.program.wasm.none", folder.toString()), false);
        return;
      }
      var bytes = java.nio.file.Files.readAllBytes(newest.get());
      for (var chunk : ProgramPackets.Artifact.chunks(menu.containerId, menu.session(), bytes))
        PacketDistributor.sendToServer(chunk);
      minecraft.player.displayClientMessage(
          Component.translatable(
              "gui.factorycore.program.wasm.sent", newest.get().getFileName().toString()),
          false);
    } catch (java.io.IOException | IllegalArgumentException failure) {
      minecraft.player.displayClientMessage(
          Component.translatable("gui.factorycore.program.wasm.none", folder.toString()), false);
    }
  }

  private void action(int action) {
    PacketDistributor.sendToServer(
        new ProgramPackets.Command(menu.containerId, menu.session(), action, 0, 1, ""));
  }

  private void togglePause() {
    action(menu.view().state().equals("running") ? ProgramPackets.PAUSE : ProgramPackets.RESUME);
  }

  private void nextTemplate() {
    template = (template + 1) % ProgramTemplates.ALL.size();
    editor.setValue(ProgramTemplates.ALL.get(template).source());
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    if (!loaded && !menu.draft().isEmpty()) {
      loaded = true;
      editor.setValue(menu.draft());
    }
    var view = menu.view();
    pause.active = view.deployed();
    pause.setMessage(
        Component.translatable(
            "gui.factorycore.program." + (view.state().equals("running") ? "pause" : "resume")));
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff282c30);
    graphics.fill(leftPos + 4, topPos + 4, leftPos + 340, topPos + 236, 0xff44484d);
    graphics.fill(leftPos + 8, topPos + 182, leftPos + 336, topPos + 232, 0xff171d22);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    graphics.drawString(font, title, 8, 6, 0xffffff, false);
    var view = menu.view();
    var state =
        Component.translatable(
            "gui.factorycore.program.state",
            Component.translatable("status.factorycore.program." + view.state()),
            view.energy());
    // Runtime problems (no FE, offline network) share the state line so nothing overlaps.
    if (!view.status().isEmpty() && !view.status().equals("running"))
      state =
          state
              .copy()
              .append(" · ")
              .append(Component.translatable("gui.factorycore.program.status." + view.status()));
    graphics.drawString(
        font, font.plainSubstrByWidth(state.getString(), 104), 234, 172, 0xe4bc83, false);
    int y = 185;
    var lines = new java.util.ArrayList<String>();
    if (!view.message().isEmpty()) lines.add(view.message());
    lines.addAll(view.diagnostics());
    if (lines.isEmpty()) lines.addAll(view.logs());
    for (int i = Math.max(0, lines.size() - 4); i < lines.size(); i++) {
      graphics.drawString(
          font,
          font.plainSubstrByWidth(lines.get(i), 324),
          12,
          y,
          view.diagnostics().contains(lines.get(i)) ? 0xff8a80 : 0xd8dee9,
          false);
      y += 11;
    }
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    // Typing in the editor must not close the screen on the inventory key.
    if (editor.isFocused() && key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)
      return editor.keyPressed(key, scan, modifiers) || true;
    return super.keyPressed(key, scan, modifiers);
  }
}
