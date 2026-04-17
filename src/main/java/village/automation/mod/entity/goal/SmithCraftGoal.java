package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.ItemRequest;
import village.automation.mod.blockentity.IWorkplaceBlockEntity;
import village.automation.mod.blockentity.SmithingBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.SmithRecipe;
import village.automation.mod.entity.VillagerWorkerEntity;

import java.util.EnumSet;
import java.util.List;

/**
 * Drives the smith through its crafting cycle:
 *
 * <ol>
 *   <li><b>IDLE</b> — checks the heart for pending requests; claims one when found.</li>
 *   <li><b>AWAITING</b> — waits until the courier has deposited all required materials
 *       into the smith's input container.</li>
 *   <li><b>CRAFTING</b> — runs a 200-tick (10 s) timer; intermediate conversions
 *       (logs→planks, planks→sticks) happen instantly, then the tool is produced.</li>
 *   <li><b>READY</b> — finished item sits in the output container until the courier
 *       collects it, then the cycle resets.</li>
 * </ol>
 */
public class SmithCraftGoal extends Goal {

    private static final int    CRAFT_TICKS      = 200;  // 10 s per craft
    private static final int    PARTICLE_INTERVAL = 20;  // particle burst every second
    private static final double WALK_SPEED        = 0.6;
    private static final double WORK_REACH_SQ     = 6.25; // within 2.5 blocks of the block

    private final VillagerWorkerEntity smith;
    private int particleCooldown = 0;

    public SmithCraftGoal(VillagerWorkerEntity smith) {
        this.smith = smith;
        // MOVE + LOOK: keep random stroll and random-look goals suppressed while working.
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── Goal lifecycle ────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        return smith.getJob() == JobType.BLACKSMITH
                && smith.getWorkplacePos() != null
                && smith.level() instanceof ServerLevel;
    }

    @Override
    public boolean canContinueToUse() { return canUse(); }

    @Override
    public void tick() {
        if (!(smith.level() instanceof ServerLevel level)) return;

        switch (smith.getSmithCraftingState()) {
            case VillagerWorkerEntity.SMITH_IDLE     -> tickIdle(level);
            case VillagerWorkerEntity.SMITH_AWAITING -> tickAwaiting();
            case VillagerWorkerEntity.SMITH_CRAFTING -> tickCrafting(level);
            case VillagerWorkerEntity.SMITH_READY    -> tickReady();
        }

        // ── Push state to the block entity for GUI sync ───────────────────────
        BlockPos wp = smith.getWorkplacePos();
        if (wp != null) {
            BlockEntity wpBe = level.getBlockEntity(wp);
            if (wpBe instanceof SmithingBlockEntity sbe) {
                int itemId = smith.getSmithCurrentRecipe() != null
                        ? BuiltInRegistries.ITEM.getId(smith.getSmithCurrentRecipe().result) : -1;
                sbe.setSmithState(
                        smith.getSmithCraftingState(),
                        smith.getSmithCraftingTimer(),
                        itemId);
            }
        }
    }

    // ── Phase: IDLE ───────────────────────────────────────────────────────────

    private void tickIdle(ServerLevel level) {
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return;

        List<ItemRequest> pending = heart.getPendingRequests();
        if (pending.isEmpty()) return;

        // Skip requests whose item already exists in a chest — the courier will deliver directly
        ItemRequest request = null;
        for (ItemRequest req : pending) {
            if (!itemExistsInChest(level, heart, req.getRequestedItem())) {
                request = req;
                break;
            }
        }
        if (request == null) return;

        SmithRecipe recipe = SmithRecipe.findFor(request.getRequestedItem().getItem()).orElse(null);
        if (recipe == null) return;  // no recipe — skip this request silently

        smith.setSmithCurrentRequest(request);
        smith.setSmithCurrentRecipe(recipe);
        smith.setSmithCraftingState(VillagerWorkerEntity.SMITH_AWAITING);
    }

