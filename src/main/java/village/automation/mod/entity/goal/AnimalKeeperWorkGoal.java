package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import village.automation.mod.ItemRequest;
import village.automation.mod.blockentity.AnimalPenBlockEntity;
import village.automation.mod.blockentity.IWorkplaceBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.AnimalType;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * AI goal for Animal Keeper (Shepherd) workers.
 *
 * <p>Phases:
 * <ol>
 *   <li><b>IDLE</b> — evaluates priority every 20 ticks:
 *       shear sheep → breed pair → herd unclaimed animal → signal courier for food.
 *   <li><b>HERD_APPROACH</b> — navigate to an unclaimed animal and leash it.
 *   <li><b>HERD</b> — lead the leashed animal back to the pen and release it.
 *   <li><b>BREED_APPROACH</b> — navigate to the first animal of a breeding pair.
 *   <li><b>BREED</b> — feed both animals to put them in love mode.
 *   <li><b>SHEAR_APPROACH</b> — navigate to a shearable sheep.
 *   <li><b>SHEAR</b> — shear the sheep and collect the wool into the output container.
 * </ol>
 */
public class AnimalKeeperWorkGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private static final double WALK_SPEED          = 0.6;
    private static final double REACH_SQ            = 6.25;   // 2.5 blocks
    private static final double PEN_REACH_SQ        = 16.0;   // 4 blocks for pen arrival
    private static final int    HERD_TIMEOUT        = 600;
    private static final int    APPROACH_TIMEOUT    = 300;
    private static final int    REQUEST_COOLDOWN_MAX = 600;
    private static final int    SEARCH_RADIUS       = 12;
    private static final int    VILLAGE_RADIUS      = 32;

    // ── Phase ─────────────────────────────────────────────────────────────────
    private enum Phase { IDLE, HERD_APPROACH, HERD, BREED_APPROACH, BREED, SHEAR_APPROACH, SHEAR }

    // ── State ─────────────────────────────────────────────────────────────────
    private final VillagerWorkerEntity keeper;
    private Phase phase           = Phase.IDLE;
    private int   idleTick        = 0;
    private int   approachTimeout = 0;
    private int   herdTimeout     = 0;
    private int   requestCooldown = 0;
    private int   shearCollectDelay = 0;

    /** UUID of the animal being herded or targeted for shearing/breeding. */
    @Nullable private UUID   targetAnimalUUID  = null;
    /** UUID of the second animal in a breeding pair. */
    @Nullable private UUID   breedAnimal2UUID  = null;
    /** Cached pen block entity position. */
    @Nullable private BlockPos cachedPenPos    = null;
    /** Last known position of a sheared sheep (for wool collection). */
    @Nullable private BlockPos shearPos        = null;

    public AnimalKeeperWorkGoal(VillagerWorkerEntity keeper) {
        this.keeper = keeper;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        if (requestCooldown > 0) requestCooldown--;
        if (keeper.isTooHungryToWork())                        return false;
        if (!(keeper.level() instanceof ServerLevel level))    return false;
        if (keeper.getJob() != JobType.SHEPHERD)               return false;
        if (keeper.getWorkplacePos() == null)                  return false;
        if (!isDaytime(level))                                 return false;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (keeper.isTooHungryToWork())                        return false;
        if (!(keeper.level() instanceof ServerLevel))          return false;
        if (keeper.getJob() != JobType.SHEPHERD)               return false;
        if (keeper.getWorkplacePos() == null)                  return false;
        if (!isDaytime((ServerLevel) keeper.level()))          return false;
        return true;
    }

    @Override
    public void start() {
        phase           = Phase.IDLE;
        idleTick        = 0;
        approachTimeout = 0;
        herdTimeout     = 0;
        targetAnimalUUID  = null;
        breedAnimal2UUID  = null;
    }

    @Override
    public void stop() {
        releaseLeash();
        keeper.getNavigation().stop();
        phase            = Phase.IDLE;
        targetAnimalUUID = null;
        breedAnimal2UUID = null;
        shearPos         = null;
        shearCollectDelay = 0;
    }

    @Override
    public void tick() {
        if (!(keeper.level() instanceof ServerLevel level)) return;

        switch (phase) {
            case IDLE          -> tickIdle(level);
            case HERD_APPROACH -> tickHerdApproach(level);
            case HERD          -> tickHerd(level);
            case BREED_APPROACH-> tickBreedApproach(level);
            case BREED         -> tickBreed(level);
            case SHEAR_APPROACH-> tickShearApproach(level);
            case SHEAR         -> tickShear(level);
        }
    }

    // ── IDLE ──────────────────────────────────────────────────────────────────

    private void tickIdle(ServerLevel level) {
        if (++idleTick < 20) return;
        idleTick = 0;

        BlockPos penPos = getPenPos();
        if (penPos == null) return;

        AnimalPenBlockEntity pen = getPen(level, penPos);
        if (pen == null) return;

        AnimalType animalType = pen.getTargetAnimalType();

        // ── 1. Shear sheep if applicable ─────────────────────────────────────
        if (animalType == AnimalType.SHEEP && hasShears()) {
            Sheep target = findShearableSheep(level, penPos);
            if (target != null) {
                targetAnimalUUID = target.getUUID();
                navigateTo(target.blockPosition());
                approachTimeout = APPROACH_TIMEOUT;
                phase = Phase.SHEAR_APPROACH;
                return;
            }
        }

        // ── 2. Breed if food is available ─────────────────────────────────────
        if (hasSufficientBreedingFood(pen)) {
            List<? extends Animal> pair = findBreedingPair(level, penPos, animalType);
            if (pair != null && pair.size() >= 2) {
                targetAnimalUUID = pair.get(0).getUUID();
                breedAnimal2UUID = pair.get(1).getUUID();
                navigateTo(pair.get(0).blockPosition());
                approachTimeout = APPROACH_TIMEOUT;
                phase = Phase.BREED_APPROACH;
                return;
            }
        }

        // ── 3. Herd unclaimed animal ──────────────────────────────────────────
        Animal unclaimedAnimal = findUnclaimedAnimal(level, penPos, pen, animalType);
        if (unclaimedAnimal != null) {
            targetAnimalUUID = unclaimedAnimal.getUUID();
            navigateTo(unclaimedAnimal.blockPosition());
            approachTimeout = APPROACH_TIMEOUT;
            phase = Phase.HERD_APPROACH;
            return;
        }

        // ── 4. Signal courier if breeding food is needed ──────────────────────
        List<? extends Animal> nearPen = findNearPen(level, penPos, animalType.getAnimalClass(), SEARCH_RADIUS);
        if (nearPen.size() >= 2 && !hasSufficientBreedingFood(pen)) {
            pen.setNeedsBreedingFood(true);
        }

        // ── Request shears if SHEEP type and no shears ────────────────────────
        if (animalType == AnimalType.SHEEP && !hasShears() && requestCooldown <= 0) {
            submitToolRequest(level, new ItemStack(Items.SHEARS));
            requestCooldown = REQUEST_COOLDOWN_MAX;
        }
    }

    // ── HERD_APPROACH ─────────────────────────────────────────────────────────

    private void tickHerdApproach(ServerLevel level) {
        if (targetAnimalUUID == null) { phase = Phase.IDLE; return; }

        Animal animal = getAnimalByUUID(level, targetAnimalUUID);
        if (animal == null || !animal.isAlive()) {
            targetAnimalUUID = null;
            phase = Phase.IDLE;
            return;
        }

        if (--approachTimeout <= 0) {
            targetAnimalUUID = null;
            phase = Phase.IDLE;
            return;
        }

        if (keeper.getNavigation().isDone()) {
            navigateTo(animal.blockPosition());
        }

        if (distSqTo(animal.blockPosition()) < REACH_SQ) {
            // Leash the animal
            if (animal instanceof Mob mob) {
                mob.setLeashedTo(keeper, true);
            }
            // Claim this animal in the block entity
            BlockPos penPos = getPenPos();
            if (penPos != null) {
                AnimalPenBlockEntity pen = getPen(level, penPos);
                if (pen != null) {
                    pen.addClaimedAnimal(targetAnimalUUID);
                }
            }
            // Navigate to the pen
            if (penPos != null) {
                navigateTo(penPos);
            }
            herdTimeout = HERD_TIMEOUT;
            phase = Phase.HERD;
        }
    }

    // ── HERD ─────────────────────────────────────────────────────────────────

    private void tickHerd(ServerLevel level) {
        if (targetAnimalUUID == null) { phase = Phase.IDLE; return; }

        Animal animal = getAnimalByUUID(level, targetAnimalUUID);
        if (animal == null || !animal.isAlive()) {
            targetAnimalUUID = null;
            phase = Phase.IDLE;
            return;
        }

        if (--herdTimeout <= 0) {
            releaseLeash();
            targetAnimalUUID = null;
            phase = Phase.IDLE;
            return;
        }

        BlockPos penPos = getPenPos();
        if (penPos == null) {
            releaseLeash();
            targetAnimalUUID = null;
            phase = Phase.IDLE;
            return;
        }

        // Keep navigating toward the pen
        if (keeper.getNavigation().isDone()) {
            navigateTo(penPos);
        }

        // Force the animal to follow the keeper
        if (animal instanceof Mob mob) {
            mob.getNavigation().moveTo(keeper, 1.2);
        }

        // When keeper reaches pen, release the leash
        if (distSqTo(penPos) < PEN_REACH_SQ) {
            releaseLeash();
            targetAnimalUUID = null;
            phase = Phase.IDLE;
        }
    }

    // ── BREED_APPROACH ────────────────────────────────────────────────────────

    private void tickBreedApproach(ServerLevel level) {
        if (targetAnimalUUID == null) { phase = Phase.IDLE; return; }

        Animal animal = getAnimalByUUID(level, targetAnimalUUID);
        if (animal == null || !animal.isAlive()) {
            targetAnimalUUID = null;
            breedAnimal2UUID = null;
            phase = Phase.IDLE;
            return;
        }

        if (--approachTimeout <= 0) {
            targetAnimalUUID = null;
            breedAnimal2UUID = null;
            phase = Phase.IDLE;
            return;
        }

        if (keeper.getNavigation().isDone()) {
            navigateTo(animal.blockPosition());
        }

        if (distSqTo(animal.blockPosition()) < REACH_SQ * 1.5) {
            phase = Phase.BREED;
        }
    }

    // ── BREED ─────────────────────────────────────────────────────────────────

    private void tickBreed(ServerLevel level) {
        BlockPos penPos = getPenPos();
        if (penPos == null) { phase = Phase.IDLE; return; }

        AnimalPenBlockEntity pen = getPen(level, penPos);
        if (pen == null) { phase = Phase.IDLE; return; }

        Animal a1 = targetAnimalUUID != null ? getAnimalByUUID(level, targetAnimalUUID) : null;
        Animal a2 = breedAnimal2UUID  != null ? getAnimalByUUID(level, breedAnimal2UUID)  : null;

        if (a1 == null || a2 == null || !a1.isAlive() || !a2.isAlive()) {
            targetAnimalUUID = null;
            breedAnimal2UUID = null;
            phase = Phase.IDLE;
            return;
        }

        // Consume one breeding food item from the pen's input
        SimpleContainer foodIn = pen.getBreedingFoodInput();
        ItemStack breedingStack = new ItemStack(pen.getTargetAnimalType().getBreedingFood());
        boolean consumed = false;
        for (int i = 0; i < foodIn.getContainerSize(); i++) {
            ItemStack slot = foodIn.getItem(i);
            if (!slot.isEmpty() && slot.is(breedingStack.getItem())) {
                slot.shrink(1);
                foodIn.setItem(i, slot.isEmpty() ? ItemStack.EMPTY : slot);
                consumed = true;
                break;
            }
        }

        if (!consumed) {
            // No food — reset
            targetAnimalUUID = null;
            breedAnimal2UUID = null;
            phase = Phase.IDLE;
            return;
        }

        // Put both animals in love mode
        a1.setInLove(null);
        a2.setInLove(null);

        // Play eating sound at keeper location
        level.playSound(null,
                keeper.getX(), keeper.getY(), keeper.getZ(),
                SoundEvents.GENERIC_EAT,
                SoundSource.NEUTRAL,
                0.5f, 1.0f);

        targetAnimalUUID = null;
        breedAnimal2UUID = null;
        phase = Phase.IDLE;
    }

    // ── SHEAR_APPROACH ────────────────────────────────────────────────────────

    private void tickShearApproach(ServerLevel level) {
        if (targetAnimalUUID == null) { phase = Phase.IDLE; return; }

        Animal animal = getAnimalByUUID(level, targetAnimalUUID);
        if (animal == null || !animal.isAlive()) {
            targetAnimalUUID = null;
            phase = Phase.IDLE;
            return;
        }

        if (--approachTimeout <= 0) {
            targetAnimalUUID = null;
            phase = Phase.IDLE;
            return;
        }

        if (keeper.getNavigation().isDone()) {
            navigateTo(animal.blockPosition());
        }

        if (distSqTo(animal.blockPosition()) < REACH_SQ) {
            phase = Phase.SHEAR;
        }
    }

    // ── SHEAR ─────────────────────────────────────────────────────────────────

    private void tickShear(ServerLevel level) {
        if (shearCollectDelay > 0) {
            shearCollectDelay--;
            if (shearCollectDelay == 0 && shearPos != null) {
                // Collect dropped wool items near the shear position
                collectDroppedWool(level, shearPos);
                shearPos = null;
                phase = Phase.IDLE;
            }
            return;
        }

        if (targetAnimalUUID == null) { phase = Phase.IDLE; return; }

        Animal animal = getAnimalByUUID(level, targetAnimalUUID);
        if (animal == null || !animal.isAlive()) {
            targetAnimalUUID = null;
            phase = Phase.IDLE;
            return;
        }

        if (!(animal instanceof Sheep sheep)) {
            targetAnimalUUID = null;
            phase = Phase.IDLE;
            return;
        }

        if (!sheep.readyForShearing()) {
            targetAnimalUUID = null;
            phase = Phase.IDLE;
            return;
        }

        // Shear the sheep
        shearPos = sheep.blockPosition();
        sheep.shear(SoundSource.PLAYERS);

        targetAnimalUUID = null;
        shearCollectDelay = 2;  // wait 2 ticks for the item entities to spawn
    }

    // ── Wool collection ───────────────────────────────────────────────────────

    private void collectDroppedWool(ServerLevel level, BlockPos pos) {
        BlockPos penPos = getPenPos();
        if (penPos == null) return;
        AnimalPenBlockEntity pen = getPen(level, penPos);
        if (pen == null) return;

        AABB searchBox = new AABB(pos).inflate(2.0);
        List<net.minecraft.world.entity.item.ItemEntity> items =
                level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, searchBox);

        SimpleContainer output = pen.getOutputContainer();
        for (net.minecraft.world.entity.item.ItemEntity itemEntity : items) {
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) continue;
            // Check if it's a wool-related item (wool block or carpet)
            boolean isWool = stack.is(net.minecraft.tags.ItemTags.WOOL)
                    || stack.is(net.minecraft.tags.ItemTags.WOOL_CARPETS);
            if (!isWool) continue;
            // Deposit into output
            depositIntoContainer(output, stack.copy());
            itemEntity.discard();
        }
    }

    // ── Search helpers ────────────────────────────────────────────────────────

    @Nullable
    private Sheep findShearableSheep(ServerLevel level, BlockPos penPos) {
        List<Sheep> sheep = findNearPen(level, penPos, Sheep.class, SEARCH_RADIUS);
        for (Sheep s : sheep) {
            if (s.isAlive() && s.readyForShearing()) return s;
        }
        return null;
    }

    @Nullable
    private List<? extends Animal> findBreedingPair(ServerLevel level, BlockPos penPos, AnimalType type) {
        @SuppressWarnings("unchecked")
        List<Animal> candidates = (List<Animal>) findNearPen(level, penPos, type.getAnimalClass(), SEARCH_RADIUS);
        List<Animal> eligible = new java.util.ArrayList<>();
        for (Animal a : candidates) {
            if (a.isAlive() && !a.isBaby() && !a.isInLove()) {
                eligible.add(a);
            }
            if (eligible.size() >= 2) break;
        }
        return eligible.size() >= 2 ? eligible : null;
    }

    @Nullable
    private Animal findUnclaimedAnimal(ServerLevel level, BlockPos penPos,
                                       AnimalPenBlockEntity pen, AnimalType type) {
        // Search within village radius around the heart
        BlockPos heartPos = getHeartPos(level);
        if (heartPos == null) return null;

        AABB searchBox = new AABB(heartPos).inflate(VILLAGE_RADIUS);
        @SuppressWarnings("unchecked")
        List<Animal> animals = (List<Animal>) level.getEntitiesOfClass(type.getAnimalClass(), searchBox);

        Animal nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (Animal a : animals) {
            if (!a.isAlive()) continue;
            if (pen.isClaimedAnimal(a.getUUID())) continue;
            double d = a.distanceToSqr(heartPos.getX(), heartPos.getY(), heartPos.getZ());
            if (d < bestDist) {
                bestDist = d;
                nearest = a;
            }
        }
        return nearest;
    }

    private <T extends Animal> List<T> findNearPen(ServerLevel level, BlockPos penPos,
                                                    Class<T> cls, int radius) {
        AABB box = new AABB(penPos).inflate(radius);
        return level.getEntitiesOfClass(cls, box);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private boolean hasShears() {
        return keeper.getToolContainer().getItem(0).is(Items.SHEARS);
    }

    private boolean hasSufficientBreedingFood(AnimalPenBlockEntity pen) {
        SimpleContainer foodIn = pen.getBreedingFoodInput();
        net.minecraft.world.item.Item food = pen.getTargetAnimalType().getBreedingFood();
        int count = 0;
        for (int i = 0; i < foodIn.getContainerSize(); i++) {
            ItemStack slot = foodIn.getItem(i);
            if (!slot.isEmpty() && slot.is(food)) {
                count += slot.getCount();
                if (count >= 2) return true;
            }
        }
        return false;
    }

    private void releaseLeash() {
        if (targetAnimalUUID == null) return;
        if (!(keeper.level() instanceof ServerLevel level)) return;
        Animal a = getAnimalByUUID(level, targetAnimalUUID);
        if (a instanceof Mob mob) {
            mob.dropLeash(true, false);
        }
    }

    private void submitToolRequest(ServerLevel level, ItemStack tool) {
        VillageHeartBlockEntity heart = findHeart(level);
        if (heart == null) return;
        heart.addRequest(new ItemRequest(
                keeper.getUUID(), keeper.getBaseName(), JobType.SHEPHERD, tool));
    }

    @Nullable
    private VillageHeartBlockEntity findHeart(ServerLevel level) {
        BlockPos workplace = keeper.getWorkplacePos();
        if (workplace == null) return null;
        if (!(level.getBlockEntity(workplace) instanceof IWorkplaceBlockEntity wbe)) return null;
        BlockPos heartPos = wbe.getLinkedHeartPos();
        if (heartPos == null) return null;
        return level.getBlockEntity(heartPos) instanceof VillageHeartBlockEntity h ? h : null;
    }

    @Nullable
    private BlockPos getHeartPos(ServerLevel level) {
        VillageHeartBlockEntity heart = findHeart(level);
        return heart != null ? heart.getBlockPos() : null;
    }

    @Nullable
    private BlockPos getPenPos() {
        return keeper.getWorkplacePos();
    }

    @Nullable
    private AnimalPenBlockEntity getPen(ServerLevel level, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        return be instanceof AnimalPenBlockEntity pen ? pen : null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private <T extends Animal> T getAnimalByUUID(ServerLevel level, UUID uuid) {
        var entity = level.getEntity(uuid);
        return (entity instanceof Animal a) ? (T) a : null;
    }

    private static boolean isDaytime(ServerLevel level) {
        long time = level.getDayTime() % 24000;
        return time < 13000;
    }

    private void navigateTo(BlockPos pos) {
        keeper.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, WALK_SPEED);
    }

    private double distSqTo(BlockPos pos) {
        return keeper.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static void depositIntoContainer(SimpleContainer container, ItemStack stack) {
        if (stack.isEmpty()) return;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, stack.copy());
                return;
            } else if (ItemStack.isSameItemSameComponents(slot, stack)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int move  = Math.min(space, stack.getCount());
                slot.grow(move);
                stack.shrink(move);
                if (stack.isEmpty()) return;
            }
        }
    }
}
