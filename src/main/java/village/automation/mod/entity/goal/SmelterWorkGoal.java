package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import village.automation.mod.block.SmelterBlock;
import village.automation.mod.blockentity.IWorkplaceBlockEntity;
import village.automation.mod.blockentity.SmelterBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Drives an assigned {@link JobType#SMELTER} worker through the ore-smelting cycle.
 *
 * <h3>State machine</h3>
 * <ol>
 *   <li><b>IDLE</b> — examines the smelter block's three containers every 20 ticks.
 *       <ul>
 *         <li>If the output slot already has items (e.g. after a server restart),
 *             signals the courier ({@code outputReady=true}) and moves straight to READY.</li>
 *         <li>Otherwise, sets {@code needsOre} / {@code needsFuel} flags on the block
 *             entity so couriers know what to fetch.  When both slots are filled,
 *             clears the flags and starts the smelt timer (SMELTING).</li>
 *       </ul>
 *   </li>
 *   <li><b>SMELTING</b> — walks to the workplace block, plays fire/smoke effects, counts
 *       down {@value #SMELT_TICKS} ticks per ore.  On each completion the worker looks up
 *       the {@link RecipeType#BLASTING} result, merges it into the output slot, and
 *       automatically starts the next ore if fuel and space allow (batch processing).
 *       Fuel items are consumed once per charge: one coal ({@code burnDuration=1600}) yields
 *       {@code 1600 / 200 = 8} smelt operations before a new fuel item is consumed.
 *       Transitions to READY when ore runs out, fuel is exhausted, or the output slot
 *       is full.</li>
 *   <li><b>READY</b> — waits until the courier empties the output slot, then loops back
 *       to IDLE.</li>
 * </ol>
 */
public class SmelterWorkGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private static final int    SMELT_TICKS      = 100;  // 5 s (blast-furnace speed)
    private static final int    PARTICLE_INTERVAL = 20;  // particle burst every second
    private static final double WALK_SPEED        = 0.6;
    private static final double WORK_REACH_SQ     = 6.25; // 2.5 blocks, squared

    // ── Internal states ───────────────────────────────────────────────────────
    private static final int STATE_IDLE     = 0;
    private static final int STATE_SMELTING = 1;
    private static final int STATE_READY    = 2;

    // ── Per-instance state ────────────────────────────────────────────────────
    private final VillagerWorkerEntity smelter;
    private int state            = STATE_IDLE;
    private int smeltTimer       = 0;
    private int particleCooldown = 0;
    private int idleCheckTimer   = 0;

    public SmelterWorkGoal(VillagerWorkerEntity smelter) {
        this.smelter = smelter;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── Goal lifecycle ────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        return smelter.getJob() == JobType.SMELTER
                && smelter.getWorkplacePos() != null
                && smelter.level() instanceof ServerLevel;
    }

    @Override
    public boolean canContinueToUse() { return canUse(); }

    @Override
    public void stop() {
        // Clear delivery-request flags so couriers stop trying to supply a smelter
        // whose worker has become inactive or was reassigned.
        if (smelter.level() instanceof ServerLevel level) {
            SmelterBlockEntity sbe = getSmelterBE(level);
            if (sbe != null) {
                sbe.setNeedsOre(false);
                sbe.setNeedsFuel(false);
                // outputReady intentionally NOT cleared — courier should still collect
                // any output that was already produced before the goal stopped.
            }
            // Ensure the block never stays in the lit model if the goal is interrupted.
            setLit(level, false);
        }
        smelter.getNavigation().stop();
        state          = STATE_IDLE;
        smeltTimer     = 0;
        idleCheckTimer = 0;
    }

    @Override
    public void tick() {
        if (!(smelter.level() instanceof ServerLevel level)) return;

        switch (state) {
            case STATE_IDLE     -> tickIdle(level);
            case STATE_SMELTING -> tickSmelting(level);
            case STATE_READY    -> tickReady(level);
        }
    }

    // ── Phase: IDLE ───────────────────────────────────────────────────────────

    private void tickIdle(ServerLevel level) {
        // Throttle checks to once per second
        if (++idleCheckTimer < 20) return;
        idleCheckTimer = 0;

        SmelterBlockEntity sbe = getSmelterBE(level);
        if (sbe == null) return;

        boolean hasOre     = !sbe.getOreContainer().getItem(0).isEmpty();
        // Fuel is "available" if there is a banked charge from a previously
        // loaded fuel item, OR if a new fuel item is sitting in the fuel slot.
        boolean hasFuel    = sbe.getFuelRemaining() > 0
                             || !sbe.getFuelContainer().getItem(0).isEmpty();
        boolean outputFull = !sbe.getOutputContainer().getItem(0).isEmpty();

        // If output is present (e.g. from before a server restart), signal the
        // courier and enter READY so we monitor until it's collected.
        if (outputFull) {
            sbe.setOutputReady(true);
            state = STATE_READY;
            return;
        }

        // Signal the courier for whichever inputs are missing.
        // Fuel is only requested when the banked charge is empty AND the fuel
        // slot is empty — the courier doesn't need to bring fuel while the
        // current charge still has smelt operations remaining.
        sbe.setNeedsOre(!hasOre);
        sbe.setNeedsFuel(!hasFuel);

        // Both ore and fuel available — begin smelting
        if (hasOre && hasFuel) {
            sbe.setNeedsOre(false);
            sbe.setNeedsFuel(false);
            smeltTimer       = SMELT_TICKS * smelter.getWorkSwings();
            particleCooldown = PARTICLE_INTERVAL;
            setLit(level, true);
            state            = STATE_SMELTING;
        }
    }

    // ── Phase: SMELTING ───────────────────────────────────────────────────────

    private void tickSmelting(ServerLevel level) {
        BlockPos workPos = smelter.getWorkplacePos();
        if (workPos == null) { state = STATE_IDLE; return; }

        // Walk to and look at the smelter block
        double distSq = smelter.distanceToSqr(
                workPos.getX() + 0.5, workPos.getY() + 0.5, workPos.getZ() + 0.5);
        if (distSq > WORK_REACH_SQ) {
            if (smelter.getNavigation().isDone()) {
                smelter.getNavigation().moveTo(
                        workPos.getX() + 0.5, workPos.getY(), workPos.getZ() + 0.5,
                        WALK_SPEED);
            }
        } else {
            smelter.getNavigation().stop();
        }
        smelter.getLookControl().setLookAt(
                workPos.getX() + 0.5,
                workPos.getY() + 1.0,
                workPos.getZ() + 0.5,
                30f, 30f);

        // Tick timer, fire effects while working
        if (smeltTimer > 0) {
            smeltTimer--;
            if (--particleCooldown <= 0) {
                particleCooldown = PARTICLE_INTERVAL;
                spawnSmeltParticles(workPos, level);
                level.playSound(null,
                        workPos.getX() + 0.5, workPos.getY() + 0.5, workPos.getZ() + 0.5,
                        SoundEvents.FURNACE_FIRE_CRACKLE,
                        SoundSource.BLOCKS,
                        0.5f, 1.0f);
            }
            return;
        }

        // Timer elapsed — produce the smelted output
        SmelterBlockEntity sbe = getSmelterBE(level);
        if (sbe == null) { state = STATE_IDLE; return; }

        // ── Guard: output slot at hard capacity — wait for the courier ────────
        ItemStack currentOut = sbe.getOutputContainer().getItem(0);
        if (!currentOut.isEmpty() && currentOut.getCount() >= currentOut.getMaxStackSize()) {
            sbe.setOutputReady(true);
            setLit(level, false);
            state = STATE_READY;
            return;
        }

        // ── Fuel: load a new charge if the current one is exhausted ──────────
        if (sbe.getFuelRemaining() <= 0) {
            ItemStack fuelStack = sbe.getFuelContainer().getItem(0);
            if (fuelStack.isEmpty()) {
                // Ran out of fuel mid-batch — collect what we have and pause
                if (!currentOut.isEmpty()) sbe.setOutputReady(true);
                setLit(level, false);
                state = STATE_READY;
                return;
            }
            int burnTime = getFuelBurnTime(fuelStack);
            int charges  = Math.max(1, burnTime / 200); // coal=1600 → 8 charges
            fuelStack.shrink(1);
            sbe.getFuelContainer().setItem(0, fuelStack.isEmpty() ? ItemStack.EMPTY : fuelStack);
            sbe.setFuelRemaining(charges);
        }

        // ── Ore ───────────────────────────────────────────────────────────────
        ItemStack ore = sbe.getOreContainer().getItem(0);
        if (ore.isEmpty()) { state = STATE_IDLE; return; }

        // Look up the blasting recipe for this ore
        var recipeHolder = level.getRecipeManager()
                .getRecipeFor(RecipeType.BLASTING, new SingleRecipeInput(ore), level);
        if (recipeHolder.isEmpty()) {
            // No longer a valid ore — discard it and reset
            sbe.getOreContainer().setItem(0, ItemStack.EMPTY);
            state = STATE_IDLE;
            return;
        }

        ItemStack result = recipeHolder.get().value()
                .getResultItem(level.registryAccess()).copy();

        // ── Consume inputs ────────────────────────────────────────────────────
        ore.shrink(1);
        sbe.getOreContainer().setItem(0, ore.isEmpty() ? ItemStack.EMPTY : ore);
        sbe.setFuelRemaining(sbe.getFuelRemaining() - 1);

        // ── Merge result into output slot ─────────────────────────────────────
        ItemStack updatedOut = sbe.getOutputContainer().getItem(0);
        if (!updatedOut.isEmpty() && ItemStack.isSameItemSameComponents(updatedOut, result)) {
            int space = updatedOut.getMaxStackSize() - updatedOut.getCount();
            updatedOut.grow(Math.min(result.getCount(), space));
            sbe.getOutputContainer().setItem(0, updatedOut);
        } else if (updatedOut.isEmpty()) {
            sbe.getOutputContainer().setItem(0, result);
        }
        // (Different item in a full slot: item is lost — cannot happen in normal
        //  play because tickIdle waits for an empty output before starting.)

        // ── Decide: batch-continue or hand off to courier ────────────────────
        ItemStack nextOre    = sbe.getOreContainer().getItem(0);
        ItemStack latestOut  = sbe.getOutputContainer().getItem(0);
        boolean moreFuel     = sbe.getFuelRemaining() > 0
                               || !sbe.getFuelContainer().getItem(0).isEmpty();
        boolean outputRoom   = latestOut.isEmpty()
                               || latestOut.getCount() < latestOut.getMaxStackSize();

        smelter.gainXp(10);

        if (!nextOre.isEmpty() && moreFuel && outputRoom) {
            // More ore in the slot and fuel available — continue the batch
            smeltTimer       = SMELT_TICKS * smelter.getWorkSwings();
            particleCooldown = PARTICLE_INTERVAL;
            // state remains STATE_SMELTING; LIT stays true
        } else {
            // Batch done (or no room/fuel) — signal courier to collect output
            if (!latestOut.isEmpty()) sbe.setOutputReady(true);
            setLit(level, false);
            state = STATE_READY;
        }
    }

    /**
     * Returns the burn duration (in ticks) for {@code stack}.
     *
     * <p>NeoForge's AT makes {@link AbstractFurnaceBlockEntity#getFuel()} public,
     * exposing the complete vanilla fuel map (coal = 1600, charcoal = 1600,
     * planks = 300, blaze rod = 2400, lava bucket = 20000, …).
     * Dividing by 200 gives the number of smelt operations one item can power
     * (coal = 1600 / 200 = 8).
     *
     * <p>Modded fuels registered only via {@code IItemExtension} and not present
     * in the vanilla map will return 0 here; {@code Math.max(1, 0/200) = 1} in
     * the caller ensures they still work as a single-smelt fuel.
     */
    private static int getFuelBurnTime(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        // getFuel() is AT'd to public static by NeoForge and returns the full
        // vanilla burn-duration map keyed by Item.
        return AbstractFurnaceBlockEntity.getFuel().getOrDefault(stack.getItem(), 0);
    }

    private static void spawnSmeltParticles(BlockPos workPos, ServerLevel level) {
        // Flame + smoke rising from the top face of the smelter block
        level.sendParticles(
                ParticleTypes.FLAME,
                workPos.getX() + 0.5,
                workPos.getY() + 1.05,
                workPos.getZ() + 0.5,
                4,
                0.20, 0.05, 0.20,
                0.02);
        level.sendParticles(
                ParticleTypes.SMOKE,
                workPos.getX() + 0.5,
                workPos.getY() + 1.15,
                workPos.getZ() + 0.5,
                2,
                0.15, 0.05, 0.15,
                0.01);
    }

    // ── Phase: READY ──────────────────────────────────────────────────────────

    private void tickReady(ServerLevel level) {
        SmelterBlockEntity sbe = getSmelterBE(level);
        if (sbe == null) { state = STATE_IDLE; return; }

        // Courier has collected the output — loop back and smelt the next ore
        if (sbe.getOutputContainer().getItem(0).isEmpty()) {
            sbe.setOutputReady(false);
            state = STATE_IDLE;
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    @Nullable
    private SmelterBlockEntity getSmelterBE(ServerLevel level) {
        BlockPos workPos = smelter.getWorkplacePos();
        if (workPos == null) return null;
        BlockEntity be = level.getBlockEntity(workPos);
        return be instanceof SmelterBlockEntity sbe ? sbe : null;
    }

    /**
     * Flips the {@link SmelterBlock#LIT} block state property on the workplace block.
     * Safe to call even if the block at {@code workPos} has been replaced.
     */
    private void setLit(ServerLevel level, boolean lit) {
        BlockPos workPos = smelter.getWorkplacePos();
        if (workPos == null) return;
        BlockState bs = level.getBlockState(workPos);
        if (bs.hasProperty(BlockStateProperties.LIT)) {
            level.setBlock(workPos, bs.setValue(BlockStateProperties.LIT, lit), 3);
        }
    }

    @SuppressWarnings("unused")
    @Nullable
    private VillageHeartBlockEntity getHeart(ServerLevel level) {
        BlockPos workPos = smelter.getWorkplacePos();
        if (workPos == null) return null;
        if (!(level.getBlockEntity(workPos) instanceof IWorkplaceBlockEntity workplace)) return null;
        BlockPos heartPos = workplace.getLinkedHeartPos();
        if (heartPos == null) return null;
        BlockEntity heartBe = level.getBlockEntity(heartPos);
        return heartBe instanceof VillageHeartBlockEntity h ? h : null;
    }
}
