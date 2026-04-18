package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.ItemRequest;
import village.automation.mod.blockentity.IWorkplaceBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * AI goal for Miner workers.
 *
 * <p>Two-phase behaviour:
 * <ol>
 *   <li><b>APPROACH</b> — picks a random solid floor block within
 *       {@value #SEARCH_RADIUS} blocks of the assigned Mine Block and navigates
 *       to it.  If the miner cannot reach the spot within
 *       {@value #APPROACH_TIMEOUT} ticks the goal self-terminates, which
 *       <em>pauses</em> the mine-block drop timer (see
 *       {@link village.automation.mod.blockentity.MineBlockEntity#serverTick}).
 *       A {@value #SCAN_INTERVAL}-tick cooldown then delays the next attempt.
 *   <li><b>MINE</b> — stops all movement, looks straight down at the floor block,
 *       swings the arm every {@value #SWING_INTERVAL} ticks, and sprays
 *       block-crack particles every {@value #PARTICLE_INTERVAL} ticks.  While in
 *       this phase {@link VillagerWorkerEntity#isMiningActive()} returns
 *       {@code true}, which allows the Mine Block timer to count down.
 * </ol>
 *
 * <p>The goal is dormant when the entity's job is not {@link JobType#MINER} or
 * no pickaxe is in the tool slot.
 */
public class MinerWorkGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    /** XZ radius around the mine block centre in which floor spots are searched. */
    private static final int    SEARCH_RADIUS    = 3;
    /** Squared distance (blocks²) at which we consider the miner "at" the spot. */
    private static final double REACH_DIST_SQ    = 2.25;  // 1.5 blocks
    /** Ticks before giving up on a navigation attempt (5 s). */
    private static final int    APPROACH_TIMEOUT = 100;
    /** Ticks between successive canUse checks after a failed approach. */
    private static final int    SCAN_INTERVAL    = 40;
    /** Ticks between arm-swing animations. */
    private static final int    SWING_INTERVAL   = 10;
    /** Ticks between particle bursts. */
    private static final int    PARTICLE_INTERVAL = 8;
    private static final double WALK_SPEED        = 0.7;

    // ── State ─────────────────────────────────────────────────────────────────
    private enum Phase { APPROACH, MINE }

    private final VillagerWorkerEntity miner;
    private Phase    phase            = Phase.APPROACH;
    private int      swingCooldown    = 0;
    private int      particleCooldown = 0;
    private int      approachTimeout  = 0;
    private int      scanCooldown     = 0;
    /** Set in tick() when approach has timed out; checked in canContinueToUse(). */
    private boolean  approachFailed   = false;
    // Throttles how often we post a tool request to the heart (30 s)
    private int      requestCooldown  = 0;

    @Nullable private BlockPos targetPos = null;

    public MinerWorkGoal(VillagerWorkerEntity miner) {
        this.miner = miner;
        // MOVE — own the pathfinder so RandomStrollGoal can't override us.
        // LOOK — own look control so RandomLookAroundGoal doesn't interrupt the
        //        downward-gaze while mining.
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── Goal lifecycle ────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        if (requestCooldown > 0) requestCooldown--;
        if (!miner.level().isDay()) return false;          // no mining at night
        if (miner.isTooHungryToWork()) return false;       // won't work below 20 % food
        if (scanCooldown > 0) { scanCooldown--; return false; }

        if (!(miner.level() instanceof ServerLevel level)) return false;
        if (miner.getJob() != JobType.MINER)              return false;
        if (!hasPickaxeEquipped()) {
            if (requestCooldown <= 0) {
                submitToolRequest(level, new ItemStack(Items.IRON_PICKAXE));
                requestCooldown = 600;
            }
            return false;
        }
        // Pickaxe is present — clear any pending request
        resolveToolRequest(level);

        BlockPos workplace = miner.getWorkplacePos();
        if (workplace == null) return false;

        targetPos = findMiningSpot(level, workplace);
        return targetPos != null;
    }

    @Override
    public void start() {
        phase           = Phase.APPROACH;
        approachTimeout = APPROACH_TIMEOUT;
        approachFailed  = false;
        swingCooldown   = SWING_INTERVAL;
        particleCooldown = PARTICLE_INTERVAL;
        navigateTo(targetPos);
    }

    @Override
    public boolean canContinueToUse() {
        if (!miner.level().isDay()) return false;          // stop at nightfall
        if (miner.isTooHungryToWork()) return false;       // stop if food drops below 20 %
        if (approachFailed)                              return false;
        if (!(miner.level() instanceof ServerLevel))     return false;
        if (miner.getJob() != JobType.MINER)             return false;
        if (!hasPickaxeEquipped())                       return false;
        return miner.getWorkplacePos() != null;
    }

    @Override
    public void tick() {
        if (targetPos == null) return;
        if (!(miner.level() instanceof ServerLevel sl)) return;

        // Distance from the miner to the standing point above the target block
        double distSq = miner.distanceToSqr(
                targetPos.getX() + 0.5,
                targetPos.getY() + 1.0,   // top face — where the miner stands
                targetPos.getZ() + 0.5);

        // ── APPROACH phase ────────────────────────────────────────────────────
        if (phase == Phase.APPROACH) {
            if (distSq <= REACH_DIST_SQ) {
                // Arrived — stop moving and start mining
                miner.getNavigation().stop();
                phase = Phase.MINE;
            } else {
                // Still approaching — check for timeout
                if (--approachTimeout <= 0) {
                    // Can't reach this spot; signal stop and apply scan cooldown
                    approachFailed = true;
                    miner.setMiningActive(false);
                }
                return;
            }
        }

        // ── MINE phase ────────────────────────────────────────────────────────

        // If the target block was broken while we were mining, pick a new spot next cycle
        BlockState targetState = sl.getBlockState(targetPos);
        if (targetState.isAir()) {
            approachFailed = true;
            miner.setMiningActive(false);
            return;
        }

        // If the miner drifted too far (e.g. knocked back), re-approach
        if (distSq > REACH_DIST_SQ * 4) {
            phase           = Phase.APPROACH;
            approachTimeout = APPROACH_TIMEOUT;
            miner.setMiningActive(false);
            navigateTo(targetPos);
            return;
        }

        // Mark as actively mining — allows MineBlockEntity timer to tick
        miner.setMiningActive(true);

        // Look straight down at the floor block's top face
        miner.getLookControl().setLookAt(
                targetPos.getX() + 0.5,
                targetPos.getY(),          // bottom of block → steep downward head angle
                targetPos.getZ() + 0.5,
                30f, 90f);

        // Arm swing + block-hit sound
        if (--swingCooldown <= 0) {
            swingCooldown = SWING_INTERVAL;
            miner.swing(InteractionHand.MAIN_HAND);
            miner.gainXp(1);

            // Play the floor block's hit sound (the soft "tick" heard while mining).
            // Pitch is randomised slightly so repeated swings don't sound mechanical.
            SoundType sounds = targetState.getSoundType();
            sl.playSound(null,
                    targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5,
                    sounds.getHitSound(),
                    SoundSource.BLOCKS,
                    sounds.getVolume() * 0.25f,
                    sounds.getPitch() * 0.75f + sl.getRandom().nextFloat() * 0.25f);
        }

        // Block-crack particles on the floor block surface
        if (--particleCooldown <= 0) {
            particleCooldown = PARTICLE_INTERVAL;
            spawnMiningParticles(targetPos, targetState, sl);
        }
    }

    @Override
    public void stop() {
        miner.getNavigation().stop();
        miner.setMiningActive(false);
        // Delay the next canUse scan so the miner doesn't thrash on unreachable spots
        scanCooldown  = SCAN_INTERVAL;
        approachFailed = false;
        targetPos     = null;
        phase         = Phase.APPROACH;
    }

    // ── Tool request helpers ──────────────────────────────────────────────────

    private void submitToolRequest(ServerLevel level, ItemStack tool) {
        VillageHeartBlockEntity heart = findHeart(level);
        if (heart == null) return;
        heart.addRequest(new ItemRequest(
                miner.getUUID(), miner.getBaseName(), JobType.MINER, tool));
    }

    private void resolveToolRequest(ServerLevel level) {
        VillageHeartBlockEntity heart = findHeart(level);
        if (heart != null) heart.resolveRequest(miner.getUUID());
    }

    @Nullable
    private VillageHeartBlockEntity findHeart(ServerLevel level) {
        BlockPos workplace = miner.getWorkplacePos();
        if (workplace == null) return null;
        if (!(level.getBlockEntity(workplace) instanceof IWorkplaceBlockEntity wbe)) return null;
        BlockPos heartPos = wbe.getLinkedHeartPos();
        if (heartPos == null) return null;
        return level.getBlockEntity(heartPos) instanceof VillageHeartBlockEntity h ? h : null;
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private void navigateTo(BlockPos pos) {
        if (pos == null) return;
        // Navigate to the standing position ON TOP of the floor block
        miner.getNavigation().moveTo(
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, WALK_SPEED);
    }

    // ── Floor-block search ────────────────────────────────────────────────────

    /**
     * Returns a random solid floor block within {@value #SEARCH_RADIUS} blocks
     * (XZ) and ±1 block (Y) of {@code center}, or {@code null} if none exist.
     *
     * <p>A valid spot has:
     * <ul>
     *   <li>a non-air floor block (the block the miner will stand on),
     *   <li>two consecutive air blocks above it (villager headroom), and
     *   <li>is not the Mine Block position itself.
     * </ul>
     */
    @Nullable
    private BlockPos findMiningSpot(ServerLevel level, BlockPos center) {
        List<BlockPos> candidates = new ArrayList<>();

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos floor  = center.offset(dx, dy, dz);
                    if (floor.equals(center)) continue;   // never target the mine block

                    BlockPos above1 = floor.above();
                    BlockPos above2 = above1.above();
                    if (level.getBlockState(floor).isAir())   continue;  // need solid floor
                    if (!level.getBlockState(above1).isAir()) continue;  // need headroom
                    if (!level.getBlockState(above2).isAir()) continue;  // need headroom

                    candidates.add(floor);
                }
            }
        }

        if (candidates.isEmpty()) return null;
        return candidates.get(miner.getRandom().nextInt(candidates.size()));
    }

    // ── Particles ─────────────────────────────────────────────────────────────

    /**
     * Sends a burst of {@link ParticleTypes#BLOCK} particles at the top face of
     * the floor block — identical to the crack chips vanilla shows during mining.
     */
    private static void spawnMiningParticles(BlockPos pos, BlockState state, ServerLevel level) {
        level.sendParticles(
                new BlockParticleOption(ParticleTypes.BLOCK, state),
                pos.getX() + 0.5,
                pos.getY() + 1.02,   // just above the top face (at the miner's feet)
                pos.getZ() + 0.5,
                5,                   // particle count per burst
                0.3, 0.05, 0.3,      // spread: wide XZ, thin Y so chips scatter sideways
                0.08                 // speed
        );
    }

    // ── Tool check ────────────────────────────────────────────────────────────

    private boolean hasPickaxeEquipped() {
        return miner.getToolContainer().getItem(0).getItem() instanceof PickaxeItem;
    }
}
