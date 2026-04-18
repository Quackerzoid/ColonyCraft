package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;
import village.automation.mod.menu.VillagerWorkerMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI screen for {@link VillagerWorkerEntity}.
 *
 * <p>Three-panel layout (left to right):
 * <pre>
 *   ┌─ Main (178) ─┐ ┌─ Entity (76) ─┐ ┌─ Stats (36) ─┐
 *   │ Tool / Equip │ │   3-D model   │ │  F  │  L      │
 *   │ ──────────── │ │               │ │ bar │ bar      │
 *   │  Inventory   │ │   Name / Job  │ │  F     L       │
 *   └──────────────┘ └───────────────┘ └─────────────── ┘
 * </pre>
 *
 * <p>The Stats panel contains two vertical bars:
 * <ul>
 *   <li>F — Food (green/amber/red like the original horizontal bar)
 *   <li>L — Level XP (blue fill; fills as the worker gains XP toward next level)
 * </ul>
 * Labels "F" and "L" are centred below each bar.
 */
public class VillagerWorkerScreen extends AbstractContainerScreen<VillagerWorkerMenu> {

    // ── Shared colour palette ─────────────────────────────────────────────────
    private static final int COL_BG        = 0xFF3B3B3B;
    private static final int COL_BORDER_LT = 0xFF666666;
    private static final int COL_BORDER_DK = 0xFF1A1A1A;
    private static final int COL_DIVIDER   = 0xFF555555;
    private static final int COL_SLOT_DK   = 0xFF373737;
    private static final int COL_SLOT_LT   = 0xFF8B8B8B;

    // ── Entity-panel colours ──────────────────────────────────────────────────
    private static final int COL_EP_BG     = 0xFF252525;
    private static final int COL_EP_BDR_LT = 0xFF555555;
    private static final int COL_EP_BDR_DK = 0xFF111111;

    // ── Dimensions ────────────────────────────────────────────────────────────
    /** Width of the main (left) panel. */
    private static final int MAIN_W = 178;
    /** Shared height of all panels. */
    private static final int MAIN_H = 184;
    /** Gap between adjacent panels. */
    private static final int PANEL_GAP = 2;
    /** Width of the entity-preview panel. */
    private static final int EP_W = 76;
    /** X of entity panel left edge (GUI-local). */
    private static final int EP_X = MAIN_W + PANEL_GAP;          // 180
    /** Width of the stats panel. */
    private static final int SP_W = 36;
    /** X of stats panel left edge (GUI-local). */
    private static final int SP_X = EP_X + EP_W + PANEL_GAP;     // 258

    // ── Single divider in the main panel ─────────────────────────────────────
    /** Divider above the player inventory section. */
    private static final int DIV_INV = 79;

    // ── Entity-model render scale ─────────────────────────────────────────────
    private static final int ENTITY_SCALE = 40;

    // ── Stats-panel bar geometry (all in GUI-local coords, before adding topPos) ─
    /** Y at which the bars start (top). */
    private static final int SP_BAR_TOP    = 8;
    /** Y at which the bars end (bottom). */
    private static final int SP_BAR_BOT    = MAIN_H - 20;
    /** Fill height of each bar in pixels. */
    private static final int SP_BAR_H      = SP_BAR_BOT - SP_BAR_TOP;   // 156
    /** Width (px) of each individual bar. */
    private static final int SP_BAR_W      = 12;
    /** Local X of the Food (F) bar within the stats panel. */
    private static final int SP_F_X        = 4;   // panel-local
    /** Local X of the Level (L) bar within the stats panel. */
    private static final int SP_L_X        = SP_F_X + SP_BAR_W + 4;    // 20
    /** Y of the "F" / "L" letter labels (panel-local, i.e. from topPos). */
    private static final int SP_LABEL_Y    = MAIN_H - 13;

    // ─────────────────────────────────────────────────────────────────────────

    public VillagerWorkerScreen(VillagerWorkerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth      = SP_X + SP_W;   // 294
        this.imageHeight     = MAIN_H;         // 184
        this.inventoryLabelY = DIV_INV + 1;   // 80 — just below the single divider
    }

    // ── Background ────────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        final int x = this.leftPos;
        final int y = this.topPos;

        // ── Main panel ────────────────────────────────────────────────────────
        g.fill(x + 1,          y + 1,          x + MAIN_W - 1, y + MAIN_H - 1, COL_BG);
        g.fill(x,              y,              x + MAIN_W,     y + 1,          COL_BORDER_LT);
        g.fill(x,              y,              x + 1,          y + MAIN_H,     COL_BORDER_LT);
        g.fill(x,              y + MAIN_H - 1, x + MAIN_W,     y + MAIN_H,     COL_BORDER_DK);
        g.fill(x + MAIN_W - 1, y,              x + MAIN_W,     y + MAIN_H,     COL_BORDER_DK);

        // Single divider above the inventory section
        g.fill(x + 4, y + DIV_INV, x + MAIN_W - 4, y + DIV_INV + 1, COL_DIVIDER);

