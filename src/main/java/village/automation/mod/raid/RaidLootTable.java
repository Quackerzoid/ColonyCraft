package village.automation.mod.raid;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.List;

public final class RaidLootTable {

    // ── Edit this list to change village chest victory rewards ────────────────
    // Format: new LootEntry(ItemStack, weight)  — higher weight = more common
    private static final List<LootEntry> HEART_LOOT = List.of(
            entry(new ItemStack(Items.EMERALD,           3),  30),
            entry(new ItemStack(Items.EMERALD,           8),  15),
            entry(new ItemStack(Items.IRON_INGOT,        4),  25),
            entry(new ItemStack(Items.GOLD_INGOT,        2),  20),
            entry(new ItemStack(Items.DIAMOND,           1),  10),
            entry(new ItemStack(Items.TOTEM_OF_UNDYING,  1),   3),
            pendingBook(Enchantments.SHARPNESS,  4,             8),
            pendingBook(Enchantments.PROTECTION, 4,             8),
            pendingBook(Enchantments.MENDING,    1,             4),
            entry(new ItemStack(Items.CROSSBOW,          1),  12),
            entry(new ItemStack(Items.ARROW,            16),  20),
            entry(new ItemStack(Items.BREAD,             6),  18)
    );

    private static final int TOTAL_WEIGHT =
            HEART_LOOT.stream().mapToInt(LootEntry::weight).sum();

    /** How many item stacks to award; scales with waves completed. */
    public static int rollCount(int wavesCompleted) {
        return 3 + wavesCompleted;
    }

    /**
     * Rolls the loot table and resolves enchanted books against the registry.
     * Call this from server-side code only.
     */
    public static List<ItemStack> rollWithLevel(RandomSource random, int wavesCompleted, ServerLevel level) {
        int count = rollCount(wavesCompleted);
        var enchReg = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        List<ItemStack> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ItemStack picked = pick(random).copy();
            if (picked.is(Items.ENCHANTED_BOOK)) {
                CustomData cd = picked.get(DataComponents.CUSTOM_DATA);
                if (cd != null) {
                    CompoundTag tag = cd.copyTag();
                    if (tag.contains("PendingEnchKey")) {
                        String keyStr  = tag.getString("PendingEnchKey");
                        int    enchLvl = tag.getInt("PendingEnchLvl");
                        picked.remove(DataComponents.CUSTOM_DATA);
                        enchReg.get(ResourceKey.create(Registries.ENCHANTMENT,
                                        ResourceLocation.parse(keyStr)))
                               .ifPresent(holder -> picked.enchant(holder, enchLvl));
                    }
                }
            }
            result.add(picked);
        }
        return result;
    }

    private static ItemStack pick(RandomSource random) {
        int roll = random.nextInt(TOTAL_WEIGHT);
        int cum  = 0;
        for (LootEntry e : HEART_LOOT) {
            cum += e.weight();
            if (roll < cum) return e.stack();
        }
        return HEART_LOOT.get(HEART_LOOT.size() - 1).stack();
    }

    private static LootEntry entry(ItemStack stack, int weight) {
        return new LootEntry(stack, weight);
    }

    // Stores a marker so the book's enchantment can be resolved when a ServerLevel is available.
    private static LootEntry pendingBook(ResourceKey<Enchantment> key, int lvl, int weight) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        CompoundTag tag = new CompoundTag();
        tag.putString("PendingEnchKey", key.location().toString());
        tag.putInt("PendingEnchLvl",    lvl);
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return new LootEntry(book, weight);
    }

    record LootEntry(ItemStack stack, int weight) {}

    private RaidLootTable() {}
}
