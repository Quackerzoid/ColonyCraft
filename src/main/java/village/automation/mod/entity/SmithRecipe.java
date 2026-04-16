package village.automation.mod.entity;

import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Defines what raw materials the smith needs to produce one output item.
 *
 * <p>Materials can be matched either by exact item or by item tag.
 * The courier fetches these items from chests and deposits them into
 * the smith's input container.
 */
public class SmithRecipe {

    // ── Recipe registry ───────────────────────────────────────────────────────

    public static final List<SmithRecipe> RECIPES = new ArrayList<>();

    static {
        // ── Iron tools ────────────────────────────────────────────────────────
        register(Items.IRON_HOE,        1, exact(Items.IRON_INGOT, 2), exact(Items.STICK, 2));
        register(Items.IRON_PICKAXE,    1, exact(Items.IRON_INGOT, 3), exact(Items.STICK, 2));
        register(Items.IRON_AXE,        1, exact(Items.IRON_INGOT, 3), exact(Items.STICK, 2));
        register(Items.IRON_SWORD,      1, exact(Items.IRON_INGOT, 2), exact(Items.STICK, 1));
        register(Items.IRON_SHOVEL,     1, exact(Items.IRON_INGOT, 1), exact(Items.STICK, 2));
        register(Items.SHEARS,          1, exact(Items.IRON_INGOT, 2));
        register(Items.FLINT_AND_STEEL, 1, exact(Items.IRON_INGOT, 1), exact(Items.FLINT, 1));

        // ── Wooden / string tools ─────────────────────────────────────────────
        register(Items.FISHING_ROD, 1, exact(Items.STICK, 3), exact(Items.STRING, 2));
        register(Items.BOW,         1, exact(Items.STICK, 3), exact(Items.STRING, 3));
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    public final Item              result;
    public final int               resultCount;
    public final List<Ingredient>  ingredients;

    private SmithRecipe(Item result, int resultCount, List<Ingredient> ingredients) {
        this.result      = result;
        this.resultCount = resultCount;
        this.ingredients = ingredients;
    }

    // ── Static factory helpers ────────────────────────────────────────────────

    private static SmithRecipe register(Item result, int count, Ingredient... ingredients) {
        SmithRecipe r = new SmithRecipe(result, count, List.of(ingredients));
        RECIPES.add(r);
        return r;
    }

    public static Ingredient exact(Item item, int count) {
        return new Ingredient(item, null, item, count);
    }

    public static Ingredient tagged(TagKey<Item> tag, Item displayItem, int count) {
        return new Ingredient(null, tag, displayItem, count);
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    /** Returns the first recipe that produces {@code target}, or empty. */
    public static Optional<SmithRecipe> findFor(Item target) {
        return RECIPES.stream().filter(r -> r.result == target).findFirst();
    }

    // ── Container helpers ─────────────────────────────────────────────────────

    /**
     * Returns how many units of {@code ingredient} are present in
     * {@code container} (counting all matching stacks).
     */
    public static int countInContainer(SimpleContainer container, Ingredient ingredient) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty() && ingredient.matches(s)) total += s.getCount();
        }
        return total;
    }

    /**
     * Returns {@code true} when {@code container} holds all ingredients in
     * sufficient quantities.
     */
    public boolean satisfied(SimpleContainer container) {
        for (Ingredient ing : ingredients) {
            if (countInContainer(container, ing) < ing.count) return false;
        }
        return true;
    }

    /**
     * Returns a list of ingredients that are still missing from
     * {@code container}, with counts adjusted to the shortfall.
     */
    public List<Ingredient> missing(SimpleContainer container) {
        List<Ingredient> result = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            int have = countInContainer(container, ing);
            int need = ing.count - have;
            if (need > 0) result.add(new Ingredient(ing.item, ing.tag, ing.displayItem, need));
        }
        return result;
    }

    // ── Ingredient ────────────────────────────────────────────────────────────

    public static final class Ingredient {
        /** Exact item to match, or {@code null} when tag-based. */
        @Nullable public final Item         item;
        /** Tag to match, or {@code null} when item-based. */
        @Nullable public final TagKey<Item> tag;
        /** Concrete example item used for display and courier requests. */
        public final Item                   displayItem;
        public final int                    count;

        private Ingredient(@Nullable Item item, @Nullable TagKey<Item> tag,
                           Item displayItem, int count) {
            this.item        = item;
            this.tag         = tag;
            this.displayItem = displayItem;
            this.count       = count;
        }

        public boolean matches(ItemStack stack) {
            if (stack.isEmpty()) return false;
            if (item != null) return stack.is(item);
            return tag != null && stack.is(tag);
        }

        /** New ingredient with the same matcher but a reduced count. */
        public Ingredient withCount(int newCount) {
            return new Ingredient(item, tag, displayItem, newCount);
        }
    }
}
