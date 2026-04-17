package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import village.automation.mod.entity.AnimalType;
import village.automation.mod.menu.AnimalPenBlockMenu;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

/**
 * Redesigned Animal Pen GUI.
 *
 * <h3>Layout overview</h3>
 * <pre>
 *   ┌──────────────────────────────────────────┐ ┌────────────────┐
 *   │ Animal Pen                               │ │ Animal         │
 *   ├──────────────────────────────────────────┤ ├────────────────┤
 *   │ <Name> [ Shepherd ]                      │ │                │
 *   ├──────────────────────────────────────────┤ │  [3-D model]   │
 *   │ Output                                   │ │                │
 *   │  [3×3 output grid]                       │ ├────────────────┤
 *   ├──────────────────────────────────────────┤ │ < [Name] >     │
 *   │ Inventory                                │ ├────────────────┤
 *   │  [player 3×9]                            │ │ Tool Required  │
 *   │  [hotbar  1×9]                           │ │ [icon] Shears  │
 *   └──────────────────────────────────────────┘ ├────────────────┤
 *                                                 │ Breeding Food  │
 *                                                 │ [icon] Wheat   │
 *                                                 └────────────────┘
 * </pre>
 *
 * <p>Main panel: 176 × 192 px.
 * Sidebar: 90 × 192 px, 4 px gap to the right.
 * Total imageWidth: 270 px.
 */
public class AnimalPenBlockScreen extends AbstractContainerScreen<AnimalPenBlockMenu> {

    // ── Main panel ────────────────────────────────────────────────────────────
    private static final int MAIN_W = 176;
    private static final int MAIN_H = 192;

    // ── Sidebar ───────────────────────────────────────────────────────────────
    private static final int SIDE_GAP = 4;
    private static final int SIDE_W   = 90;
    /** Panel-relative x where the sidebar begins. */
    private static final int SIDE_X   = MAIN_W + SIDE_GAP;   // 180

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int COL_BG          = 0xFF3B3B3B;
    private static final int COL_BORDER_LT   = 0xFF666666;
    private static final int COL_BORDER_DK   = 0xFF1A1A1A;
    private static final int COL_DIVIDER     = 0xFF555555;
    private static final int COL_SLOT_DK     = 0xFF373737;
    private static final int COL_SLOT_LT     = 0xFF8B8B8B;
    private static final int COL_ACCENT      = 0xFFAA6633;   // warm earthy brown
    private static final int COL_SIDE_BG     = 0xFF2A2A2A;
    private static final int COL_SIDE_BDR_LT = 0xFF555555;
    private static final int COL_SIDE_BDR_DK = 0xFF111111;

    // ── Sidebar arrow buttons (all panel-relative) ────────────────────────────
    private static final int ARR_W  = 8;
    private static final int ARR_H  = 11;
    /** Panel-relative x of the left  arrow background. */
    private static final int ARR_LX = SIDE_X + 4;                    // 184
    /** Panel-relative x of the right arrow background. */
    private static final int ARR_RX = SIDE_X + SIDE_W - 4 - ARR_W;  // 258
    /** Panel-relative y shared by both arrow buttons. */
    private static final int ARR_Y  = 91;

    // ── 3-D model viewport (panel-relative) ──────────────────────────────────
    private static final int MODEL_TOP    = 16;
    private static final int MODEL_BOTTOM = 89;

    // ── Dummy entity cache for 3-D model rendering ────────────────────────────
    private final EnumMap<AnimalType, LivingEntity> dummies = new EnumMap<>(AnimalType.class);

    // ── Constructor ───────────────────────────────────────────────────────────

