package village.automation.mod.client.renderer;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.VillagerSoulEntity;

public class VillagerSoulRenderer extends MobRenderer<VillagerSoulEntity, VillagerSoulModel<VillagerSoulEntity>> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/villager_soul.png");

    public VillagerSoulRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new VillagerSoulModel<>(ctx.bakeLayer(VillageMod.VILLAGER_SOUL_LAYER)), 0.3f);
    }

    @Override
    public ResourceLocation getTextureLocation(VillagerSoulEntity entity) {
        return TEXTURE;
    }
}
