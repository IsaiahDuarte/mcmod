package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.crafting.GridRequirement;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Server-owned manual crafting grid for one terminal session. Cells are exact ghost keys; no items
 * are held. Crafting consumes only the player's main inventory and commits each bounded batch
 * atomically on the server thread after outputs and remainders are proven to fit.
 */
final class TerminalCrafting {
  static final int SLOTS = 36;

  /** Result of one bounded request. A deficit means more ingredients must be gathered first. */
  record Outcome(int crafted, String status, GridRequirement.Deficit deficit) {}

  private final ResourceKey[] cells = new ResourceKey[GridRequirement.CELLS];
  private long revision = 1;
  private long cachedRevision;
  private Optional<RecipeHolder<CraftingRecipe>> cached = Optional.empty();

  long revision() {
    return revision;
  }

  ResourceKey cell(int index) {
    return cells[index];
  }

  void set(int index, ResourceKey key) {
    if (index < 0 || index >= cells.length) throw new IllegalArgumentException("Grid cell");
    cells[index] = key;
    revision++;
  }

  void clear() {
    Arrays.fill(cells, null);
    revision++;
  }

  GridRequirement requirement() {
    return new GridRequirement(Arrays.asList(cells.clone()));
  }

  private static List<ItemStack> ghosts(ResourceKey[] keys, PlatformResourceCodec codec) {
    var stacks = new ArrayList<ItemStack>(keys.length);
    for (var key : keys) stacks.add(key == null ? ItemStack.EMPTY : codec.itemStack(key, 1));
    return stacks;
  }

  /** Current recipe for the ghost selections, if any; advisory until a craft revalidates it. */
  Optional<RecipeHolder<CraftingRecipe>> recipe(ServerPlayer player) {
    if (cachedRevision == revision) return cached;
    cachedRevision = revision;
    cached = lookup(player);
    return cached;
  }

  private Optional<RecipeHolder<CraftingRecipe>> lookup(ServerPlayer player) {
    if (requirement().empty()) return Optional.empty();
    try {
      var codec = new PlatformResourceCodec(player.registryAccess());
      var input = CraftingInput.of(3, 3, ghosts(cells, codec));
      return player
          .serverLevel()
          .getRecipeManager()
          .getRecipeFor(RecipeType.CRAFTING, input, player.serverLevel());
    } catch (IllegalArgumentException unsupported) {
      return Optional.empty();
    }
  }

  ItemStack preview(ServerPlayer player) {
    var codec = new PlatformResourceCodec(player.registryAccess());
    return recipe(player)
        .map(
            holder ->
                holder
                    .value()
                    .assemble(
                        CraftingInput.of(3, 3, ghosts(cells, codec)), player.registryAccess()))
        .orElse(ItemStack.EMPTY);
  }

  /** Exact main-inventory units for each distinct grid key. */
  Map<ResourceKey, Long> owned(ServerPlayer player) {
    var codec = new PlatformResourceCodec(player.registryAccess());
    var counts = new HashMap<ResourceKey, Long>();
    var wanted = requirement().perCraft().keySet();
    for (int slot = 0; slot < SLOTS; slot++) {
      var stack = player.getInventory().getItem(slot);
      if (stack.isEmpty()) continue;
      var key = keyOf(codec, stack);
      if (key != null && wanted.contains(key))
        counts.merge(key, (long) stack.getCount(), Long::sum);
    }
    return counts;
  }

  /**
   * Crafts up to {@code crafts} times. Returns a deficit (with nothing consumed) when the inventory
   * cannot cover every requested craft, so the caller may gather and retry.
   */
  Outcome craft(ServerPlayer player, int crafts) {
    var requirement = requirement();
    if (requirement.empty()) return new Outcome(0, "empty", null);
    var holder = recipe(player);
    if (holder.isEmpty()) return new Outcome(0, "no_recipe", null);
    var deficit = requirement.deficit(crafts, owned(player));
    if (deficit != null) return new Outcome(0, "shortage", deficit);
    var codec = new PlatformResourceCodec(player.registryAccess());
    var inventory = player.getInventory();
    var committed = new ArrayList<ItemStack>(SLOTS);
    for (int slot = 0; slot < SLOTS; slot++) committed.add(inventory.getItem(slot).copy());
    var recipe = holder.get().value();
    int done = 0;
    ItemStack first = ItemStack.EMPTY;
    String status = "crafted";
    for (; done < crafts; done++) {
      var trial = new ArrayList<ItemStack>(SLOTS);
      for (var stack : committed) trial.add(stack.copy());
      var used = new ArrayList<ItemStack>(cells.length);
      boolean missing = false;
      for (var key : cells) {
        if (key == null) {
          used.add(ItemStack.EMPTY);
          continue;
        }
        var taken = take(trial, key, codec);
        if (taken.isEmpty()) {
          missing = true;
          break;
        }
        used.add(taken);
      }
      var input = missing ? null : CraftingInput.of(3, 3, used);
      if (missing || !recipe.matches(input, player.serverLevel())) {
        status = "shortage";
        break;
      }
      var output = recipe.assemble(input, player.registryAccess());
      var remainders = recipe.getRemainingItems(input);
      boolean fits = output.isEmpty() || add(trial, output.copy());
      for (var remainder : remainders)
        if (fits && !remainder.isEmpty()) fits = add(trial, remainder.copy());
      if (!fits) {
        status = "full";
        break;
      }
      if (first.isEmpty()) first = output.copy();
      committed = trial;
    }
    if (done == 0) return new Outcome(0, status, null);
    for (int slot = 0; slot < SLOTS; slot++)
      if (!ItemStack.matches(inventory.getItem(slot), committed.get(slot)))
        inventory.setItem(
            slot, committed.get(slot).isEmpty() ? ItemStack.EMPTY : committed.get(slot));
    inventory.setChanged();
    first.onCraftedBy(player.serverLevel(), player, first.getCount() * done);
    player.awardRecipes(List.of(holder.get()));
    return new Outcome(done, status, null);
  }

