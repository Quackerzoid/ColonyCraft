package village.automation.mod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import village.automation.mod.VillageMod;
import village.automation.mod.client.renderer.layer.BellGolemLayer;
import village.automation.mod.entity.SoulIronGolemEntity;

public class SoulIronGolemRenderer
        extends MobRenderer<SoulIronGolemEntity, SoulIronGolemModel<SoulIronGolemEntity>> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/soul_iron_golem.png");

    /** Only render the status tag within this distance (squared). 32 blocks = 1024. */
    private static final double STATUS_TAG_DIST_SQ = 1024.0;

    public SoulIronGolemRenderer(EntityRendererProvider.Context ctx) {
        // SoulIronGolemModel reuses the vanilla IRON_GOLEM layer definition
        // but overrides setupAnim() to add the repair slump animation.
        super(ctx, new SoulIronGolemModel<>(ctx.bakeLayer(ModelLayers.IRON_GOLEM)), 0.7f);
        this.addLayer(new BellGolemLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(SoulIronGolemEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(SoulIronGolemEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
        if (this.entityRenderDispatcher.distanceToSqr(entity) <= STATUS_TAG_DIST_SQ) {
            renderStatusTag(entity, poseStack, buffer, packedLight);
        }
    }

    /**
     * Renders the golem's current status as a two-pass billboard label above its head.
     *
     * <ul>
     *   <li><b>Patrolling</b> — aqua (soul-fire palette)</li>
     *   <li><b>Attacking: …</b> — red</li>
     *   <li><b>Repairing</b> — green</li>
     * </ul>
     */
    private void renderStatusTag(SoulIronGolemEntity entity, PoseStack poseStack,
                                  MultiBufferSource buffer, int packedLight) {
        String statusStr = entity.getStatus();

        ChatFormatting colour;
        if (statusStr.startsWith("Attacking")) {
            colour = ChatFormatting.RED;
        } else if ("Repairing".equals(statusStr)) {
            colour = ChatFormatting.GREEN;
        } else {
            colour = ChatFormatting.AQUA;   // Patrolling
        }

        Component status = Component.literal(statusStr).withStyle(colour);

        poseStack.pushPose();

        poseStack.translate(0.0, entity.getBbHeight() + 0.5, 0.0);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(0.025f, -0.025f, 0.025f);

        Matrix4f matrix  = poseStack.last().pose();
        Font     font    = this.getFont();
        float    x       = -font.width(status) / 2f;
        float    bgAlpha = Minecraft.getInstance().options.getBackgroundOpacity(0.25f);
        int      bgColor = (int) (bgAlpha * 255.0f) << 24;

        // Dim see-through pass (visible even behind blocks)
        font.drawInBatch(status, x, 0f, 0x20FFFFFF, false, matrix, buffer,
                Font.DisplayMode.SEE_THROUGH, bgColor, packedLight);

        // Full-brightness pass (when unoccluded)
        font.drawInBatch(status, x, 0f, 0xFFFFFF, false, matrix, buffer,
                Font.DisplayMode.NORMAL, 0, packedLight);

        poseStack.popPose();
    }
}
