package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import village.automation.mod.blockentity.BeekeeperBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.BeekeeperBlockMenu;

/**
 * Screen for the Beekeeper workplace GUI.
 *
 * <p>Layout (panel-relative coordinates, 176 × 192):
 * <pre>
 *   y= 6   block title
 *   y=18   worker name + job badge
 *   y=30   divider
 *   y=34   Fuel label (left) / Output label (right)
 *   y=34   3×3 fuel grid (x=8)  |  3×3 output grid (x=98)
 *   y=92   divider
 *   y=94   bee count  "Bees: N/4"
 *   y=94   pollen count  "Pollen: N"
 *   y=104  smoking progress bar (if active)
 *   y=110  divider
 *   y=112  "Inventory" label
 *   y=112  player main inventory
 *   y=166  player hotbar
 * </pre>
 */
public class BeekeeperBlockScreen extends AbstractContainerScreen<BeekeeperBlockMenu> {

    // ── Dimensions ────────────────────────────────────────────────────────────
    private static final int W = 176;
    private static final int H = 192;

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int COL_BG        = 0xFF3B3B3B;
    private static final int COL_BORDER_LT = 0xFF666666;
    private static final int COL_BORDER_DK = 0xFF1A1A1A;
    private static final int COL_DIVIDER   = 0xFF555555;
    private static final int COL_SLOT_DK   = 0xFF373737;
    private static final int COL_SLOT_LT   = 0xFF8B8B8B;
    private static final int COL_ACCENT    = 0xFFDDB33A;   // honey amber
    private static final int COL_BAR_BG    = 0xFF222222;
    private static final int COL_BAR_SMOKE = 0xFFAA7733;   // warm brown smoke bar
    private static final int COL_BEE       = 0xFFFFCC00;   // bee yellow

    public BeekeeperBlockScreen(BeekeeperBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth  = W;
        this.imageHeight = H;
        this.inventoryLabelY = 112;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        int x = this.leftPos;
        int y = this.topPos;

        // Panel body + bevel border
        g.fill(x + 1, y + 1, x + W - 1, y + H - 1, COL_BG);
        g.fill(x,         y,         x + W,     y + 1,     COL_BORDER_LT);
        g.fill(x,         y,         x + 1,     y + H,     COL_BORDER_LT);
        g.fill(x,         y + H - 1, x + W,     y + H,     COL_BORDER_DK);
        g.fill(x + W - 1, y,         x + W,     y + H,     COL_BORDER_DK);

        // Divider below worker info
        g.fill(x + 4, y + 30, x + W - 4, y + 31, COL_DIVIDER);

        // Slot backgrounds
        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, COL_SLOT_DK);
            g.fill(sx,     sy,     sx + 16, sy + 16, COL_SLOT_LT);
        }

        // Divider between grids and info row
        g.fill(x + 4, y + 92, x + W - 4, y + 93, COL_DIVIDER);

        // Smoking progress bar (x=8..168, y=104, h=6)
        final int barX = x + 8;
        final int barY = y + 104;
        final int barW = W - 16;
        g.fill(barX - 1, barY - 1, barX + barW + 1, barY + 7, COL_BAR_BG);
        if (menu.isFuelBurning()) {
            int fill = (int) (barW * menu.getSmokingProgress());
            if (fill > 0) {
                g.fill(barX, barY, barX + fill, barY + 6, COL_BAR_SMOKE);
            }
        }

        // Divider above player inventory
        g.fill(x + 4, y + 110, x + W - 4, y + 111, COL_DIVIDER);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // Block title
        g.drawString(this.font, this.title, 8, 6, 0xFFFFFF, false);

        // "Inventory"
        g.drawString(this.font, "Inventory", 8, 112, 0x404040, false);

        // Worker info
        int infoY = 18;
        if (!menu.hasWorker()) {
            g.drawString(this.font,
                    Component.literal("No worker assigned").withStyle(ChatFormatting.DARK_GRAY),
                    8, infoY, 0x777777, false);
        } else {
            String name = menu.getWorkerName();
            g.drawString(this.font,
                    Component.literal(name).withStyle(ChatFormatting.WHITE),
                    8, infoY, 0xFFFFFF, false);
            JobType job  = menu.getWorkerJob();
            int badgeX   = 8 + this.font.width(name) + 4;
            g.drawString(this.font,
                    Component.literal("[ " + job.getTitle() + " ]"),
                    badgeX, infoY, COL_ACCENT & 0xFFFFFF, false);
        }

        // Section labels above grids
        g.drawString(this.font,
                Component.literal("Fuel (Logs)").withStyle(ChatFormatting.GRAY),
                8, 25, 0xAAAAAA, false);
        g.drawString(this.font,
                Component.literal("Honeycomb").withStyle(ChatFormatting.GRAY),
                98, 25, 0xAAAAAA, false);

        // Bee count
        int beeCount = menu.getBeeCount();
        g.drawString(this.font,
                Component.literal("Bees: " + beeCount + "/" + BeekeeperBlockEntity.MAX_BEES),
                8, 94, COL_BEE & 0xFFFFFF, false);

        // Pollen count
        int pollen = menu.getPollenCount();
        g.drawString(this.font,
                Component.literal("Pollen: " + pollen),
                90, 94, COL_ACCENT & 0xFFFFFF, false);

        // Smoking label
        if (menu.isFuelBurning()) {
            g.drawString(this.font,
                    Component.literal("Smoking...").withStyle(ChatFormatting.GRAY),
                    8, 97, 0x887755, false);
        } else if (pollen > 0) {
            g.drawString(this.font,
                    Component.literal("No fuel").withStyle(ChatFormatting.RED),
                    8, 97, 0xFF5555, false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);
        this.renderTooltip(g, mx, my);
    }
}
