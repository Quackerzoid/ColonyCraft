package village.automation.mod.entity;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Shared navigation utilities for village entities.
 *
 * <p>All village mobs (workers, couriers, soul golems) should route through
 * closed doors and fence gates rather than treating them as solid obstacles.
 * Call {@link #createOpenDoorsNavigation} from {@code createNavigation(Level)}
 * in each entity class.
 */
public final class VillageNavigation {

    private VillageNavigation() {}

    /**
     * Creates a {@link GroundPathNavigation} whose {@link WalkNodeEvaluator}
     * has {@code canPassDoors = true}, so closed doors and fence gates are
     * treated as walkable nodes during path-finding.
     *
     * <p>The evaluator is set at {@code createPathFinder()} time (inside the
     * navigation constructor) — the only moment guaranteed to work before any
     * path is first calculated.
     */
    public static PathNavigation createOpenDoorsNavigation(Mob mob, Level level) {
        return new GroundPathNavigation(mob, level) {
            @Override
            protected PathFinder createPathFinder(int maxVisitedNodes) {
                WalkNodeEvaluator eval = new WalkNodeEvaluator();
                eval.setCanPassDoors(true);
                this.nodeEvaluator = eval;
                return new PathFinder(eval, maxVisitedNodes);
            }
        };
    }
}
