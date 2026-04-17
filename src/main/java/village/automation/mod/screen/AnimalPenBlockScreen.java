package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.AnimalPenBlockMenu;

import java.util.List;

/**
 * Screen for the Animal Pen GUI.
 *
 * <p>Layout (all coords panel-relative):
 * <pre>
 *   y =  6   block title
 *   y = 18   worker name + job badge
 *   y = 30   divider
 *   y = 34   3×3 output grid
 *   y = 93   animal type selector  (< name >)
 *   y = 99   divider
 *   y = 101  "Inventory" label
 *   y = 103  player main inventory
 *   y = 157  player hotbar
 * </pre>
 */
public class AnimalPenBlockScreen extends AbstractContainerScreen<AnimalPenBlockMenu> {

    // ── Dimensions ────────────────────────────────────────────────────────────
    private static final int W = 176;
    private static final int H = 180;

    // ── Arrow button areas (panel-relative) ───────────────────────────────────
    private static final int ARROW_L_X = 8;
    private static final int ARROW_L_Y = 93;
    private static final int ARROW_R_X = 163;
    private static final int ARROW_R_Y = 93;
    private static final int ARROW_W   = 5;
    private static final int ARROW_H   = 11;

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int COL_BG        = 0xFF3B3B3B;
    private static final int COL_BORDER_LT = 0xFF666666;
    private static final int COL_BORDER_DK = 0xFF1A1A1A;
    private static final int COL_DIVIDER   = 0xFF555555;
    private static final int COL_SLOT_DK   = 0xFF373737;
    private static final int COL_SLOT_LT   = 0xFF8B8B8B;
    private static final int COL_ARROW     = 0xFF888888;
    private static final int COL_ACCENT    = 0xFFAA6633;   // warm brown for animals

    // ── Constructor ───────────────────────────────────────────────────────────

    public AnimalPenBlockScreen(AnimalPenBlockMenu menu, Inventory inventory, Component title) {
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

        // Arrow buttons (left / right)
        g.fill(x + ARROW_L_X, y + ARROW_L_Y, x + ARROW_L_X + ARROW_W, y + ARROW_L_Y + ARROW_H, COL_ARROW);
        g.fill(x + ARROW_R_X, y + ARROW_R_Y, x + ARROW_R_X + ARROW_W, y + ARROW_R_Y + ARROW_H, COL_ARROW);

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

        // Animal type name (centred between arrows)
        String typeName = menu.getAnimalType().getDisplayName();
        int nameWidth   = this.font.width(typeName);
        int centreX     = W / 2 - nameWidth / 2;
        g.drawString(this.font, Component.literal(typeName), centreX, 95, COL_ACCENT & 0xFFFFFF, false);

        // Arrow labels
        g.drawString(this.font, Component.literal("<"), ARROW_L_X + 1, ARROW_L_Y + 2, 0xFFFFFF, false);
        g.drawString(this.font, Component.literal(">"), ARROW_R_X,     ARROW_R_Y + 2, 0xFFFFFF, false);
    }

    // ── Full render ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);
        this.renderTooltip(g, mx, my);

        // Tooltip over animal type area
        int ax = this.leftPos + ARROW_L_X + ARROW_W;
        int ay = this.topPos  + ARROW_L_Y;
        int aw = (ARROW_R_X - ARROW_L_X - ARROW_W);
        if (mx >= ax && mx < ax + aw && my >= ay && my < ay + ARROW_H) {
            g.renderTooltip(this.font,
                    List.of(Component.literal("Click arrows to change animal")),
                    java.util.Optional.empty(), mx, my);
        }
    }

    // ── Mouse input ───────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Convert to panel-relative
            int rx = (int) mouseX - this.leftPos;
            int ry = (int) mouseY - this.topPos;

            // Right arrow (cycle forward)
            if (rx >= ARROW_R_X && rx < ARROW_R_X + ARROW_W
                    && ry >= ARROW_R_Y && ry < ARROW_R_Y + ARROW_H) {
                assert this.minecraft != null;
                this.minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0);
                return true;
            }

            // Left arrow (cycle backward)
            if (rx >= ARROW_L_X && rx < ARROW_L_X + ARROW_W
                    && ry >= ARROW_L_Y && ry < ARROW_L_Y + ARROW_H) {
                assert this.minecraft != null;
                this.minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 1);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