    public AnimalPenBlockScreen(AnimalPenBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth      = MAIN_W + SIDE_GAP + SIDE_W;  // 270
        this.imageHeight     = MAIN_H;                       // 192
        // All labels drawn manually
        this.titleLabelX     = -9999;
        this.titleLabelY     = -9999;
        this.inventoryLabelY = -9999;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Lazily creates and caches a dummy entity used only for rendering. */
    @Nullable
    private LivingEntity getDummy(AnimalType type) {
        if (!dummies.containsKey(type) && minecraft != null && minecraft.level != null) {
            LivingEntity e = switch (type) {
                case PIG     -> EntityType.PIG.create(minecraft.level);
                case SHEEP   -> EntityType.SHEEP.create(minecraft.level);
                case COW     -> EntityType.COW.create(minecraft.level);
                case CHICKEN -> EntityType.CHICKEN.create(minecraft.level);
            };
            if (e != null) dummies.put(type, e);
        }
        return dummies.get(type);
    }

    // ── Background ────────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        final int x = this.leftPos;
        final int y = this.topPos;

        // ── Main panel body + bevel ───────────────────────────────────────────
        g.fill(x + 1,          y + 1,          x + MAIN_W - 1, y + MAIN_H - 1, COL_BG);
        g.fill(x,              y,               x + MAIN_W,     y + 1,          COL_BORDER_LT);
        g.fill(x,              y,               x + 1,          y + MAIN_H,     COL_BORDER_LT);
        g.fill(x,              y + MAIN_H - 1, x + MAIN_W,     y + MAIN_H,     COL_BORDER_DK);
        g.fill(x + MAIN_W - 1, y,              x + MAIN_W,     y + MAIN_H,     COL_BORDER_DK);

        // Main panel dividers
        g.fill(x + 4, y + 14, x + MAIN_W - 4, y + 15, COL_DIVIDER);   // below title
        g.fill(x + 4, y + 30, x + MAIN_W - 4, y + 31, COL_DIVIDER);   // below worker row
        g.fill(x + 4, y + 98, x + MAIN_W - 4, y + 99, COL_DIVIDER);   // above inventory

        // Slot backgrounds (output grid + player inventory + hotbar)
        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, COL_SLOT_DK);
            g.fill(sx,     sy,     sx + 16, sy + 16, COL_SLOT_LT);
        }

        // ── Sidebar panel body + bevel ────────────────────────────────────────
        final int sX = x + SIDE_X;
        g.fill(sX + 1,          y + 1,          sX + SIDE_W - 1, y + MAIN_H - 1, COL_SIDE_BG);
        g.fill(sX,              y,               sX + SIDE_W,     y + 1,          COL_SIDE_BDR_LT);
        g.fill(sX,              y,               sX + 1,          y + MAIN_H,     COL_SIDE_BDR_LT);
        g.fill(sX,              y + MAIN_H - 1, sX + SIDE_W,     y + MAIN_H,     COL_SIDE_BDR_DK);
        g.fill(sX + SIDE_W - 1, y,              sX + SIDE_W,     y + MAIN_H,     COL_SIDE_BDR_DK);

        // Sidebar dividers
        g.fill(sX + 4, y + 14,  sX + SIDE_W - 4, y + 15,  COL_DIVIDER);  // below "Animal" header
        g.fill(sX + 4, y + 90,  sX + SIDE_W - 4, y + 91,  COL_DIVIDER);  // below 3-D model
        g.fill(sX + 4, y + 106, sX + SIDE_W - 4, y + 107, COL_DIVIDER);  // below < Name > row
        g.fill(sX + 4, y + 134, sX + SIDE_W - 4, y + 135, COL_DIVIDER);  // below tool section
        g.fill(sX + 4, y + 162, sX + SIDE_W - 4, y + 163, COL_DIVIDER);  // below food section

        // Recessed dark background for the 3-D model viewport
        g.fill(sX + 4, y + MODEL_TOP, sX + SIDE_W - 4, y + MODEL_BOTTOM, 0xFF1A1A1A);

        // Arrow button backgrounds
        g.fill(x + ARR_LX, y + ARR_Y, x + ARR_LX + ARR_W, y + ARR_Y + ARR_H, 0xFF555555);
        g.fill(x + ARR_RX, y + ARR_Y, x + ARR_RX + ARR_W, y + ARR_Y + ARR_H, 0xFF555555);

        // ── Live 3-D animal model ─────────────────────────────────────────────
        AnimalType type = menu.getAnimalType();
        LivingEntity dummy = getDummy(type);
        if (dummy != null) {
            // Pass the viewport centre as the "mouse" so the animal always faces forward.
            int modelCx = sX + SIDE_W / 2;
            int modelCy = y + (MODEL_TOP + MODEL_BOTTOM) / 2;
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    g,
                    sX + 5, y + MODEL_TOP,
                    sX + SIDE_W - 5, y + MODEL_BOTTOM,
                    type.getRenderScale(),
                    0f,
                    modelCx, modelCy,
                    dummy);
        }
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // All coordinates are PANEL-RELATIVE (pose already translated by leftPos/topPos).

        // ── Main panel ────────────────────────────────────────────────────────

        // Title
        g.drawString(this.font, this.title, 8, 5, COL_ACCENT & 0xFFFFFF, false);

        // Worker row — name + job badge on one line
        if (!menu.hasWorker()) {
            g.drawString(this.font,
                    Component.literal("Unassigned").withStyle(ChatFormatting.DARK_GRAY),
                    8, 18, 0x666666, false);
        } else {
            String workerName = menu.getWorkerName();
            String badge = "[ " + menu.getWorkerJob().getTitle() + " ]";
            int maxNameW = MAIN_W - 16 - this.font.width(badge) - 4;
            if (this.font.width(workerName) > maxNameW) {
                workerName = this.font.plainSubstrByWidth(
                        workerName, maxNameW - this.font.width("…")) + "…";
            }
            g.drawString(this.font,
                    Component.literal(workerName).withStyle(ChatFormatting.WHITE),
                    8, 18, 0xFFFFFF, false);
            g.drawString(this.font,
                    Component.literal(badge),
                    8 + this.font.width(workerName) + 4, 18, COL_ACCENT & 0xFFFFFF, false);
        }

        // "Output" section label (sits above the 3×3 grid)
        g.drawString(this.font,
                Component.literal("Output").withStyle(ChatFormatting.GRAY),
                8, 34, 0x888888, false);

        // "Inventory" section label
        g.drawString(this.font, "Inventory", 8, 102, 0x777777, false);

        // ── Sidebar ───────────────────────────────────────────────────────────
        final int stx = SIDE_X + 6;   // standard left-margin text x inside the sidebar

        // Section header
        g.drawString(this.font,
                Component.literal("Animal").withStyle(ChatFormatting.GRAY),
                stx, 5, 0x888888, false);

        // Animal name — horizontally centred in the sidebar, between the arrow buttons
        AnimalType type = menu.getAnimalType();
        String animalName = type.getDisplayName();
        int nameX = SIDE_X + (SIDE_W - this.font.width(animalName)) / 2;
        g.drawString(this.font,
                Component.literal(animalName).withStyle(ChatFormatting.WHITE),
                nameX, ARR_Y + 2, COL_ACCENT & 0xFFFFFF, false);

        // Arrow glyphs inside each button background
        g.drawString(this.font, Component.literal("<"), ARR_LX + 1, ARR_Y + 2, 0xFFFFFF, false);
        g.drawString(this.font, Component.literal(">"), ARR_RX + 1, ARR_Y + 2, 0xFFFFFF, false);

        // ── Tool Required section ─────────────────────────────────────────────
        g.drawString(this.font,
                Component.literal("Tool Required").withStyle(ChatFormatting.GRAY),
                stx, 110, 0x888888, false);

        if (type.requiresTool()) {
            Item tool = type.getRequiredTool();
            assert tool != null;
            g.renderItem(new ItemStack(tool), stx, 119);
            g.drawString(this.font,
                    Component.literal(tool.getDescription().getString())
                              .withStyle(ChatFormatting.WHITE),
                    stx + 20, 123, 0xFFFFFF, false);
        } else {
            g.drawString(this.font,
                    Component.literal("None").withStyle(ChatFormatting.DARK_GRAY),
                    stx, 121, 0x555555, false);
        }

        // ── Breeding Food section ─────────────────────────────────────────────
        g.drawString(this.font,
                Component.literal("Breeding Food").withStyle(ChatFormatting.GRAY),
                stx, 138, 0x888888, false);

        Item food = type.getBreedingFood();
        g.renderItem(new ItemStack(food), stx, 147);
        g.drawString(this.font,
                Component.literal(food.getDescription().getString())
                          .withStyle(ChatFormatting.WHITE),
                stx + 20, 151, 0xCCCCCC, false);
    }

    // ── Full render pass ──────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);
        this.renderTooltip(g, mx, my);

        // Tooltip over the name area between the two arrows
        int selX = this.leftPos + ARR_LX + ARR_W;
        int selY = this.topPos  + ARR_Y;
        int selW = ARR_RX - ARR_LX - ARR_W;
        if (mx >= selX && mx < selX + selW && my >= selY && my < selY + ARR_H) {
            g.renderTooltip(this.font,
                    List.of(Component.literal("Click arrows to change animal")),
                    Optional.empty(), mx, my);
        }
    }

    // ── Mouse input ───────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int rx = (int) mouseX - this.leftPos;
            int ry = (int) mouseY - this.topPos;

            // Right arrow → next animal (server button ID 0)
            if (rx >= ARR_RX && rx < ARR_RX + ARR_W && ry >= ARR_Y && ry < ARR_Y + ARR_H) {
                assert this.minecraft != null;
                this.minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0);
                return true;
            }

            // Left arrow → previous animal (server button ID 1)
            if (rx >= ARR_LX && rx < ARR_LX + ARR_W && ry >= ARR_Y && ry < ARR_Y + ARR_H) {
                assert this.minecraft != null;
                this.minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 1);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
