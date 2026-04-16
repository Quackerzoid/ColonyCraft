package village.automation.mod.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Abstract base for all profession workplace block entities.
 *
 * <p>Handles the two fields common to every workplace:
 * <ul>
 *   <li>{@code linkedHeartPos} — which Village Heart "owns" this workplace.
 *   <li>{@code assignedWorkerUUID} — which worker is currently working here.
 * </ul>
 *
 * <p>Subclasses must supply:
 * <ul>
 *   <li>{@link IWorkplaceBlockEntity#getRequiredJob()} — the {@link village.automation.mod.entity.JobType}
 *       that gets assigned to a worker placed here.
 *   <li>{@link MenuProvider#getDisplayName()} — block name for the GUI title.
 *   <li>{@link MenuProvider#createMenu(int, net.minecraft.world.entity.player.Inventory,
 *       net.minecraft.world.entity.player.Player)} — opens the block's GUI.
 * </ul>
 *
 * <p>Subclasses that have extra inventories (e.g. {@link FarmBlockEntity}) should
 * call {@code super.saveAdditional} / {@code super.loadAdditional} and then
 * persist their own data.
 */
public abstract class WorkplaceBlockEntityBase extends BlockEntity
        implements IWorkplaceBlockEntity, MenuProvider {

    @Nullable private BlockPos linkedHeartPos    = null;
    @Nullable private UUID     assignedWorkerUUID = null;

    protected WorkplaceBlockEntityBase(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ── IWorkplaceBlockEntity ─────────────────────────────────────────────────

    @Override @Nullable
    public BlockPos getLinkedHeartPos() { return linkedHeartPos; }

    @Override
    public void setLinkedHeartPos(@Nullable BlockPos pos) {
        this.linkedHeartPos = pos;
        setChanged();
    }

    @Override
    public boolean isLinked() { return linkedHeartPos != null; }

    @Override @Nullable
    public UUID getAssignedWorkerUUID() { return assignedWorkerUUID; }

    @Override
    public void setAssignedWorkerUUID(@Nullable UUID uuid) {
        this.assignedWorkerUUID = uuid;
        setChanged();
    }

    @Override
    public boolean hasAssignedWorker() { return assignedWorkerUUID != null; }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        if (linkedHeartPos != null) {
            tag.putInt("LinkedHeartX", linkedHeartPos.getX());
            tag.putInt("LinkedHeartY", linkedHeartPos.getY());
            tag.putInt("LinkedHeartZ", linkedHeartPos.getZ());
        }
        if (assignedWorkerUUID != null) {
            tag.putUUID("AssignedWorkerUUID", assignedWorkerUUID);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        if (tag.contains("LinkedHeartX")) {
            this.linkedHeartPos = new BlockPos(
                    tag.getInt("LinkedHeartX"),
                    tag.getInt("LinkedHeartY"),
                    tag.getInt("LinkedHeartZ"));
        } else {
            this.linkedHeartPos = null;
        }

        if (tag.hasUUID("AssignedWorkerUUID")) {
            this.assignedWorkerUUID = tag.getUUID("AssignedWorkerUUID");
        } else {
            this.assignedWorkerUUID = null;
        }
    }
}
