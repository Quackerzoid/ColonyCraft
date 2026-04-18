package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.ItemRequest;
import village.automation.mod.blockentity.FarmBlockEntity;
import village.automation.mod.blockentity.IWorkplaceBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * Three-phase AI goal for Farmer workers.
 *
 * <p>Phases:
 * <ol>
 *   <li><b>HARVEST</b> — walk to a mature crop; swing for
 *       {@link VillagerWorkerEntity#getWorkSwings()} ticks before breaking it.
 *       A chance ({@link VillagerWorkerEntity#getFarmlandDestroyChance()}) exists
 *       that the farmland is accidentally damaged to dirt — if that happens the
 *       goal transitions to RETILL before continuing.
 *   <li><b>RETILL</b> — walk to the damaged dirt and swing for
 *       {@code getWorkSwings()} ticks to restore it to farmland.
 *   <li><b>PLANT</b> — walk to empty farmland and swing for
 *       {@code getWorkSwings()} ticks before placing a seed.
 *   <li><b>FETCH</b> — walk to the Farm Block and pull seeds into the
 *       worker's personal inventory, then switch to PLANT.
 * </ol>
 */
public class FarmerWorkGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private static final int    SCAN_INTERVAL = 40;
    private static final int    TASK_TIMEOUT  = 400;  // extra headroom for multi-swing actions
    private static final int    SEARCH_RADIUS = 4;
    private static final int    SEARCH_Y      = 3;
    private static final double REACH_SQ      = 4.0;
    private static final double FETCH_SQ      = 9.0;
    private static final double WALK_SPEED    = 0.7;
    /**
     * Ticks between each arm-swing animation.
     * Each swing counts as one unit toward {@link VillagerWorkerEntity#getWorkSwings()},
     * so the total action time = swings × this interval.
     * At level 0 (10 swings × 8 ticks) = 80 ticks (4 s) per harvest/plant.
     * At level 20 (1 swing  × 8 ticks) = 8 ticks (0.4 s) per harvest/plant.
     */
    private static final int    SWING_ANIM_INTERVAL = 8;

    // ── State ─────────────────────────────────────────────────────────────────
    private enum Phase { FETCH, HARVEST, RETILL, PLANT }

    private final VillagerWorkerEntity farmer;
    private Phase    phase;
    @Nullable private BlockPos targetPos;
    private int      scanCooldown    = 0;
    private int      timeout         = 0;
    private int      requestCooldown = 0;
    /** Counter incremented each tick while the farmer is at a target; action fires when it reaches getWorkSwings(). */
    private int      swingCounter    = 0;
    /** Ticks between arm-swing animations. */
    private int      swingAnimTimer  = 0;

    public FarmerWorkGoal(VillagerWorkerEntity farmer) {
        this.farmer = farmer;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    // ── Goal lifecycle ────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        if (requestCooldown > 0) requestCooldown--;
        if (!farmer.level().isDay()) return false;
        if (farmer.isTooHungryToWork()) return false;
        if (scanCooldown > 0) { scanCooldown--; return false; }
        scanCooldown = SCAN_INTERVAL;

        if (!(farmer.level() instanceof ServerLevel level)) return false;
        if (farmer.getJob() != JobType.FARMER) return false;
        if (!hasHoeEquipped()) {
            if (requestCooldown <= 0) {
                submitToolRequest(level, new ItemStack(Items.IRON_HOE));
                requestCooldown = 600;
            }
            return false;
        }
        resolveToolRequest(level);

        BlockPos workplace = farmer.getWorkplacePos();
        if (workplace == null) return false;

        // Priority 1: harvest mature crops
        BlockPos matureCrop = findMatureCrop(level, workplace);
        if (matureCrop != null) {
            targetPos = matureCrop;
            phase = Phase.HARVEST;
            return true;
        }

        // Priority 2: retill any dirt in the farm area (from accidental farmland destruction)
        BlockPos dirtPos = findDirtToRetill(level, workplace);
        if (dirtPos != null) {
            targetPos = dirtPos;
            phase = Phase.RETILL;
            return true;
        }

        // Priority 3: plant seeds from personal inventory
        if (hasSeedsInInventory()) {
            targetPos = findEmptyFarmland(level, workplace);
            if (targetPos != null) {
                phase = Phase.PLANT;
                return true;
            }
        }

        // Priority 4: fetch seeds from the Farm Block
        FarmBlockEntity farmBE = getFarmBlock(level, workplace);
        if (farmBE != null && containerHasSeeds(farmBE.getSeedContainer())
                && findEmptyFarmland(level, workplace) != null) {
            targetPos = workplace;
            phase = Phase.FETCH;
            return true;
        }

        return false;
    }

    @Override
    public void start() {
        timeout = TASK_TIMEOUT;
        resetSwing();
        navigateTo(targetPos);
    }

    @Override
    public boolean canContinueToUse() {
        if (!farmer.level().isDay()) return false;
        if (farmer.isTooHungryToWork()) return false;
        if (!(farmer.level() instanceof ServerLevel)) return false;
        if (farmer.getJob() != JobType.FARMER) return false;
        if (!hasHoeEquipped()) return false;
        if (targetPos == null) return false;
        return --timeout > 0;
    }

    @Override
    public void tick() {
        if (targetPos == null || !(farmer.level() instanceof ServerLevel level)) return;

        double distSq = farmer.distanceToSqr(
                targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

        switch (phase) {

            case HARVEST -> {
                if (distSq < REACH_SQ) {
                    // Accumulate swings; act only once the required count is reached
                    if (!tickSwing() || swingCounter < farmer.getWorkSwings()) return;
                    resetSwing();

                    BlockPos retillPos = harvestCrop(level, targetPos);
                    farmer.gainXp(1);

                    if (retillPos != null) {
                        // Farmland was accidentally destroyed — retill before continuing
                        targetPos = retillPos;
                        phase     = Phase.RETILL;
                        navigateTo(targetPos);
                        timeout = TASK_TIMEOUT;
                    } else {
                        BlockPos workplace = farmer.getWorkplacePos();
                        BlockPos next = workplace != null ? findMatureCrop(level, workplace) : null;
                        if (next != null) {
                            targetPos = next;
                            navigateTo(next);
                            timeout = TASK_TIMEOUT;
                        } else {
                            targetPos = null;
                        }
                    }
                } else {
                    // Travelling — reset the swing counter so we always start fresh on arrival
                    resetSwing();
                }
            }

            case RETILL -> {
                if (distSq < REACH_SQ) {
                    if (!tickSwing() || swingCounter < farmer.getWorkSwings()) return;
                    resetSwing();

                    BlockState cur = level.getBlockState(targetPos);
                    if (cur.is(Blocks.DIRT) || cur.is(Blocks.GRASS_BLOCK)
                            || cur.is(Blocks.COARSE_DIRT)) {
                        level.setBlock(targetPos, Blocks.FARMLAND.defaultBlockState(),
                                Block.UPDATE_ALL);
                        damageHoe();
                        farmer.gainXp(1);
                        SoundType sound = Blocks.FARMLAND.defaultBlockState().getSoundType();
                        level.playSound(null,
                                targetPos.getX() + 0.5, targetPos.getY() + 0.5,
                                targetPos.getZ() + 0.5,
                                sound.getHitSound(), SoundSource.BLOCKS,
                                sound.getVolume() * 0.8f,
                                sound.getPitch() * 0.9f + farmer.getRandom().nextFloat() * 0.2f);
                    }
                    // After retilling, look for more crops then let PLANT handle the new farmland
                    BlockPos workplace = farmer.getWorkplacePos();
                    BlockPos nextCrop = workplace != null ? findMatureCrop(level, workplace) : null;
                    if (nextCrop != null) {
                        targetPos = nextCrop;
                        phase     = Phase.HARVEST;
                        navigateTo(targetPos);
                        timeout = TASK_TIMEOUT;
                    } else {
                        targetPos = null;
                    }
                } else {
                    resetSwing();
                }
            }

            case FETCH -> {
                if (distSq < FETCH_SQ) {
                    BlockPos workplace = farmer.getWorkplacePos();
                    if (workplace == null) { targetPos = null; return; }

                    FarmBlockEntity farmBE = getFarmBlock(level, workplace);
                    if (farmBE != null) {
                        transferSeeds(farmBE.getSeedContainer(), farmer.getWorkerInventory());
                    }

                    targetPos = hasSeedsInInventory()
                            ? findEmptyFarmland(level, workplace)
                            : null;

                    if (targetPos != null) {
                        phase = Phase.PLANT;
                        navigateTo(targetPos);
                        timeout = TASK_TIMEOUT;
                    }
                }
            }

            case PLANT -> {
                if (distSq < REACH_SQ) {
                    if (!tickSwing() || swingCounter < farmer.getWorkSwings()) return;
                    resetSwing();

                    plantSeed(level, targetPos);
                    farmer.gainXp(1);

                    BlockPos workplace = farmer.getWorkplacePos();
                    if (workplace != null && hasSeedsInInventory()) {
                        targetPos = findEmptyFarmland(level, workplace);
                        if (targetPos != null) {
                            navigateTo(targetPos);
                            timeout = TASK_TIMEOUT;
                            return;
                        }
                    }
                    targetPos = null;
                } else {
                    resetSwing();
                }
            }
        }
    }

    @Override
    public void stop() {
        farmer.getNavigation().stop();
        targetPos    = null;
        scanCooldown = SCAN_INTERVAL;
        resetSwing();
    }

    // ── Swing counting ────────────────────────────────────────────────────────

    /**
     * Ticks the animation timer and, when it fires, plays one arm-swing and
     * increments {@link #swingCounter}.
     *
     * @return {@code true} when a swing just happened (caller can read the
     *         updated {@link #swingCounter} to decide whether to act).
     */
    private boolean tickSwing() {
        if (--swingAnimTimer > 0) return false;
        swingAnimTimer = SWING_ANIM_INTERVAL;
        farmer.swing(InteractionHand.MAIN_HAND);
        swingCounter++;
        return true;
    }

    /** Resets the swing state ready for the next action. */
    private void resetSwing() {
        swingCounter   = 0;
        swingAnimTimer = SWING_ANIM_INTERVAL;
    }

    // ── Tool request helpers ──────────────────────────────────────────────────

    private void submitToolRequest(ServerLevel level, ItemStack tool) {
        VillageHeartBlockEntity heart = findHeart(level);
        if (heart == null) return;
        heart.addRequest(new ItemRequest(
                farmer.getUUID(), farmer.getBaseName(), JobType.FARMER, tool));
    }

    private void resolveToolRequest(ServerLevel level) {
        VillageHeartBlockEntity heart = findHeart(level);
        if (heart != null) heart.resolveRequest(farmer.getUUID());
    }

    @Nullable
    private VillageHeartBlockEntity findHeart(ServerLevel level) {
        BlockPos workplace = farmer.getWorkplacePos();
        if (workplace == null) return null;
        if (!(level.getBlockEntity(workplace) instanceof IWorkplaceBlockEntity wbe)) return null;
        BlockPos heartPos = wbe.getLinkedHeartPos();
        if (heartPos == null) return null;
        return level.getBlockEntity(heartPos) instanceof VillageHeartBlockEntity h ? h : null;
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void navigateTo(BlockPos pos) {
        if (pos == null) return;
        farmer.getNavigation().moveTo(
                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, WALK_SPEED);
    }

    // ── Harvest ───────────────────────────────────────────────────────────────

    /**
     * Breaks a mature crop, sorts drops, and immediately replants from the seed container.
     *
     * @return the farmland position if it was accidentally destroyed (caller should retill),
     *         or {@code null} if the farmland survived.
     */
    @Nullable
    private BlockPos harvestCrop(ServerLevel level, BlockPos cropPos) {
        BlockState cropState = level.getBlockState(cropPos);
        if (!(cropState.getBlock() instanceof CropBlock crop)) return null;
        if (!crop.isMaxAge(cropState)) return null;

        BlockPos workplace = farmer.getWorkplacePos();
        FarmBlockEntity farmBE = workplace != null ? getFarmBlock(level, workplace) : null;

        List<ItemStack> drops = Block.getDrops(cropState, level, cropPos, null);
        level.removeBlock(cropPos, false);
        damageHoe();

        SoundType harvestSound = cropState.getSoundType();
        level.playSound(null,
                cropPos.getX() + 0.5, cropPos.getY() + 0.5, cropPos.getZ() + 0.5,
                harvestSound.getBreakSound(), SoundSource.BLOCKS,
                harvestSound.getVolume() * 0.8f,
                harvestSound.getPitch() * 0.9f + farmer.getRandom().nextFloat() * 0.2f);

        for (ItemStack drop : drops) {
            if (farmBE == null) break;
            if (isSeed(drop)) {
                depositIntoContainer(drop.copy(), farmBE.getSeedContainer());
            } else {
                depositIntoContainer(drop.copy(), farmBE.getOutputContainer());
            }
        }

        // Check farmland destroy chance
        BlockPos farmlandPos = cropPos.below();
        boolean farmlandDestroyed = false;
        if (level.getBlockState(farmlandPos).is(Blocks.FARMLAND)) {
            if (farmer.getRandom().nextFloat() < farmer.getFarmlandDestroyChance()) {
                level.setBlock(farmlandPos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                farmlandDestroyed = true;
            }
        }

        // Immediately replant if farmland survived
        if (!farmlandDestroyed && farmBE != null) {
            SimpleContainer seeds = farmBE.getSeedContainer();
            for (int i = 0; i < seeds.getContainerSize(); i++) {
                ItemStack seed = seeds.getItem(i);
                if (!isSeed(seed)) continue;
                Block seedBlock  = ((BlockItem) seed.getItem()).getBlock();
                BlockState plant = seedBlock.defaultBlockState();
                if (plant.canSurvive(level, cropPos)) {
                    level.setBlock(cropPos, plant, Block.UPDATE_ALL);
                    seed.shrink(1);
                    seeds.setItem(i, seed.isEmpty() ? ItemStack.EMPTY : seed);
                    SoundType replantSound = plant.getSoundType();
                    level.playSound(null,
                            cropPos.getX() + 0.5, cropPos.getY() + 0.5, cropPos.getZ() + 0.5,
                            replantSound.getPlaceSound(), SoundSource.BLOCKS,
                            replantSound.getVolume() * 0.8f,
                            replantSound.getPitch() * 0.9f + level.getRandom().nextFloat() * 0.2f);
                    break;
                }
            }
        }

        return farmlandDestroyed ? farmlandPos : null;
    }

    // ── Planting ──────────────────────────────────────────────────────────────

    private void plantSeed(ServerLevel level, BlockPos farmlandPos) {
        if (!level.getBlockState(farmlandPos).is(Blocks.FARMLAND)) return;
        if (!level.getBlockState(farmlandPos.above()).isAir()) return;

        SimpleContainer inv = farmer.getWorkerInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!isSeed(stack)) continue;
            Block cropBlock  = ((BlockItem) stack.getItem()).getBlock();
            BlockState cropState = cropBlock.defaultBlockState();
            BlockPos cropPos = farmlandPos.above();
            if (cropState.canSurvive(level, cropPos)) {
                level.setBlock(cropPos, cropState, Block.UPDATE_ALL);
                stack.shrink(1);
                inv.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
                damageHoe();
                SoundType plantSound = cropState.getSoundType();
                level.playSound(null,
                        cropPos.getX() + 0.5, cropPos.getY() + 0.5, cropPos.getZ() + 0.5,
                        plantSound.getPlaceSound(), SoundSource.BLOCKS,
                        plantSound.getVolume() * 0.8f,
                        plantSound.getPitch() * 0.9f + farmer.getRandom().nextFloat() * 0.2f);
            }
            break;
        }
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    @Nullable
    private BlockPos findMatureCrop(ServerLevel level, BlockPos center) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -SEARCH_Y; dy <= SEARCH_Y; dy++) {
                    BlockPos check = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(check);
                    if (!(state.getBlock() instanceof CropBlock c)) continue;
                    if (!c.isMaxAge(state)) continue;
                    double d = farmer.distanceToSqr(
                            check.getX() + 0.5, check.getY() + 0.5, check.getZ() + 0.5);
                    if (d < bestDistSq) { bestDistSq = d; best = check; }
                }
            }
        }
        return best;
    }

    @Nullable
    private BlockPos findEmptyFarmland(ServerLevel level, BlockPos center) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -SEARCH_Y; dy <= SEARCH_Y; dy++) {
                    BlockPos check = center.offset(dx, dy, dz);
                    if (!level.getBlockState(check).is(Blocks.FARMLAND)) continue;
                    if (!level.getBlockState(check.above()).isAir()) continue;
                    double d = farmer.distanceToSqr(
                            check.getX() + 0.5, check.getY() + 0.5, check.getZ() + 0.5);
                    if (d < bestDistSq) { bestDistSq = d; best = check; }
                }
            }
        }
        return best;
    }

    /**
     * Finds DIRT blocks in the farm area that should be retilled (surrounded by
     * farmland on at least one side, indicating they were accidentally destroyed).
     */
    @Nullable
    private BlockPos findDirtToRetill(ServerLevel level, BlockPos center) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -SEARCH_Y; dy <= SEARCH_Y; dy++) {
                    BlockPos check = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(check);
                    if (!state.is(Blocks.DIRT) && !state.is(Blocks.COARSE_DIRT)
                            && !state.is(Blocks.GRASS_BLOCK)) continue;
                    if (!level.getBlockState(check.above()).isAir()) continue;
                    // Must have at least one farmland neighbour (was farmland before)
                    boolean hasFarmlandNeighbour = false;
                    for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        if (level.getBlockState(check.relative(dir)).is(Blocks.FARMLAND)) {
                            hasFarmlandNeighbour = true;
                            break;
                        }
                    }
                    if (!hasFarmlandNeighbour) continue;
                    double d = farmer.distanceToSqr(
                            check.getX() + 0.5, check.getY() + 0.5, check.getZ() + 0.5);
                    if (d < bestDistSq) { bestDistSq = d; best = check; }
                }
            }
        }
        return best;
    }

    // ── Container helpers ─────────────────────────────────────────────────────

    private static void depositIntoContainer(ItemStack stack, SimpleContainer container) {
        if (stack.isEmpty()) return;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, stack);
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

    private static void transferSeeds(SimpleContainer from, SimpleContainer to) {
        for (int i = 0; i < from.getContainerSize(); i++) {
            ItemStack src = from.getItem(i);
            if (!isSeed(src)) continue;
            for (int j = 0; j < to.getContainerSize(); j++) {
                ItemStack dst = to.getItem(j);
                if (dst.isEmpty()) {
                    to.setItem(j, src.copy());
                    from.setItem(i, ItemStack.EMPTY);
                    break;
                } else if (ItemStack.isSameItemSameComponents(dst, src)) {
                    int space = dst.getMaxStackSize() - dst.getCount();
                    int move  = Math.min(space, src.getCount());
                    dst.grow(move);
                    src.shrink(move);
                    if (src.isEmpty()) from.setItem(i, ItemStack.EMPTY);
                    break;
                }
            }
        }
    }

    // ── Seed check ────────────────────────────────────────────────────────────

    public static boolean isSeed(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof CropBlock;
    }

    private boolean hasSeedsInInventory() {
        SimpleContainer inv = farmer.getWorkerInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isSeed(inv.getItem(i))) return true;
        }
        return false;
    }

    private static boolean containerHasSeeds(SimpleContainer container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (isSeed(container.getItem(i))) return true;
        }
        return false;
    }

    @Nullable
    private static FarmBlockEntity getFarmBlock(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FarmBlockEntity f ? f : null;
    }

    // ── Tool helpers ──────────────────────────────────────────────────────────

    private boolean hasHoeEquipped() {
        return farmer.getToolContainer().getItem(0).getItem() instanceof HoeItem;
    }

    private void damageHoe() {
        ItemStack hoe = farmer.getToolContainer().getItem(0);
        if (hoe.getItem() instanceof HoeItem) {
            hoe.hurtAndBreak(1, farmer, EquipmentSlot.MAINHAND);
        }
    }
}
