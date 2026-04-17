package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import village.automation.mod.blockentity.CookingBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * AI goal for Chef workers.
 *
 * <p>The chef walks to their cooking block and waits for ingredients.  When the
 * courier delivers an ingredient the chef cooks it over {@value #COOK_TICKS}
 * ticks and deposits the result into the output container for the courier to
 * collect.
 *
 * <p>Supported recipes:
 * <ul>
 *   <li>{@link Items#WHEAT}         → {@link Items#BREAD}
 *   <li>{@link Items#COD}           → {@link Items#COOKED_COD}
 *   <li>{@link Items#SALMON}        → {@link Items#COOKED_SALMON}
 *   <li>{@link Items#BEEF}          → {@link Items#COOKED_BEEF}
 *   <li>{@link Items#PORKCHOP}      → {@link Items#COOKED_PORKCHOP}
 *   <li>{@link Items#CHICKEN}       → {@link Items#COOKED_CHICKEN}
 *   <li>{@link Items#MUTTON}        → {@link Items#COOKED_MUTTON}
 *   <li>{@link Items#RABBIT}        → {@link Items#COOKED_RABBIT}
 * </ul>
 *
 * <p>When the input container is empty, {@link CookingBlockEntity#setNeedsIngredients}
 * is set so the courier knows to fetch more.
 */
public class ChefWorkGoal extends Goal {

    private static final int    COOK_TICKS        = 200;   // 10 s per item
    private static final int    PARTICLE_INTERVAL  = 20;   // burst every second
    private static final double WALK_SPEED         = 0.6;
    private static final double WORK_REACH_SQ      = 6.25; // 2.5 blocks

    private final VillagerWorkerEntity chef;
    private int cookTimer        = 0;
    private int particleCooldown = 0;

    /** The ingredient currently being cooked, or null when idle. */
    @Nullable private Item currentIngredient = null;

    public ChefWorkGoal(VillagerWorkerEntity chef) {
        this.chef = chef;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return chef.getJob() == JobType.CHEF
                && chef.getWorkplacePos() != null
                && !chef.isTooHungryToWork()
                && chef.level() instanceof ServerLevel;
    }

    @Override
    public boolean canContinueToUse() { return canUse(); }

    @Override
    public void stop() {
        cookTimer        = 0;
        particleCooldown = 0;
        currentIngredient = null;
    }

    @Override
    public void tick() {
        if (!(chef.level() instanceof ServerLevel level)) return;
        BlockPos workPos = chef.getWorkplacePos();
        if (workPos == null) return;

        BlockEntity be = level.getBlockEntity(workPos);
        if (!(be instanceof CookingBlockEntity cooking)) return;

        // Walk to cooking block
        double distSq = chef.distanceToSqr(
                workPos.getX() + 0.5, workPos.getY() + 0.5, workPos.getZ() + 0.5);
        if (distSq > WORK_REACH_SQ) {
            if (chef.getNavigation().isDone()) {
                chef.getNavigation().moveTo(
                        workPos.getX() + 0.5, workPos.getY(), workPos.getZ() + 0.5, WALK_SPEED);
            }
            cookTimer = 0;
            currentIngredient = null;
            cooking.setCookProgress(0);
            return;
        }

        chef.getNavigation().stop();
        chef.getLookControl().setLookAt(
                workPos.getX() + 0.5, workPos.getY() + 1.0, workPos.getZ() + 0.5, 30f, 30f);

        if (cookTimer <= 0) {
            // Find the first cookable ingredient in the input container
            currentIngredient = findCookable(cooking.getInputContainer());

            if (currentIngredient == null || !hasOutputSpace(currentIngredient, cooking.getOutputContainer())) {
                // Signal the golem to bring more ingredients
                if (!cooking.isNeedsIngredients()) cooking.setNeedsIngredients(true);
                currentIngredient = null;
                return;
            }

            // Ingredient available — start cooking
            cooking.setNeedsIngredients(false);
            cookTimer        = COOK_TICKS;
            particleCooldown = PARTICLE_INTERVAL;

        } else {
            if (--particleCooldown <= 0) {
                particleCooldown = PARTICLE_INTERVAL;
                spawnParticles(workPos, level);
                level.playSound(null,
                        workPos.getX() + 0.5, workPos.getY() + 1.0, workPos.getZ() + 0.5,
                        SoundEvents.FURNACE_FIRE_CRACKLE,
                        SoundSource.BLOCKS,
                        0.4f,
                        0.8f + level.getRandom().nextFloat() * 0.4f);
            }

            if (--cookTimer <= 0) {
                // Re-confirm the ingredient is still there (courier might have taken it)
                if (currentIngredient == null) {
                    currentIngredient = findCookable(cooking.getInputContainer());
                }
                if (currentIngredient != null && consumeIngredient(currentIngredient, cooking.getInputContainer())) {
                    produceOutput(currentIngredient, cooking.getOutputContainer());
                }
                currentIngredient = null; // reset so next tick re-evaluates
            }
        }

        // Mirror current timer into the block entity for GUI sync
        cooking.setCookProgress(cookTimer);
    }

    // ── Ingredient helpers ────────────────────────────────────────────────────

    /**
     * Returns the first cookable ingredient found in {@code input}, or
     * {@code null} if the container holds nothing cookable.
     */
    @Nullable
    private static Item findCookable(SimpleContainer input) {
        for (int i = 0; i < input.getContainerSize(); i++) {
            ItemStack slot = input.getItem(i);
            if (slot.isEmpty()) continue;
            if (isCookable(slot.getItem())) return slot.getItem();
        }
        return null;
    }

    private static boolean isCookable(Item item) {
        return item == Items.WHEAT
            || item == Items.COD
            || item == Items.SALMON
            || item == Items.BEEF
            || item == Items.PORKCHOP
            || item == Items.CHICKEN
            || item == Items.MUTTON
            || item == Items.RABBIT;
    }

    /** Returns the output item that results from cooking {@code ingredient}. */
    private static Item outputFor(Item ingredient) {
        if (ingredient == Items.COD)      return Items.COOKED_COD;
        if (ingredient == Items.SALMON)   return Items.COOKED_SALMON;
        if (ingredient == Items.BEEF)     return Items.COOKED_BEEF;
        if (ingredient == Items.PORKCHOP) return Items.COOKED_PORKCHOP;
        if (ingredient == Items.CHICKEN)  return Items.COOKED_CHICKEN;
        if (ingredient == Items.MUTTON)   return Items.COOKED_MUTTON;
        if (ingredient == Items.RABBIT)   return Items.COOKED_RABBIT;
        return Items.BREAD; // WHEAT → BREAD
    }

    private static boolean hasOutputSpace(Item ingredient, SimpleContainer output) {
        Item out = outputFor(ingredient);
        for (int i = 0; i < output.getContainerSize(); i++) {
            ItemStack slot = output.getItem(i);
            if (slot.isEmpty()) return true;
            if (slot.is(out) && slot.getCount() < slot.getMaxStackSize()) return true;
        }
        return false;
    }

    /**
     * Removes one of {@code ingredient} from the input container.
     * Returns {@code true} if an item was consumed.
     */
    private static boolean consumeIngredient(Item ingredient, SimpleContainer input) {
        for (int i = 0; i < input.getContainerSize(); i++) {
            ItemStack slot = input.getItem(i);
            if (slot.isEmpty() || slot.getItem() != ingredient) continue;
            slot.shrink(1);
            input.setItem(i, slot.isEmpty() ? ItemStack.EMPTY : slot);
            return true;
        }
        return false;
    }

    /**
     * Adds one cooked output item to the output container.
     */
    private static void produceOutput(Item ingredient, SimpleContainer output) {
        Item out = outputFor(ingredient);
        // Merge with existing stack first
        for (int i = 0; i < output.getContainerSize(); i++) {
            ItemStack slot = output.getItem(i);
            if (slot.is(out) && slot.getCount() < slot.getMaxStackSize()) {
                slot.grow(1);
                output.setItem(i, slot);
                return;
            }
        }
        // Fill an empty slot
        for (int i = 0; i < output.getContainerSize(); i++) {
            if (output.getItem(i).isEmpty()) {
                output.setItem(i, new ItemStack(out, 1));
                return;
            }
        }
    }

    // ── Particles ─────────────────────────────────────────────────────────────

    private static void spawnParticles(BlockPos workPos, ServerLevel level) {
        level.sendParticles(ParticleTypes.SMOKE,
                workPos.getX() + 0.5, workPos.getY() + 1.1, workPos.getZ() + 0.5,
                3, 0.15, 0.05, 0.15, 0.02);
        level.sendParticles(ParticleTypes.FLAME,
                workPos.getX() + 0.5, workPos.getY() + 0.5, workPos.getZ() + 0.5,
                1, 0.1, 0.05, 0.1, 0.01);
    }
}
