package village.automation.mod.raid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.animal.horse.Donkey;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Variant;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.SoulIronGolemEntity;
import village.automation.mod.entity.VillagerWorkerEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public final class RaidSpawnHelper {

    // ── Wave spawning ─────────────────────────────────────────────────────────

    /**
     * Spawns supply units first (rear echelon), then clusters all combat mobs near the
     * first supply keeper for the 15-second "Gearing Up" phase.
     * Returns the total number of tracked mobs (supply + combat).
     */
    public static void spawnWave(VillageHeartBlockEntity be, ServerLevel level, int wave) {
        BlockPos hp = be.getBlockPos();

        // ── Supply unit: reuse the existing keeper on waves 2+; spawn fresh on wave 1 ──
        BlockPos rally = null;
        for (UUID uuid : be.getRaidMobUUIDs()) {
            Entity e = level.getEntity(uuid);
            if (e instanceof PathfinderMob mob && mob.isAlive()
                    && mob.getPersistentData().getInt("SupplyKeeper") == 1) {
                rally = mob.blockPosition();
                break;
            }
        }
        if (rally == null) {
            // Wave 1 (or fallback): spawn one supply keeper + 2 donkeys
            BlockPos sPos = getSpawnPos(level, hp, 70, 80);
            Pillager keeper = spawnSupplyUnitAt(level, sPos);
            be.addRaidMob(keeper.getUUID());
            for (int d = 0; d < 2; d++) {
                BlockPos dPos = getSpawnPosNear(level, sPos, 6);
                Donkey donkey = new Donkey(EntityType.DONKEY, level);
                placeAndTag(donkey, dPos, level);
                donkey.setChest(true);
                populateDonkeyChest(donkey, level);
                donkey.restrictTo(sPos, 8);
                be.addRaidMob(donkey.getUUID());
            }
            rally = sPos;
        }

        // ── Combat mobs — gather near the supply keeper ───────────────────────
        List<UUID> combatUUIDs = new ArrayList<>();

        switch (wave) {
            case 1 -> {
                spawnGroupNear(be, level, rally, hp, EntityType.PILLAGER,   4, combatUUIDs);
                spawnGroupNear(be, level, rally, hp, EntityType.VINDICATOR, 2, combatUUIDs);
                spawnCaptainNear(be, level, rally, hp, combatUUIDs);
            }
            case 2 -> {
                spawnGroupNear(be, level, rally, hp, EntityType.PILLAGER,   5, combatUUIDs);
                spawnMountedNear(be, level, rally, hp, 3, combatUUIDs);
                spawnGroupNear(be, level, rally, hp, EntityType.VINDICATOR, 2, combatUUIDs);
                spawnCaptainNear(be, level, rally, hp, combatUUIDs);
            }
            case 3 -> {
                spawnGroupNear(be, level, rally, hp, EntityType.PILLAGER,   6, combatUUIDs);
                spawnMountedNear(be, level, rally, hp, 3, combatUUIDs);
                spawnGroupNear(be, level, rally, hp, EntityType.VINDICATOR, 2, combatUUIDs);
                spawnGroupNear(be, level, rally, hp, EntityType.EVOKER,     1, combatUUIDs);
                spawnCaptainNear(be, level, rally, hp, combatUUIDs);
            }
            case 4 -> {
                spawnGroupNear(be, level, rally, hp, EntityType.PILLAGER,   8, combatUUIDs);
                spawnMountedNear(be, level, rally, hp, 4, combatUUIDs);
                spawnGroupNear(be, level, rally, hp, EntityType.EVOKER,     2, combatUUIDs);
                spawnCaptainNear(be, level, rally, hp, combatUUIDs);
            }
            case 5 -> {
                spawnGroupNear(be, level, rally, hp, EntityType.PILLAGER,   8, combatUUIDs);
                spawnMountedNear(be, level, rally, hp, 4, combatUUIDs);
                spawnGroupNear(be, level, rally, hp, EntityType.WITCH,      3, combatUUIDs);
                spawnGroupNear(be, level, rally, hp, EntityType.EVOKER,     2, combatUUIDs);
                spawnCaptainNear(be, level, rally, hp, combatUUIDs);
            }
            case 6 -> {
                spawnGroupNear(be, level, rally, hp, EntityType.PILLAGER,   12, combatUUIDs);
                spawnMountedNear(be, level, rally, hp, 6, combatUUIDs);
                spawnGroupNear(be, level, rally, hp, EntityType.WITCH,       4, combatUUIDs);
                spawnGroupNear(be, level, rally, hp, EntityType.EVOKER,      3, combatUUIDs);
                spawnWitchOnVindicatorNear(be, level, rally, hp, combatUUIDs);
                spawnCaptainNear(be, level, rally, hp, combatUUIDs);
            }
            default -> {}
        }

        // Register combat count and start gearing-up (freezes mobs for 15 s)
        be.setCombatMobsThisWave(combatUUIDs.size());
        if (!combatUUIDs.isEmpty()) {
            RaidEventHandler.startGearingUp(combatUUIDs, be, level);
        }
    }

    // ── Group helpers (rally-point variants) ─────────────────────────────────

    private static <T extends PathfinderMob> void spawnGroupNear(
            VillageHeartBlockEntity be, ServerLevel level,
            BlockPos rally, BlockPos heartPos,
            EntityType<T> type, int count, List<UUID> gearList) {
        for (int i = 0; i < count; i++) {
            BlockPos pos = getSpawnPosNear(level, rally, 12);
            T mob        = type.create(level);
            if (mob == null) continue;
            placeAndTag(mob, pos, level);
            addRaiderAI(mob, heartPos);   // also sets GearingUp tag
            be.addRaidMob(mob.getUUID());
            gearList.add(mob.getUUID());
        }
    }

    private static void spawnMountedNear(
            VillageHeartBlockEntity be, ServerLevel level,
            BlockPos rally, BlockPos heartPos, int count, List<UUID> gearList) {
        for (int i = 0; i < count; i++) {
            BlockPos pos   = getSpawnPosNear(level, rally, 12);
            Pillager rider = spawnMountedPillager(level, pos);
            addRaiderAI(rider, heartPos);
            be.addRaidMob(rider.getUUID());
            gearList.add(rider.getUUID());
        }
    }

    private static void spawnCaptainNear(
            VillageHeartBlockEntity be, ServerLevel level,
            BlockPos rally, BlockPos heartPos, List<UUID> gearList) {
        BlockPos pos  = getSpawnPosNear(level, rally, 12);
        Pillager cap  = spawnRaidCaptain(level, pos);
        addRaiderAI(cap, heartPos);
        be.addRaidMob(cap.getUUID());
        gearList.add(cap.getUUID());
    }

    private static void spawnWitchOnVindicatorNear(
            VillageHeartBlockEntity be, ServerLevel level,
            BlockPos rally, BlockPos heartPos, List<UUID> gearList) {
        BlockPos pos     = getSpawnPosNear(level, rally, 12);
        Vindicator mount = new Vindicator(EntityType.VINDICATOR, level);
        placeAndTag(mount, pos, level);
        addRaiderAI(mount, heartPos);
        Witch rider = new Witch(EntityType.WITCH, level);
        placeAndTag(rider, pos, level);
        addRaiderAI(rider, heartPos);
        rider.startRiding(mount, true);
        be.addRaidMob(rider.getUUID());
        be.addRaidMob(mount.getUUID());
        gearList.add(rider.getUUID());
        gearList.add(mount.getUUID());
    }

    // ── Named spawn helpers ───────────────────────────────────────────────────

    /** Spawns a Pillager riding a black Horse. Returns the Pillager (the tracked mob). */
    public static Pillager spawnMountedPillager(ServerLevel level, BlockPos pos) {
        Horse horse = new Horse(EntityType.HORSE, level);
        placeAndTag(horse, pos, level);
        horse.setTamed(true);
        horse.setVariant(Variant.BLACK);

        Pillager pillager = new Pillager(EntityType.PILLAGER, level);
        placeAndTag(pillager, pos, level);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        enchant(crossbow, level, Enchantments.QUICK_CHARGE, 3);
        enchant(crossbow, level, Enchantments.MULTISHOT, 1);
        pillager.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
        tagMob(pillager);

        pillager.startRiding(horse, true);
        return pillager;
    }

    /** Spawns a Raid Captain Pillager with enhanced stats and gear. */
    public static Pillager spawnRaidCaptain(ServerLevel level, BlockPos pos) {
        Pillager cap = new Pillager(EntityType.PILLAGER, level);
        placeAndTag(cap, pos, level);

        cap.setCustomName(Component.literal("§c§lRaid Captain"));
        cap.setCustomNameVisible(true);

        var health = cap.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) health.setBaseValue(60.0);
        cap.setHealth(60.0f);

        var atk = cap.getAttribute(Attributes.ATTACK_DAMAGE);
        if (atk != null) atk.setBaseValue(atk.getBaseValue() * 1.5);

        var spd = cap.getAttribute(Attributes.MOVEMENT_SPEED);
        if (spd != null) spd.setBaseValue(spd.getBaseValue() * 1.3);

        cap.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        cap.setItemSlot(EquipmentSlot.HEAD,  new ItemStack(Items.IRON_HELMET));

        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        enchant(crossbow, level, Enchantments.QUICK_CHARGE, 3);
        enchant(crossbow, level, Enchantments.MULTISHOT,    1);
        enchant(crossbow, level, Enchantments.PIERCING,     4);
        cap.setItemSlot(EquipmentSlot.MAINHAND, crossbow);

        cap.addEffect(new MobEffectInstance(MobEffects.GLOWING, 99999, 0, true, false));

        cap.getPersistentData().putInt("RaidCaptain", 1);
        return cap;
    }

    /**
     * Spawns a Supply Keeper (unarmed Pillager) plus 2 donkeys.
     * Returns the Supply Keeper. Donkeys are handled by the caller.
     */
    static Pillager spawnSupplyUnitAt(ServerLevel level, BlockPos pos) {
        Pillager keeper = new Pillager(EntityType.PILLAGER, level);
        placeAndTag(keeper, pos, level);

        keeper.setCustomName(Component.literal("§6Supply Keeper"));
        keeper.setCustomNameVisible(true);
        keeper.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        keeper.getPersistentData().putInt("SupplyKeeper", 1);

        // Strip all AI, leave only float + restricted wander
        keeper.goalSelector.removeAllGoals(g -> true);
        keeper.targetSelector.removeAllGoals(g -> true);
        keeper.goalSelector.addGoal(0, new FloatGoal(keeper));
        keeper.goalSelector.addGoal(1, new WaterAvoidingRandomStrollGoal(keeper, 0.6));
        keeper.restrictTo(pos, 8);

        return keeper;
    }

    // ── Kidnap mechanic ───────────────────────────────────────────────────────

    /** Attempts a kidnap by the given raider. Safe to call if already attempted. */
    public static void attemptKidnap(PathfinderMob raider, VillageHeartBlockEntity be, ServerLevel level) {
        if (raider.getPersistentData().getInt("KidnapAttempted") == 1) return;
        raider.getPersistentData().putInt("KidnapAttempted", 1);

        VillagerWorkerEntity target = level.getEntitiesOfClass(
                        VillagerWorkerEntity.class,
                        new AABB(raider.blockPosition()).inflate(12),
                        w -> w.isAlive() && !w.getPersistentData().getBoolean("Kidnapped"))
                .stream()
                .min(Comparator.comparingDouble(w -> w.distanceToSqr(raider)))
                .orElse(null);

        if (target == null) return;

        target.startRiding(raider, true);
        target.getPersistentData().putBoolean("Kidnapped", true);
        RaidEventHandler.startKidnapTimer(raider.getUUID());
        be.onWorkerLost(level); // count the kidnap as a permanent loss immediately

        // Carrying a villager slows the raider by 50%
        var speedAttr = raider.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null) speedAttr.setBaseValue(speedAttr.getBaseValue() * 0.5);

        String name = target.getCustomName() != null
                ? target.getCustomName().getString() : "A villager";
        be.broadcastActionBar(level, "§c" + name + " has been kidnapped!", 96);
    }

    // ── AI setup ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void addRaiderAI(PathfinderMob mob, BlockPos heartPos) {
        // Mark as gearing up — cleared by RaidEventHandler after 15 s
        mob.getPersistentData().putBoolean("GearingUp", true);

        // Target priority: Golem > Worker > Player
        mob.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(mob, SoulIronGolemEntity.class, false));
        mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(mob, VillagerWorkerEntity.class, false));
        mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, Player.class, false));

        // Melee mobs get a melee goal; ranged mobs already have their AI from registerGoals()
        if (mob instanceof Vindicator || mob instanceof Evoker) {
            mob.goalSelector.addGoal(1, new MeleeAttackGoal(mob, 1.0, true));
        }

        // Walk toward heart when no target (blocked during GearingUp and Retreating)
        mob.goalSelector.addGoal(5, new MoveToBlockPosGoal(mob, heartPos));
    }

    // ── Shared utilities ──────────────────────────────────────────────────────

    /** Picks a random surface BlockPos within maxDist blocks of origin (used for clustering). */
    public static BlockPos getSpawnPosNear(ServerLevel level, BlockPos origin, int maxDist) {
        var rng   = level.getRandom();
        double ang = rng.nextDouble() * 2 * Math.PI;
        double d   = rng.nextDouble() * maxDist;
        int tx = (int)(origin.getX() + Math.cos(ang) * d);
        int tz = (int)(origin.getZ() + Math.sin(ang) * d);
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(tx, 0, tz));
    }

    /** Picks a random surface BlockPos on the perimeter, minDist–maxDist from origin. */
    public static BlockPos getSpawnPos(ServerLevel level, BlockPos origin, int minDist, int maxDist) {
        var rng   = level.getRandom();
        double ang = rng.nextDouble() * 2 * Math.PI;
        double d   = minDist + rng.nextDouble() * (maxDist - minDist);
        int tx = (int)(origin.getX() + Math.cos(ang) * d);
        int tz = (int)(origin.getZ() + Math.sin(ang) * d);
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(tx, 0, tz));
    }

    private static void placeAndTag(PathfinderMob mob, BlockPos pos, ServerLevel level) {
        mob.setPersistenceRequired();
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.getRandom().nextFloat() * 360f, 0f);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.MOB_SUMMONED, null);
        tagMob(mob);
        level.addFreshEntity(mob);
    }

    private static void placeAndTag(Donkey donkey, BlockPos pos, ServerLevel level) {
        donkey.setPersistenceRequired();
        donkey.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.getRandom().nextFloat() * 360f, 0f);
        donkey.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.MOB_SUMMONED, null);
        donkey.getPersistentData().putInt("ColonyCraftRaid", 1);
        level.addFreshEntity(donkey);
    }

    private static void tagMob(Entity mob) {
        mob.getPersistentData().putInt("ColonyCraftRaid", 1);
    }

    private static void populateDonkeyChest(Donkey donkey, ServerLevel level) {
        var rng = level.getRandom();
        Container inv = donkey.getInventory();
        if (inv.getContainerSize() < 6) return;
        inv.setItem(2, new ItemStack(Items.EMERALD, 1 + rng.nextInt(3)));
        if (rng.nextBoolean()) inv.setItem(3, new ItemStack(Items.ARROW,     4 + rng.nextInt(12)));
        if (rng.nextBoolean()) inv.setItem(4, new ItemStack(Items.IRON_INGOT, 1 + rng.nextInt(4)));
        if (rng.nextBoolean()) inv.setItem(5, new ItemStack(Items.BREAD,      2 + rng.nextInt(4)));
    }

    private static void enchant(ItemStack stack, ServerLevel level,
                                ResourceKey<Enchantment> key, int lvl) {
        level.registryAccess()
             .lookup(Registries.ENCHANTMENT)
             .flatMap(reg -> reg.get(key))
             .ifPresent(holder -> stack.enchant(holder, lvl));
    }

    // ── Bonus supply-keeper death drops ──────────────────────────────────────

    public static void spawnSupplyKeeperLoot(ServerLevel level, BlockPos pos) {
        var rng   = level.getRandom();
        int count = 2 + rng.nextInt(3); // 2–4 items
        List<ItemStack> pool = List.of(
                new ItemStack(Items.EMERALD,    1 + rng.nextInt(3)),
                new ItemStack(Items.ARROW,      4 + rng.nextInt(8)),
                new ItemStack(Items.CROSSBOW),
                new ItemStack(Items.IRON_INGOT, 1 + rng.nextInt(4)),
                new ItemStack(Items.BREAD,      1 + rng.nextInt(3))
        );
        for (int i = 0; i < count; i++) {
            ItemStack drop = pool.get(rng.nextInt(pool.size())).copy();
            level.addFreshEntity(new ItemEntity(level,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop));
        }
    }

    // ── Inner goal ────────────────────────────────────────────────────────────

    static final class MoveToBlockPosGoal extends Goal {
        private final PathfinderMob mob;
        private final BlockPos target;

        MoveToBlockPosGoal(PathfinderMob mob, BlockPos target) {
            this.mob    = mob;
            this.target = target;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override public boolean canUse() {
            // Blocked while gearing up or retreating so those systems own navigation
            return mob.getTarget() == null
                    && !mob.getPersistentData().getBoolean("GearingUp")
                    && !mob.getPersistentData().getBoolean("Retreating")
                    && !mob.getNavigation().isInProgress();
        }

        @Override public boolean canContinueToUse() {
            return mob.getTarget() == null && mob.getNavigation().isInProgress();
        }

        @Override public void start() {
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
        }

        @Override public void stop() {
            mob.getNavigation().stop();
        }
    }

    private RaidSpawnHelper() {}
}
