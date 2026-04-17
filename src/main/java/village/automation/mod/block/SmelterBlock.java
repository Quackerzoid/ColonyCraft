package village.automation.mod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.blockentity.SmelterBlockEntity;

/**
 * The Smelter Block — a workplace block for the Smelter profession.
 *
 * <p>The block itself performs <em>no</em> smelting. It is a storage/staging
 * point: players (or couriers) load ore into the ore slot and fuel into the
 * fuel slot; the assigned Smelter worker pulls from those slots, smelts in
 * their AI loop, and places the result in the output slot.
 *
 * <p>GUI has a furnace-like layout:
 * <ul>
 *   <li>Top-left  — ore/input slot (blast-furnace smeltables only)</li>
 *   <li>Bottom-left — fuel slot (valid furnace fuel only)</li>
 *   <li>Right     — output slot (take-only)</li>
 * </ul>
 */
public class SmelterBlock extends WorkplaceBlock {

    public static final MapCodec<SmelterBlock> CODEC = simpleCodec(SmelterBlock::new);

    public SmelterBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends WorkplaceBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SmelterBlockEntity(pos, state);
    }
    // No ticker — the Smelter worker profession drives all smelting logic.
}
