package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import village.automation.mod.entity.CourierEntity;
import village.automation.mod.menu.CourierMenu;

/**
 * Display-only GUI shown when a player right-clicks the courier.
 *
 * <p>Layout (panel-relative coordinates):
 * <pre>
 *   y=  0 ─ panel top border
 *   y=  6   courier name (white, centred)
 *   y= 17 ─ divider
 *   y= 22   "Current Task" label (gray)
 *   y= 32   task description (white, truncated to fit)
 *   y= 44 ─ divider
 *   y= 49   "Carrying" label (gray)
 *   y= 61   9 read-only carried-item slots (1 row)
 *   y= 77   bottom of slots
 *   y= 89 ─ panel bottom border
 * </pre>
 */
public class CourierScreen extends AbstractContainerScreen<CourierMenu> {

    // ── Colour palette (matches the rest of the mod's screens) ───────────────
    private static final int COL_BG        = 0xFF3B3B3B;
    private static final int COL_BORDER_LT = 0xFF666666;
    private static final int COL_BORDER_DK = 0xFF1A1A1A;
    private static final int COL_DIVIDER   = 0xFF555555;
    private static final int COL_SLOT_DK   = 0xFF373737;
    private static final int COL_SLOT_LT   = 0xFF8B8B8B;

    public CourierScreen(CourierMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth      = 176;
        this.imageHeight     = 90;
        // Suppress the default title and inventory labels — we draw our own.
        this.titleLabelX     = -999;
        this.titleLabelY     = -999;
        this.inventoryLabelY = -999;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        final int x = this.leftPos;
        final int y = this.topPos;

        // Panel background + bevel border
        g.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, COL_BG);
        g.fill(x,              y,               x + imageWidth, y + 1,           COL_BORDER_LT);
        g.fill(x,              y,               x + 1,          y + imageHeight, COL_BORDER_LT);
        g.fill(x,              y + imageHeight - 1, x + imageWidth, y + imageHeight, COL_BORDER_DK);
        g.fill(x + imageWidth - 1, y,           x + imageWidth, y + imageHeight, COL_BORDER_DK);

        // Dividers
        g.fill(x + 4, y + 17, x + imageWidth - 4, y + 18, COL_DIVIDER);
        g.fill(x + 4, y + 44, x + imageWidth - 4, y + 45, COL_DIVIDER);

        // Slot backgrounds for the 9 read-only carried-item slots
        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, COL_SLOT_DK);
            g.fill(sx,     sy,     sx + 16, sy + 16, COL_SLOT_LT);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        final int maxW = imageWidth - 8;

        // ── Courier name (white, centred) ────────────────────────────────────
        CourierEntity courier = this.menu.getCourier();
        String name = courier != null ? courier.getDisplayName().getString() : "Courier";
        int ellW = this.font.width("\u2026");
        if (this.font.width(name) > maxW) {
            name = this.font.plainSubstrByWidth(name, maxW - ellW).trim() + "\u2026";
        }
        int nameX = (imageWidth - this.font.width(name)) / 2;
        g.drawString(this.font, Component.literal(name).withStyle(ChatFormatting.WHITE),
                nameX, 6, 0xFFFFFF, false);

        // ── Task section ─────────────────────────────────────────────────────
        g.drawString(this.font,
                Component.literal("Current Task").withStyle(ChatFormatting.GRAY),
                8, 22, 0xAAAAAA, false);

        String task = this.menu.getCurrentTask();
        if (this.font.width(task) > maxW) {
            task = this.font.plainSubstrByWidth(task, maxW - ellW).trim() + "\u2026";
        }
        g.drawString(this.font, Component.literal(task).withStyle(ChatFormatting.WHITE),
                8, 32, 0xFFFFFF, false);

        // ── Carrying label ───────────────────────────────────────────────────
        g.drawString(this.font,
                Component.literal("Carrying").withStyle(ChatFormatting.GRAY),
                8, 49, 0xAAAAAA, false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);
        this.renderTooltip(g, mx, my);
    }
}
