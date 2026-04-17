package village.automation.mod.client.renderer;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
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

    public CourierRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new CourierModel<>(ctx.bakeLayer(VillageMod.COURIER_LAYER)), 0.4f);
        this.addLayer(new CourierItemLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(CourierEntity entity) {
        return TEXTURE;
    }
}
