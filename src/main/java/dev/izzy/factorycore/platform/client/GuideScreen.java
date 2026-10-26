package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.guide.GuideContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * The Factory Guide in the vanilla book screen, with the page's blocks pictured to the left of the
 * book and the first one's real crafting recipe to the right (U9). Side panels are skipped when the
 * window leaves too little room beside the book. The side panels use the panel kit (playtest H3c).
 */
public final class GuideScreen extends BookViewScreen {
  private static final int BOOK = 192, SLOT = 18, CYCLE_TICKS = 20;
  private int page;
  private int shownPage = -1;
  private List<ItemStack> pictures = List.of();

  /** Recipe grid (row-major, 3×3; empty ingredients are null) and result, or null. */
  private List<Ingredient> grid;

  private ItemStack result = ItemStack.EMPTY;

  private GuideScreen() {
    super(new BookAccess(GuideContent.pages()));
  }

  public static void open() {
    Minecraft.getInstance().setScreen(new GuideScreen());
  }

  @Override
  public boolean setPage(int target) {
    page = Math.max(0, Math.min(GuideContent.PAGES - 1, target));
    return super.setPage(target);
  }

  @Override
  protected boolean forcePage(int target) {
    page = Math.max(0, Math.min(GuideContent.PAGES - 1, target));
    return super.forcePage(target);
  }

  @Override
  protected void pageBack() {
    if (page > 0) page--;
    super.pageBack();
  }

  @Override
  protected void pageForward() {
    if (page < GuideContent.PAGES - 1) page++;
    super.pageForward();
  }

  private static Item item(String id) {
    var location = ResourceLocation.tryParse(id);
    return location == null ? Items.AIR : BuiltInRegistries.ITEM.get(location);
  }

  /** Loads the pictures and recipe for the current page once per page change. */
  private void load() {
    if (shownPage == page) return;
    shownPage = page;
    var stacks = new ArrayList<ItemStack>();
    for (var id : GuideContent.PICTURES.get(page)) {
      var item = item(id);
      if (item != Items.AIR) stacks.add(new ItemStack(item));
    }
    pictures = stacks;
    grid = null;
    result = ItemStack.EMPTY;
    if (stacks.isEmpty() || minecraft == null || minecraft.level == null) return;
    var target = stacks.getFirst().getItem();
    var access = minecraft.level.registryAccess();
    for (var holder : minecraft.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
      var recipe = holder.value();
      var output = recipe.getResultItem(access);
      if (output.getItem() != target) continue;
      var cells = new ArrayList<Ingredient>(9);
      for (int i = 0; i < 9; i++) cells.add(null);
      var ingredients = recipe.getIngredients();
      int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
      for (int i = 0; i < ingredients.size() && i < 9; i++) {
        var ingredient = ingredients.get(i);
        if (!ingredient.isEmpty()) cells.set(i / width * 3 + i % width, ingredient);
      }
      grid = cells;
      result = output.copy();
      return;
    }
  }

  private ItemStack shown(Ingredient ingredient) {
    var options = ingredient.getItems();
    if (options.length == 0) return ItemStack.EMPTY;
    long tick = minecraft == null || minecraft.level == null ? 0 : minecraft.level.getGameTime();
    return options[(int) (tick / CYCLE_TICKS % options.length)];
  }

  private void slot(GuiGraphics graphics, int x, int y, ItemStack stack) {
    Panels.slot(graphics, x, y);
    if (!stack.isEmpty()) {
      graphics.renderItem(stack, x + 1, y + 1);
      graphics.renderItemDecorations(font, stack, x + 1, y + 1);
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    load();
    int book = (width - BOOK) / 2, margin = book - 8;
    ItemStack hovered = ItemStack.EMPTY;
    // Left: the page's blocks, one per row.
    if (margin >= SLOT + 4 && !pictures.isEmpty()) {
      int x = book - SLOT - 6;
      Panels.panel(graphics, new Rect(x - 5, 25, SLOT + 10, pictures.size() * (SLOT + 2) + 8));
      for (int i = 0; i < pictures.size(); i++) {
        int y = 30 + i * (SLOT + 2);
        slot(graphics, x, y, pictures.get(i));
        if (over(mouseX, mouseY, x, y)) hovered = pictures.get(i);
      }
    }
    // Right: the first block's crafting recipe as a 3×3 grid, an arrow and the result.
    int needed = 3 * SLOT + 12 + SLOT;
    if (grid != null && margin >= needed + 4) {
      int x = book + BOOK + 6, y = 30;
      Panels.panel(graphics, new Rect(x - 5, y - 16, needed + 10, 3 * SLOT + 21));
      graphics.drawString(
          font,
          Component.translatable("gui.factorycore.guide.recipe"),
          x,
          y - 11,
          Panels.TEXT,
          false);
      for (int cell = 0; cell < 9; cell++) {
        int cx = x + cell % 3 * SLOT, cy = y + cell / 3 * SLOT;
        var stack = grid.get(cell) == null ? ItemStack.EMPTY : shown(grid.get(cell));
        slot(graphics, cx, cy, stack);
        if (!stack.isEmpty() && over(mouseX, mouseY, cx, cy)) hovered = stack;
      }
      int rx = x + 3 * SLOT + 12, ry = y + SLOT;
      graphics.drawString(font, "→", x + 3 * SLOT + 2, ry + 5, Panels.TEXT, false);
      slot(graphics, rx, ry, result);
      if (over(mouseX, mouseY, rx, ry)) hovered = result;
    }
    if (!hovered.isEmpty()) graphics.renderTooltip(font, hovered, mouseX, mouseY);
  }

  private static boolean over(int mouseX, int mouseY, int x, int y) {
    return mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
  }
}
