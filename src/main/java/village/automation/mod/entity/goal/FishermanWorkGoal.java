package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.FluidState;
import village.automation.mod.ItemRequest;
import village.automation.mod.blockentity.FishingBlockEntity;
import village.automation.mod.blockentity.IWorkplaceBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * AI goal for Fisherman workers.
 *
 * <p>Two-phase behaviour:
 * <ol>
 *   <li><b>APPROACH</b> — scans the village territory for the nearest surface
 *       water source block (air above, not in a cave), navigates toward it, and
 *       transitions once within casting range.
 *   <li><b>FISH</b> — stops moving, sets
 *       {@link VillagerWorkerEntity#isFishingActive()} to {@code true} so the
 *       {@link FishingBlockEntity#serverTick} counts down the 20 s timer.
 *       Every {@value #CAST_INTERVAL} ticks the bobber-throw sound is played;
 *       bubble particles appear near the water every {@value #PARTICLE_INTERVAL}
 *       ticks.  When the block entity signals {@link FishingBlockEntity#isFishComplete()},
 *       the retrieve + splash sounds play, the signal is cleared, and the cast
 *       immediately restarts (no phase change — the fisherman stays at the water).
 * </ol>
 *
 * <p>Loot is generated <em>by the block entity</em> and deposited straight into
 * its 9-slot output container.  The fisherman never needs to carry items back;
 * couriers collect from the output via {@link village.automation.mod.entity.goal.CourierGoal}.
 *
 * <p>The goal requires a {@link Items#FISHING_ROD} in the worker's tool slot.
 * If none is present, an iron-fishing-rod request is submitted to the Village
 * Heart every {@value #REQUEST_COOLDOWN_MAX} ticks.
 */
public class FishermanWorkGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    /** XZ radius around the heart to search for surface water. */
    private static final int    SEARCH_RADIUS        = 24;
    /** Squared reach at which the fisherman is "close enough" to cast. */
    private static final double REACH_SQ             = 16.0;  // 4 blocks
    /** Ticks before giving up on a navigation attempt and re-scanning. */
    private static final int    APPROACH_TIMEOUT     = 300;
    /** Ticks between canUse scans after a failed approach. */
    private static final int    SCAN_COOLDOWN_MAX    = 60;
    /** Ticks between tool-request submissions. */
    private static final int    REQUEST_COOLDOWN_MAX = 600;
    /** Ticks between successive bobber-throw sounds. */
    private static final int    CAST_INTERVAL        = 100;
    /** Ticks between bubble-particle bursts. */
    private static final int    PARTICLE_INTERVAL    = 20;
    private static final double WALK_SPEED           = 0.6;

    // ── State ─────────────────────────────────────────────────────────────────
    private enum Phase { APPROACH, FISH }

    private final VillagerWorkerEntity fisherman;
    private Phase phase           = Phase.APPROACH;
    private int   approachTimeout = 0;
    private boolean approachFailed  = false;
    private int   scanCooldown    = 0;
    private int   requestCooldown = 0;
    private int   animTick        = 0;

    @Nullable private BlockPos waterPos     = null;
    @Nullable private BlockPos fishingBlock = null;

    public FishermanWorkGoal(VillagerWorkerEntity fisherman) {
        this.fisherman = fisherman;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── Goal lifecycle ────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        if (requestCooldown > 0) requestCooldown--;
        if (fisherman.isTooHungryToWork())               return false;
        if (scanCooldown > 0) { scanCooldown--;          return false; }
        if (!(fisherman.level() instanceof ServerLevel level)) return false;
        if (fisherman.getJob() != JobType.FISHERMAN)     return false;

        if (!hasFishingRod()) {
            if (requestCooldown <= 0) {
                submitToolRequest(level, new ItemStack(Items.FISHING_ROD));
                requestCooldown = REQUEST_COOLDOWN_MAX;
            }
            return false;
        }
        resolveToolRequest(level);

        fishingBlock = fisherman.getWorkplacePos();
        if (fishingBlock == null) return false;

        waterPos = findNearbyWater(level);
        return waterPos != null;
    }

    @Override
    public void start() {
        phase           = Phase.APPROACH;
        approachTimeout = APPROACH_TIMEOUT;
        approachFailed  = false;
        animTick        = 0;
        navigateTo(waterPos);
    }

    @Override
    public boolean canContinueToUse() {
        if (approachFailed)                                       return false;
        if (fisherman.isTooHungryToWork())                        return false;
        if (!(fisherman.level() instanceof ServerLevel))          return false;
        if (fisherman.getJob() != JobType.FISHERMAN)              return false;
        if (!hasFishingRod())                                     return false;
        return fisherman.getWorkplacePos() != null;
    }

    @Override
    public void tick() {
        if (!(fisherman.level() instanceof ServerLevel level)) return;

        switch (phase) {
            case APPROACH -> tickApproach(level);
            case FISH     -> tickFish(level);
        }
    }

    @Override
    public void stop() {
        fisherman.setFishingActive(false);
        fisherman.getNavigation().stop();
        scanCooldown   = SCAN_COOLDOWN_MAX;
        approachFailed = false;
        waterPos       = null;
        fishingBlock   = null;
        phase          = Phase.APPROACH;
    }

    // ── Phase ticks ───────────────────────────────────────────────────────────

    private void tickApproach(ServerLevel level) {
        fisherman.setFishingActive(false);

        // Re-scan for water if we don't have a target (e.g. after start())
        if (waterPos == null) {
            waterPos = findNearbyWater(level);
            if (waterPos == null) {
                approachFailed = true;
                return;
            }
            approachTimeout = APPROACH_TIMEOUT;
            navigateTo(waterPos);
            return;
        }

        if (distSqTo(waterPos) <= REACH_SQ) {
            // Close enough — start fishing
            fisherman.getNavigation().stop();
            phase    = Phase.FISH;
            animTick = 0;
            return;
        }

        if (--approachTimeout <= 0) {
            // Timed out — try a different water block next time
            waterPos = null;
            approachFailed = true;
        } else if (fisherman.getNavigation().isDone()) {
            navigateTo(waterPos);
        }
    }

    private void tickFish(ServerLevel level) {
        FishingBlockEntity be = getFishingBlockEntity(level);
        if (be == null) {
            // Fishing block disappeared — go back to APPROACH
            phase    = Phase.APPROACH;
            waterPos = null;
            fisherman.setFishingActive(false);
            return;
        }

        // Ensure we haven't drifted too far (e.g. pushed by player)
        if (waterPos != null && distSqTo(waterPos) > REACH_SQ * 4) {
            phase    = Phase.APPROACH;
            fisherman.setFishingActive(false);
            navigateTo(waterPos);
            return;
        }

        fisherman.setFishingActive(true);
        fisherman.getNavigation().stop();

        // Face the water block
        if (waterPos != null) {
            fisherman.getLookControl().setLookAt(
                    waterPos.getX() + 0.5,
                    waterPos.getY() + 0.3,
                    waterPos.getZ() + 0.5,
                    30f, 30f);
        }

        animTick++;

        // Bobber-throw sound — fires once at the start of each cast cycle
        if (animTick % CAST_INTERVAL == 1) {
            level.playSound(null,
                    fisherman.getX(), fisherman.getY(), fisherman.getZ(),
                    SoundEvents.FISHING_BOBBER_THROW,
                    SoundSource.NEUTRAL,
                    0.5f,
                    0.4f / (level.getRandom().nextFloat() * 0.4f + 0.8f));
        }

        // Bubble particles rising from the water surface
        if (waterPos != null && animTick % PARTICLE_INTERVAL == 0) {
            level.sendParticles(ParticleTypes.BUBBLE,
                    waterPos.getX() + 0.5 + (level.getRandom().nextDouble() - 0.5) * 0.6,
                    waterPos.getY() + 0.15,
                    waterPos.getZ() + 0.5 + (level.getRandom().nextDouble() - 0.5) * 0.6,
                    3,
                    0.1, 0.05, 0.1,
                    0.02);
        }

        // Poll for a completed catch
        if (be.isFishComplete()) {
            // Retrieve sound (line reeling in)
            level.playSound(null,
                    fisherman.getX(), fisherman.getY(), fisherman.getZ(),
                    SoundEvents.FISHING_BOBBER_RETRIEVE,
                    SoundSource.NEUTRAL,
                    0.5f,
                    0.4f / (level.getRandom().nextFloat() * 0.4f + 0.8f));

            // Splash at the water block
            if (waterPos != null) {
                level.playSound(null,
                        waterPos.getX() + 0.5, waterPos.getY(), waterPos.getZ() + 0.5,
                        SoundEvents.FISHING_BOBBER_SPLASH,
                        SoundSource.NEUTRAL,
                        0.4f,
                        0.4f / (level.getRandom().nextFloat() * 0.4f + 0.8f));
            }

            be.clearFishComplete();
            // Immediately re-cast — reset animTick so the throw sound plays on the
            // very next tick that equals (animTick % CAST_INTERVAL == 1)
            animTick = 0;
        }
    }

    // ── Water search ──────────────────────────────────────────────────────────

    /**
     * Scans the heart's territory for the nearest surface water source block.
     * "Surface" means: a water source ({@code FluidState.isSource() + WATER tag})
     * with air directly above it.
     */
    @Nullable
    private BlockPos findNearbyWater(ServerLevel level) {
        if (fishingBlock == null) return null;
        BlockPos origin = getHeartPos(level);
        if (origin == null) origin = fishingBlock;

        BlockPos best    = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                // Only check a vertical band around expected water level
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos check = origin.offset(dx, dy, dz);
                    FluidState fluid = level.getFluidState(check);
                    if (!fluid.isSource() || !fluid.is(FluidTags.WATER)) continue;
                    if (!level.getBlockState(check.above()).isAir()) continue;

                    double d = check.distSqr(origin);
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = check;
                    }
                }
            }
        }
        return best;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Nullable
    private FishingBlockEntity getFishingBlockEntity(ServerLevel level) {
        if (fishingBlock == null) return null;
        var be = level.getBlockEntity(fishingBlock);
        return be instanceof FishingBlockEntity fishing ? fishing : null;
    }

    private boolean hasFishingRod() {
        return fisherman.getToolContainer().getItem(0).is(Items.FISHING_ROD);
    }

    private void submitToolRequest(ServerLevel level, ItemStack tool) {
        VillageHeartBlockEntity heart = findHeart(level);
        if (heart == null) return;
        heart.addRequest(new ItemRequest(
                fisherman.getUUID(), fisherman.getBaseName(), JobType.FISHERMAN, tool));
    }

    private void resolveToolRequest(ServerLevel level) {
        VillageHeartBlockEntity heart = findHeart(level);
        if (heart != null) heart.resolveRequest(fisherman.getUUID());
    }

    @Nullable
    private VillageHeartBlockEntity findHeart(ServerLevel level) {
        BlockPos workplace = fisherman.getWorkplacePos();
        if (workplace == null) return null;
        if (!(level.getBlockEntity(workplace) instanceof IWorkplaceBlockEntity wbe)) return null;
        BlockPos heartPos = wbe.getLinkedHeartPos();
        if (heartPos == null) return null;
        return level.getBlockEntity(heartPos) instanceof VillageHeartBlockEntity h ? h : null;
    }

    @Nullable
    private BlockPos getHeartPos(ServerLevel level) {
        VillageHeartBlockEntity heart = findHeart(level);
        return heart != null ? heart.getBlockPos() : null;
    }

    private void navigateTo(BlockPos pos) {
        if (pos == null) return;
        fisherman.getNavigation().moveTo(
                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, WALK_SPEED);
    }

    private double distSqTo(BlockPos pos) {
        return fisherman.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
