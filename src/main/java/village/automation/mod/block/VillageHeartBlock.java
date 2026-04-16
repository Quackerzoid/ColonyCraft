package village.automation.mod.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.VillageHeartBlockEntity;

import javax.annotation.Nullable;

public class VillageHeartBlock extends BaseEntityBlock {

    public static final MapCodec<VillageHeartBlock> CODEC = simpleCodec(VillageHeartBlock::new);

    public VillageHeartBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VillageHeartBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, VillageMod.VILLAGE_HEART_BE.get(), VillageHeartBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof VillageHeartBlockEntity villageHeart) {
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.openMenu(villageHeart, buf -> {
                        buf.writeBlockPos(pos);
                        buf.writeUtf(villageHeart.getVillageName());
                    });
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
