package village.automation.mod.blockentity;

import net.minecraft.core.BlockPos;
import village.automation.mod.entity.JobType;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Common contract for all profession workplace block entities.
 *
 * <p>Implementing this interface allows the {@link VillageHeartBlockEntity}
 * and {@link village.automation.mod.item.VillageWandItem} to handle every
 * profession block generically, without referencing concrete block-entity
 * types.
 */
public interface IWorkplaceBlockEntity {

    /** Position of the Village Heart this block is linked to, or {@code null}. */
    @Nullable BlockPos getLinkedHeartPos();
    void setLinkedHeartPos(@Nullable BlockPos pos);
    boolean isLinked();

    /** UUID of the worker currently assigned here, or {@code null}. */
    @Nullable UUID getAssignedWorkerUUID();
    void setAssignedWorkerUUID(@Nullable UUID uuid);
    boolean hasAssignedWorker();

    /**
     * The {@link JobType} that the Village Heart assigns to a worker placed at
     * this workplace (e.g. {@code JobType.MINER} for a Mine Block).
     */
    JobType getRequiredJob();
}
