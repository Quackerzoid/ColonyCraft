package village.automation.mod.client.renderer.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import village.automation.mod.client.renderer.SoulIronGolemModel;
import village.automation.mod.client.renderer.SoulIronGolemRenderer;
import village.automation.mod.entity.SoulIronGolemEntity;

public class BellGolemLayer
        extends RenderLayer<SoulIronGolemEntity, SoulIronGolemModel<SoulIronGolemEntity>> {

    private static final ItemStack BELL_STACK = new ItemStack(Items.BELL);

    public BellGolemLayer(SoulIronGolemRenderer renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       SoulIronGolemEntity entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (!entity.isBellGolem()) return;

        poseStack.pushPose();

        // Move into the head bone's local space so the bell follows head movement
        getParentModel().getHead().translateAndRotate(poseStack);

        // Position on top of the head — Y is negative in model space (upward)
        poseStack.translate(0.0, -0.55, 0.0);
        poseStack.scale(0.65f, 0.65f, 0.65f);

        Minecraft.getInstance().getItemRenderer().renderStatic(
                BELL_STACK,
                ItemDisplayContext.GROUND,
                packedLight,
                OverlayTexture.NO_OVERLAY,
                poseStack,
                bufferSource,
                entity.level(),
                entity.getId());

        poseStack.popPose();
    }
}
