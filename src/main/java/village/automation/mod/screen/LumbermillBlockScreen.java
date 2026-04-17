package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.LumbermillBlockMenu;

import java.util.List;

/**
 * Screen for the Lumbermill GUI.
 *
 * <p>Layout (all coords panel-relative unless stated otherwise):
 * <pre>
 *   y =  6   block title
 *   y = 18   worker name + job badge  (or "No worker assigned")
 *   y = 30   divider
 *   y = 34   3×3 output grid  (slots at y = 34 / 52 / 70, x = 61 / 79 / 97)
 *   y = 93   chopping progress bar  (160 px wide, 5 px tall, green fill)
 *   y = 99   divider
 *   y = 101  "Inventory" label
 *   y = 103  player main inventory  (3 rows at y = 103 / 121 / 139)
 *   y = 157  player hotbar
 * </pre>
 *
 * <p>The progress bar fills left-to-right as the chop timer counts down toward
 * the next tree harvest.  Full = drop about to be collected; empty = just reset.
 */
public class LumbermillBlockScreen extends AbstractContainerScreen<LumbermillBlockMenu> {

    // ── Dimensions ────────────────────────────────────────────────────────────
    private static final int W = 176;
    private static final int H = 180;

    // ── Progress bar ──────────────────────────────────────────────────────────
    private static final int BAR_X = 8;
    private static final int BAR_Y = 93;
    private static final int BAR_W = 160;
    private static final int BAR_H = 5;

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int COL_BG        = 0xFF3B3B3B;
    private static final int COL_BORDER_LT = 0xFF666666;
    private static final int COL_BORDER_DK = 0xFF1A1A1A;
    private static final int COL_DIVIDER   = 0xFF555555;
    private static final int COL_SLOT_DK   = 0xFF373737;
    private static final int COL_SLOT_LT   = 0xFF8B8B8B;
    private static final int COL_BAR_BG    = 0xFF222222;
    private static final int COL_BAR_FG    = 0xFF44AA44;   // green wood-chopping fill

    // ── Constructor ───────────────────────────────────────────────────────────

    public LumbermillBlockScreen(LumbermillBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth  = W;
        this.imageHeight = H;
        this.inventoryLabelY = 101;
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

        // Divider below worker info
        g.fill(x + 4, y + 30, x + W - 4, y + 31, COL_DIVIDER);

        // Slot backgrounds (output grid + player inventory + hotbar)
        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, COL_SLOT_DK);
            g.fill(sx,     sy,     sx + 16, sy + 16, COL_SLOT_LT);
        }

        // Chopping progress bar background
        g.fill(x + BAR_X, y + BAR_Y, x + BAR_X + BAR_W, y + BAR_Y + BAR_H, COL_BAR_BG);

        // Chopping progress bar fill — elapsed fills left-to-right
        int interval = menu.getChopInterval();
        int timer    = menu.getChopTimer();
        if (interval > 0) {
            int elapsed = interval - timer;
            int fillW   = (int) ((float) elapsed / interval * BAR_W);
            if (fillW > 0) {
                g.fill(x + BAR_X, y + BAR_Y,
                       x + BAR_X + fillW, y + BAR_Y + BAR_H,
                       COL_BAR_FG);
            }
        }

        // Divider above player inventory
        g.fill(x + 4, y + 99, x + W - 4, y + 100, COL_DIVIDER);
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // Block title
        g.drawString(this.font, this.title, 8, 6, 0xFFFFFF, false);

        // "Inventory" label
        g.drawString(this.font, "Inventory", 8, 101, 0x404040, false);

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

            JobType job = menu.getWorkerJob();
            int badgeX  = 8 + this.font.width(name) + 4;
            g.drawString(this.font,
                    Component.literal("[ " + job.getTitle() + " ]"),
                    badgeX, infoY, 0xD4A800, false);
        }
    }

    // ── Full render ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);
        this.renderTooltip(g, mx, my);

        // Progress bar hover tooltip — shows seconds until next tree harvest
        int bx = this.leftPos + BAR_X;
        int by = this.topPos  + BAR_Y;
        if (mx >= bx && mx < bx + BAR_W && my >= by && my < by + BAR_H) {
            int timer    = menu.getChopTimer();
            int interval = menu.getChopInterval();
            if (interval > 0) {
                int secsLeft = timer / 20;
                g.renderTooltip(this.font,
                        List.of(Component.literal("Tree ready in: " + secsLeft + "s")),
                        java.util.Optional.empty(), mx, my);
            }
        }
    }
}
