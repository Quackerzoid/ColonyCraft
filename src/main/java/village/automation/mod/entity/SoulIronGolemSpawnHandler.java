package village.automation.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.VillageHeartBlockEntity;

import java.util.Optional;

@EventBusSubscriber(modid = VillageMod.MODID)
public class SoulIronGolemSpawnHandler {

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!event.getState().is(VillageMod.SOUL_PUMPKIN.get())) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        BlockPos pumpkinPos = event.getPos();

        // Check both arm orientations (X-axis and Z-axis)
        boolean xArms = isIronAt(serverLevel, pumpkinPos.below(1))
                && isIronAt(serverLevel, pumpkinPos.below(2))
                && isIronAt(serverLevel, pumpkinPos.below(1).east())
                && isIronAt(serverLevel, pumpkinPos.below(1).west());

        boolean zArms = isIronAt(serverLevel, pumpkinPos.below(1))
                && isIronAt(serverLevel, pumpkinPos.below(2))
                && isIronAt(serverLevel, pumpkinPos.below(1).north())
                && isIronAt(serverLevel, pumpkinPos.below(1).south());

        if (!xArms && !zArms) return;

        // Remove all 5 blocks that form the golem pattern
        BlockPos bodyPos  = pumpkinPos.below(1);
        BlockPos legsPos  = pumpkinPos.below(2);
        serverLevel.setBlock(pumpkinPos, Blocks.AIR.defaultBlockState(), 35);
        serverLevel.setBlock(bodyPos,    Blocks.AIR.defaultBlockState(), 35);
        serverLevel.setBlock(legsPos,    Blocks.AIR.defaultBlockState(), 35);
        if (xArms) {
            serverLevel.setBlock(bodyPos.east(), Blocks.AIR.defaultBlockState(), 35);
            serverLevel.setBlock(bodyPos.west(), Blocks.AIR.defaultBlockState(), 35);
        } else {
            serverLevel.setBlock(bodyPos.north(), Blocks.AIR.defaultBlockState(), 35);
            serverLevel.setBlock(bodyPos.south(), Blocks.AIR.defaultBlockState(), 35);
        }

        // Find the nearest heart that claims this position (golem spawns unlinked if none)
        Optional<BlockPos> heartPosOpt =
                VillageHeartBlockEntity.findClaimingHeart(serverLevel, pumpkinPos, null);

        SoulIronGolemEntity golem = new SoulIronGolemEntity(VillageMod.SOUL_IRON_GOLEM.get(), serverLevel);
        double spawnX = bodyPos.getX() + 0.5;
        double spawnY = bodyPos.getY();
        double spawnZ = bodyPos.getZ() + 0.5;
        golem.moveTo(spawnX, spawnY, spawnZ, serverLevel.getRandom().nextFloat() * 360f, 0f);

        heartPosOpt.ifPresent(golem::linkToHeart);

        serverLevel.addFreshEntity(golem);

        // Summoning sound
        serverLevel.playSound(null, bodyPos, SoundEvents.IRON_GOLEM_DAMAGE,
                SoundSource.NEUTRAL, 1.0f, 0.8f);

        // Spawn 10 smoke + flame particles as a visual cue
        for (int i = 0; i < 10; i++) {
            double ox = (serverLevel.getRandom().nextDouble() - 0.5) * 1.2;
            double oy = serverLevel.getRandom().nextDouble() * 2.0;
            double oz = (serverLevel.getRandom().nextDouble() - 0.5) * 1.2;
            serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE,
                    spawnX + ox, spawnY + oy, spawnZ + oz, 1, 0, 0, 0, 0.02);
            serverLevel.sendParticles(ParticleTypes.FLAME,
                    spawnX + ox, spawnY + oy, spawnZ + oz, 1, 0, 0, 0, 0.02);
        }
    }

    private static boolean isIronAt(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.IRON_BLOCK);
    }
}
