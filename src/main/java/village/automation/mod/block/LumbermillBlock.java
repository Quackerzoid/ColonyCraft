package village.automation.mod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.blockentity.LumbermillBlockEntity;

/**
 * The Lumbermill workplace — links to a Village Heart and gives the assigned
 * worker the {@link village.automation.mod.entity.JobType#LUMBERJACK} job.
 *
 * <p>All GUI interaction (right-click → open menu) and break-cleanup
 * (unassign worker, unlink from heart) are handled by {@link WorkplaceBlock}.
 * This class only needs to supply the block entity and codec.
 */
public class LumbermillBlock extends WorkplaceBlock {

    public static final MapCodec<LumbermillBlock> CODEC = simpleCodec(LumbermillBlock::new);

    public LumbermillBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends WorkplaceBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LumbermillBlockEntity(pos, state);
    }
}
