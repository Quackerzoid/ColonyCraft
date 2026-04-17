package village.automation.mod.entity;

import net.minecraft.world.entity.animal.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

public enum AnimalType {

    //         display      breeding food       entity class     required tool  render scale
    PIG    ("Pig",      Items.CARROT,       Pig.class,      null,          30),
    SHEEP  ("Sheep",    Items.WHEAT,        Sheep.class,    Items.SHEARS,  25),
    COW    ("Cow",      Items.WHEAT,        Cow.class,      null,          22),
    CHICKEN("Chicken",  Items.WHEAT_SEEDS,  Chicken.class,  null,          36);

    private final String                  displayName;
    private final Item                    breedingFood;
    private final Class<? extends Animal> animalClass;
    /** Tool the shepherd must carry to harvest this animal; {@code null} = none needed. */
    @Nullable private final Item          requiredTool;
    /** Scale passed to {@code InventoryScreen.renderEntityInInventoryFollowsMouse}. */
    private final int                     renderScale;

    AnimalType(String name, Item food, Class<? extends Animal> cls,
               @Nullable Item tool, int scale) {
        this.displayName  = name;
        this.breedingFood = food;
        this.animalClass  = cls;
        this.requiredTool = tool;
        this.renderScale  = scale;
    }

    public String                  getDisplayName()  { return displayName; }
    public Item                    getBreedingFood() { return breedingFood; }
    public Class<? extends Animal> getAnimalClass()  { return animalClass; }
    @Nullable public Item          getRequiredTool() { return requiredTool; }
    public boolean                 requiresTool()    { return requiredTool != null; }
    public int                     getRenderScale()  { return renderScale; }

    public AnimalType next() { var v = values(); return v[(ordinal() + 1) % v.length]; }
    public AnimalType prev() { var v = values(); return v[(ordinal() + v.length - 1) % v.length]; }
}
