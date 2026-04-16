package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
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
 * Three-phase AI goal for Farmer workers:
 *
 * <ol>
 *   <li><b>HARVEST</b> (highest priority) — walk to any fully-grown crop in
 *       the 9×9 area, break it, sort drops: seeds → seed container (so the
 *       farm refills itself), other crops → output container.  Immediately
 *       replants from the seed container so the farmland is never left bare.
 *   </li>
 *   <li><b>PLANT</b> — walk to empty farmland and plant one seed from the
 *       worker's personal inventory.
 *   </li>
 *   <li><b>FETCH</b> — walk to the Farm Block and transfer seeds from its seed
 *       container into the worker's personal inventory, then switch to PLANT.
 *   </li>
 * </ol>
 */
public class FarmerWorkGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private static final int SCAN_INTERVAL = 40;   // ticks between idle scans
    private static final int TASK_TIMEOUT  = 200;  // ticks before giving up on a navigation
    private static final int SEARCH_RADIUS = 4;    // ±4 blocks → 9×9 square
    private static final int SEARCH_Y      = 3;    // ±3 blocks vertically
    private static final double REACH_SQ   = 4.0;  // 2-block reach, squared
    private static final double FETCH_SQ   = 9.0;  // 3-block fetch, squared
    private static final double WALK_SPEED = 0.7;

    // ── State ─────────────────────────────────────────────────────────────────
    private enum Phase { FETCH, HARVEST, PLANT }

    private final VillagerWorkerEntity farmer;
    private Phase    phase;
    @Nullable
    private BlockPos targetPos;
    private int      scanCooldown    = 0;
    private int      timeout         = 0;
    // Throttles how often we post a tool request to the heart (30 s)
    private int      requestCooldown = 0;

    public FarmerWorkGoal(VillagerWorkerEntity farmer) {
        this.farmer = farmer;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    // ── Goal lifecycle ────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        if (requestCooldown > 0) requestCooldown--;
        if (!farmer.level().isDay()) return false;         // no farming at night
        if (farmer.isTooHungryToWork()) return false;      // won't work below 20 % food
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
        // Hoe is present — clear any pending request
        resolveToolRequest(level);

        BlockPos workplace = farmer.getWorkplacePos();
        if (workplace == null) return false;

        // ── Priority 1: harvest mature crops ─────────────────────────────────
        BlockPos matureCrop = findMatureCrop(level, workplace);
        if (matureCrop != null) {
            targetPos = matureCrop;
            phase = Phase.HARVEST;
            return true;
        }

        // ── Priority 2: plant seeds from personal inventory ───────────────────
        if (hasSeedsInInventory()) {
            targetPos = findEmptyFarmland(level, workplace);
            if (targetPos != null) {
                phase = Phase.PLANT;
                return true;
            }
        }

        // ── Priority 3: fetch seeds from the Farm Block ───────────────────────
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
        navigateTo(targetPos);
    }

    @Override
    public boolean canContinueToUse() {
        if (!farmer.level().isDay()) return false;       // stop at nightfall
        if (farmer.isTooHungryToWork()) return false;    // stop if food drops below 20 %
        if (!(farmer.level() instanceof ServerLevel)) return false;
        if (farmer.getJob() != JobType.FARMER) return false;
        if (!hasHoeEquipped()) return false;   // stop if hoe breaks mid-task
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
                    harvestCrop(level, targetPos);

                    // Find the next mature crop to harvest
                    BlockPos workplace = farmer.getWorkplacePos();
                    BlockPos next = workplace != null ? findMatureCrop(level, workplace) : null;
                    if (next != null) {
                        targetPos = next;
                        navigateTo(next);
                        timeout = TASK_TIMEOUT;
                    } else {
                        targetPos = null;   // all crops harvested
                    }
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

                    // Switch to planting if we got seeds
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
                    plantSeed(level, targetPos);

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
                }
            }
        }
    }

    @Override
    public void stop() {
        farmer.getNavigation().stop();
        targetPos    = null;
        scanCooldown = SCAN_INTERVAL;
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
        farmer.getNavigation().moveTo(
                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, WALK_SPEED);
    }

    // ── Harvest ───────────────────────────────────────────────────────────────

    /**
     * Breaks the given fully-grown crop, distributes drops, then replants from
     * the Farm Block's seed container so farmland is never left bare.
     *
     * <ul>
     *   <li>Seed-type drops   → Farm Block's <em>seed</em> container (self-sustaining)
     *   <li>Non-seed drops    → Farm Block's <em>output</em> container (for the player)
     * </ul>
     */
    private void harvestCrop(ServerLevel level, BlockPos cropPos) {
        BlockState cropState = level.getBlockState(cropPos);
        if (!(cropState.getBlock() instanceof CropBlock crop)) return;
        if (!crop.isMaxAge(cropState)) return;

        BlockPos workplace = farmer.getWorkplacePos();
        FarmBlockEntity farmBE = workplace != null ? getFarmBlock(level, workplace) : null;

        // Collect drops using vanilla loot-table logic (no tool, no fortune)
        List<ItemStack> drops = Block.getDrops(cropState, level, cropPos, null);

        // Remove the mature crop (farmland block stays intact)
        level.removeBlock(cropPos, false);
        damageHoe();   // 1 durability per harvest

        // Play the crop's break sound so nearby players can hear the harvest
        SoundType harvestSound = cropState.getSoundType();
        level.playSound(null,
                cropPos.getX() + 0.5, cropPos.getY() + 0.5, cropPos.getZ() + 0.5,
                harvestSound.getBreakSound(),
                SoundSource.BLOCKS,
                harvestSound.getVolume() * 0.8f,
                harvestSound.getPitch() * 0.9f + farmer.getRandom().nextFloat() * 0.2f);

        // Sort drops into the correct containers
        for (ItemStack drop : drops) {
            if (farmBE == null) break;
            if (isSeed(drop)) {
                depositIntoContainer(drop.copy(), farmBE.getSeedContainer());
            } else {
                depositIntoContainer(drop.copy(), farmBE.getOutputContainer());
            }
        }

        // Immediately replant from the seed container so farmland is never bare
        if (farmBE != null) {
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

                    // Play plant sound for the immediate replant too
                    SoundType replantSound = plant.getSoundType();
                    level.playSound(null,
                            cropPos.getX() + 0.5, cropPos.getY() + 0.5, cropPos.getZ() + 0.5,
                            replantSound.getPlaceSound(),
                            SoundSource.BLOCKS,
                            replantSound.getVolume() * 0.8f,
                            replantSound.getPitch() * 0.9f + level.getRandom().nextFloat() * 0.2f);
                    break;
                }
            }
        }
    }

    // ── Planting ──────────────────────────────────────────────────────────────

    /** Places one seed from the worker's personal inventory onto farmland. */
    private void plantSeed(ServerLevel level, BlockPos farmlandPos) {
        if (!level.getBlockState(farmlandPos).is(Blocks.FARMLAND)) return;
        if (!level.getBlockState(farmlandPos.above()).isAir()) return;

        SimpleContainer inv = farmer.getWorkerInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!isSeed(stack)) continue;

            Block cropBlock = ((BlockItem) stack.getItem()).getBlock();
            BlockState cropState = cropBlock.defaultBlockState();
            BlockPos cropPos = farmlandPos.above();

            if (cropState.canSurvive(level, cropPos)) {
                level.setBlock(cropPos, cropState, Block.UPDATE_ALL);
                stack.shrink(1);
                inv.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
                damageHoe();   // 1 durability per seed placed

                // Play the crop's place sound so nearby players hear the planting
                SoundType plantSound = cropState.getSoundType();
                level.playSound(null,
                        cropPos.getX() + 0.5, cropPos.getY() + 0.5, cropPos.getZ() + 0.5,
                        plantSound.getPlaceSound(),
                        SoundSource.BLOCKS,
                        plantSound.getVolume() * 0.8f,
                        plantSound.getPitch() * 0.9f + farmer.getRandom().nextFloat() * 0.2f);
            }
            break;
        }
    }

    // ── Scanning ─────────────────────────────────────────────────────────────

    /** Returns the closest fully-grown crop in the 9×9 area, or {@code null}. */
    @Nullable
    private BlockPos findMatureCrop(ServerLevel level, BlockPos center) {
        BlockPos best    = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -SEARCH_Y; dy <= SEARCH_Y; dy++) {
                    BlockPos check = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(check);
                    if (!(state.getBlock() instanceof CropBlock crop)) continue;
                    if (!crop.isMaxAge(state)) continue;

                    double d = farmer.distanceToSqr(
                            check.getX() + 0.5, check.getY() + 0.5, check.getZ() + 0.5);
                    if (d < bestDistSq) { bestDistSq = d; best = check; }
                }
            }
        }
        return best;
    }

    /** Returns the closest empty farmland tile in the 9×9 area, or {@code null}. */
    @Nullable
    private BlockPos findEmptyFarmland(ServerLevel level, BlockPos center) {
        BlockPos best    = null;
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

    // ── Container helpers ─────────────────────────────────────────────────────

    /** Pushes {@code stack} into the first available space in {@code container}. */
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
        // Container full — items are silently discarded (same as vanilla hopper overflow)
    }

    /** Moves all seeds from {@code from} to available slots in {@code to}. */
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

    /** Returns true if the stack is a plantable crop seed (any CropBlock seed). */
    public static boolean isSeed(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof CropBlock;
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

    /** Returns {@code true} when a hoe of any material is in the tool slot. */
    private boolean hasHoeEquipped() {
        return farmer.getToolContainer().getItem(0).getItem() instanceof HoeItem;
    }

    /**
     * Reduces the hoe's durability by 1.  Handles Unbreaking enchantment
     * automatically (via {@link ItemStack#hurtAndBreak}).  If the hoe breaks
     * it is removed from the tool slot through the normal equipment-break path.
     */
    private void damageHoe() {
        ItemStack hoe = farmer.getToolContainer().getItem(0);
        if (hoe.getItem() instanceof HoeItem) {
            hoe.hurtAndBreak(1, farmer, EquipmentSlot.MAINHAND);
        }
    }
}
