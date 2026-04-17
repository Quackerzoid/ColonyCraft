package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import village.automation.mod.menu.ButcherBlockMenu;

/**
 * GUI for the Butcher block.
 *
 * <p>Layout (176 × 180, panel-relative coords):
 * <pre>
 *   y =  6   "Butcher" title
 *   y = 16   divider
 *   y = 20   "Output" label
 *   y = 28   3×3 output grid (centred at x=44)
 *   y = 82   divider
 *   y = 84   "Inventory" label
 *   y = 102  player 3×9 inventory
 *   y = 156  player hotbar
 * </pre>
 */
public class ButcherBlockScreen extends AbstractContainerScreen<ButcherBlockMenu> {

    // ── Dimensions ────────────────────────────────────────────────────────────
    private static final int W = 176;
    private static final int H = 180;

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int COL_BG        = 0xFF3B3B3B;
    private static final int COL_BORDER_LT = 0xFF666666;
    private static final int COL_BORDER_DK = 0xFF1A1A1A;
    private static final int COL_DIVIDER   = 0xFF555555;
    private static final int COL_SLOT_DK   = 0xFF373737;
    private static final int COL_SLOT_LT   = 0xFF8B8B8B;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ButcherBlockScreen(ButcherBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth      = W;
        this.imageHeight     = H;
        this.titleLabelX     = -9999;
        this.titleLabelY     = -9999;
        this.inventoryLabelY = -9999;
    }

    // ── Background ────────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        final int x = this.leftPos;
        final int y = this.topPos;

        // Panel body
        g.fill(x + 1, y + 1, x + W - 1, y + H - 1, COL_BG);

        // Bevel border
        g.fill(x,         y,         x + W,     y + 1,     COL_BORDER_LT);
        g.fill(x,         y,         x + 1,     y + H,     COL_BORDER_LT);
        g.fill(x,         y + H - 1, x + W,     y + H,     COL_BORDER_DK);
        g.fill(x + W - 1, y,         x + W,     y + H,     COL_BORDER_DK);

        // Dividers
        g.fill(x + 4, y + 16, x + W - 4, y + 17, COL_DIVIDER);  // below title
        g.fill(x + 4, y + 82, x + W - 4, y + 83, COL_DIVIDER);  // above inventory

        // Slot backgrounds
        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, COL_SLOT_DK);
            g.fill(sx,     sy,     sx + 16, sy + 16, COL_SLOT_LT);
        }
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(this.font,
                Component.literal("Butcher").withStyle(ChatFormatting.WHITE),
                8, 6, 0xFFFFFF, false);

        g.drawString(this.font,
                Component.literal("Output").withStyle(ChatFormatting.GRAY),
                8, 20, 0xAAAAAA, false);

        g.drawString(this.font,
                Component.literal("Inventory").withStyle(ChatFormatting.GRAY),
                8, 84, 0x404040, false);
    }

    // ── Full render ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);
        this.renderTooltip(g, mx, my);
    }
}
