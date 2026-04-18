package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import com.mojang.math.Axis;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import village.automation.mod.blockentity.BeekeeperBlockEntity;
import village.automation.mod.menu.BeekeeperBlockMenu;

import javax.annotation.Nullable;

/**
 * Redesigned Beekeeper workplace GUI.
 *
 * <h3>Layout overview</h3>
 * <pre>
 *   ┌──────────────────────────────────────────┐ ┌────────────────┐
 *   │ Village Beehive                          │ │ Worker         │
 *   ├──────────────────────────────────────────┤ ├────────────────┤
 *   │ Fuel (logs)  │ Pollen │ Honeycomb output │ │ <Name>         │
 *   │  [3×3 grid]  │ [bar]  │   [3×3 grid]    │ │ <Job>          │
 *   ├──────────────────────────────────────────┤ ├────────────────┤
 *   │  [Smoking...] ▓▓▓▓▓▓▓▓▓▓░░░░░░ progress │ │ Bees    N / 4  │
 *   ├──────────────────────────────────────────┤ ├────────────────┤
 *   │ Inventory                                │ │ [●][●][○][○]   │
 *   │  [player 3×9]                            │ └────────────────┘
 *   │  [hotbar  1×9]                           │
 *   └──────────────────────────────────────────┘
 * </pre>
 *
 * <p>Main panel: 176 × 192 px.
 * Sidebar: 90 × 192 px, 4 px gap to the right.
 * Total imageWidth: 270 px.
 */
public class BeekeeperBlockScreen extends AbstractContainerScreen<BeekeeperBlockMenu> {

    // ── Main panel ────────────────────────────────────────────────────────────
    private static final int MAIN_W = 176;
    private static final int MAIN_H = 192;

    // ── Sidebar ───────────────────────────────────────────────────────────────
    /** Pixel gap between main panel right edge and sidebar left edge. */
    private static final int SIDE_GAP = 4;
    /** Sidebar width. */
    private static final int SIDE_W   = 90;
    /** Panel-relative x origin of the sidebar. */
    private static final int SIDE_X   = MAIN_W + SIDE_GAP;   // 180

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int COL_BG          = 0xFF3B3B3B;
    private static final int COL_BORDER_LT   = 0xFF666666;
    private static final int COL_BORDER_DK   = 0xFF1A1A1A;
    private static final int COL_DIVIDER     = 0xFF555555;
    private static final int COL_SLOT_DK     = 0xFF373737;
    private static final int COL_SLOT_LT     = 0xFF8B8B8B;
    private static final int COL_ACCENT      = 0xFFDDB33A;   // honey amber
    private static final int COL_BAR_BG      = 0xFF1E1E1E;
    private static final int COL_BAR_SMOKE   = 0xFFAA7733;

    // ── Sidebar colours ───────────────────────────────────────────────────────
    private static final int COL_SIDE_BG     = 0xFF2A2A2A;
    private static final int COL_SIDE_BDR_LT = 0xFF555555;
    private static final int COL_SIDE_BDR_DK = 0xFF111111;

    // ── Pollen bar (panel-relative, in the gap between the two slot grids) ────
    private static final int POLLEN_BAR_X    = 74;
    private static final int POLLEN_BAR_Y    = 28;   // aligns with top slot row
    private static final int POLLEN_BAR_W    = 10;
    private static final int POLLEN_BAR_H    = 52;   // matches 3 × 18 px grid height
    private static final int COL_POLLEN_BG   = 0xFF1A1A1A;
    private static final int COL_POLLEN_FILL = 0xFFDDB33A;
    private static final int COL_POLLEN_FULL = 0xFFFFDD44;

    // ── Bee indicator dots (sidebar-relative) ─────────────────────────────────
    private static final int DOT_SIZE = 16;   // each square is 16×16 px
    private static final int DOT_GAP  = 6;    // gap between squares

    /** Lazily created dummy bee used solely for sidebar face rendering. */
    @Nullable private Bee dummyBee;

    public BeekeeperBlockScreen(BeekeeperBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth  = MAIN_W + SIDE_GAP + SIDE_W;  // 270
        this.imageHeight = MAIN_H;                       // 192
        // We draw all labels ourselves — suppress the parent defaults
        this.titleLabelX     = -9999;
        this.titleLabelY     = -9999;
        this.inventoryLabelY = -9999;
    }

    // ── Background (drawn before items so it appears behind slots) ────────────

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        final int x = this.leftPos;
        final int y = this.topPos;

        // ── Main panel body + bevel border ────────────────────────────────────
        g.fill(x + 1,          y + 1,          x + MAIN_W - 1, y + MAIN_H - 1, COL_BG);
        g.fill(x,              y,               x + MAIN_W,     y + 1,          COL_BORDER_LT);
        g.fill(x,              y,               x + 1,          y + MAIN_H,     COL_BORDER_LT);
        g.fill(x,              y + MAIN_H - 1, x + MAIN_W,     y + MAIN_H,     COL_BORDER_DK);
        g.fill(x + MAIN_W - 1, y,              x + MAIN_W,     y + MAIN_H,     COL_BORDER_DK);

