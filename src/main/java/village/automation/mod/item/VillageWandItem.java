package village.automation.mod.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.IWorkplaceBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.VillagerWorkerEntity;
import net.minecraft.ChatFormatting;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

public class VillageWandItem extends Item {

    private static final String KEY_X = "LinkedHeartX";
    private static final String KEY_Y = "LinkedHeartY";
    private static final String KEY_Z = "LinkedHeartZ";

    public VillageWandItem(Properties properties) {
        super(properties);
    }

    // ── Right-click behaviour ─────────────────────────────────────────────────

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        ItemStack wand = context.getItemInHand();

        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player == null)     return InteractionResult.PASS;

        // ── Step 1: right-clicking a Village Heart links the wand to it ───────
        if (level.getBlockState(pos).is(VillageMod.VILLAGE_HEART.get())) {
            setLinkedHeart(wand, pos);
            player.sendSystemMessage(
                    Component.literal("Village Heart linked at " + pos.toShortString())
                             .withStyle(ChatFormatting.GREEN));
            return InteractionResult.SUCCESS;
        }

        // ── Step 2: right-clicking any workplace block links it to the heart ──
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof IWorkplaceBlockEntity wbe) {

            BlockPos heartPos = getLinkedHeart(wand);
            if (heartPos == null) {
                player.sendSystemMessage(
                        Component.literal("Right-click a Village Heart first to link the wand.")
                                 .withStyle(ChatFormatting.GRAY));
                return InteractionResult.SUCCESS;
            }

            // Verify the stored heart still exists
            if (!(level.getBlockEntity(heartPos) instanceof VillageHeartBlockEntity newHeartBE)) {
                player.sendSystemMessage(
                        Component.literal("The linked Village Heart no longer exists.")
                                 .withStyle(ChatFormatting.RED));
                return InteractionResult.SUCCESS;
            }

            // Verify the workplace is within this heart's territory
            int radius = newHeartBE.getRadius();
            if (heartPos.distSqr(pos) > (double) (radius * radius)) {
                player.sendSystemMessage(
                        Component.literal("That block is outside this Village Heart's radius ("
                                          + radius + " blocks).")
                                 .withStyle(ChatFormatting.RED));
                return InteractionResult.SUCCESS;
            }

            // If already linked to a DIFFERENT heart, clean up the old link first
            BlockPos oldHeartPos = wbe.getLinkedHeartPos();
            if (oldHeartPos != null && !oldHeartPos.equals(heartPos)) {
                if (level.getBlockEntity(oldHeartPos) instanceof VillageHeartBlockEntity oldHeartBE) {
                    oldHeartBE.unlinkWorkplace(pos);
                }
                // Unassign the worker that came from the old heart
                if (level instanceof ServerLevel serverLevel) {
                    UUID oldWorkerUUID = wbe.getAssignedWorkerUUID();
                    if (oldWorkerUUID != null
                            && serverLevel.getEntity(oldWorkerUUID) instanceof VillagerWorkerEntity oldWorker) {
                        oldWorker.makeUnemployed();
                    }
                }
                wbe.setAssignedWorkerUUID(null);
            }

            // Establish the new bidirectional link
            wbe.setLinkedHeartPos(heartPos);
            newHeartBE.linkWorkplace(pos);

            // Friendly block name for the confirmation message
            String blockName = level.getBlockState(pos).getBlock()
                    .getName().getString();
            player.sendSystemMessage(
                    Component.literal(blockName + " linked to Village Heart at "
                            + heartPos.toShortString() + ".")
                             .withStyle(ChatFormatting.GREEN));
            return InteractionResult.SUCCESS;
        }

        // ── Unknown block ─────────────────────────────────────────────────────
        BlockPos heartPos = getLinkedHeart(wand);
        if (heartPos != null) {
            player.sendSystemMessage(
                    Component.literal("This block cannot be linked to a Village Heart.")
                             .withStyle(ChatFormatting.RED));
        } else {
            player.sendSystemMessage(
                    Component.literal("Right-click a Village Heart first to link the wand.")
                             .withStyle(ChatFormatting.GRAY));
        }
        return InteractionResult.SUCCESS;
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        BlockPos heart = getLinkedHeart(stack);
        if (heart != null) {
            tooltip.add(Component.literal("Linked to Village Heart at " + heart.toShortString())
                                 .withStyle(ChatFormatting.GREEN));
        } else {
            tooltip.add(Component.literal("Not linked to any Village Heart")
                                 .withStyle(ChatFormatting.GRAY));
        }
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    /** Stores the given heart position in the wand's custom data component. */
    public static void setLinkedHeart(ItemStack wand, BlockPos pos) {
        CompoundTag tag = wand.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putInt(KEY_X, pos.getX());
        tag.putInt(KEY_Y, pos.getY());
        tag.putInt(KEY_Z, pos.getZ());
        wand.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** Returns the stored heart position, or {@code null} if the wand is not linked. */
    @Nullable
    public static BlockPos getLinkedHeart(ItemStack wand) {
        CustomData data = wand.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        if (!tag.contains(KEY_X)) return null;
        return new BlockPos(tag.getInt(KEY_X), tag.getInt(KEY_Y), tag.getInt(KEY_Z));
    }

    /** Returns {@code true} if this wand has a stored Village Heart position. */
    public static boolean isLinked(ItemStack wand) {
        return getLinkedHeart(wand) != null;
    }
}
