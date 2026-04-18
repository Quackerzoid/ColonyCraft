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

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public final class RaidSpawnHelper {

    // ── Wave spawning ─────────────────────────────────────────────────────────

    /**
     * Spawns the mobs for the given wave number, registers their UUIDs with the
     * VillageHeartBlockEntity, and returns the total number of spawned mobs.
     */
    public static int spawnWave(VillageHeartBlockEntity be, ServerLevel level, int wave) {
        BlockPos hp = be.getBlockPos();
        int spawned = 0;

        switch (wave) {
            case 1 -> {
                spawned += spawnGroup(be, level, hp, EntityType.PILLAGER, 4);
                spawned += spawnGroup(be, level, hp, EntityType.VINDICATOR, 2);
                spawned += spawnCaptain(be, level, hp);
                spawned += spawnSupplyUnit(be, level, hp);
            }
            case 2 -> {
                spawned += spawnGroup(be, level, hp, EntityType.PILLAGER, 5);
                spawned += spawnMountedPillagerGroup(be, level, hp, 3);
                spawned += spawnGroup(be, level, hp, EntityType.VINDICATOR, 2);
                spawned += spawnCaptain(be, level, hp);
                spawned += spawnSupplyUnit(be, level, hp);
            }
            case 3 -> {
                spawned += spawnGroup(be, level, hp, EntityType.PILLAGER, 6);
                spawned += spawnMountedPillagerGroup(be, level, hp, 3);
                spawned += spawnGroup(be, level, hp, EntityType.VINDICATOR, 2);
                spawned += spawnGroup(be, level, hp, EntityType.EVOKER, 1);
                spawned += spawnCaptain(be, level, hp);
                spawned += spawnSupplyUnit(be, level, hp);
            }
            case 4 -> {
                spawned += spawnGroup(be, level, hp, EntityType.PILLAGER, 8);
                spawned += spawnMountedPillagerGroup(be, level, hp, 4);
                spawned += spawnGroup(be, level, hp, EntityType.EVOKER, 2);
                spawned += spawnCaptain(be, level, hp);
                spawned += spawnSupplyUnit(be, level, hp);
                spawned += spawnSupplyUnit(be, level, hp);
            }
            case 5 -> {
                spawned += spawnGroup(be, level, hp, EntityType.PILLAGER, 8);
                spawned += spawnMountedPillagerGroup(be, level, hp, 4);
                spawned += spawnGroup(be, level, hp, EntityType.WITCH, 3);
                spawned += spawnGroup(be, level, hp, EntityType.EVOKER, 2);
                spawned += spawnCaptain(be, level, hp);
                spawned += spawnSupplyUnit(be, level, hp);
                spawned += spawnSupplyUnit(be, level, hp);
            }
            case 6 -> {
                spawned += spawnGroup(be, level, hp, EntityType.PILLAGER, 12);
                spawned += spawnMountedPillagerGroup(be, level, hp, 6);
                spawned += spawnGroup(be, level, hp, EntityType.WITCH, 4);
                spawned += spawnGroup(be, level, hp, EntityType.EVOKER, 3);
                spawned += spawnWitchOnVindicator(be, level, hp);
                spawned += spawnCaptain(be, level, hp);
                spawned += spawnSupplyUnit(be, level, hp);
                spawned += spawnSupplyUnit(be, level, hp);
                spawned += spawnSupplyUnit(be, level, hp);
            }
            default -> {}
        }
        return spawned;
    }

    // ── Group helpers ─────────────────────────────────────────────────────────

    private static <T extends PathfinderMob> int spawnGroup(
            VillageHeartBlockEntity be, ServerLevel level,
            BlockPos heartPos, EntityType<T> type, int count) {
        for (int i = 0; i < count; i++) {
            BlockPos pos  = getSpawnPos(level, heartPos, 48, 80);
            T mob         = type.create(level);
            if (mob == null) continue;
            placeAndTag(mob, pos, level);
            addRaiderAI(mob, heartPos);
            be.addRaidMob(mob.getUUID());
        }
        return count;
    }

    private static int spawnMountedPillagerGroup(
            VillageHeartBlockEntity be, ServerLevel level, BlockPos heartPos, int count) {
        for (int i = 0; i < count; i++) {
            BlockPos pos    = getSpawnPos(level, heartPos, 48, 80);
            Pillager rider  = spawnMountedPillager(level, pos);
            addRaiderAI(rider, heartPos);
            be.addRaidMob(rider.getUUID());
        }
        return count;
    }

    private static int spawnCaptain(VillageHeartBlockEntity be, ServerLevel level, BlockPos heartPos) {
        BlockPos pos  = getSpawnPos(level, heartPos, 48, 80);
        Pillager cap  = spawnRaidCaptain(level, pos);
        addRaiderAI(cap, heartPos);
        be.addRaidMob(cap.getUUID());
        return 1;
    }

    private static int spawnSupplyUnit(VillageHeartBlockEntity be, ServerLevel level, BlockPos heartPos) {
        // Supply units spawn 70–80 blocks from heart (rear echelon)
        BlockPos pos          = getSpawnPos(level, heartPos, 70, 80);
        Pillager supplyKeeper = spawnSupplyUnitAt(level, pos);
        be.addRaidMob(supplyKeeper.getUUID());
        // Two donkeys near supply keeper
        for (int i = 0; i < 2; i++) {
            BlockPos dpos  = getSpawnPos(level, pos, 2, 6);
            Donkey donkey  = new Donkey(EntityType.DONKEY, level);
            placeAndTag(donkey, dpos, level);
            donkey.setChest(true);
            populateDonkeyChest(donkey, level);
            donkey.restrictTo(pos, 8);
            be.addRaidMob(donkey.getUUID());
        }
        return 3; // keeper + 2 donkeys
    }

    private static int spawnWitchOnVindicator(VillageHeartBlockEntity be, ServerLevel level, BlockPos heartPos) {
        BlockPos pos        = getSpawnPos(level, heartPos, 48, 80);
        Vindicator mount    = new Vindicator(EntityType.VINDICATOR, level);
        placeAndTag(mount, pos, level);
        addRaiderAI(mount, heartPos);
        Witch rider         = new Witch(EntityType.WITCH, level);
        placeAndTag(rider, pos, level);
        addRaiderAI(rider, heartPos);
        rider.startRiding(mount, true);
        // Track the witch; the vindicator dies when the witch despawns (it is discarded by endRaid)
        be.addRaidMob(rider.getUUID());
        be.addRaidMob(mount.getUUID());
        return 2;
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
        String name = target.getCustomName() != null
                ? target.getCustomName().getString() : "A villager";
        be.broadcastActionBar(level, "§c" + name + " has been kidnapped!", 96);
    }

    // ── AI setup ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void addRaiderAI(PathfinderMob mob, BlockPos heartPos) {
        // Target priority: Golem > Worker > Player
        mob.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(mob, SoulIronGolemEntity.class, false));
        mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(mob, VillagerWorkerEntity.class, false));
        mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, Player.class, false));

        // Melee mobs get a melee goal; ranged mobs already have their AI from registerGoals()
        if (mob instanceof Vindicator || mob instanceof Evoker) {
            mob.goalSelector.addGoal(1, new MeleeAttackGoal(mob, 1.0, true));
        }

        // Walk toward heart when no target
        mob.goalSelector.addGoal(5, new MoveToBlockPosGoal(mob, heartPos));
    }

    // ── Shared utilities ──────────────────────────────────────────────────────

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
            return mob.getTarget() == null && !mob.getNavigation().isInProgress();
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
