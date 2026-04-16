package village.automation.mod.loot;

import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import village.automation.mod.VillageMod;

public class VillagerSoulLootModifier implements IGlobalLootModifier {

    // No configurable fields — MapCodec.unit always produces this singleton
    public static final MapCodec<VillagerSoulLootModifier> CODEC =
            MapCodec.unit(new VillagerSoulLootModifier());

    @Override
    public ObjectArrayList<ItemStack> apply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        // Only fire for vanilla Villager kills
        if (!context.hasParam(LootContextParams.THIS_ENTITY)) return generatedLoot;
        var entity = context.getParam(LootContextParams.THIS_ENTITY);
        if (!(entity instanceof Villager)) return generatedLoot;

        // 25% chance to drop a Villager Soul
        if (context.getRandom().nextFloat() < 0.25f) {
            generatedLoot.add(new ItemStack(VillageMod.VILLAGER_SOUL.get()));
        }
        return generatedLoot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return VillageMod.VILLAGER_SOUL_GLM.get();
    }
}
