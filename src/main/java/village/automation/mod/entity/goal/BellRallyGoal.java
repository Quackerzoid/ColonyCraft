package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Forces the worker to rally at the bell that was just rung by a player.
 *
 * <p>Registered at priority 0 so it preempts sleep (priority 1) and all
 * work goals (priority 2).  Workers scatter to random positions within
 * {@value #BELL_RADIUS} blocks of the bell, face it, and remain for 30 s
 * before resuming their normal routine.
 */
public class BellRallyGoal extends Goal {

    private static final double WALK_SPEED    = 0.7;
    private static final double BELL_RADIUS   = 4.0;  // scatter radius
    private static final double ARRIVAL_SQ    = 9.0;  // 3-block arrival threshold
    private static final int    REPATH_TICKS  = 40;   // re-navigate if still far away

    private final VillagerWorkerEntity worker;
    @Nullable private BlockPos targetPos   = null;
    private int repathTimer = 0;

    public BellRallyGoal(VillagerWorkerEntity worker) {
        this.worker = worker;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return !worker.level().isClientSide()
                && worker.getBellRallyPos() != null
                && worker.getBellRallyTimer() > 0;
    }

    @Override
    public boolean canContinueToUse() {
        return worker.getBellRallyTimer() > 0;
    }

    @Override
    public void start() {
        BlockPos bell = worker.getBellRallyPos();
        if (bell == null) return;
        targetPos   = pickScatterPos(bell);
        repathTimer = 0;
        worker.getNavigation().moveTo(
                targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, WALK_SPEED);
    }

    @Override
    public void tick() {
        worker.tickBellRallyTimer();

        BlockPos bell = worker.getBellRallyPos();
        if (bell != null) {
            worker.getLookControl().setLookAt(
                    bell.getX() + 0.5, bell.getY() + 1.0, bell.getZ() + 0.5, 30f, 30f);
        }

        if (targetPos == null) return;

        double distSq = worker.distanceToSqr(
                targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
        if (distSq <= ARRIVAL_SQ) return; // already in position

        if (++repathTimer >= REPATH_TICKS) {
            repathTimer = 0;
            worker.getNavigation().moveTo(
                    targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, WALK_SPEED);
        }
    }

    @Override
    public void stop() {
        worker.clearBellRally();
        worker.getNavigation().stop();
        targetPos = null;
    }

    private BlockPos pickScatterPos(BlockPos bell) {
        var random = worker.getRandom();
        double angle = random.nextDouble() * Math.PI * 2.0;
        double dist  = 1.5 + random.nextDouble() * (BELL_RADIUS - 1.5);
        return new BlockPos(
                (int) Math.round(bell.getX() + Math.cos(angle) * dist),
                bell.getY(),
                (int) Math.round(bell.getZ() + Math.sin(angle) * dist));
    }
}
