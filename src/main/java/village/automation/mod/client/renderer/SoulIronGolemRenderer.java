package village.automation.mod.client.renderer;

import net.minecraft.client.model.IronGolemModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.SoulIronGolemEntity;

public class SoulIronGolemRenderer
        extends MobRenderer<SoulIronGolemEntity, IronGolemModel<SoulIronGolemEntity>> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/soul_iron_golem.png");

    public SoulIronGolemRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new IronGolemModel<>(ctx.bakeLayer(ModelLayers.IRON_GOLEM)), 0.7f);
    }

    @Override
    public ResourceLocation getTextureLocation(SoulIronGolemEntity entity) {
        return TEXTURE;
    }
}
