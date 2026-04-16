package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;
import village.automation.mod.menu.VillagerWorkerMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI screen for {@link VillagerWorkerEntity}.
 *
 * <p>Layout (relative to panel top-left, main panel only):
 * <pre>
 *   y=  0 ─ panel top border
 *   y=  6   "Tool" label (x=8)  |  "Equipment" label (x=62)
 *   y= 17   worker 3×3 grid (x=62)
 *   y= 36   tool slot (x=35)
 *   y= 71   grid bottom
 *   y= 79 ─ divider
 *   y= 82   "Food" label
 *   y= 88   food bar (width = panel − 16 px margins)
 *   y= 94 ─ divider
 *   y= 95   "Inventory" label
 *   y=102   player main inventory (3 rows × 9)
 *   y=160   player hotbar
 *   y=183 ─ panel bottom border
 * </pre>
 *
 * <p>An entity-preview panel of the same height sits to the right of the
 * main panel, separated by a 2 px gap, showing the worker's 3-D model with
 * the worker name at the top and job title at the bottom.
 */
public class VillagerWorkerScreen extends AbstractContainerScreen<VillagerWorkerMenu> {

    // ── Shared colour palette (matches the rest of the mod's screens) ─────────
    private static final int COL_BG        = 0xFF3B3B3B;
    private static final int COL_BORDER_LT = 0xFF666666;
    private static final int COL_BORDER_DK = 0xFF1A1A1A;
    private static final int COL_DIVIDER   = 0xFF555555;
    private static final int COL_SLOT_DK   = 0xFF373737;
    private static final int COL_SLOT_LT   = 0xFF8B8B8B;

    // ── Entity-panel colours (slightly darker to read as a distinct viewport) ─
    private static final int COL_EP_BG     = 0xFF252525;
    private static final int COL_EP_BDR_LT = 0xFF555555;
    private static final int COL_EP_BDR_DK = 0xFF111111;

    // ── Dimensions ────────────────────────────────────────────────────────────
    /** Width of the main (left) panel. */
    private static final int MAIN_W = 178;
    /** Shared height of both panels. */
    private static final int MAIN_H = 184;
    /** Gap between main panel and entity panel. */
    private static final int EP_GAP = 2;
    /** Width of the entity-preview panel. */
    private static final int EP_W   = 76;
    /** X offset of the entity panel's left edge, in GUI-local coordinates. */
    private static final int EP_X   = MAIN_W + EP_GAP;   // = 180

    // ── Food bar layout (GUI-local coordinates) ───────────────────────────────
    private static final int BAR_X      = 8;
    private static final int BAR_Y      = 88;
    private static final int BAR_W      = MAIN_W - 16;   // 8 px margin each side  = 162
    private static final int BAR_H      = 6;

    // ── Key Y values ─────────────────────────────────────────────────────────
    private static final int DIV_FOOD = 79;   // divider below worker-item area
    private static final int DIV_INV  = 94;   // divider below food area

    // ── Entity-model render scale ─────────────────────────────────────────────
    private static final int ENTITY_SCALE = 40;

    // ─────────────────────────────────────────────────────────────────────────

    public VillagerWorkerScreen(VillagerWorkerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth      = MAIN_W + EP_GAP + EP_W;   // 178 + 2 + 76 = 256
        this.imageHeight     = MAIN_H;                    // 184
        this.inventoryLabelY = DIV_INV + 1;              // 95 — just below the divider
    }

    // ── Background ────────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        final int x = this.leftPos;
        final int y = this.topPos;

        // ── Main panel ────────────────────────────────────────────────────────

        // Body fill
        g.fill(x + 1,          y + 1,          x + MAIN_W - 1, y + MAIN_H - 1, COL_BG);

        // Bevel border — top + left lighter, bottom + right darker
        g.fill(x,              y,              x + MAIN_W,     y + 1,          COL_BORDER_LT);
        g.fill(x,              y,              x + 1,          y + MAIN_H,     COL_BORDER_LT);
        g.fill(x,              y + MAIN_H - 1, x + MAIN_W,     y + MAIN_H,     COL_BORDER_DK);
        g.fill(x + MAIN_W - 1, y,              x + MAIN_W,     y + MAIN_H,     COL_BORDER_DK);

        // Horizontal dividers
        g.fill(x + 4, y + DIV_FOOD,     x + MAIN_W - 4, y + DIV_FOOD + 1, COL_DIVIDER);
        g.fill(x + 4, y + DIV_INV,      x + MAIN_W - 4, y + DIV_INV  + 1, COL_DIVIDER);

        // ── Sunken slot backgrounds (for every slot in the menu) ──────────────
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

        // Body fill
        g.fill(pL + 1, pT + 1, pR - 1, pB - 1, COL_EP_BG);

        // Bevel border
        g.fill(pL,     pT,     pR,     pT + 1, COL_EP_BDR_LT);
        g.fill(pL,     pT,     pL + 1, pB,     COL_EP_BDR_LT);
        g.fill(pL,     pB - 1, pR,     pB,     COL_EP_BDR_DK);
        g.fill(pR - 1, pT,     pR,     pB,     COL_EP_BDR_DK);

        // Inner decorative rules — below the name, above the job badge
        g.fill(pL + 4, pT + 17, pR - 4, pT + 18, COL_DIVIDER);
        g.fill(pL + 4, pB - 18, pR - 4, pB - 17, COL_DIVIDER);

        // ── Food bar ─────────────────────────────────────────────────────────