        // Title divider
        g.fill(x + 4, y + 14, x + MAIN_W - 4, y + 15, COL_DIVIDER);

        // ── Slot backgrounds ──────────────────────────────────────────────────
        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, COL_SLOT_DK);
            g.fill(sx,     sy,     sx + 16, sy + 16, COL_SLOT_LT);
        }

        // ── Pollen bar ────────────────────────────────────────────────────────
        // Outer border (1 px, matches slot-border style)
        g.fill(x + POLLEN_BAR_X - 1, y + POLLEN_BAR_Y - 1,
               x + POLLEN_BAR_X + POLLEN_BAR_W + 1, y + POLLEN_BAR_Y + POLLEN_BAR_H + 1,
               COL_SLOT_DK);
        // Dark inner background
        g.fill(x + POLLEN_BAR_X, y + POLLEN_BAR_Y,
               x + POLLEN_BAR_X + POLLEN_BAR_W, y + POLLEN_BAR_Y + POLLEN_BAR_H,
               COL_POLLEN_BG);
        // Fill from bottom to top
        int pollen = menu.getPollenCount();
        if (pollen > 0) {
            float fraction = Math.min(1f, pollen / (float) BeekeeperBlockEntity.MAX_POLLEN);
            int   fillH    = Math.max(1, (int) (POLLEN_BAR_H * fraction));
            int   fillY    = y + POLLEN_BAR_Y + (POLLEN_BAR_H - fillH);
            boolean full   = pollen >= BeekeeperBlockEntity.MAX_POLLEN;
            g.fill(x + POLLEN_BAR_X, fillY,
                   x + POLLEN_BAR_X + POLLEN_BAR_W, y + POLLEN_BAR_Y + POLLEN_BAR_H,
                   full ? COL_POLLEN_FULL : COL_POLLEN_FILL);
        }

        // Post-grid divider
        g.fill(x + 4, y + 84, x + MAIN_W - 4, y + 85, COL_DIVIDER);

        // ── Smoking progress bar ──────────────────────────────────────────────
        final int barX = x + 8;
        final int barY = y + 100;
        final int barW = MAIN_W - 16;
        g.fill(barX - 1, barY - 1, barX + barW + 1, barY + 7, COL_BAR_BG);
        if (menu.isFuelBurning()) {
            int fill = (int) (barW * menu.getSmokingProgress());
            if (fill > 0) g.fill(barX, barY, barX + fill, barY + 6, COL_BAR_SMOKE);
        }

        // Pre-inventory divider
        g.fill(x + 4, y + 110, x + MAIN_W - 4, y + 111, COL_DIVIDER);

        // ── Sidebar panel body + bevel border ─────────────────────────────────
        final int sX = x + SIDE_X;
        g.fill(sX + 1,          y + 1,          sX + SIDE_W - 1, y + MAIN_H - 1, COL_SIDE_BG);
        g.fill(sX,              y,               sX + SIDE_W,     y + 1,          COL_SIDE_BDR_LT);
        g.fill(sX,              y,               sX + 1,          y + MAIN_H,     COL_SIDE_BDR_LT);
        g.fill(sX,              y + MAIN_H - 1, sX + SIDE_W,     y + MAIN_H,     COL_SIDE_BDR_DK);
        g.fill(sX + SIDE_W - 1, y,              sX + SIDE_W,     y + MAIN_H,     COL_SIDE_BDR_DK);

        // Sidebar internal dividers
        g.fill(sX + 4, y + 14, sX + SIDE_W - 4, y + 15, COL_DIVIDER);   // below "Worker"
        g.fill(sX + 4, y + 46, sX + SIDE_W - 4, y + 47, COL_DIVIDER);   // below worker info
        g.fill(sX + 4, y + 68, sX + SIDE_W - 4, y + 69, COL_DIVIDER);   // below "Bees" + count

        // ── Bee slot indicators (2 × 2 grid — bee face or empty) ─────────────
        int beeCount = menu.getBeeCount();
        int dotsW    = 2 * DOT_SIZE + DOT_GAP;               // total width of the 2-col grid
        int dotsX    = sX + (SIDE_W - dotsW) / 2;            // horizontally centred
        int dotsY    = y + 76;

        // Ensure the dummy bee exists for rendering
        if (dummyBee == null && this.minecraft != null && this.minecraft.level != null) {
            dummyBee = EntityType.BEE.create(this.minecraft.level);
        }

        for (int i = 0; i < BeekeeperBlockEntity.MAX_BEES; i++) {
            int col = i % 2;
            int row = i / 2;
            int dx  = dotsX + col * (DOT_SIZE + DOT_GAP);
            int dy  = dotsY + row * (DOT_SIZE + DOT_GAP);
            boolean occupied = i < beeCount;

            // Slot background: amber border for occupied, grey for empty
            g.fill(dx,     dy,     dx + DOT_SIZE,     dy + DOT_SIZE,
                   occupied ? COL_ACCENT : 0xFF444444);
            g.fill(dx + 1, dy + 1, dx + DOT_SIZE - 1, dy + DOT_SIZE - 1,
                   0xFF1A1A1A);

            if (occupied && dummyBee != null) {
                // Semi-side profile: pose the dummy bee forward then rotate ~40° around Y.
                dummyBee.yBodyRot  = 180f;
                dummyBee.yHeadRot  = 180f;
                dummyBee.yHeadRotO = 180f;
                dummyBee.setYRot(180f);
                dummyBee.setXRot(0f);

                // Standard inventory flip (Z=180°) + semi-side Y rotation
                Quaternionf pose = new Quaternionf(Axis.ZP.rotationDegrees(180f));
                pose.mul(Axis.YP.rotationDegrees(40f));

                int cx = dx + DOT_SIZE / 2;
                InventoryScreen.renderEntityInInventory(
                        g,
                        cx, (float)(dy + DOT_SIZE - 1),
                        7,
                        new Vector3f(0f, 0f, 0f),
                        pose,
                        null,
                        dummyBee);
            }
        }
    }

    // ── Labels (drawn after items, in panel-relative coords) ──────────────────

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // ── Main panel ────────────────────────────────────────────────────────

        // Title — amber so it stands out immediately
        g.drawString(this.font, this.title, 8, 5, COL_ACCENT & 0xFFFFFF, false);

        // Column labels above the fuel and honeycomb grids (pollen bar has no label)
        g.drawString(this.font,
                Component.literal("Fuel").withStyle(ChatFormatting.GRAY),
                8, 19, 0xAAAAAA, false);
        g.drawString(this.font,
                Component.literal("Honeycomb").withStyle(ChatFormatting.GRAY),
                98, 19, 0xAAAAAA, false);

        // Smoking status (left side, in the space above the progress bar)
        int pollenNow = menu.getPollenCount();
        if (menu.isFuelBurning()) {
            g.drawString(this.font,
                    Component.literal("Smoking…").withStyle(ChatFormatting.GRAY),
                    8, 90, 0xAA8844, false);
        } else if (pollenNow > 0 && menu.hasFuel()) {
            g.drawString(this.font,
                    Component.literal("Awaiting worker").withStyle(ChatFormatting.GRAY),
                    8, 90, 0x887755, false);
        } else if (pollenNow > 0) {
            g.drawString(this.font,
                    Component.literal("No fuel").withStyle(ChatFormatting.RED),
                    8, 90, 0xFF5555, false);
        } else {
            g.drawString(this.font,
                    Component.literal("Idle").withStyle(ChatFormatting.DARK_GRAY),
                    8, 90, 0x666666, false);
        }

        // Inventory section label
        g.drawString(this.font, "Inventory", 8, 114, 0x777777, false);

        // ── Sidebar ───────────────────────────────────────────────────────────
        final int sxText = SIDE_X + 6;   // left-aligned text x inside sidebar

        // "Worker" section header
        g.drawString(this.font,
                Component.literal("Worker").withStyle(ChatFormatting.GRAY),
                sxText, 5, 0x888888, false);

        // Worker name / assignment status
        if (!menu.hasWorker()) {
            g.drawString(this.font,
                    Component.literal("Unassigned").withStyle(ChatFormatting.DARK_GRAY),
                    sxText, 20, 0x666666, false);
        } else {
            // Truncate name to fit sidebar width
            String name = menu.getWorkerName();
            int maxW = SIDE_W - 12;
            if (this.font.width(name) > maxW) {
                name = this.font.plainSubstrByWidth(name, maxW - this.font.width("…")) + "…";
            }
            g.drawString(this.font,
                    Component.literal(name).withStyle(ChatFormatting.WHITE),
                    sxText, 20, 0xFFFFFF, false);
            g.drawString(this.font,
                    Component.literal(menu.getWorkerJob().getTitle()).withStyle(ChatFormatting.GOLD),
                    sxText, 31, COL_ACCENT & 0xFFFFFF, false);
        }

        // "Bees" section header + count on same row (header left, count right)
        g.drawString(this.font,
                Component.literal("Bees").withStyle(ChatFormatting.GRAY),
                sxText, 52, 0x888888, false);

        String countStr = menu.getBeeCount() + " / " + BeekeeperBlockEntity.MAX_BEES;
        int    countX   = SIDE_X + SIDE_W - 6 - this.font.width(countStr);
        g.drawString(this.font,
                Component.literal(countStr).withStyle(ChatFormatting.WHITE),
                countX, 52, 0xCCCCCC, false);
    }

    // ── Full render pass (background → slots/items → labels → tooltips) ───────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);

        // ── Pollen bar tooltip ────────────────────────────────────────────────
        int barScreenX = this.leftPos + POLLEN_BAR_X;
        int barScreenY = this.topPos  + POLLEN_BAR_Y;
        if (mx >= barScreenX && mx <= barScreenX + POLLEN_BAR_W
                && my >= barScreenY && my <= barScreenY + POLLEN_BAR_H) {
            int p = menu.getPollenCount();
            g.renderTooltip(this.font,
                    Component.literal("Pollen: " + p + " / " + BeekeeperBlockEntity.MAX_POLLEN)
                             .withStyle(ChatFormatting.GOLD),
                    mx, my);
        }

        this.renderTooltip(g, mx, my);
    }

}