        // Sunken slot backgrounds
        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, COL_SLOT_DK);
            g.fill(sx,     sy,     sx + 16, sy + 16, COL_SLOT_LT);
        }

        // ── Entity-preview panel ──────────────────────────────────────────────
        final int pL = x + EP_X;
        final int pT = y;
        final int pR = pL + EP_W;
        final int pB = y + MAIN_H;

        g.fill(pL + 1, pT + 1, pR - 1, pB - 1, COL_EP_BG);
        g.fill(pL,     pT,     pR,     pT + 1, COL_EP_BDR_LT);
        g.fill(pL,     pT,     pL + 1, pB,     COL_EP_BDR_LT);
        g.fill(pL,     pB - 1, pR,     pB,     COL_EP_BDR_DK);
        g.fill(pR - 1, pT,     pR,     pB,     COL_EP_BDR_DK);
        g.fill(pL + 4, pT + 17, pR - 4, pT + 18, COL_DIVIDER);
        g.fill(pL + 4, pB - 18, pR - 4, pB - 17, COL_DIVIDER);

        // ── Stats panel ───────────────────────────────────────────────────────
        final int sL = x + SP_X;
        final int sT = y;
        final int sR = sL + SP_W;
        final int sB = y + MAIN_H;

        g.fill(sL + 1, sT + 1, sR - 1, sB - 1, COL_EP_BG);
        g.fill(sL,     sT,     sR,     sT + 1, COL_EP_BDR_LT);
        g.fill(sL,     sT,     sL + 1, sB,     COL_EP_BDR_LT);
        g.fill(sL,     sB - 1, sR,     sB,     COL_EP_BDR_DK);
        g.fill(sR - 1, sT,     sR,     sB,     COL_EP_BDR_DK);

        // Decorative divider above the labels
        g.fill(sL + 2, sB - 18, sR - 2, sB - 17, COL_DIVIDER);

        // ── Food (F) bar ──────────────────────────────────────────────────────
        renderVerticalBar(g, sL + SP_F_X, y + SP_BAR_TOP, SP_BAR_W, SP_BAR_H,
                this.menu.getFoodLevel(), VillagerWorkerEntity.MAX_FOOD, true);

        // ── Level / XP (L) bar ────────────────────────────────────────────────
        int level         = this.menu.getLevel();
        int xp            = this.menu.getXp();
        int xpForNext     = this.menu.getXpForNextLevel();
        // At max level the bar is always full
        int fillXp    = (level >= VillagerWorkerEntity.MAX_LEVEL) ? xpForNext : xp;
        int maxXp     = (level >= VillagerWorkerEntity.MAX_LEVEL) ? 1         : xpForNext;
        renderVerticalBar(g, sL + SP_L_X, y + SP_BAR_TOP, SP_BAR_W, SP_BAR_H,
                fillXp, maxXp, false);
    }

    /**
     * Draws a single vertical bar (fills from bottom to top).
     *
     * @param x       absolute screen X of the bar's left edge
     * @param y       absolute screen Y of the bar's top edge
     * @param w       bar width in pixels
     * @param h       bar height in pixels
     * @param current current value
     * @param max     maximum value
     * @param isFood  {@code true} = food colours (green/amber/red);
     *                {@code false} = XP colour (blue gradient)
     */
    private static void renderVerticalBar(GuiGraphics g, int x, int y, int w, int h,
                                          int current, int max, boolean isFood) {
        // Sunken track
        g.fill(x,     y,     x + w,     y + h,     COL_SLOT_DK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF1E1E1E);

        if (max <= 0) return;
        float fraction = Math.min(1.0f, (float) current / max);
        int fillPx = Math.round((h - 2) * fraction);
        if (fillPx <= 0) return;

        int fillColor;
        if (isFood) {
            if (fraction > 0.5f) {
                fillColor = 0xFF44AA22;   // green  — well fed
            } else if (current >= VillagerWorkerEntity.WORK_FOOD_THRESHOLD) {
                fillColor = 0xFFFFAA00;   // amber  — hungry but working
            } else {
                fillColor = 0xFFCC2222;   // red    — too hungry to work
            }
        } else {
            // XP: darker blue at bottom, brighter blue toward the top
            fillColor = 0xFF3399EE;
        }

        // Fill from the BOTTOM of the bar upward
        int fillBottom = y + h - 1;
        int fillTop    = fillBottom - fillPx;
        g.fill(x + 1, fillTop, x + w - 1, fillBottom, fillColor);
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // ── Main panel ────────────────────────────────────────────────────────
        g.drawString(this.font,
                Component.literal("Tool").withStyle(ChatFormatting.DARK_GRAY),
                8, 6, 0xAAAAAA, false);
        g.drawString(this.font,
                Component.literal("Equipment").withStyle(ChatFormatting.DARK_GRAY),
                62, 6, 0xAAAAAA, false);
        g.drawString(this.font,
                Component.translatable("container.inventory"),
                8, this.inventoryLabelY, 0x404040, false);

        // ── Entity-preview panel ──────────────────────────────────────────────
        VillagerWorkerEntity worker = this.menu.getEntity();
        JobType job = this.menu.getJob();

        String nameStr = (worker != null) ? worker.getDisplayName().getString() : "Worker";
        final int maxW      = EP_W - 10;
        final int ellipsisW = this.font.width("\u2026");
        if (this.font.width(nameStr) > maxW) {
            nameStr = this.font.plainSubstrByWidth(nameStr, maxW - ellipsisW).trim() + "\u2026";
        }
        int nameX = EP_X + (EP_W - this.font.width(nameStr)) / 2;
        g.drawString(this.font,
                Component.literal(nameStr).withStyle(ChatFormatting.WHITE),
                nameX, 5, 0xFFFFFF, false);

        Component badge = Component.literal(job.getTitle())
                .withStyle(job == JobType.UNEMPLOYED ? ChatFormatting.DARK_GRAY : ChatFormatting.GOLD);
        int badgeX = EP_X + (EP_W - this.font.width(badge.getString())) / 2;
        g.drawString(this.font, badge, badgeX, MAIN_H - 13, 0xFFFFFF, false);

        // ── Stats panel labels ────────────────────────────────────────────────
        // "F" centred under the food bar, "L" centred under the XP bar
        int fCentreX = SP_X + SP_F_X + SP_BAR_W / 2 - this.font.width("F") / 2;
        int lCentreX = SP_X + SP_L_X + SP_BAR_W / 2 - this.font.width("L") / 2;
        g.drawString(this.font,
                Component.literal("F").withStyle(ChatFormatting.GRAY),
                fCentreX, SP_LABEL_Y, 0xAAAAAA, false);
        g.drawString(this.font,
                Component.literal("L").withStyle(ChatFormatting.GRAY),
                lCentreX, SP_LABEL_Y, 0xAAAAAA, false);

        // Level number above the L bar
        String lvlStr = String.valueOf(this.menu.getLevel());
        int lvlX = SP_X + SP_L_X + SP_BAR_W / 2 - this.font.width(lvlStr) / 2;
        g.drawString(this.font,
                Component.literal(lvlStr).withStyle(ChatFormatting.WHITE),
                lvlX, SP_BAR_TOP - 1, 0xFFFFFF, false);
    }

    // ── Full render ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);

        // ── 3-D entity preview ────────────────────────────────────────────────
        VillagerWorkerEntity worker = this.menu.getEntity();
        if (worker != null) {
            final int pL = this.leftPos + EP_X + 2;
            final int pT = this.topPos  + 19;
            final int pR = this.leftPos + EP_X + EP_W - 2;
            final int pB = this.topPos  + MAIN_H - 19;

            g.enableScissor(pL, pT, pR, pB);
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    g, pL, pT, pR, pB, ENTITY_SCALE, 0.0f, mx, my, worker);
            g.disableScissor();
        }

        // ── Slot tooltips ─────────────────────────────────────────────────────
        this.renderTooltip(g, mx, my);

        // ── Stats-bar tooltips ────────────────────────────────────────────────
        final int sL = this.leftPos + SP_X;
        final int sT = this.topPos  + SP_BAR_TOP;
        final int sB = this.topPos  + SP_BAR_BOT;

        // Food bar tooltip
        int fbL = sL + SP_F_X;
        int fbR = fbL + SP_BAR_W;
        if (mx >= fbL && mx < fbR && my >= sT && my < sB) {
            int food = this.menu.getFoodLevel();
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("Food: ")
                    .append(Component.literal(food + " / " + VillagerWorkerEntity.MAX_FOOD)
                            .withStyle(ChatFormatting.WHITE))
                    .withStyle(ChatFormatting.GRAY));
            if (food < VillagerWorkerEntity.WORK_FOOD_THRESHOLD) {
                lines.add(Component.literal("Too hungry to work!").withStyle(ChatFormatting.RED));
            } else if (food < VillagerWorkerEntity.MAX_FOOD / 2) {
                lines.add(Component.literal("Getting hungry\u2026").withStyle(ChatFormatting.YELLOW));
            }
            g.renderComponentTooltip(this.font, lines, mx, my);
        }

        // XP bar tooltip
        int lbL = sL + SP_L_X;
        int lbR = lbL + SP_BAR_W;
        if (mx >= lbL && mx < lbR && my >= sT && my < sB) {
            int level  = this.menu.getLevel();
            int xp     = this.menu.getXp();
            int needed = this.menu.getXpForNextLevel();
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("Level: ")
                    .append(Component.literal(level + " / " + VillagerWorkerEntity.MAX_LEVEL)
                            .withStyle(ChatFormatting.WHITE))
                    .withStyle(ChatFormatting.GRAY));
            if (level < VillagerWorkerEntity.MAX_LEVEL) {
                lines.add(Component.literal("XP: ")
                        .append(Component.literal(xp + " / " + needed)
                                .withStyle(ChatFormatting.WHITE))
                        .withStyle(ChatFormatting.GRAY));
            } else {
                lines.add(Component.literal("Max level reached!").withStyle(ChatFormatting.GOLD));
            }
            g.renderComponentTooltip(this.font, lines, mx, my);
        }
    }
}
