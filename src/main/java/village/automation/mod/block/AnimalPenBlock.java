package village.automation.mod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.blockentity.AnimalPenBlockEntity;

public class AnimalPenBlock extends WorkplaceBlock {

    public static final MapCodec<AnimalPenBlock> CODEC = simpleCodec(AnimalPenBlock::new);

    public AnimalPenBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends WorkplaceBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AnimalPenBlockEntity(pos, state);
    }
}
