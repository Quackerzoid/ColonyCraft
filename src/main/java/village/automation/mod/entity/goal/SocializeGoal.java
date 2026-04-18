package village.automation.mod.entity.goal;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * When a worker's happiness drops below {@link VillagerWorkerEntity#HAPPINESS_LOW},
 * they seek out a nearby colleague to socialise with.
 *
 * <p>Behaviour:
 * <ol>
 *   <li>Finds a nearby worker that is not the same as the last socialised partner.
 *   <li>Walks up to them and stands face-to-face for 30 seconds.
 *   <li>Every 2 seconds both workers emit happy-villager particles and play a
 *       friendly grunt.
 *   <li>On completion the initiating worker gains +10 happiness; {@code lastSocializedWith}
 *       is updated to prevent immediately repeating with the same partner.
 * </ol>
 */
public class SocializeGoal extends Goal {

    private static final double SEARCH_RANGE  = 16.0;
    private static final double REACH_DIST_SQ = 6.25;  // 2.5 blocks
    private static final int    SOCIAL_TICKS  = 600;   // 30 s
    private static final int    EFFECT_EVERY  = 40;    // every 2 s
    private static final double WALK_SPEED    = 0.5;

    private final VillagerWorkerEntity worker;
    @Nullable private VillagerWorkerEntity target = null;
    private int socialTimer  = 0;
    private int effectTimer  = 0;
    private boolean finished = false;

    public SocializeGoal(VillagerWorkerEntity worker) {
        this.worker = worker;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        if (worker.isTooUnhappyToWork()
                && worker.level() instanceof ServerLevel sl
                && worker.level().isDay()) {
            target = findTarget(sl);
            return target != null;
        }
        return false;
    }

    @Override
    public void start() {
        socialTimer = SOCIAL_TICKS;
        effectTimer = EFFECT_EVERY;
        finished    = false;
        navigateTo();
    }

    @Override
    public boolean canContinueToUse() {
        return socialTimer > 0
                && target != null
                && target.isAlive()
                && worker.level() instanceof ServerLevel;
    }

    @Override
    public void tick() {
        if (target == null || !(worker.level() instanceof ServerLevel sl)) return;

        worker.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ());

        if (worker.distanceToSqr(target) > REACH_DIST_SQ) {
            navigateTo();
            return;
        }

        worker.getNavigation().stop();
        socialTimer--;

        if (--effectTimer <= 0) {
            effectTimer = EFFECT_EVERY;
            spawnHappyParticles(sl);
            playGruntSound(sl);
        }

        if (socialTimer <= 0) finished = true;
    }

    @Override
    public void stop() {
        if (finished && target != null) {
            worker.modifyHappiness(10);
            worker.setLastSocializedWith(target.getUUID());
        }
        target      = null;
        socialTimer = 0;
        effectTimer = 0;
        finished    = false;
        worker.getNavigation().stop();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Nullable
    private VillagerWorkerEntity findTarget(ServerLevel sl) {
        AABB box = worker.getBoundingBox().inflate(SEARCH_RANGE);
        List<VillagerWorkerEntity> candidates = sl.getEntitiesOfClass(
                VillagerWorkerEntity.class, box,
                v -> v != worker
                        && v.isAlive()
                        && (worker.getLastSocializedWith() == null
                            || !v.getUUID().equals(worker.getLastSocializedWith())));
        if (candidates.isEmpty()) return null;
        return candidates.get(worker.getRandom().nextInt(candidates.size()));
    }

    private void navigateTo() {
        if (target == null) return;
        worker.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), WALK_SPEED);
    }

    private void spawnHappyParticles(ServerLevel sl) {
        sl.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                worker.getX(), worker.getY() + 1.8, worker.getZ(),
                3, 0.3, 0.3, 0.3, 0.1);
        sl.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                target.getX(), target.getY() + 1.8, target.getZ(),
                3, 0.3, 0.3, 0.3, 0.1);
    }

    private void playGruntSound(ServerLevel sl) {
        sl.playSound(null, worker.getX(), worker.getY(), worker.getZ(),
                SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL,
                0.5f, 0.8f + worker.getRandom().nextFloat() * 0.4f);
    }
}
