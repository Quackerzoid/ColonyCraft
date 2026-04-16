package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.WorkplaceBlockMenu;

/**
 * Generic screen for all template profession workplace blocks.
 *
 * <p>Layout (relative to panel top-left):
 * <pre>
 *   y=  6  block title
 *   y= 18  worker name + job badge  (or "No worker assigned" in dark grey)
 *   y= 30  horizontal divider
 *   y= 32  "Inventory" label
 *   y= 34  player main inventory  (3 rows × 9, rows at y=34,52,70)
 *   y= 88  player hotbar
 *   y=110  bottom of panel
 * </pre>
 *
 * <p>Concrete screens (e.g. {@link MineBlockScreen}) simply extend this class
 * and forward the constructor — no additional code is needed.
 *
 * @param <M> the concrete {@link WorkplaceBlockMenu} subtype
 */
public class WorkplaceBlockScreen<M extends WorkplaceBlockMenu>
        extends AbstractContainerScreen<M> {

    // ── Dimensions ────────────────────────────────────────────────────────────
    private static final int W = 176;
    private static final int H = 110;

    // Panel colours (shared with other mod screens)
    private static final int COL_BG        = 0xFF3B3B3B;
    private static final int COL_BORDER_LT = 0xFF666666;
    private static final int COL_BORDER_DK = 0xFF1A1A1A;
    private static final int COL_DIVIDER   = 0xFF555555;
    private static final int COL_SLOT_DK   = 0xFF373737;
    private static final int COL_SLOT_LT   = 0xFF8B8B8B;

    public WorkplaceBlockScreen(M menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth      = W;
        this.imageHeight     = H;
        this.inventoryLabelY = 32;   // "Inventory" text
    }

    // ── Background ────────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        int x = this.leftPos;
        int y = this.topPos;

        // Panel body
        g.fill(x + 1, y + 1, x + W - 1, y + H - 1, COL_BG);

        // Bevel border
        g.fill(x,         y,         x + W,     y + 1,     COL_BORDER_LT);
        g.fill(x,         y,         x + 1,     y + H,     COL_BORDER_LT);
        g.fill(x,         y + H - 1, x + W,     y + H,     COL_BORDER_DK);
        g.fill(x + W - 1, y,         x + W,     y + H,     COL_BORDER_DK);

        // Divider between worker info and player inventory section
        g.fill(x + 4, y + 30, x + W - 4, y + 31, COL_DIVIDER);

        // Sunken slot backgrounds
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
        // Block title
        g.drawString(this.font, this.title, 8, 6, 0xFFFFFF, false);

        // "Inventory" label
        g.drawString(this.font,
                Component.translatable("container.inventory"),
                8, this.inventoryLabelY, 0x404040, false);

        // Worker info row
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

            JobType job   = menu.getWorkerJob();
            int badgeCol  = (job == JobType.UNEMPLOYED) ? 0x888888 : 0xD4A800;
            g.drawString(this.font,
                    Component.literal("[ " + job.getTitle() + " ]"),
                    8 + this.font.width(name) + 4, infoY, badgeCol, false);
        }
    }

    // ── Full render ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);
        this.renderTooltip(g, mx, my);
    }
}
