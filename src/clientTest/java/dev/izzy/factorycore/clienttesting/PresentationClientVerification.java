package dev.izzy.factorycore.clienttesting;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.client.BufferSettingsScreen;
import dev.izzy.factorycore.platform.client.ConnectorSettingsScreen;
import dev.izzy.factorycore.platform.client.DriveScreen;
import dev.izzy.factorycore.platform.client.GatewayPolicyScreen;
import dev.izzy.factorycore.platform.client.ItemDescriptions;
import dev.izzy.factorycore.platform.client.MachineSettingsScreen;
import dev.izzy.factorycore.platform.client.NetworkAccessScreen;
import dev.izzy.factorycore.platform.client.ProgramScreen;
import dev.izzy.factorycore.platform.client.RecoveryScreen;
import dev.izzy.factorycore.platform.client.TerminalScreen;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.guide.GuideContent;
import dev.izzy.factorycore.platform.network.DeviceLamp;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalJobPackets;
import dev.izzy.factorycore.platform.wireless.AccessPointEntity;
import dev.izzy.factorycore.platform.wireless.LinkEndpointEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import org.lwjgl.glfw.GLFW;

/**
 * Graphical presentation fixture ({@code runPresentationClient}): device orientations, status lamps
 * and drive bays by day and night, cable arms at sockets, first/third-person held items, real item
 * tooltips at screen edges, and every Factory Core screen at GUI scales 1–4 in the smallest
 * supported window (427×240 GUI pixels at scale 4). Each screen at each scale must keep its panel
 * and every visible control on screen, without overlap and with button labels that fit.
 */
public final class PresentationClientVerification {
  /** Smallest supported framebuffer: 427×240 GUI pixels at scale 4. */
  static final int MIN_WIDTH = 1708;

  static final int MIN_HEIGHT = 960;

  private interface Step {
    /** True when the step is complete; called once per client tick. */
    boolean tick(Minecraft client);
  }

  private final Path report = Path.of(System.getProperty("factorycore.client.report"));
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final AtomicBoolean serverDone = new AtomicBoolean();
  private final StringBuilder evidence = new StringBuilder();
  private final Captures captures = new Captures("presentation", report, evidence);
  private final List<Step> steps = new ArrayList<>();
  private final List<BlockPos> fed = new ArrayList<>();
  private final List<String> chat = new java.util.concurrent.CopyOnWriteArrayList<>();
  private int index;
  private int ticks;
  private int entered;
  private int scale;
  private boolean requested;

