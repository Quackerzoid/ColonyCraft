package village.automation.mod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.MineBlockEntity;

import javax.annotation.Nullable;

/**
 * The Mine Block workplace — links to a Village Heart and gives the assigned
 * worker the {@link village.automation.mod.entity.JobType#MINER} job.
 *
 * <p>All GUI interaction and break-cleanup are handled by {@link WorkplaceBlock}.
 * This class additionally hooks the server-side ticker so
 * {@link MineBlockEntity#serverTick} runs every game tick.
 */
public class MineBlock extends WorkplaceBlock {

    public static final MapCodec<MineBlock> CODEC = simpleCodec(MineBlock::new);

    public MineBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends WorkplaceBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MineBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, VillageMod.MINE_BLOCK_BE.get(),
                MineBlockEntity::serverTick);
    }
}
