package village.automation.mod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import village.automation.mod.blockentity.IWorkplaceBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.blockentity.WorkplaceBlockEntityBase;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import java.util.UUID;

/**
 * Abstract base for all profession workplace blocks.
 *
 * <p>Provides:
 * <ul>
 *   <li>Right-click → opens the block's GUI (block entity must implement
 *       {@link WorkplaceBlockEntityBase}, which is also a {@link net.minecraft.world.MenuProvider}).
 *   <li>{@link #onRemove} → unassigns the worker and notifies the Village Heart
 *       when the block is broken.
 *   <li>{@link #getRenderShape} → {@code MODEL} (required by {@link BaseEntityBlock}).
 * </ul>
 *
 * <p>Subclasses must supply:
 * <ul>
 *   <li>{@link #codec()} — the block's {@link com.mojang.serialization.MapCodec}.
 *   <li>{@link #newBlockEntity(BlockPos, BlockState)} — the concrete block entity.
 * </ul>
 */
public abstract class WorkplaceBlock extends BaseEntityBlock {

    protected WorkplaceBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // ── Right-click → open GUI ────────────────────────────────────────────────

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide
                && level.getBlockEntity(pos) instanceof WorkplaceBlockEntityBase wbe
                && player instanceof ServerPlayer serverPlayer) {

            serverPlayer.openMenu(wbe, buf -> {
                buf.writeBlockPos(pos);

                // Resolve worker info server-side so the client menu has it immediately
                String name = "";
                String job  = JobType.UNEMPLOYED.name();
                UUID workerUUID = wbe.getAssignedWorkerUUID();
                if (workerUUID != null && level instanceof ServerLevel serverLevel) {
                    var entity = serverLevel.getEntity(workerUUID);
                    if (entity instanceof VillagerWorkerEntity worker) {
                        name = worker.getDisplayName().getString();
                        job  = worker.getJob().name();
                    }
                }
                buf.writeUtf(name);
                buf.writeUtf(job);
            });
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // ── Cleanup on break ──────────────────────────────────────────────────────

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide
                && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof IWorkplaceBlockEntity wbe) {

            // Unassign the worker → they become unemployed and available again
            UUID workerUUID = wbe.getAssignedWorkerUUID();
            if (workerUUID != null
                    && serverLevel.getEntity(workerUUID) instanceof VillagerWorkerEntity worker) {
                worker.makeUnemployed();
            }

            // Remove this pos from the Village Heart's linked set
            BlockPos heartPos = wbe.getLinkedHeartPos();
            if (heartPos != null
                    && level.getBlockEntity(heartPos) instanceof VillageHeartBlockEntity heartBE) {
                heartBE.unlinkWorkplace(pos);
            }
        }

        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
