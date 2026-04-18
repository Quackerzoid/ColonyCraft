package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.SmelterBlockMenu;

/**
 * GUI for the Smelter Block.
 *
 * <p>Layout (panel-relative coordinates, 176 × 168 px):
 * <pre>
 *  y=  5  "Smelter Block" title  (gray)
 *  y= 17  Worker name + job badge  / "No worker assigned"
 *  y= 29  ──── divider ────
 *
 *           [Ore slot x=56, y=35]
 *           [Fire icon x=57, y=52]           [Output slot x=116, y=50]
 *           [Fuel slot x=56, y=67]
 *           ← left column →    ←←← arrow →→→   ← right column →
 *
 *  y= 80  ──── divider ────
 *  y= 83  "Inventory" label
 *  y= 90  Player main inventory  (rows at y=90, 108, 126)
 *  y=148  Player hotbar
 * </pre>
 *
 * <p>All decorative elements (background, borders, slot bevels, fire icon,
 * arrow) are drawn with {@code GuiGraphics.fill} — no texture atlas needed.
 */
public class SmelterBlockScreen extends AbstractContainerScreen<SmelterBlockMenu> {

    // ── Dimensions ────────────────────────────────────────────────────────────
    private static final int W = 176;
    private static final int H = 168;

    // ── Colour palette ────────────────────────────────────────────────────────
    private static final int COL_BG        = 0xFF3B3B3B;
    private static final int COL_BORDER_LT = 0xFF666666;
    private static final int COL_BORDER_DK = 0xFF1A1A1A;
    private static final int COL_DIVIDER   = 0xFF555555;
    private static final int COL_SLOT_DK   = 0xFF373737;
    private static final int COL_SLOT_LT   = 0xFF8B8B8B;

    // Progress arrow colours
    private static final int COL_BAR_BG    = 0xFF1A1A1A;
    private static final int COL_BAR_FG    = 0xFFFF8C00;  // orange, matches fire

    // Fire icon colours
    private static final int COL_FIRE_BG   = 0xFF2A1000;
    private static final int COL_FIRE_LO   = 0xFF7A2800;
    private static final int COL_FIRE_MID  = 0xFFCC4400;
    private static final int COL_FIRE_HI   = 0xFFFF8C00;
    private static final int COL_FIRE_TIP  = 0xFFFFD700;

