package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
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
 *  Main panel (176 × 80)          Entity panel (76 × 80, 2 px gap)
 *  ───────────────────────         ────────────────────────────────
 *  y= 0  top border                y= 0  top border
 *  y= 6  "Current Task" label      y= 5  courier name (centred)
 *  y=18  task description          y=17  decorative rule
 *  y=31  divider                   y=17─63  3-D courier model
 *  y=36  "Carrying" label          y=63  decorative rule
 *  y=52  9 read-only item slots    y=80  bottom border
 *  y=68  bottom of slots
 *  y=80  bottom border
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

    // ── Entity panel colours (slightly darker inset) ──────────────────────────
    private static final int COL_EP_BG     = 0xFF252525;
    private static final int COL_EP_BDR_LT = 0xFF555555;
    private static final int COL_EP_BDR_DK = 0xFF111111;

    // ── Dimensions ───────────────────────────────────────────────────────────
    private static final int MAIN_W = 176;
    private static final int MAIN_H = 80;
    private static final int EP_GAP = 2;
    private static final int EP_W   = 104;               // wide enough for "Copper Soul Golem"
    private static final int EP_X   = MAIN_W + EP_GAP;   // 178, panel-relative

    private static final int ENTITY_SCALE = 35;

    public CourierScreen(CourierMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth      = MAIN_W + EP_GAP + EP_W;   // 254
        this.imageHeight     = MAIN_H;                    // 80
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

        // ── Main panel background + bevel border ─────────────────────────────
        g.fill(x + 1,          y + 1,          x + MAIN_W - 1, y + MAIN_H - 1, COL_BG);
        g.fill(x,              y,               x + MAIN_W,     y + 1,          COL_BORDER_LT);
        g.fill(x,              y,               x + 1,          y + MAIN_H,     COL_BORDER_LT);
        g.fill(x,              y + MAIN_H - 1, x + MAIN_W,     y + MAIN_H,     COL_BORDER_DK);
        g.fill(x + MAIN_W - 1, y,              x + MAIN_W,     y + MAIN_H,     COL_BORDER_DK);

        // ── Main panel divider ────────────────────────────────────────────────
        g.fill(x + 4, y + 31, x + MAIN_W - 4, y + 32, COL_DIVIDER);

        // ── Slot backgrounds ──────────────────────────────────────────────────
        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, COL_SLOT_DK);
            g.fill(sx,     sy,     sx + 16, sy + 16, COL_SLOT_LT);
        }

        // ── Entity panel background + bevel border ────────────────────────────
        final int pL = x + EP_X;
        final int pT = y;
        final int pR = pL + EP_W;
        final int pB = y + MAIN_H;
        g.fill(pL + 1, pT + 1, pR - 1, pB - 1, COL_EP_BG);
        g.fill(pL,     pT,     pR,     pT + 1, COL_EP_BDR_LT);
        g.fill(pL,     pT,     pL + 1, pB,     COL_EP_BDR_LT);
        g.fill(pL,     pB - 1, pR,     pB,     COL_EP_BDR_DK);
        g.fill(pR - 1, pT,     pR,     pB,     COL_EP_BDR_DK);

        // Decorative rules framing the model area
        g.fill(pL + 4, pT + 17, pR - 4, pT + 18, COL_DIVIDER);
        g.fill(pL + 4, pB - 18, pR - 4, pB - 17, COL_DIVIDER);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        final int maxW    = MAIN_W - 16;
        final int ellipsisW = this.font.width("\u2026");

        // ── Current Task ─────────────────────────────────────────────────────
        g.drawString(this.font,
                Component.literal("Current Task").withStyle(ChatFormatting.GRAY),
                8, 6, 0xAAAAAA, false);

        String task = this.menu.getCurrentTask();
        if (this.font.width(task) > maxW) {
            task = this.font.plainSubstrByWidth(task, maxW - ellipsisW).trim() + "\u2026";
        }
        g.drawString(this.font,
                Component.literal(task).withStyle(ChatFormatting.WHITE),
                8, 18, 0xFFFFFF, false);

        // ── Carrying label ────────────────────────────────────────────────────
        g.drawString(this.font,
                Component.literal("Carrying").withStyle(ChatFormatting.GRAY),
                8, 36, 0xAAAAAA, false);

        // ── Entity panel: courier name (centred at top) ───────────────────────
        CourierEntity courier = this.menu.getCourier();
        String name = courier != null
                ? courier.getDisplayName().getString()
                : "Copper Soul Golem";
        int epMaxW = EP_W - 8;
        if (this.font.width(name) > epMaxW) {
            name = this.font.plainSubstrByWidth(name, epMaxW - ellipsisW).trim() + "\u2026";
        }
        int nameX = EP_X + (EP_W - this.font.width(name)) / 2;
        g.drawString(this.font,
                Component.literal(name).withStyle(ChatFormatting.WHITE),
                nameX, 5, 0xFFFFFF, false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);

        // ── 3-D courier model ─────────────────────────────────────────────────
        CourierEntity courier = this.menu.getCourier();
        if (courier != null) {
            final int pL = this.leftPos + EP_X + 2;
            final int pT = this.topPos  + 19;
            final int pR = this.leftPos + EP_X + EP_W - 2;
            final int pB = this.topPos  + MAIN_H - 19;
            g.enableScissor(pL, pT, pR, pB);
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    g, pL, pT, pR, pB, ENTITY_SCALE, 0.0f, mx, my, courier);
            g.disableScissor();
        }

        this.renderTooltip(g, mx, my);
    }
}
