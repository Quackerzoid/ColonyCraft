package village.automation.mod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.CourierEntity;

public class CourierRenderer extends MobRenderer<CourierEntity, CourierModel<CourierEntity>> {

    /**
     * Copper-tinted texture for the courier golem.
     * Place the texture file at:
     *   src/main/resources/assets/colonycraft/textures/entity/courier.png
     * (64×64 px, UV layout documented in {@link CourierModel}).
     */
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/courier.png");

    /** Only render the task tag within this distance (squared, in blocks). 32 blocks = 1024. */
    private static final double TASK_TAG_DIST_SQ = 1024.0;

    public CourierRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new CourierModel<>(ctx.bakeLayer(VillageMod.COURIER_LAYER)), 0.4f);
        this.addLayer(new CourierItemLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(CourierEntity entity) {
        return TEXTURE;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    public void render(CourierEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
        if (this.entityRenderDispatcher.distanceToSqr(entity) <= TASK_TAG_DIST_SQ) {
            renderTaskTag(entity, poseStack, buffer, packedLight);
        }
    }

    /**
     * Renders the courier's current task as a billboard label above its head,
     * using the same two-pass technique as vanilla name tags:
     * <ol>
     *   <li>SEE_THROUGH pass — dimmed, visible even when the courier is behind a block.</li>
     *   <li>NORMAL pass    — full-brightness, rendered only when unoccluded.</li>
     * </ol>
     * Idle couriers show the label in gray; active ones show it in gold.
     */
    private void renderTaskTag(CourierEntity entity, PoseStack poseStack,
                               MultiBufferSource buffer, int packedLight) {
        String taskStr = entity.getCurrentTask();
        Component task = "Idle".equals(taskStr)
                ? Component.literal(taskStr).withStyle(ChatFormatting.GRAY)
                : Component.literal(taskStr).withStyle(ChatFormatting.GOLD);

        poseStack.pushPose();

        // Position just above the bounding-box top — same height as a vanilla name tag.
        poseStack.translate(0.0, entity.getBbHeight() + 0.5, 0.0);
        // Billboard: always face the camera.
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        // MC name-tag scale: 0.025 with Y flipped.
        poseStack.scale(0.025f, -0.025f, 0.025f);

        Matrix4f matrix  = poseStack.last().pose();
        Font     font    = this.getFont();
        float    x       = -font.width(task) / 2f;
        float    bgAlpha = Minecraft.getInstance().options.getBackgroundOpacity(0.25f);
        int      bgColor = (int) (bgAlpha * 255.0f) << 24;

        // Dim see-through pass (behind-block visibility).
        font.drawInBatch(task, x, 0f, 0x20FFFFFF, false, matrix, buffer,
                Font.DisplayMode.SEE_THROUGH, bgColor, packedLight);

        // Full-brightness pass (when unoccluded).
        font.drawInBatch(task, x, 0f, 0xFFFFFF, false, matrix, buffer,
                Font.DisplayMode.NORMAL, 0, packedLight);

        poseStack.popPose();
    }
}