  /** Exact key, or null for stacks the resource codec cannot represent; those are never used. */
  /**
   * Writes the current recipe onto one blank pattern from the main inventory. The encoded output
   * and remainders come from the server recipe; nothing else is consumed.
   */
  Outcome encode(ServerPlayer player) {
    var holder = recipe(player);
    if (holder.isEmpty()) return new Outcome(0, "no_recipe", null);
    var codec = new PlatformResourceCodec(player.registryAccess());
    var ghosts = ghosts(cells, codec);
    var input = CraftingInput.of(3, 3, ghosts);
    var recipe = holder.get().value();
    var output = recipe.assemble(input, player.registryAccess());
    var remainders = new ArrayList<ItemStack>();
    for (var remainder : recipe.getRemainingItems(input))
      if (!remainder.isEmpty()) remainders.add(remainder);
    ItemStack encoded;
    try {
      var contents =
          new dev.izzy.factorycore.platform.crafting.PatternContents(
              java.util.UUID.randomUUID(), 1, 0, ghosts, output, remainders);
      contents.pattern(codec);
      encoded =
          new ItemStack(
              dev.izzy.factorycore.platform.crafting.CraftingContent.CRAFTING_PATTERN.get());
      encoded.set(dev.izzy.factorycore.platform.crafting.CraftingContent.PATTERN.get(), contents);
    } catch (IllegalArgumentException unsupported) {
      return new Outcome(0, "unsupported", null);
    }
    var inventory = player.getInventory();
    var trial = new ArrayList<ItemStack>(SLOTS);
    for (int slot = 0; slot < SLOTS; slot++) trial.add(inventory.getItem(slot).copy());
    boolean blank = false;
    for (var stack : trial)
      if (stack.is(dev.izzy.factorycore.platform.crafting.CraftingContent.BLANK_PATTERN.get())) {
        stack.shrink(1);
        blank = true;
        break;
      }
    if (!blank) return new Outcome(0, "no_blank", null);
    if (!add(trial, encoded)) return new Outcome(0, "full", null);
    for (int slot = 0; slot < SLOTS; slot++)
      if (!ItemStack.matches(inventory.getItem(slot), trial.get(slot)))
        inventory.setItem(slot, trial.get(slot).isEmpty() ? ItemStack.EMPTY : trial.get(slot));
    inventory.setChanged();
    return new Outcome(0, "encoded", null);
  }

  private static ResourceKey keyOf(PlatformResourceCodec codec, ItemStack stack) {
    try {
      return codec.itemKey(stack);
    } catch (IllegalArgumentException unsupported) {
      return null;
    }
  }

  private static ItemStack take(
      List<ItemStack> slots, ResourceKey key, PlatformResourceCodec codec) {
    for (var stack : slots) {
      if (!stack.isEmpty() && key.equals(keyOf(codec, stack))) return stack.split(1);
    }
    return ItemStack.EMPTY;
  }

  /** Merges into matching stacks, then empty slots; false when any unit does not fit. */
  private static boolean add(List<ItemStack> slots, ItemStack stack) {
    int limit = Math.min(stack.getMaxStackSize(), 64);
    for (var held : slots) {
      if (stack.isEmpty()) return true;
      if (!held.isEmpty() && ItemStack.isSameItemSameComponents(held, stack)) {
        int moved = Math.min(stack.getCount(), limit - held.getCount());
        if (moved > 0) {
          held.grow(moved);
          stack.shrink(moved);
        }
      }
    }
    for (int slot = 0; slot < slots.size() && !stack.isEmpty(); slot++) {
      if (!slots.get(slot).isEmpty()) continue;
      slots.set(slot, stack.split(Math.min(stack.getCount(), limit)));
    }
    return stack.isEmpty();
  }
}
