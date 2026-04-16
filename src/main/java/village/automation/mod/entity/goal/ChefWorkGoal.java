package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import village.automation.mod.blockentity.CookingBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import java.util.EnumSet;

public class ChefWorkGoal extends Goal {

    private static final int    COOK_TICKS        = 200;  // 10 s per bread
    private static final int    PARTICLE_INTERVAL  = 20;   // burst every second
    private static final double WALK_SPEED         = 0.6;
    private static final double WORK_REACH_SQ      = 6.25; // 2.5 blocks

    private final VillagerWorkerEntity chef;
    private int cookTimer        = 0;
    private int particleCooldown = 0;

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
        cookTimer = 0;
        particleCooldown = 0;
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
            return;
        }

        chef.getNavigation().stop();
        chef.getLookControl().setLookAt(
                workPos.getX() + 0.5, workPos.getY() + 1.0, workPos.getZ() + 0.5, 30f, 30f);

        if (cookTimer <= 0) {
            if (!hasWheat(cooking.getInputContainer()) || !hasOutputSpace(cooking.getOutputContainer())) {
                // Signal to the golem that this cooking block needs a wheat delivery
                if (!cooking.isNeedsIngredients()) cooking.setNeedsIngredients(true);
                return;
            }
            // Wheat is here — clear the request and start cooking
            cooking.setNeedsIngredients(false);
            cookTimer = COOK_TICKS;
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
                if (consumeWheat(cooking.getInputContainer())) {
                    produceBread(cooking.getOutputContainer());
                }
            }
        }
    }

    private static boolean hasWheat(SimpleContainer input) {
        for (int i = 0; i < input.getContainerSize(); i++) {
            if (input.getItem(i).is(Items.WHEAT)) return true;
        }
        return false;
    }

    private static boolean hasOutputSpace(SimpleContainer output) {
        for (int i = 0; i < output.getContainerSize(); i++) {
            ItemStack slot = output.getItem(i);
            if (slot.isEmpty()) return true;
            if (slot.is(Items.BREAD) && slot.getCount() < slot.getMaxStackSize()) return true;
        }
        return false;
    }

    private static boolean consumeWheat(SimpleContainer input) {
        for (int i = 0; i < input.getContainerSize(); i++) {
            ItemStack slot = input.getItem(i);
            if (!slot.is(Items.WHEAT)) continue;
            slot.shrink(1);
            input.setItem(i, slot.isEmpty() ? ItemStack.EMPTY : slot);
            return true;
        }
        return false;
    }

    private static void produceBread(SimpleContainer output) {
        for (int i = 0; i < output.getContainerSize(); i++) {
            ItemStack slot = output.getItem(i);
            if (slot.is(Items.BREAD) && slot.getCount() < slot.getMaxStackSize()) {
                slot.grow(1);
                output.setItem(i, slot);
                return;
            }
        }
        for (int i = 0; i < output.getContainerSize(); i++) {
            if (output.getItem(i).isEmpty()) {
                output.setItem(i, new ItemStack(Items.BREAD, 1));
                return;
            }
        }
    }

    private static void spawnParticles(BlockPos workPos, ServerLevel level) {
        level.sendParticles(ParticleTypes.SMOKE,
                workPos.getX() + 0.5, workPos.getY() + 1.1, workPos.getZ() + 0.5,
                3, 0.15, 0.05, 0.15, 0.02);
        level.sendParticles(ParticleTypes.FLAME,
                workPos.getX() + 0.5, workPos.getY() + 0.5, workPos.getZ() + 0.5,
                1, 0.1, 0.05, 0.1, 0.01);
    }
}
