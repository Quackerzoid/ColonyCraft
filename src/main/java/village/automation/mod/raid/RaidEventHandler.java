package village.automation.mod.raid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = VillageMod.MODID)
public final class RaidEventHandler {

    // Throttle the ominous-bottle check to once per second per player
    private static final Map<UUID, Integer> playerCheckCooldown = new HashMap<>();

    // Tracks how many ticks remain before a kidnapped worker is freed (keyed by raider UUID)
    private static final Map<UUID, Integer> kidnapTimers = new HashMap<>();

    // Gearing-up state: mob UUID → ticks remaining
    private static final Map<UUID, Integer> gearingUpMobs = new HashMap<>();
    // Per-heart countdown: heart BlockPos → ticks until "ATTACK!" (300 = 15 s)
    private static final Map<BlockPos, Integer> waveCountdown = new HashMap<>();

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

        // ── Raid-mob death ────────────────────────────────────────────────────
        if (dead.getPersistentData().getInt("ColonyCraftRaid") == 1) {
            VillageHeartBlockEntity be =
                    VillageHeartBlockEntity.findNearestWithin(level, dead.blockPosition(), 200);
            if (be != null && be.isRaidActive()) {
                boolean isSupply = dead.getPersistentData().getInt("SupplyKeeper") == 1;
                boolean isDonkey = dead instanceof AbstractChestedHorse;

                if (isSupply) {
                    RaidSpawnHelper.spawnSupplyKeeperLoot(level, dead.blockPosition());
                    be.onSupplyKeeperKilled(level);
                } else if (!isDonkey) {
                    be.onCombatMobKilled(level);
                }
            }
            // Release any kidnapped worker carried by this mob
            if (dead instanceof PathfinderMob mob) {
                mob.getPassengers().stream()
                   .filter(p -> p instanceof VillagerWorkerEntity
                           && p.getPersistentData().getBoolean("Kidnapped"))
                   .forEach(p -> {
                       p.stopRiding();
                       p.getPersistentData().remove("Kidnapped");
                   });
                kidnapTimers.remove(dead.getUUID());
                gearingUpMobs.remove(dead.getUUID());
            }
        }

        // ── Worker death inside territory — morale rises for raiders ──────────
        if (dead instanceof VillagerWorkerEntity) {
            VillageHeartBlockEntity be =
                    VillageHeartBlockEntity.findNearestWithin(level, dead.blockPosition(), 200);
            if (be != null && be.isRaidActive()) {
                BlockPos hp = be.getBlockPos();
                double r    = be.getRadius();
                // Skip if already counted as kidnapped (onWorkerLost was called at kidnap time)
                if (hp.distSqr(dead.blockPosition()) <= r * r
                        && !dead.getPersistentData().getBoolean("Kidnapped")) {
                    be.onWorkerLost(level);
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
            tickGearingUpMobs(level);
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

            Entity e = level.getEntity(entry.getKey());
            if (!(e instanceof PathfinderMob raider) || !raider.isAlive()) {
                it.remove();
                continue;
            }

            // Find the kidnapped passenger (if still present)
            VillagerWorkerEntity victim = raider.getPassengers().stream()
                    .filter(p -> p instanceof VillagerWorkerEntity
                            && p.getPersistentData().getBoolean("Kidnapped"))
                    .map(p -> (VillagerWorkerEntity) p)
                    .findFirst().orElse(null);

            if (victim == null) {
                // Passenger was already released or fell off
                it.remove();
                continue;
            }

            // If raider has crossed 2× the territory radius, the escape is complete:
            // kill the villager normally (triggers soul conversion etc.) and remove the raider.
            VillageHeartBlockEntity be =
                    VillageHeartBlockEntity.findNearestWithin(level, raider.blockPosition(), 600);
            if (be != null) {
                BlockPos hp      = be.getBlockPos();
                double escapeR   = be.getRadius() * 2.0;
                double distSq    = raider.distanceToSqr(hp.getX(), hp.getY(), hp.getZ());
                if (distSq > escapeR * escapeR) {
                    victim.stopRiding();
                    victim.getPersistentData().remove("Kidnapped");
                    victim.hurt(level.damageSources().generic(), Float.MAX_VALUE);
                    raider.discard();
                    it.remove();
                    continue;
                }
            }

            // Countdown: release the worker when the timer expires
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                victim.stopRiding();
                victim.getPersistentData().remove("Kidnapped");
                it.remove();
            } else {
                entry.setValue(remaining);
            }
        }
    }

    /** Called from RaidSpawnHelper.attemptKidnap to start a 600-tick kidnap timer. */
    public static void startKidnapTimer(UUID raiderUUID) {
        kidnapTimers.put(raiderUUID, 600);
    }

    // ── Gearing-up system ─────────────────────────────────────────────────────

    /** Called from RaidSpawnHelper after spawning a wave's combat mobs. */
    public static void startGearingUp(List<UUID> mobs, VillageHeartBlockEntity be, ServerLevel level) {
        BlockPos hp = be.getBlockPos().immutable();
        for (UUID uuid : mobs) gearingUpMobs.put(uuid, 300);
        waveCountdown.put(hp, 300);
        be.setWaveGearingUp(true);
        be.broadcastActionBar(level, "§6⚒ The raiders are gearing up...", 96);
    }

    /** Called from VillageHeartBlockEntity.endRaid to clean up any active gearing-up state. */
    public static void clearGearingUp(VillageHeartBlockEntity be) {
        waveCountdown.remove(be.getBlockPos().immutable());
        for (UUID uuid : be.getRaidMobUUIDs()) gearingUpMobs.remove(uuid);
    }

    private static void tickGearingUpMobs(ServerLevel level) {
        // Freeze individual mobs and emit particles while they gear up
        Iterator<Map.Entry<UUID, Integer>> mobIt = gearingUpMobs.entrySet().iterator();
        while (mobIt.hasNext()) {
            Map.Entry<UUID, Integer> entry = mobIt.next();
            Entity e = level.getEntity(entry.getKey());
            if (e == null || !e.isAlive()) { mobIt.remove(); continue; }
            if (!(e instanceof PathfinderMob mob)) { mobIt.remove(); continue; }

            mob.getNavigation().stop();
            mob.setTarget(null);

            if (entry.getValue() % 20 == 0) {
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE,
                        mob.getX(), mob.getY() + 1.0, mob.getZ(), 2, 0.4, 0.3, 0.4, 0.01);
            }

            int rem = entry.getValue() - 1;
            if (rem <= 0) {
                mobIt.remove();
                mob.getPersistentData().remove("GearingUp");
            } else {
                entry.setValue(rem);
            }
        }

        // Per-heart countdown — broadcast ATTACK! when it hits zero
        Iterator<Map.Entry<BlockPos, Integer>> heartIt = waveCountdown.entrySet().iterator();
        while (heartIt.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = heartIt.next();
            int rem = entry.getValue() - 1;
            if (rem <= 0) {
                heartIt.remove();
                if (level.getBlockEntity(entry.getKey()) instanceof VillageHeartBlockEntity be) {
                    be.setWaveGearingUp(false);
                    be.broadcastActionBar(level, "§c☠ ATTACK! ☠", 96);
                    // If mobs died during gearing-up and already pushed morale to 0, trigger now
                    if (be.isRaidActive() && !be.isRaidRetreating() && be.getRaidMorale() <= 0f) {
                        be.adjustMorale(0f, level);
                    }
                }
            } else {
                entry.setValue(rem);
            }
        }
    }

    private RaidEventHandler() {}
}
