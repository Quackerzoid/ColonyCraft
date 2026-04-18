package village.automation.mod.raid;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.OminousBottleItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.VillagerWorkerEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = VillageMod.MODID)
public final class RaidEventHandler {

    // Throttle the ominous-bottle check to once per second per player
    private static final Map<UUID, Integer> playerCheckCooldown = new HashMap<>();

    // Tracks how many ticks remain before a kidnapped worker is freed (keyed by raider UUID)
    private static final Map<UUID, Integer> kidnapTimers = new HashMap<>();

    // ── Ominous Bottle trigger ────────────────────────────────────────────────

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        UUID uid = player.getUUID();
        int cooldown = playerCheckCooldown.getOrDefault(uid, 0);
        if (cooldown > 0) {
            playerCheckCooldown.put(uid, cooldown - 1);
            return;
        }
        playerCheckCooldown.put(uid, 20); // check once per second

        // Does the player hold an ominous bottle in either hand?
        ItemStack held = ItemStack.EMPTY;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack s = player.getItemInHand(hand);
            if (s.getItem() instanceof OminousBottleItem) { held = s; break; }
        }
        if (held.isEmpty()) return;

        // Find a village heart within 128 blocks whose territory contains the player
        BlockPos playerPos = player.blockPosition();
        VillageHeartBlockEntity heart = VillageHeartBlockEntity.findNearestWithin(level, playerPos, 128);
        if (heart == null) return;

        BlockPos hp = heart.getBlockPos();
        double r   = heart.getRadius();
        if (hp.distSqr(playerPos) > r * r)           return; // player outside territory
        if (heart.isRaidActive())                     return;
        if (level.getGameTime() < heart.getRaidCooldownUntil()) return;

        // Trigger the raid
        held.shrink(1);
        player.addEffect(new MobEffectInstance(MobEffects.BAD_OMEN, 200, 0, false, true));

        // Broadcast incoming title to nearby players
        Component title    = Component.literal("§c☠ RAID INCOMING ☠");
        Component subtitle = Component.literal("§7Defend your village!");
        double radius96sq  = 96.0 * 96.0;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(hp.getX(), hp.getY(), hp.getZ()) <= radius96sq) {
                p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
                p.connection.send(new ClientboundSetTitleTextPacket(title));
                p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            }
        }

        heart.startRaid(level);
    }

    // ── Mob and worker death tracking ─────────────────────────────────────────

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        Entity dead = event.getEntity();

        // Track raid-mob deaths
        if (dead.getPersistentData().getInt("ColonyCraftRaid") == 1) {
            VillageHeartBlockEntity be =
                    VillageHeartBlockEntity.findNearestWithin(level, dead.blockPosition(), 200);
            if (be != null && be.isRaidActive()) {
                if (dead.getPersistentData().getInt("SupplyKeeper") == 1) {
                    RaidSpawnHelper.spawnSupplyKeeperLoot(level, dead.blockPosition());
                }
                be.decrementRaidMobsRemaining();
                be.checkRetreatCondition(level);
                if (be.getRaidMobsRemaining() == 0 && !be.isRaidRetreating()) {
                    be.advanceWave(level);
                }
            }
            // Release any kidnapped worker riding this mob
            if (dead instanceof PathfinderMob mob) {
                mob.getPassengers().stream()
                   .filter(p -> p instanceof VillagerWorkerEntity
                           && p.getPersistentData().getBoolean("Kidnapped"))
                   .forEach(p -> {
                       p.stopRiding();
                       p.getPersistentData().remove("Kidnapped");
                   });
                kidnapTimers.remove(dead.getUUID());
            }
        }

        // Track worker deaths that might trigger raider victory
        if (dead instanceof VillagerWorkerEntity) {
            VillageHeartBlockEntity be =
                    VillageHeartBlockEntity.findNearestWithin(level, dead.blockPosition(), 200);
            if (be != null && be.isRaidActive()) {
                BlockPos hp = be.getBlockPos();
                double r    = be.getRadius();
                if (hp.distSqr(dead.blockPosition()) <= r * r) {
                    be.checkRaiderVictoryCondition(level);
                }
            }
        }
    }

    // ── Per-tick retreat navigation and kidnap timer ──────────────────────────

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Tick kidnap timers across all loaded levels
        for (ServerLevel level : event.getServer().getAllLevels()) {
            tickRetreatingMobs(level);
            tickKidnapTimers(level);
        }
    }

    private static void tickRetreatingMobs(ServerLevel level) {
        // Ensure retreating mobs keep navigating toward their retreat point
        // (vanilla nav occasionally stalls; re-issue the command if progress stops)
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof PathfinderMob mob)) continue;
            if (!mob.getPersistentData().getBoolean("Retreating")) continue;
            if (!mob.isAlive()) continue;
            if (mob.getNavigation().isInProgress()) continue;

            int rx = mob.getPersistentData().getInt("RetreatX");
            int ry = mob.getPersistentData().getInt("RetreatY");
            int rz = mob.getPersistentData().getInt("RetreatZ");
            mob.getNavigation().moveTo(rx + 0.5, ry, rz + 0.5, 1.3);
        }
    }

    private static void tickKidnapTimers(ServerLevel level) {
        Iterator<Map.Entry<UUID, Integer>> it = kidnapTimers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                it.remove();
                Entity raider = level.getEntity(entry.getKey());
                if (raider instanceof PathfinderMob mob) {
                    mob.getPassengers().stream()
                       .filter(p -> p instanceof VillagerWorkerEntity
                               && p.getPersistentData().getBoolean("Kidnapped"))
                       .forEach(p -> {
                           p.stopRiding();
                           p.getPersistentData().remove("Kidnapped");
                       });
                }
            } else {
                entry.setValue(remaining);
            }
        }
    }

    /** Called from RaidSpawnHelper.attemptKidnap to start a 600-tick kidnap timer. */
    public static void startKidnapTimer(UUID raiderUUID) {
        kidnapTimers.put(raiderUUID, 600);
    }

    private RaidEventHandler() {}
}
