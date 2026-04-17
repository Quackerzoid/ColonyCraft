package village.automation.mod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.BeekeeperBlockEntity;

import javax.annotation.Nullable;

public class BeekeeperBlock extends WorkplaceBlock {

    public static final MapCodec<BeekeeperBlock> CODEC = simpleCodec(BeekeeperBlock::new);

    public BeekeeperBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends WorkplaceBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BeekeeperBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, VillageMod.BEEKEEPER_BE.get(),
                BeekeeperBlockEntity::serverTick);
    }
}