  public PresentationClientVerification() {
    buildSteps();
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::feed);
    NeoForge.EVENT_BUS.addListener(
        (net.neoforged.neoforge.client.event.ClientChatReceivedEvent.System event) ->
            chat.add(event.getMessage().getString()));
  }

  // ------------------------------------------------------------------ plumbing

  /** Keeps gallery wireless devices powered: 1,000 FE per tick each, their real input limit. */
  private void feed(ServerTickEvent.Post event) {
    var level = event.getServer().overworld();
    for (var position : List.copyOf(fed)) {
      var entity = level.getBlockEntity(position);
      if (entity instanceof LinkEndpointEntity link)
        link.energyStorage().receiveEnergy(1_000, false);
      if (entity instanceof AccessPointEntity point)
        point.energyStorage().receiveEnergy(1_000, false);
    }
  }

  private void tick(ClientTickEvent.Post event) {
    var client = Minecraft.getInstance();
    ticks++;
    if (index >= steps.size()) return;
    if (failure.get() != null)
      throw new IllegalStateException("Presentation fixture failed", failure.get());
    if (ticks - entered > 1200)
      throw new IllegalStateException(
          "Presentation stage timeout: "
              + index
              + " screen="
              + (client.screen == null ? "none" : client.screen.getClass().getName()));
    if (steps.get(index).tick(client)) {
      index++;
      entered = ticks;
      requested = false;
      serverDone.set(false);
      System.out.println("FACTORYCORE_PRESENTATION_STAGE " + index);
    }
  }

  private int waited() {
    return ticks - entered;
  }

  private void step(Step step) {
    steps.add(step);
  }

  /** Runs {@code action} on the server thread once; the step completes when it has run. */
  private void server(Consumer<ServerPlayer> action) {
    step(
        client -> {
          if (!requested) {
            requested = true;
            var server = client.getSingleplayerServer();
            var id = client.player.getUUID();
            server.execute(
                () -> {
                  try {
                    action.accept(server.getPlayerList().getPlayer(id));
                    serverDone.set(true);
                  } catch (RuntimeException error) {
                    failure.set(error);
                  }
                });
          }
          return serverDone.get();
        });
  }

  /** Waits until a server-side condition holds (checked once per client tick). */
  private void serverUntil(Predicate<ServerPlayer> condition) {
    step(
        client -> {
          if (serverDone.get()) return true;
          var server = client.getSingleplayerServer();
          var id = client.player.getUUID();
          server.execute(
              () -> {
                try {
                  if (condition.test(server.getPlayerList().getPlayer(id))) serverDone.set(true);
                } catch (RuntimeException error) {
                  failure.set(error);
                }
              });
          return false;
        });
  }

  private void waitTicks(int count) {
    step(client -> waited() >= count);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private static void setScale(Minecraft client, int scale) {
    client.options.guiScale().set(scale);
    client.resizeDisplay();
  }

  /** Holds the camera on a view once the teleport has arrived; false until then. */
  private static boolean aim(Minecraft client, PresentationScenes.View view) {
    var player = client.player;
    if (player.distanceToSqr(view.x(), view.y(), view.z()) > 1) return false;
    player.getAbilities().flying = true;
    player.setDeltaMovement(Vec3.ZERO);
    player.setYRot(view.yaw());
    player.setXRot(view.pitch());
    player.yRotO = view.yaw();
    player.xRotO = view.pitch();
    return true;
  }

  private void view(PresentationScenes.View view, String capture, int settle) {
    server(player -> PresentationScenes.teleport(player, view));
    step(client -> aim(client, view) && waited() >= settle);
    step(
        client -> {
          aim(client, view);
          captures.grab(client, capture);
          return true;
        });
  }

  // ------------------------------------------------------------------ screen fit

  /**
   * The screen's panel and every visible control lie inside the GUI, controls do not overlap and
   * button labels fit their buttons.
   */
  static String checkFits(Minecraft client, Screen screen) {
    int width = screen.width;
    int height = screen.height;
    var bounds = new StringBuilder();
    if (screen instanceof AbstractContainerScreen<?> container) {
      int left = container.getGuiLeft();
      int top = container.getGuiTop();
      bounds.append(container.getXSize()).append('x').append(container.getYSize());
      check(
          left >= 0
              && top >= 0
              && left + container.getXSize() <= width
              && top + container.getYSize() <= height,
          "Panel "
              + bounds
              + " at "
              + left
              + ","
              + top
              + " clipped in "
              + width
              + "x"
              + height
              + ": "
              + screen.getClass().getSimpleName());
    }
    var widgets =
        screen.children().stream()
            .filter(child -> child instanceof AbstractWidget widget && widget.visible)
            .map(child -> (AbstractWidget) child)
            .toList();
    if (screen instanceof BufferSettingsScreen) {
      for (var widget : widgets)
        if (widget instanceof EditBox field
            && (field.getMessage().getString().equals(I18n.get("gui.factorycore.buffer.rate"))
                || field
                    .getMessage()
                    .getString()
                    .equals(I18n.get("gui.factorycore.buffer.target"))))
          check(!field.getValue().isEmpty(), "Buffer numeric field lost its value after resize");
    }
    for (var widget : widgets)
      check(
          widget.getX() >= 0
              && widget.getY() >= 0
              && widget.getRight() <= width
              && widget.getBottom() <= height,
          "Control off screen at "
              + width
              + "x"
              + height
              + ": "
              + widget.getMessage().getString()
              + " "
              + widget.getRectangle());
    for (int first = 0; first < widgets.size(); first++)
      for (int second = first + 1; second < widgets.size(); second++) {
        var a = widgets.get(first);
        var b = widgets.get(second);
        check(
            a.getRight() <= b.getX()
                || b.getRight() <= a.getX()
                || a.getBottom() <= b.getY()
                || b.getBottom() <= a.getY(),
            "Controls overlap: " + a.getMessage().getString() + " / " + b.getMessage().getString());
      }
    for (var widget : widgets)
      if (widget instanceof Button button)
        check(
            client.font.width(button.getMessage()) <= button.getWidth() - 4,
            "Label does not fit its button: " + button.getMessage().getString());
    return screen.getClass().getSimpleName()
        + " panel="
        + (bounds.isEmpty() ? "none" : bounds)
        + " gui="
        + width
        + "x"
        + height
        + " controls="
        + widgets.size();
  }

  /** Visits scales 1–4 with {@code screen} open; captures scales 1 and 4 (all four if asked). */
  private void scales(String name, Class<? extends Screen> type, boolean captureAll) {
    for (int value = 1; value <= 4; value++) {
      int target = value;
      step(
          client -> {
            setScale(client, target);
            scale = target;
            return true;
          });
      step(
          client -> {
            if (!type.isInstance(client.screen) || waited() < 6) return false;
            check(
                client.getWindow().getGuiScale() == scale,
                "GUI scale " + scale + " not available: " + client.getWindow().getGuiScale());
            evidence
                .append("fit ")
                .append(name)
                .append(" scale=")
                .append(scale)
                .append(": ")
                .append(checkFits(client, client.screen))
                .append('\n');
            if (captureAll || scale == 1 || scale == 4)
              captures.grab(client, name + "-scale-" + scale + ".png");
            return true;
          });
    }
    step(
        client -> {
          setScale(client, 2);
          if (client.screen != null) client.screen.onClose();
          if (client.player.containerMenu != client.player.inventoryMenu)
            client.player.closeContainer();
          return true;
        });
    step(client -> client.screen == null && waited() > 4);
  }

  private static TerminalScreen terminal(Minecraft client) {
    return (TerminalScreen) client.screen;
  }

  private static boolean useGhost(Minecraft client, String id) {
    var screen = terminal(client);
    var view = screen.getMenu().view();
    if (view.busy() || waitedForRows(screen)) return false;
    for (int row = 0; row < Math.min(8, view.rows().size()); row++) {
      if (!view.rows().get(row).icon().equals(id)) continue;
      screen.keyPressed(GLFW.GLFW_KEY_1 + row, 0, 0);
      return press(client, "Use selected");
    }
    return false;
  }

  private static boolean waitedForRows(TerminalScreen screen) {
    return screen.getMenu().view().size() != screen.columns() * screen.rows();
  }

  private void panelCaptures(String name) {
    for (int target : new int[] {1, 4}) {
      step(
          client -> {
            setScale(client, target);
            return true;
          });
      step(
          client -> {
            if (waited() < 6 || waitedForRows(terminal(client))) return false;
            evidence
                .append("fit ")
                .append(name)
                .append(" scale=")
                .append(target)
                .append(": ")
                .append(checkFits(client, client.screen))
                .append('\n');
            check(
                terminal(client).columns() >= 9 && terminal(client).rows() >= 4,
                "Expanded crafting panel keeps a usable resource browser");
            captures.grab(client, name + "-scale-" + target + ".png");
            return true;
          });
    }
  }

  private void countGhost(int cell, int amount) {
    for (int value = 2; value <= amount; value++) {
      int previous = value - 1;
      step(
          client ->
              terminal(client).getMenu().gridView().counts().get(cell) == previous
                  && waited() > 2
                  && press(client, "+"));
    }
    step(
        client ->
            terminal(client).getMenu().gridView().counts().get(cell) == amount && waited() > 4);
  }

  /** Actual panel controls, crafts, both encoders and exact fluid units at scales 1 and 4. */
  private void craftingTour(BlockPos base) {
    server(
        player -> {
          var drive = (StorageDriveEntity) player.serverLevel().getBlockEntity(base.east());
          check(
              drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_FLUID))) == 2,
              "Fluid cell installed");
          player.getInventory().clearContent();
          player.getInventory().setItem(22, new ItemStack(CraftingContent.BLANK_PATTERN.get(), 2));
        });
    serverUntil(
        player -> {
          var drive = (StorageDriveEntity) player.serverLevel().getBlockEntity(base.east());
          var key =
              new PlatformResourceCodec(player.registryAccess())
                  .fluidKey(new FluidStack(Fluids.WATER, 1));
          return valid(player, base.east(2))
              && DeviceSavedData.get(player.server)
                  .registry()
                  .stock(CellHandleData.read(drive.cell(2)), key)
                  .loaded();
        });
    server(
        player -> {
          var drive = (StorageDriveEntity) player.serverLevel().getBlockEntity(base.east());
          var saved = DeviceSavedData.get(player.server);
          var codec = new PlatformResourceCodec(player.registryAccess());
          check(
              saved
                      .registry()
                      .insert(
                          CellHandleData.read(drive.cell(1)),
                          codec.itemKey(new ItemStack(Items.OAK_LOG)),
                          1)
                      .amount()
                  == 1,
              "Craft log admitted");
          check(
              saved
                      .registry()
                      .insert(
                          CellHandleData.read(drive.cell(2)),
                          codec.fluidKey(new FluidStack(Fluids.WATER, 1)),
                          4000)
                      .amount()
                  == 4000,
              "Processing water admitted");
        });
    use(base.east(2), Direction.SOUTH, false);
    step(
        client ->
            client.screen instanceof TerminalScreen && waited() > 4 && press(client, "Crafting »"));
    step(
        client ->
            terminal(client).craftingOpen()
                && waited() > 6
                && useGhost(client, "minecraft:oak_log"));
    step(
        client ->
            terminal(client).getMenu().gridView().result().equals("minecraft:oak_planks")
                && waited() > 4);
    panelCaptures("crafting-table");
    step(client -> press(client, "Encode"));
    step(
        client -> terminal(client).getMenu().gridView().status().equals("encoded") && waited() > 4);
    server(
        player -> {
          var patterns =
              player.getInventory().items.stream()
                  .filter(stack -> stack.has(CraftingContent.PATTERN.get()))
                  .toList();
          check(
              patterns.size() == 1
                  && patterns.getFirst().get(CraftingContent.PATTERN.get()).output().getCount()
                      == 4,
              "Table Encode creates one exact four-plank pattern");
          check(
              player.getInventory().countItem(CraftingContent.BLANK_PATTERN.get()) == 1,
              "Table Encode consumes one blank");
        });
    step(client -> press(client, "Craft"));
    step(
        client -> terminal(client).getMenu().gridView().status().equals("crafted") && waited() > 4);
    server(
        player ->
            check(
                player.getInventory().countItem(Items.OAK_PLANKS) == 4,
                "Craft returns four real planks"));
    step(client -> press(client, "Clear"));
    step(
        client ->
            terminal(client).getMenu().gridView().cells().stream().allMatch(String::isEmpty)
                && waited() > 4
                && press(client, "Inputs"));
    step(
        client ->
            terminal(client).getMenu().gridView().processing()
                && waited() > 4
                && press(client, "Fluids"));
    step(
        client ->
            terminal(client).getMenu().view().kind() == 1
                && waited() > 6
                && useGhost(client, "minecraft:water"));
    step(client -> terminal(client).getMenu().gridView().fluid(0) && waited() > 4);
    countGhost(0, 4);
    step(client -> press(client, "−"));
    step(
        client ->
            terminal(client).getMenu().gridView().amount(0) == 750
                && waited() > 4
                && press(client, "+"));
    step(client -> terminal(client).getMenu().gridView().amount(0) == 1000 && waited() > 4);
    panelCaptures("crafting-inputs");
    step(client -> press(client, "Outputs"));
    step(client -> waited() > 4 && useGhost(client, "minecraft:water"));
    step(client -> terminal(client).getMenu().gridView().fluid(9) && waited() > 4);
    countGhost(9, 2);
    step(client -> press(client, "Items"));
    step(client -> terminal(client).getMenu().view().kind() == 0 && waited() > 6);
    step(
        client -> {
          var screen = terminal(client);
          screen.mouseClicked(
              screen.getGuiLeft() + screen.gridX() + 27,
              screen.getGuiTop() + screen.gridY() + 9,
              0);
          return useGhost(client, "minecraft:stone");
        });
    step(
        client ->
            terminal(client).getMenu().gridView().cells().get(10).equals("minecraft:stone")
                && waited() > 4);
    countGhost(10, 2);
    panelCaptures("crafting-outputs");
    step(client -> press(client, "« Crafting"));
    step(
        client -> {
          if (waited() < 6) return false;
          check(!terminal(client).craftingOpen(), "Panel stays collapsed with populated ghosts");
          check(
              terminal(client).getMenu().gridView().amount(0) == 1000
                  && terminal(client).getMenu().gridView().amount(9) == 500,
              "Collapse preserves all ghosts");
          return press(client, "Crafting »");
        });
    step(client -> terminal(client).craftingOpen() && waited() > 6 && press(client, "Encode"));
    step(
        client -> terminal(client).getMenu().gridView().status().equals("encoded") && waited() > 4);
    server(
        player -> {
          var pattern =
              player.getInventory().items.stream()
                  .filter(stack -> stack.has(CraftingContent.PROCESSING.get()))
                  .findFirst()
                  .orElseThrow()
                  .get(CraftingContent.PROCESSING.get());
          check(
              pattern.inputs().isEmpty()
                  && pattern.fluidInputs().size() == 1
                  && pattern.fluidInputs().getFirst().getAmount() == 1000
                  && pattern.outputs().size() == 1
                  && pattern.outputs().getFirst().is(Items.STONE)
                  && pattern.outputs().getFirst().getCount() == 2
                  && pattern.fluidOutputs().size() == 1
                  && pattern.fluidOutputs().getFirst().getAmount() == 500,
              "Processing Encode preserves exact item and mB declarations");
          check(
              player.getInventory().countItem(CraftingContent.BLANK_PATTERN.get()) == 0,
              "Both encoders each consume exactly one blank");
          var drive = (StorageDriveEntity) player.serverLevel().getBlockEntity(base.east());
          var codec = new PlatformResourceCodec(player.registryAccess());
          var registry = DeviceSavedData.get(player.server).registry();
          check(
              registry
                          .stock(
                              CellHandleData.read(drive.cell(2)),
                              codec.fluidKey(new FluidStack(Fluids.WATER, 1)))
                          .total()
                      == 4000
                  && registry
                          .stock(
                              CellHandleData.read(drive.cell(1)),
                              codec.itemKey(new ItemStack(Items.STONE)))
                          .total()
                      == 1,
              "Ghost edits and encoding consume no declared resources");
          player.getInventory().clearContent();
        });
    step(client -> press(client, "Table"));
    step(
        client -> {
          if (waited() < 6 || terminal(client).getMenu().gridView().processing()) return false;
          check(
              terminal(client).getMenu().gridView().fluidMask() == 0,
              "Table drops fluid ghosts under the existing contract");
          evidence.append(
              "crafting panel: real table craft and both encoders; 1000 mB water input -> 2 Stone + 500 mB water output, no declared stock consumed; collapse/reopen and scale changes retain ghosts.\n");
          client.player.closeContainer();
          setScale(client, 2);
          return true;
        });
    step(client -> client.screen == null && waited() > 4);
  }

  /**
   * Terminal Jobs mode through real clicks: a pattern's craftable row, ×8 preview, Start, the
   * finished delivery, then a ×64 shortage preview at scale 4 with fit checks.
   */
  private void jobsTour(BlockPos base) {
    server(PresentationScenes::jobs);
    serverUntil(player -> valid(player, PresentationScenes.JOB_UNIT) && planks(player) == 0);
    server(PresentationScenes::stockJobs);
    use(base.east(2), Direction.SOUTH, false);
    step(
        client -> client.screen instanceof TerminalScreen && waited() > 4 && press(client, "Jobs"));
    step(client -> !jobView(client).craftables().isEmpty() && press(client, "×8"));
    step(
        client -> {
          var view = jobView(client);
          if (view.preview().icon().isEmpty() || waited() < 6) return false;
          check(
              view.previewReason().isEmpty(),
              "Eight planks are craftable: "
                  + view.previewReason()
                  + " "
                  + view.missing()
                  + " "
                  + view.preview());
          captures.grab(client, "jobs-preview-scale-2.png");
          return press(client, "Start");
        });
    step(
        client -> {
          if (waited() < 6) return false;
          captures.grab(client, "jobs-started-scale-2.png");
          return true;
        });
    serverUntil(
        player ->
            DeviceSavedData.get(player.server).craftingJobs().all().isEmpty()
                && planks(player) == 8);
    step(
        client -> {
          if (!chat.contains("Crafting job finished: 8 × Oak Planks")) return false;
          return waited() > 10 && jobView(client).jobs().isEmpty() && press(client, "×64");
        });
    step(
        client -> {
          var view = jobView(client);
          if (view.missing().isEmpty() || waited() < 4) return false;
          check(!view.previewReason().isEmpty(), "A 64-plank plan reports its shortage");
          setScale(client, 4);
          return true;
        });
    step(
        client -> {
          if (waited() < 6) return false;
          evidence
              .append("fit jobs scale=4: ")
              .append(checkFits(client, client.screen))
              .append('\n');
          captures.grab(client, "jobs-missing-scale-4.png");
          // Keep 64 planks with only 6 logs: the rule waits and the row must show it is blocked.
          return press(client, "Keep");
        });
    step(
        client -> {
          var rows = jobView(client).craftables();
          if (rows.isEmpty() || !rows.getFirst().note().equals("missing") || waited() < 6)
            return false;
          captures.grab(client, "jobs-keep-waiting-scale-4.png");
          press(client, "Keep");
          setScale(client, 2);
          press(client, "Items");
          client.screen.onClose();
          return true;
        });
    step(client -> client.screen == null && waited() > 4);
  }

  private static long planks(ServerPlayer player) {
    var drive =
        (StorageDriveEntity) player.serverLevel().getBlockEntity(PresentationScenes.SCREENS.east());
    var key =
        new PlatformResourceCodec(player.registryAccess()).itemKey(new ItemStack(Items.OAK_PLANKS));
    var registry = DeviceSavedData.get(player.server).registry();
    long total = 0;
    for (int i = 0; i < 4; i++) {
      var cell = drive.cell(i);
      if (cell.isEmpty()) continue;
      var handle = CellHandleData.read(cell);
      if (registry.ownership(handle.backing()).definition().kind() != ResourceKind.ITEM) continue;
      var stock = registry.stock(handle, key);
      if (!stock.loaded()) return -1;
      total = Math.addExact(total, stock.total());
    }
    return total;
  }

  private static TerminalJobPackets.View jobView(Minecraft client) {
    check(client.screen instanceof TerminalScreen, "Terminal closed during the jobs tour");
    return ((TerminalScreen) client.screen).getMenu().jobView();
  }

  /** Presses the visible, active button with this exact label; false when none is pressable. */
  private static boolean press(Minecraft client, String label) {
    for (var child : client.screen.children())
      if (child instanceof Button button
          && button.visible
          && button.active
          && button.getMessage().getString().equals(label)) {
        client.screen.mouseClicked(
            button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0);
        return true;
      }
    return false;
  }

  private void use(BlockPos target, Direction face, boolean sneak) {
    step(
        client -> {
          if (client.screen != null || waited() < 4) return false;
          if (sneak) shift(client, true);
          client.gameMode.useItemOn(
              client.player,
              InteractionHand.MAIN_HAND,
              new BlockHitResult(Vec3.atCenterOf(target), face, target, false));
          return true;
        });
  }

  private static void shift(Minecraft client, boolean down) {
    client.player.connection.send(
        new ServerboundPlayerCommandPacket(
            client.player,
            down
                ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
  }

  private static NodeRegistry.Position at(ServerPlayer player, BlockPos position) {
    return Positions.of(player.serverLevel(), position);
  }

  private static boolean valid(ServerPlayer player, BlockPos position) {
    return WorldNetworks.get(player.server).membership(at(player, position)).status()
        == NetworkTopology.Status.VALID;
  }

  /** Installs through the actual client interaction path, then checks consumption and synced UI. */
  private void upgrade(BlockPos target, ThroughputTier tier, boolean buffer) {
    String item = tier == ThroughputTier.ADVANCED ? "throughput_upgrade" : "overdrive_upgrade";
    String name = (buffer ? "buffer-" : "machine-") + item.replace("_upgrade", "");
    int maximum = buffer ? tier.bufferRate() : tier.portRate(ResourceKind.ITEM);
    server(
        player -> {
          player.setGameMode(GameType.SURVIVAL);
          player.getInventory().clearContent();
          player.getInventory().selected = 0;
          player.getInventory().setItem(0, stack(item));
        });
    step(client -> client.player.getMainHandItem().is(stack(item).getItem()));
    use(target, Direction.SOUTH, false);
    serverUntil(
        player -> {
          var entity = player.serverLevel().getBlockEntity(target);
          boolean installed =
              buffer
                  ? entity instanceof BufferedInterfaceEntity device && device.tier() == tier
                  : entity instanceof MachineInterfaceEntity machine && machine.tier() == tier;
          return installed && player.getMainHandItem().isEmpty() && valid(player, target);
        });
    step(client -> client.player.getMainHandItem().isEmpty());
    use(target, Direction.SOUTH, false);
    step(
        client -> {
          if (buffer) {
            if (!(client.screen instanceof BufferSettingsScreen screen)
                || screen.getMenu().view().rate() != maximum) return false;
          } else {
            if (!(client.screen instanceof MachineSettingsScreen screen)
                || screen.getMenu().view().maxRate() != maximum
                || screen.getMenu().view().form().rate() != maximum) return false;
            check(
                screen.getMenu().view().tier().equals(tier.name().toLowerCase(Locale.ROOT)),
                "Wrong installed tier in machine payload");
          }
          evidence
              .append(name)
              .append(": real survival use consumed one; synced rate=")
              .append(maximum)
              .append('\n');
          return true;
        });
    scales(name, buffer ? BufferSettingsScreen.class : MachineSettingsScreen.class, false);
  }

  // ------------------------------------------------------------------ tooltips

  /** Real item tooltips (including the ItemTooltipEvent lines) drawn at the four GUI corners. */
  static final class TooltipBoard extends Screen {
    private final List<ItemStack> stacks;
    private final int corner;

    TooltipBoard(List<ItemStack> stacks, int corner) {
      super(Component.literal("Tooltip board"));
      this.stacks = stacks;
      this.corner = corner;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.render(graphics, mouseX, mouseY, partialTick);
      int[][] corners = {{4, 20}, {width - 4, 20}, {4, height - 4}, {width - 4, height - 4}};
      graphics.renderTooltip(font, stacks.get(corner), corners[corner][0], corners[corner][1]);
    }
  }

  private void checkTooltips(Minecraft client) {
    int described = 0;
    for (var id : BuiltInRegistries.ITEM.keySet().stream().sorted().toList()) {
      if (!id.getNamespace().equals(FactoryCoreMod.MOD_ID)) continue;
      var stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
      var lines =
          Screen.getTooltipFromItem(client, stack).stream().map(Component::getString).toList();
      for (var line : lines)
        check(
            !line.contains("tooltip.factorycore")
                && !line.contains("item.factorycore")
                && !line.contains("block.factorycore"),
            "Untranslated tooltip line for " + id + ": " + line);
      String key = ItemDescriptions.descriptionKey(id.getPath());
      if (I18n.exists(key)) {
        check(lines.contains(I18n.get(key)), "Description missing from tooltip of " + id);
        described++;
      }
      String legend = ItemDescriptions.lampLegendKey(BuiltInRegistries.ITEM.get(id));
      if (legend != null)
        check(lines.contains(I18n.get(legend)), "Lamp legend missing from tooltip of " + id);
      evidence.append("tooltip ").append(id.getPath()).append(": ").append(lines).append('\n');
    }
    check(described >= 14, "Too few described items: " + described);
  }

  // ------------------------------------------------------------------ script

  @SuppressWarnings("PMD.NcssCount") // One linear, readable scenario script.
  private void buildSteps() {
    step(
        client -> {
          if (client.screen instanceof AccessibilityOnboardingScreen onboarding)
            onboarding.onClose();
          if (!(client.screen instanceof TitleScreen)) return false;
          var window = client.getWindow();
          double ratio = (double) window.getWidth() / Math.max(1, window.getScreenWidth());
          window.setWindowed(
              (int) Math.round(MIN_WIDTH / ratio), (int) Math.round(MIN_HEIGHT / ratio));
          client.options.guiScale().set(2);
          client.options.pauseOnLostFocus = false;
          client.getTutorial().setStep(TutorialSteps.NONE);
          client
              .createWorldOpenFlows()
              .createFreshLevel(
                  "factorycore-presentation-verification",
                  new LevelSettings(
                      "Presentation verification",
                      GameType.CREATIVE,
                      false,
                      Difficulty.PEACEFUL,
                      true,
                      new GameRules(),
                      WorldDataConfiguration.DEFAULT),
                  new WorldOptions(0x51A7EL, false, false),
                  registries ->
                      registries
                          .registryOrThrow(Registries.WORLD_PRESET)
                          .getHolderOrThrow(WorldPresets.FLAT)
                          .value()
                          .createWorldDimensions(),
                  client.screen);
          return true;
        });
    step(
        client ->
            client.player != null
                && client.screen == null
                && client.getSingleplayerServer() != null);
    step(
        client -> {
          var window = client.getWindow();
          check(
              window.getWidth() == MIN_WIDTH && window.getHeight() == MIN_HEIGHT,
              "Window framebuffer " + window.getWidth() + "x" + window.getHeight());
          return true;
        });
    server(
        player -> {
          var level = player.serverLevel();
          level.setDayTime(6000);
          level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, player.server);
          level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, player.server);
          player.getInventory().clearContent();
          evidence
              .append("orientations: placed ")
              .append(PresentationScenes.orientations(level))
              .append(" blocks\n");
          evidence.append("states: ").append(PresentationScenes.states(player)).append('\n');
          for (int x : new int[] {50, 52})
            fed.add(new BlockPos(x, 65, PresentationScenes.STATE_Z + 3));
          fed.add(new BlockPos(46, 65, PresentationScenes.STATE_Z + 3));
          PresentationScenes.screens(player);
          var drive = (StorageDriveEntity) level.getBlockEntity(PresentationScenes.SCREENS.east());
          drive.installCell(new ItemStack(StorageContent.cell(CellTier.ADVANCED_ITEM)));
        });
    step(
        client -> {
          client.options.hideGui = true;
          return true;
        });

    // Orientation gallery from two opposite corners covers every facing's front.
    view(PresentationScenes.ORIENT_NORTH_EAST_UP, "orientation-north-east-up.png", 80);
    view(PresentationScenes.ORIENT_SOUTH_WEST_DOWN, "orientation-south-west-down.png", 60);

    // Status lamps follow real state within the refresh period.
    serverUntil(
        player -> {
          int z = PresentationScenes.STATE_Z;
          var level = player.serverLevel();
          boolean ready =
              lamp(level, 40, z) == DeviceLamp.ACTIVE
                  && lamp(level, 42, z) == DeviceLamp.FAULT
                  && lamp(level, 45, z) == DeviceLamp.FAULT
                  && lamp(level, 47, z) == DeviceLamp.ACTIVE
                  && lamp(level, 40, z + 3) == DeviceLamp.OFF
                  && lamp(level, 41, z + 3) == DeviceLamp.ACTIVE
                  && lamp(level, 42, z + 3) == DeviceLamp.PAUSED
                  && lamp(level, 43, z + 3) == DeviceLamp.FAULT
                  && lamp(level, 46, z + 3) == DeviceLamp.ACTIVE
                  && lamp(level, 48, z + 3) == DeviceLamp.FAULT
                  && lamp(level, 50, z + 3) == DeviceLamp.ACTIVE
                  && lamp(level, 52, z + 3) == DeviceLamp.ACTIVE
                  && lamp(level, 54, z + 3) == DeviceLamp.FAULT
                  && lamp(level, 58, z + 3) == DeviceLamp.OFF;
          if (!ready && level.getGameTime() % 100 == 0) {
            for (var position :
                List.of(
                    new BlockPos(40, 65, z), new BlockPos(42, 65, z),
                    new BlockPos(45, 65, z), new BlockPos(47, 65, z),
                    new BlockPos(40, 65, z + 3), new BlockPos(41, 65, z + 3),
                    new BlockPos(42, 65, z + 3), new BlockPos(43, 65, z + 3),
                    new BlockPos(46, 65, z + 3), new BlockPos(48, 65, z + 3),
                    new BlockPos(50, 65, z + 3), new BlockPos(52, 65, z + 3),
                    new BlockPos(54, 65, z + 3), new BlockPos(58, 65, z + 3))) {
              System.out.println(
                  "FACTORYCORE_LAMP_WAIT "
                      + position
                      + " lamp="
                      + level.getBlockState(position).getValue(DeviceLamp.PROPERTY)
                      + " membership="
                      + WorldNetworks.get(player.server).membership(at(player, position)).status());
            }
          }
          return ready;
        });
    step(
        client -> {
          evidence.append(
              "lamps: root active, joined roots fault, rootless gateway fault, rooted gateway active;"
                  + " programs off/active/paused/fault; access points active/fault; links"
                  + " active/active/fault/off\n");
          return true;
        });
    view(PresentationScenes.STATES, "states-day.png", 40);
    view(PresentationScenes.STATES_CLOSE, "states-day-close.png", 20);
    server(player -> player.serverLevel().setDayTime(18000));
    view(PresentationScenes.STATES, "states-night.png", 40);
    view(PresentationScenes.STATES_CLOSE, "states-night-close.png", 20);
    server(player -> player.serverLevel().setDayTime(6000));

    // Held items: first person with main/off hand pairs, then third person front.
    String[][] pairs = {
      {"network_controller", "starter_item_cell"},
      {"network_cable", "wireless_terminal"},
      {"factory_guide", "processing_pattern"},
      {"gateway", "infinite_item_cell"}
    };
    step(
        client -> {
          client.options.hideGui = false;
          return true;
        });
    for (int i = 0; i < pairs.length; i++) {
      var pair = pairs[i];
      boolean third = i == pairs.length - 1;
      server(
          player -> {
            player.getInventory().clearContent();
            player.getInventory().selected = 0;
            player.getInventory().setItem(0, stack(pair[0]));
            player.setItemInHand(InteractionHand.OFF_HAND, stack(pair[1]));
            PresentationScenes.teleport(player, PresentationScenes.HELD);
          });
      step(
          client -> {
            client.options.setCameraType(
                third ? CameraType.THIRD_PERSON_FRONT : CameraType.FIRST_PERSON);
            return aim(client, PresentationScenes.HELD)
                && client.player.getMainHandItem().is(stack(pair[0]).getItem())
                && waited() >= 30;
          });
      String name = (third ? "held-third-person-" : "held-first-person-") + pair[0] + ".png";
      step(
          client -> {
            captures.grab(client, name);
            return true;
          });
    }
    step(
        client -> {
          client.options.setCameraType(CameraType.FIRST_PERSON);
          return true;
        });
    server(player -> player.getInventory().clearContent());

    // Tooltips: every item's real lines are translated; corners exercise edge positioning.
    for (int targetScale : new int[] {4, 2}) {
      for (int corner = 0; corner < 4; corner++) {
        int targetCorner = corner;
        step(
            client -> {
              checkTooltips(client);
              client.setScreen(
                  new TooltipBoard(
                      List.of(
                          stack("network_controller"), stack("program_controller"),
                          stack("starter_energy_bank"), stack("wireless_link_endpoint")),
                      targetCorner));
              setScale(client, targetScale);
              return true;
            });
        step(
            client -> {
              if (!(client.screen instanceof TooltipBoard) || waited() < 6) return false;
              String edge = targetCorner == 0 ? "edges" : "corner-" + targetCorner;
              captures.grab(client, "tooltips-" + edge + "-scale-" + targetScale + ".png");
              return true;
            });
      }
    }
    step(
        client -> {
          client.setScreen(null);
          return true;
        });

    // Screens at scales 1–4 in the smallest window.
    var base = PresentationScenes.SCREENS;
    server(
        player -> {
          player.getAbilities().flying = false;
          player.onUpdateAbilities();
          player.teleportTo(
              player.serverLevel(), base.getX() + 4.5, 65, base.getZ() + 2.5, 180, 15);
        });
    serverUntil(player -> valid(player, base.east(2)) && valid(player, base.east(8)));
    server(
        player -> {
          var drive = (StorageDriveEntity) player.serverLevel().getBlockEntity(base.east());
          var handle = CellHandleData.read(drive.cell(1));
          var registry = DeviceSavedData.get(player.server).registry();
          var codec = new PlatformResourceCodec(player.registryAccess());
          ItemStack[] examples = {
            new ItemStack(Items.STONE),
            new ItemStack(Items.IRON_INGOT),
            new ItemStack(Items.GOLD_INGOT),
            new ItemStack(Items.DIAMOND),
            new ItemStack(Items.EMERALD)
          };
          long[] counts = {1, 23, 64, 1234, 999000};
          for (int i = 0; i < examples.length; i++)
            check(
                registry.insert(handle, codec.itemKey(examples[i]), counts[i]).amount()
                    == counts[i],
                "Count rendering stock admitted");
          evidence.append(
              "terminal count rendering: Stone=1, Iron=23, Gold=64, Diamond=1k (1234), Emerald=999k (999000); vanilla overlay layer; inspect full glyphs at scales 1–4.\n");
        });
    use(base.east(2), Direction.SOUTH, false);
    step(
        client -> {
          if (!(client.screen instanceof TerminalScreen)) return false;
          setScale(client, 4);
          return true;
        });
    step(
        client -> {
          if (!(client.screen instanceof TerminalScreen screen)
              || waited() < 6
              || screen.getMenu().view().size() != screen.columns() * screen.rows()) return false;
          var cell =
              screen.children().stream()
                  .filter(
                      child ->
                          child instanceof AbstractButton button
                              && !(child instanceof Button)
                              && button.visible
                              && button.active)
                  .findFirst()
                  .orElse(null);
          if (cell == null) return false;
          screen.setFocused(cell);
          return true;
        });
    waitTicks(6);
    step(
        client -> {
          captures.grab(client, "terminal-keyboard-scale-4.png");
          return client.screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        });
    step(
        client ->
            !client.player.containerMenu.getCarried().isEmpty()
                && waited() > 4
                && client.screen instanceof TerminalScreen screen
                && !screen.getMenu().view().busy()
                && !screen.getMenu().view().rows().isEmpty());
    step(
        client -> {
          var screen = (TerminalScreen) client.screen;
          return screen.mouseClicked(screen.itemX(0) + 9, screen.itemY(0) + 9, 0);
        });
    step(client -> client.player.containerMenu.getCarried().isEmpty() && waited() > 4);
    scales("terminal", TerminalScreen.class, true);
    craftingTour(base);
    jobsTour(base);
    use(base.east(), Direction.SOUTH, false);
    step(client -> client.screen instanceof DriveScreen && waited() > 4);
    step(
        client -> {
          var screen = (DriveScreen) client.screen;
          return screen.mouseClicked(screen.getGuiLeft() + 16, screen.getGuiTop() + 26, 0);
        });
    step(client -> !client.player.containerMenu.getCarried().isEmpty());
    step(
        client -> {
          var screen = (DriveScreen) client.screen;
          return screen.mouseClicked(screen.getGuiLeft() + 16, screen.getGuiTop() + 26, 0);
        });
    step(client -> client.player.containerMenu.getCarried().isEmpty() && waited() > 4);
    scales("drive", DriveScreen.class, false);
    use(base.east(3), Direction.SOUTH, false);
    scales("buffer", BufferSettingsScreen.class, false);
    use(base.east(4), Direction.SOUTH, false);
    scales("machine", MachineSettingsScreen.class, false);
    use(base.east(6).north(), Direction.SOUTH, false);
    step(
        client ->
            client.screen instanceof ConnectorSettingsScreen screen
                && screen.getMenu().view().generation() > 0
                && screen.getMenu().view().binding().equals("ok"));
    scales("connector", ConnectorSettingsScreen.class, false);
    upgrade(base.east(4), ThroughputTier.ADVANCED, false);
    upgrade(base.east(4), ThroughputTier.ENDGAME, false);
    upgrade(base.east(3), ThroughputTier.ADVANCED, true);
    upgrade(base.east(3), ThroughputTier.ENDGAME, true);
    use(base.east(5), Direction.SOUTH, false);
    scales("program", ProgramScreen.class, false);
    use(base.east(7), Direction.SOUTH, true);
    step(
        client -> {
          if (!(client.screen instanceof GatewayPolicyScreen)) return false;
          shift(client, false);
          return true;
        });
    scales("gateway-policy", GatewayPolicyScreen.class, false);
    // U8: the Custom choice reveals the three columns of toggles.
    use(base.east(7), Direction.SOUTH, false);
    step(
        client -> {
          if (!(client.screen instanceof GatewayPolicyScreen screen)) return false;
          for (var child : screen.children())
            if (child instanceof net.minecraft.client.gui.components.Button button
                && button.visible
                && button.active
                && button.getMessage().getString().equals("Custom")) {
              button.onPress();
              return true;
            }
          throw new IllegalStateException("Gateway Custom choice missing");
        });
    scales("gateway-custom", GatewayPolicyScreen.class, false);
    // Network Access (ADR 0055): a second principal row so grant toggles are on screen.
    server(
        player ->
            check(
                WorldNetworks.get(player.getServer())
                        .setGrants(
                            new NetworkPermissions.Actor(player.getUUID(), false),
                            at(player, base),
                            UUID.nameUUIDFromBytes(
                                "presentation-guest".getBytes(StandardCharsets.UTF_8)),
                            // U8: a Member role, so the row shows a named role.
                            NetworkPermissions.AccessRole.MEMBER.grants())
                    == NetworkPermissions.Change.OK,
                "Owner grants a second principal before opening Network Access"));
    use(base, Direction.SOUTH, true);
    step(
        client -> {
          if (!(client.screen instanceof NetworkAccessScreen)) return false;
          shift(client, false);
          return true;
        });
    scales("access", NetworkAccessScreen.class, false);
    // U8: Advanced swaps the role buttons for the seven permission toggles.
    use(base, Direction.SOUTH, false);
    step(
        client -> {
          if (!(client.screen instanceof NetworkAccessScreen screen)) return false;
          for (var child : screen.children())
            if (child instanceof net.minecraft.client.gui.components.Button button
                && button.visible
                && button.getMessage().getString().equals("Advanced")) {
              button.onPress();
              return true;
            }
          return false;
        });
    scales("access-advanced", NetworkAccessScreen.class, false);
    // Transfer Recovery opens through its real player command.
    server(
        player ->
            player
                .getServer()
                .getCommands()
                .performPrefixedCommand(
                    player.createCommandSourceStack(), "factorycore recovery screen"));
    scales("recovery", RecoveryScreen.class, false);
    server(player -> player.getInventory().setItem(0, new ItemStack(GuideContent.GUIDE.get())));
    step(
        client -> {
          if (!client.player.getInventory().getItem(0).is(GuideContent.GUIDE.get())) return false;
          client.player.getInventory().selected = 0;
          client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
          return true;
        });
    step(client -> client.screen instanceof BookViewScreen);
    for (int page = 1; page < GuideContent.PAGES; page++) {
      step(
          client -> {
            check(client.screen instanceof BookViewScreen, "Guide closed while paging");
            client.screen.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0);
            return true;
          });
    }
    scales("guide-throughput", BookViewScreen.class, false);
    // Reopen at its first page to retain the original guide captures.
    step(
        client -> {
          client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
          return true;
        });
    scales("guide", BookViewScreen.class, false);
    for (int corner = 0; corner < 2; corner++) {
      int targetCorner = corner;
      step(
          client -> {
            client.setScreen(
                new TooltipBoard(
                    List.of(stack("throughput_upgrade"), stack("overdrive_upgrade")),
                    targetCorner));
            setScale(client, 4);
            return true;
          });
      step(
          client -> {
            if (waited() < 6) return false;
            captures.grab(client, "upgrade-tooltip-" + targetCorner + "-scale-4.png");
            return true;
          });
    }
    step(
        client -> {
          writeReport(client);
          return true;
        });
  }

  private static DeviceLamp lamp(ServerLevel level, int x, int z) {
    return level.getBlockState(new BlockPos(x, 65, z)).getValue(DeviceLamp.PROPERTY);
  }

  private static ItemStack stack(String path) {
    return new ItemStack(
        BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, path)));
  }

  private void writeReport(Minecraft client) {
    try {
      Files.writeString(
          report,
          "PASS\n"
              + evidence
              + "Orientation: every FACING device in all six facings, horizontal devices in four;"
              + " two opposite corner views show every front.\n"
              + "Screens: terminal, drive, buffer settings, machine settings, connector settings, program editor, gateway"
              + " policy, network access, transfer recovery and guide at GUI scales 1-4 in a "
              + MIN_WIDTH
              + "x"
              + MIN_HEIGHT
              + " framebuffer (427x240 at scale 4): panels and controls on screen, no overlap,"
              + " labels fit.\n");
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
    client.level.disconnect();
    client.disconnect();
    client.stop();
  }
}