    public SmelterBlockScreen(SmelterBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth      = W;
        this.imageHeight     = H;
        // Suppress default title/inventory labels — we draw our own
        this.titleLabelX     = -999;
        this.titleLabelY     = -999;
        this.inventoryLabelY = -999;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);
        this.renderTooltip(g, mx, my);
    }

    // ── Background ────────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        final int x = this.leftPos;
        final int y = this.topPos;

        // ── Panel body + bevel border ────────────────────────────────────────
        g.fill(x + 1,     y + 1,     x + W - 1, y + H - 1, COL_BG);
        g.fill(x,         y,         x + W,     y + 1,     COL_BORDER_LT);
        g.fill(x,         y,         x + 1,     y + H,     COL_BORDER_LT);
        g.fill(x,         y + H - 1, x + W,     y + H,     COL_BORDER_DK);
        g.fill(x + W - 1, y,         x + W,     y + H,     COL_BORDER_DK);

        // ── Dividers ─────────────────────────────────────────────────────────
        g.fill(x + 4, y + 29, x + W - 4, y + 30, COL_DIVIDER); // header
        g.fill(x + 4, y + 80, x + W - 4, y + 81, COL_DIVIDER); // footer (above player inv)

        // ── Slot backgrounds ──────────────────────────────────────────────────
        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, COL_SLOT_DK);
            g.fill(sx,     sy,     sx + 16, sy + 16, COL_SLOT_LT);
        }

        // ── Fire icon (between ore and fuel slots, x=57 y=52) ─────────────
        //    Stylised flame built from layered filled rectangles.
        drawFire(g, x + 57, y + 52);

        // ── Arrow (progress indicator, fills left→right as smelting progresses) ─
        drawArrow(g, x + 79, y + 46, menu.isSmelting() ? menu.getSmeltProgress() : 0f);
    }

    /**
     * Draws a small decorative flame at ({@code fx}, {@code fy}).
     * Total footprint: 14 × 14 px.
     */
    private static void drawFire(GuiGraphics g, int fx, int fy) {
        // Dark background tile
        g.fill(fx,      fy,      fx + 14, fy + 14, COL_FIRE_BG);
        // Ember base (wide, low)
        g.fill(fx + 1,  fy + 9,  fx + 13, fy + 13, COL_FIRE_LO);
        // Flame body
        g.fill(fx + 2,  fy + 5,  fx + 12, fy + 11, COL_FIRE_MID);
        g.fill(fx + 3,  fy + 3,  fx + 11, fy + 8,  COL_FIRE_MID);
        // Bright core
        g.fill(fx + 4,  fy + 4,  fx + 10, fy + 8,  COL_FIRE_HI);
        g.fill(fx + 5,  fy + 2,  fx + 9,  fy + 5,  COL_FIRE_HI);
        // Hot tip
        g.fill(fx + 5,  fy + 1,  fx + 9,  fy + 3,  COL_FIRE_TIP);
        g.fill(fx + 6,  fy,      fx + 8,  fy + 2,  COL_FIRE_TIP);
    }

    /**
     * Draws a right-pointing arrow at ({@code ax}, {@code ay}) with a progress fill.
     * Shaft is 20 px; arrowhead adds another 13 px. {@code progress} 0→1 fills the shaft.
     */
    private static void drawArrow(GuiGraphics g, int ax, int ay, float progress) {
        // Dark backgrounds
        g.fill(ax,      ay + 4, ax + 20, ay + 9,  COL_BAR_BG);
        g.fill(ax + 18, ay + 2, ax + 22, ay + 11, COL_BAR_BG);
        g.fill(ax + 22, ay + 3, ax + 26, ay + 10, COL_BAR_BG);
        g.fill(ax + 26, ay + 4, ax + 30, ay + 9,  COL_BAR_BG);
        g.fill(ax + 30, ay + 5, ax + 33, ay + 8,  COL_BAR_BG);
        // Orange fill on the shaft based on progress
        int fillW = (int) (20 * progress);
        if (fillW > 0) {
            g.fill(ax, ay + 4, ax + fillW, ay + 9, COL_BAR_FG);
        }
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // ── Block title ───────────────────────────────────────────────────────
        g.drawString(this.font,
                Component.translatable("block.colonycraft.smelter_block")
                         .withStyle(ChatFormatting.GRAY),
                8, 5, 0xAAAAAA, false);

        // ── Worker info ───────────────────────────────────────────────────────
        if (!menu.hasWorker()) {
            g.drawString(this.font,
                    Component.literal("No worker assigned").withStyle(ChatFormatting.DARK_GRAY),
                    8, 17, 0x777777, false);
        } else {
            String  name     = menu.getWorkerName();
            JobType job      = menu.getWorkerJob();
            int     badgeCol = (job == JobType.UNEMPLOYED) ? 0x888888 : 0xD4A800;
            g.drawString(this.font,
                    Component.literal(name).withStyle(ChatFormatting.WHITE),
                    8, 17, 0xFFFFFF, false);
            g.drawString(this.font,
                    Component.literal("[ " + job.getTitle() + " ]"),
                    8 + this.font.width(name) + 4, 17, badgeCol, false);
        }

        // ── Slot micro-labels ─────────────────────────────────────────────────
        g.drawString(this.font,
                Component.literal("Ore").withStyle(ChatFormatting.GRAY),
                38, 38, 0x888888, false);
        g.drawString(this.font,
                Component.literal("Fuel").withStyle(ChatFormatting.GRAY),
                35, 70, 0x888888, false);

        // ── Inventory label ───────────────────────────────────────────────────
        g.drawString(this.font,
                Component.translatable("container.inventory"),
                8, 83, 0x404040, false);
    }
}
