package village.automation.mod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class SoulPumpkinBlock extends Block {

    public static final MapCodec<SoulPumpkinBlock> CODEC = simpleCodec(SoulPumpkinBlock::new);

    public SoulPumpkinBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<SoulPumpkinBlock> codec() {
        return CODEC;
    }
}
