package village.automation.mod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
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
 *
 * <p>Block states:
 * <ul>
 *   <li>{@link #FACING} — which horizontal direction the front face looks.</li>
 *   <li>{@link #LIT}    — {@code true} while the Smelter worker is actively smelting.</li>
 * </ul>
 */
public class SmelterBlock extends WorkplaceBlock {

    public static final MapCodec<SmelterBlock> CODEC = simpleCodec(SmelterBlock::new);

    /** Horizontal direction the front (blast-furnace door) texture faces. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** {@code true} while the worker is actively smelting; drives the lit model variant. */
    public static final BooleanProperty   LIT    = BlockStateProperties.LIT;

    public SmelterBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, false));
    }

    @Override
    protected MapCodec<? extends WorkplaceBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING, LIT);
    }

    /** Place the block so the front face looks toward the player. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(LIT, false);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SmelterBlockEntity(pos, state);
    }
    // No ticker — the Smelter worker profession drives all smelting logic.
}