    private static boolean itemExistsInChest(ServerLevel level, VillageHeartBlockEntity heart,
                                             ItemStack item) {
        for (BlockPos chestPos : heart.getRegisteredChests()) {
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof Container container)) continue;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack slot = container.getItem(i);
                if (!slot.isEmpty() && ItemStack.isSameItem(slot, item)) return true;
            }
        }
        return false;
    }

    // ── Phase: AWAITING ───────────────────────────────────────────────────────

    private void tickAwaiting() {
        SmithRecipe recipe = smith.getSmithCurrentRecipe();
        if (recipe == null) {
            smith.setSmithCraftingState(VillagerWorkerEntity.SMITH_IDLE);
            return;
        }

        if (recipe.satisfied(smith.getSmithInputContainer())) {
            smith.setSmithCraftingTimer(CRAFT_TICKS);
            particleCooldown = PARTICLE_INTERVAL;
            smith.setSmithCraftingState(VillagerWorkerEntity.SMITH_CRAFTING);
        }
    }

    // ── Phase: CRAFTING ───────────────────────────────────────────────────────

    private void tickCrafting(ServerLevel level) {
        BlockPos workPos = smith.getWorkplacePos();

        // Walk to the smithing block and look at it while working
        if (workPos != null) {
            double distSq = smith.distanceToSqr(
                    workPos.getX() + 0.5, workPos.getY() + 0.5, workPos.getZ() + 0.5);
            if (distSq > WORK_REACH_SQ) {
                if (smith.getNavigation().isDone()) {
                    smith.getNavigation().moveTo(
                            workPos.getX() + 0.5, workPos.getY(), workPos.getZ() + 0.5,
                            WALK_SPEED);
                }
            } else {
                smith.getNavigation().stop();
            }
            // Always look at the top face of the block
            smith.getLookControl().setLookAt(
                    workPos.getX() + 0.5,
                    workPos.getY() + 1.0,
                    workPos.getZ() + 0.5,
                    30f, 30f);
        }

        // Tick the crafting timer
        smith.tickSmithCraftingTimer();

        // Particles + sound burst while crafting
        if (smith.getSmithCraftingTimer() > 0) {
            if (--particleCooldown <= 0) {
                particleCooldown = PARTICLE_INTERVAL;
                if (workPos != null) {
                    spawnSmithParticles(workPos, level);
                    level.playSound(null,
                            workPos.getX() + 0.5, workPos.getY() + 1.0, workPos.getZ() + 0.5,
                            SoundEvents.ANVIL_USE,
                            SoundSource.BLOCKS,
                            0.4f,
                            0.8f + level.getRandom().nextFloat() * 0.4f);
                }
            }
            return;
        }

        // Timer finished — consume ingredients and produce the item
        SmithRecipe recipe = smith.getSmithCurrentRecipe();
        if (recipe == null) {
            smith.setSmithCraftingState(VillagerWorkerEntity.SMITH_IDLE);
            return;
        }

        if (consumeIngredients(smith.getSmithInputContainer(), recipe)) {
            smith.getSmithOutputContainer().setItem(0, new ItemStack(recipe.result, recipe.resultCount));
            smith.setSmithCraftingState(VillagerWorkerEntity.SMITH_READY);
        } else {
            smith.setSmithCraftingState(VillagerWorkerEntity.SMITH_IDLE);
            smith.setSmithCurrentRequest(null);
            smith.setSmithCurrentRecipe(null);
        }
    }

    private static void spawnSmithParticles(BlockPos workPos, ServerLevel level) {
        BlockState state = level.getBlockState(workPos);
        level.sendParticles(
                new BlockParticleOption(ParticleTypes.BLOCK, state),
                workPos.getX() + 0.5,
                workPos.getY() + 1.05,
                workPos.getZ() + 0.5,
                6,
                0.3, 0.05, 0.3,
                0.08);
    }

    // ── Phase: READY ──────────────────────────────────────────────────────────

    private void tickReady() {
        if (smith.getSmithOutputContainer().getItem(0).isEmpty()) {
            smith.setSmithCraftingState(VillagerWorkerEntity.SMITH_IDLE);
            smith.setSmithCurrentRequest(null);
            smith.setSmithCurrentRecipe(null);
        }
    }

    // ── Consume exact recipe ingredients from container ───────────────────────

    private static boolean consumeIngredients(SimpleContainer inv, SmithRecipe recipe) {
        // Verify everything is still there
        for (SmithRecipe.Ingredient ing : recipe.ingredients) {
            if (SmithRecipe.countInContainer(inv, ing) < ing.count) return false;
        }
        // Consume
        for (SmithRecipe.Ingredient ing : recipe.ingredients) {
            int remaining = ing.count;
            for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
                ItemStack slot = inv.getItem(i);
                if (slot.isEmpty() || !ing.matches(slot)) continue;
                int take = Math.min(slot.getCount(), remaining);
                slot.shrink(take);
                inv.setItem(i, slot.isEmpty() ? ItemStack.EMPTY : slot);
                remaining -= take;
            }
        }
        return true;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private VillageHeartBlockEntity getHeart(ServerLevel level) {
        BlockPos workPos = smith.getWorkplacePos();
        if (workPos == null) return null;
        if (!(level.getBlockEntity(workPos) instanceof IWorkplaceBlockEntity workplace)) return null;
        BlockPos heartPos = workplace.getLinkedHeartPos();
        if (heartPos == null) return null;
        BlockEntity heartBe = level.getBlockEntity(heartPos);
        return heartBe instanceof VillageHeartBlockEntity h ? h : null;
    }
}
