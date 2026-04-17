package village.automation.mod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import village.automation.mod.entity.CourierEntity;

/**
 * Render layer that draws the courier's carried item at the tip of its right hand.
 *
 * <p>The item is only rendered when {@link CourierEntity#getDisplayItem()} is
 * non-empty (the first stack in the carried inventory, synced via
 * {@code DATA_DISPLAY_ITEM}). It uses
 * {@link ItemDisplayContext#THIRD_PERSON_RIGHT_HAND} so all item model transforms
 * defined by resource packs are honoured automatically.
 */
public class CourierItemLayer extends RenderLayer<CourierEntity, CourierModel<CourierEntity>> {

    public CourierItemLayer(RenderLayerParent<CourierEntity, CourierModel<CourierEntity>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack,
                       MultiBufferSource bufferSource,
                       int packedLight,
                       CourierEntity entity,
                       float limbSwing,
                       float limbSwingAmount,
                       float partialTick,
                       float ageInTicks,
                       float netHeadYaw,
                       float headPitch) {

        ItemStack stack = entity.getDisplayItem();
        if (stack.isEmpty()) return;

        poseStack.pushPose();

        // Navigate to the midpoint between the courier's cupped arms.
        this.getParentModel().translateToHeldItemPosition(poseStack);

        // Scale so the item sits comfortably between both hands.
        poseStack.scale(0.5f, 0.5f, 0.5f);

        Minecraft.getInstance().getItemRenderer().renderStatic(
                stack,
                ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight,
                OverlayTexture.NO_OVERLAY,
                poseStack,
                bufferSource,
                entity.level(),
                entity.getId());

        poseStack.popPose();
    }
}