        final int bL = x + BAR_X;
        final int bT = y + BAR_Y;
        final int bR = bL + BAR_W;
        final int bB = bT + BAR_H;

        // Sunken track
        g.fill(bL,     bT,     bR,     bB,     COL_SLOT_DK);
        g.fill(bL + 1, bT + 1, bR - 1, bB - 1, 0xFF1E1E1E);

        // Coloured fill
        int   food     = this.menu.getFoodLevel();
        float fraction = (float) food / VillagerWorkerEntity.MAX_FOOD;
        int   fillPx   = Math.round((BAR_W - 2) * fraction);
        if (fillPx > 0) {
            int fillColor;
            if (fraction > 0.5f) {
                fillColor = 0xFF44AA22;   // green  — well fed
            } else if (food >= VillagerWorkerEntity.WORK_FOOD_THRESHOLD) {
                fillColor = 0xFFFFAA00;   // amber  — hungry but working
            } else {
                fillColor = 0xFFCC2222;   // red    — too hungry to work
            }
            g.fill(bL + 1, bT + 1, bL + 1 + fillPx, bB - 1, fillColor);
        }
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // ── Main panel ────────────────────────────────────────────────────────

        // Section headers above the worker-item slots
        g.drawString(this.font,
                Component.literal("Tool").withStyle(ChatFormatting.DARK_GRAY),
                8, 6, 0xAAAAAA, false);
        g.drawString(this.font,
                Component.literal("Equipment").withStyle(ChatFormatting.DARK_GRAY),
                62, 6, 0xAAAAAA, false);

        // "Food" caption above the bar
        g.drawString(this.font,
                Component.literal("Food").withStyle(ChatFormatting.GRAY),
                BAR_X, BAR_Y - 10, 0xAAAAAA, false);

        // "Inventory" label — standard mod style (dark gray)
        g.drawString(this.font,
                Component.translatable("container.inventory"),
                8, this.inventoryLabelY, 0x404040, false);

        // ── Entity-preview panel ──────────────────────────────────────────────

        VillagerWorkerEntity worker = this.menu.getEntity();
        JobType job = this.menu.getJob();

        // Worker name — centred in the top band of the entity panel
        String nameStr = (worker != null) ? worker.getDisplayName().getString() : "Worker";
        // Truncate if it overflows the panel interior (EP_W minus 8 px padding each side)
        int maxW = EP_W - 10;
        while (this.font.width(nameStr) > maxW && nameStr.length() > 4) {
            nameStr = nameStr.substring(0, nameStr.length() - 1);
        }
        if (this.font.width(nameStr) > maxW) {
            nameStr = nameStr.substring(0, nameStr.length() - 1) + "\u2026"; // ellipsis
        }
        int nameX = EP_X + (EP_W - this.font.width(nameStr)) / 2;
        g.drawString(this.font,
                Component.literal(nameStr).withStyle(ChatFormatting.WHITE),
                nameX, 5, 0xFFFFFF, false);

        // Job badge — centred in the bottom band of the entity panel
        Component badge = Component.literal(job.getTitle())
                .withStyle(job == JobType.UNEMPLOYED ? ChatFormatting.DARK_GRAY : ChatFormatting.GOLD);
        int badgeX = EP_X + (EP_W - this.font.width(badge.getString())) / 2;
        g.drawString(this.font, badge, badgeX, MAIN_H - 13, 0xFFFFFF, false);
    }

    // ── Full render ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        // Standard mod order: dim background → bg fills → slots/items → overlays.
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);

        // ── 3-D entity preview ────────────────────────────────────────────────
        // Drawn after super.render() so it sits above the slot backgrounds but
        // the entity panel is entirely outside the slot area, so z-order is safe.
        VillagerWorkerEntity worker = this.menu.getEntity();
        if (worker != null) {
            // Scissor to the area between the two decorative dividers
            // (below the name band, above the job badge band).
            final int pL = this.leftPos + EP_X + 1;
            final int pT = this.topPos  + 19;
            final int pR = this.leftPos + EP_X + EP_W - 1;
            final int pB = this.topPos  + MAIN_H - 19;

            g.enableScissor(pL, pT, pR, pB);

            // Horizontal centre of the panel; vertical position places the entity
            // at ~65 % of the panel height so the head clears the name divider.
            float cx = (pL + pR) * 0.5f;
            float cy = this.topPos + MAIN_H * 0.65f;

            // Mouse position drives ambient lighting for a subtle interactive feel.
            Vector3f lightDelta = new Vector3f(cx - mx, cy - my, 0f);

            // rotateZ(π)  →  entity faces the viewer
            // rotateX(-15°)  →  slight downward camera tilt for a natural angle
            Quaternionf pose     = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf camAngle = new Quaternionf().rotateX((float) Math.toRadians(-15.0));

            InventoryScreen.renderEntityInInventory(
                    g, cx, cy,
                    ENTITY_SCALE,
                    lightDelta,
                    pose, camAngle,
                    worker);

            g.disableScissor();
        }

        // ── Slot tooltips ─────────────────────────────────────────────────────
        this.renderTooltip(g, mx, my);

        // ── Food bar tooltip ──────────────────────────────────────────────────
        final int bL = this.leftPos + BAR_X;
        final int bT = this.topPos  + BAR_Y;
        final int bR = bL + BAR_W;
        final int bB = bT + BAR_H;

        if (mx >= bL && mx < bR && my >= bT && my < bB) {
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
    }
}
