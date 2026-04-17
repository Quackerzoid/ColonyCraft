package village.automation.mod.entity;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared task-coordination layer for all courier golems linked to a single
 * Village Heart.
 *
 * <p>Before a courier starts any task that consumes a specific resource
 * (chest, workplace, cooking block, the smith, or a pending item request)
 * it checks that the resource is free and then claims it.  On task completion
 * or reset the claim is released so another courier can take the next job.
 *
 * <h3>Lock lifetime</h3>
 * <ul>
 *   <li><b>Explicit release</b> — {@link #release} (all locks for a courier),
 *       {@link #releaseChest} and {@link #releaseWorkplace} (single-resource
 *       release once the courier is done at that location).</li>
 *   <li><b>Automatic expiry</b> — every lock expires after
 *       {@value #LOCK_LIFETIME} ticks (30 s), which exceeds
 *       {@code CourierGoal.NAV_TIMEOUT} (400 ticks / 20 s).  This prevents
 *       permanently orphaned locks when a courier dies or gets stuck.</li>
 * </ul>
 *
 * <p>All access occurs on the single server game-thread — no synchronisation
 * is needed.
 */
public class CourierDispatcher {

    /**
     * Ticks before an unclaimed lock auto-expires.
     * Must comfortably exceed {@code CourierGoal.NAV_TIMEOUT} (400 ticks).
     */
    private static final int LOCK_LIFETIME = 600; // 30 s

    // ── Internal record ───────────────────────────────────────────────────────

    private record Lock(UUID owner, long expiresAt) {
        boolean heldBy(UUID id)   { return owner.equals(id); }
        boolean expired(long now) { return expiresAt <= now; }
    }

    private long tick = 0;

    // ── Lock tables ───────────────────────────────────────────────────────────

    /** chestPos → courier currently heading to / fetching from that chest. */
    private final Map<BlockPos, Lock> chestLocks     = new HashMap<>();
    /** workplacePos → courier currently gathering from that block's output. */
    private final Map<BlockPos, Lock> workplaceLocks = new HashMap<>();
    /** cookingPos → courier currently handling that cooking block. */
    private final Map<BlockPos, Lock> cookingLocks   = new HashMap<>();
    /** smelterPos → courier currently delivering to or picking up from that smelter. */
    private final Map<BlockPos, Lock> smelterLocks   = new HashMap<>();
    /** workerUUID → courier handling the pending item request for that worker. */
    private final Map<UUID, Lock>     requestLocks   = new HashMap<>();
    /** The single courier permitted to interact with the village smith, or null if free. */
    @Nullable
    private Lock smithLock = null;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Must be called once per server tick from
     * {@code VillageHeartBlockEntity.serverTick()}.  Advances the internal
     * clock and purges expired locks.
     */
    public void tick() {
        ++tick;
        chestLocks.entrySet().removeIf(e -> e.getValue().expired(tick));
        workplaceLocks.entrySet().removeIf(e -> e.getValue().expired(tick));
        cookingLocks.entrySet().removeIf(e -> e.getValue().expired(tick));
        smelterLocks.entrySet().removeIf(e -> e.getValue().expired(tick));
        requestLocks.entrySet().removeIf(e -> e.getValue().expired(tick));
        if (smithLock != null && smithLock.expired(tick)) smithLock = null;
    }

    /**
     * Releases every lock held by {@code courierUUID}.
     * Call this in {@code CourierGoal.resetToIdle()} and {@code stop()},
     * and from {@code VillageHeartBlockEntity.cleanDeadCouriers()}.
     */
    public void release(UUID courierUUID) {
        chestLocks.entrySet().removeIf(e -> e.getValue().heldBy(courierUUID));
        workplaceLocks.entrySet().removeIf(e -> e.getValue().heldBy(courierUUID));
        cookingLocks.entrySet().removeIf(e -> e.getValue().heldBy(courierUUID));
        smelterLocks.entrySet().removeIf(e -> e.getValue().heldBy(courierUUID));
        requestLocks.entrySet().removeIf(e -> e.getValue().heldBy(courierUUID));
        if (smithLock != null && smithLock.heldBy(courierUUID)) smithLock = null;
    }

    // ── Fine-grained releases ─────────────────────────────────────────────────

    /**
     * Releases the fetch-lock on a single chest.
     * Call once the courier has finished picking up items from that chest so
     * the position becomes immediately available to other couriers.
     */
    public void releaseChest(BlockPos pos) {
        chestLocks.remove(pos);
    }

    /**
     * Releases the gather-lock on a single workplace block.
     * Call once the courier has collected its output and moved on to deposit.
     */
    public void releaseWorkplace(BlockPos pos) {
        workplaceLocks.remove(pos);
    }

    // ── Free checks (read-only) ───────────────────────────────────────────────

    /**
     * {@code true} when no <em>other</em> courier holds a fetch-lock on this
     * chest position.  A courier that already owns the lock is still considered
     * free (it can refresh/extend its own lock).
     */
    public boolean isChestFree(BlockPos pos, UUID myId) {
        Lock l = chestLocks.get(pos);
        return l == null || l.heldBy(myId);
    }

    /** {@code true} when no other courier is gathering from this workplace block. */
    public boolean isWorkplaceFree(BlockPos pos, UUID myId) {
        Lock l = workplaceLocks.get(pos);
        return l == null || l.heldBy(myId);
    }

    /** {@code true} when no other courier is handling this cooking block. */
    public boolean isCookingFree(BlockPos pos, UUID myId) {
        Lock l = cookingLocks.get(pos);
        return l == null || l.heldBy(myId);
    }

    /** {@code true} when no other courier is currently delivering to or collecting from this smelter. */
    public boolean isSmelterFree(BlockPos pos, UUID myId) {
        Lock l = smelterLocks.get(pos);
        return l == null || l.heldBy(myId);
    }

    /** {@code true} when no other courier is currently handling the village smith. */
    public boolean isSmithFree(UUID myId) {
        return smithLock == null || smithLock.heldBy(myId);
    }

    /**
     * {@code true} when no other courier is already fetching or delivering for
     * this worker's pending item request.
     */
    public boolean isRequestFree(UUID workerUUID, UUID myId) {
        Lock l = requestLocks.get(workerUUID);
        return l == null || l.heldBy(myId);
    }

    // ── Claim operations ──────────────────────────────────────────────────────

    /** Reserves {@code pos} as the active fetch-chest for {@code courierUUID}. */
    public void claimChest(BlockPos pos, UUID courierUUID) {
        chestLocks.put(pos, new Lock(courierUUID, tick + LOCK_LIFETIME));
    }

    /** Reserves {@code pos} as the workplace being gathered by {@code courierUUID}. */
    public void claimWorkplace(BlockPos pos, UUID courierUUID) {
        workplaceLocks.put(pos, new Lock(courierUUID, tick + LOCK_LIFETIME));
    }

    /** Reserves the cooking block at {@code pos} for {@code courierUUID}. */
    public void claimCooking(BlockPos pos, UUID courierUUID) {
        cookingLocks.put(pos, new Lock(courierUUID, tick + LOCK_LIFETIME));
    }

    /** Reserves the smelter block at {@code pos} for {@code courierUUID}. */
    public void claimSmelter(BlockPos pos, UUID courierUUID) {
        smelterLocks.put(pos, new Lock(courierUUID, tick + LOCK_LIFETIME));
    }

    /**
     * Releases the smelter lock on a single block position.
     * Call once the courier has finished depositing to or collecting from that smelter.
     */
    public void releaseSmelter(BlockPos pos) {
        smelterLocks.remove(pos);
    }

    /** Reserves the village smith for {@code courierUUID}. */
    public void claimSmith(UUID courierUUID) {
        smithLock = new Lock(courierUUID, tick + LOCK_LIFETIME);
    }

    /** Reserves the pending item request for {@code workerUUID} for {@code courierUUID}. */
    public void claimRequest(UUID workerUUID, UUID courierUUID) {
        requestLocks.put(workerUUID, new Lock(courierUUID, tick + LOCK_LIFETIME));
    }
}
