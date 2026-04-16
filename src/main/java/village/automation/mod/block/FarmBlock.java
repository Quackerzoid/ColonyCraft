package village.automation.mod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.blockentity.FarmBlockEntity;

/**
 * The Farm Block workplace — links to a Village Heart and gives the assigned
 * worker the {@link village.automation.mod.entity.JobType#FARMER} job.
 *
 * <p>All interaction (right-click → GUI, break → cleanup) is handled by
 * {@link WorkplaceBlock}.  This class only needs to supply the block entity
 * and codec.
 */
public class FarmBlock extends WorkplaceBlock {

    public static final MapCodec<FarmBlock> CODEC = simpleCodec(FarmBlock::new);

    public FarmBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends WorkplaceBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FarmBlockEntity(pos, state);
    }
}
