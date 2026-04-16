package village.automation.mod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.blockentity.FishingBlockEntity;

public class FishingBlock extends WorkplaceBlock {

    public static final MapCodec<FishingBlock> CODEC = simpleCodec(FishingBlock::new);

    public FishingBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends WorkplaceBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FishingBlockEntity(pos, state);
    }
}
