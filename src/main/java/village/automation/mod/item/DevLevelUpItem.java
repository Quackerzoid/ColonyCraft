package village.automation.mod.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import village.automation.mod.entity.VillagerWorkerEntity;

public class DevLevelUpItem extends Item {

    public DevLevelUpItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide()) return InteractionResult.SUCCESS;
        if (!player.isCrouching()) return InteractionResult.PASS;
        if (!(target instanceof VillagerWorkerEntity worker)) return InteractionResult.PASS;

        int levelBefore = worker.getLevel();
        worker.devLevelUp();
        int levelAfter = worker.getLevel();

        if (levelAfter > levelBefore) {
            player.sendSystemMessage(
                    Component.literal(worker.getDisplayName().getString()
                            + " levelled up: " + levelBefore + " → " + levelAfter)
                            .withStyle(ChatFormatting.AQUA));
        } else {
            player.sendSystemMessage(
                    Component.literal(worker.getDisplayName().getString()
                            + " is already at max level (" + levelAfter + ").")
                            .withStyle(ChatFormatting.GRAY));
        }

        return InteractionResult.SUCCESS;
    }
}
