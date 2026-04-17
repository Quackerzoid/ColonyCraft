package village.automation.mod.entity;

import net.minecraft.world.entity.animal.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;

public enum AnimalType {
    PIG    ("Pig",     Items.CARROT,       Pig.class),
    SHEEP  ("Sheep",   Items.WHEAT,        Sheep.class),
    COW    ("Cow",     Items.WHEAT,        Cow.class),
    CHICKEN("Chicken", Items.WHEAT_SEEDS,  Chicken.class);

    private final String displayName;
    private final Item   breedingFood;
    private final Class<? extends Animal> animalClass;

    AnimalType(String n, Item food, Class<? extends Animal> cls) {
        this.displayName = n; this.breedingFood = food; this.animalClass = cls;
    }
    public String                          getDisplayName()  { return displayName; }
    public Item                            getBreedingFood() { return breedingFood; }
    public Class<? extends Animal>         getAnimalClass()  { return animalClass; }
    public AnimalType                      next()            { var v = values(); return v[(ordinal()+1) % v.length]; }
    public AnimalType                      prev()            { var v = values(); return v[(ordinal()+v.length-1) % v.length]; }
}
