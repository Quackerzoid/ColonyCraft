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
 * <p>Layout — main panel (panel-relative coordinates):
 * <pre>
 *  Normal variant (176 × 80)        Ender variant (176 × 104)
 *  ────────────────────────          ─────────────────────────
 *  y= 0  top border                  … same as normal …
 *  y= 6  "Current Task" label        y=80  divider
 *  y=18  task description            y=83  "Teleport Cooldown" (gray)
 *  y=31  divider                     y=83  time-remaining (right-aligned)
 *  y=36  "Carrying" label            y=93  cooldown bar (8 px)
 *  y=52  9 read-only item slots     y=104  bottom border
 *  y=80  bottom border
 * </pre>
 * Entity panel (76 × mainH, 2 px gap to the right) scales with mainH.
 */
public class CourierScreen extends AbstractContainerScreen<CourierMenu> {

    // ── Colour palette ────────────────────────────────────────────────────────
    private static final int COL_BG        = 0xFF3B3B3B;
    private static final int COL_BORDER_LT = 0xFF666666;
    private static final int COL_BORDER_DK = 0xFF1A1A1A;
    private static final int COL_DIVIDER   = 0xFF555555;
    private static final int COL_SLOT_DK   = 0xFF373737;
    private static final int COL_SLOT_LT   = 0xFF8B8B8B;

    // ── Entity panel colours ──────────────────────────────────────────────────
    private static final int COL_EP_BG     = 0xFF252525;
    private static final int COL_EP_BDR_LT = 0xFF555555;
    private static final int COL_EP_BDR_DK = 0xFF111111;

    // ── Teleport bar colours ──────────────────────────────────────────────────
    private static final int COL_BAR_BG    = 0xFF222222;
    private static final int COL_BAR_READY = 0xFF00EE88;   // green tinge when ≥ 90 % charged
    private static final int COL_BAR_FILL  = 0xFF7722EE;   // ender purple

    // ── Honey bar colours ─────────────────────────────────────────────────────
    private static final int COL_HONEY_BAR_BG   = 0xFF222222;
    private static final int COL_HONEY_BAR_FILL = 0xFFDDB33A;   // amber honey
    private static final int COL_HONEY_FULL     = 0xFFFFDD55;   // bright gold when full

    // ── Fixed dimensions ──────────────────────────────────────────────────────
    private static final int MAIN_W       = 176;
    private static final int MAIN_H_BASE  = 108;           // +28 px for honey section
    private static final int MAIN_H_ENDER = 132;           // extra 24 px for teleport section
    private static final int EP_GAP       = 2;
    private static final int EP_W         = 104;
    private static final int EP_X         = MAIN_W + EP_GAP;  // panel-relative x of entity panel

    // ── Honey bar geometry (panel-relative) ───────────────────────────────────
    /** x of the honey input slot (panel-relative). */
    private static final int HONEY_SLOT_X  = 7;
    /** y of the honey input slot (panel-relative). */
    private static final int HONEY_SLOT_Y  = 82;
    /** x-origin of the vertical honey bar. */
    private static final int HONEY_BAR_X   = 32;
    /** y-origin of the vertical honey bar (top of bar). */
    private static final int HONEY_BAR_Y   = 82;
    /** Width of the vertical honey bar. */
    private static final int HONEY_BAR_W   = 12;
    /** Height of the vertical honey bar. */
    private static final int HONEY_BAR_H   = 18;

    private static final int ENTITY_SCALE = 35;

    // ── Instance fields ───────────────────────────────────────────────────────
    /** Actual panel height — 80 for normal couriers, 104 for ender variant. */
    private final int mainH;
    /** Cached flag so we avoid repeated entity look-ups in every render call. */
    private final boolean isEnder;

    public CourierScreen(CourierMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        CourierEntity courier = menu.getCourier();
        this.isEnder         = courier != null && courier.isEnderVariant();
        this.mainH           = isEnder ? MAIN_H_ENDER : MAIN_H_BASE;
        this.imageWidth      = MAIN_W + EP_GAP + EP_W;   // 282
        this.imageHeight     = mainH;
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

        // ── Main panel background + bevel border ──────────────────────────────
        g.fill(x + 1,          y + 1,          x + MAIN_W - 1, y + mainH - 1, COL_BG);
        g.fill(x,              y,               x + MAIN_W,     y + 1,          COL_BORDER_LT);
        g.fill(x,              y,               x + 1,          y + mainH,      COL_BORDER_LT);
        g.fill(x,              y + mainH - 1,  x + MAIN_W,     y + mainH,      COL_BORDER_DK);
        g.fill(x + MAIN_W - 1, y,              x + MAIN_W,     y + mainH,      COL_BORDER_DK);

        // ── Main panel divider (below task) ───────────────────────────────────
        g.fill(x + 4, y + 31, x + MAIN_W - 4, y + 32, COL_DIVIDER);

        // ── Slot backgrounds ──────────────────────────────────────────────────
        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, COL_SLOT_DK);
            g.fill(sx,     sy,     sx + 16, sy + 16, COL_SLOT_LT);
        }

        // ── Honey section (always shown) ──────────────────────────────────────
        // Divider above honey section
        g.fill(x + 4, y + 79, x + MAIN_W - 4, y + 80, COL_DIVIDER);

        // Vertical honey bar background
        g.fill(x + HONEY_BAR_X - 1, y + HONEY_BAR_Y - 1,
               x + HONEY_BAR_X + HONEY_BAR_W + 1, y + HONEY_BAR_Y + HONEY_BAR_H + 1,
               COL_HONEY_BAR_BG);

        // Filled portion — fills from bottom to top
        int honeyLevel = menu.getHoneyLevel();
        if (honeyLevel > 0) {
            float fraction = honeyLevel / (float) CourierEntity.MAX_HONEY_LEVEL;
            int fillH = Math.max(1, (int) (HONEY_BAR_H * fraction));
            int fillY = y + HONEY_BAR_Y + (HONEY_BAR_H - fillH);
            boolean full = honeyLevel >= CourierEntity.MAX_HONEY_LEVEL;
            g.fill(x + HONEY_BAR_X, fillY,
                   x + HONEY_BAR_X + HONEY_BAR_W, y + HONEY_BAR_Y + HONEY_BAR_H,
                   full ? COL_HONEY_FULL : COL_HONEY_BAR_FILL);
        }

        // ── Ender teleport section (y=108+) ──────────────────────────────────
        if (isEnder) {
            // Divider below honey, above teleport section
            g.fill(x + 4, y + 108, x + MAIN_W - 4, y + 109, COL_DIVIDER);

            // Cooldown bar (y=121, height 8 px)
            final int barX = x + 8;
            final int barY = y + 121;
            final int barW = MAIN_W - 16;

            // Dark background trough
            g.fill(barX - 1, barY - 1, barX + barW + 1, barY + 9, COL_BAR_BG);

            // Filled portion — progress goes from 0 (just reset) → 1 (ready to fire)
            CourierEntity courier = this.menu.getCourier();
            int cooldown = courier != null ? courier.getEnderTeleportCooldown() : 0;
            int interval = CourierEntity.ENDER_TELEPORT_INTERVAL;
            float progress  = 1.0f - (cooldown / (float) interval);
            int   fillW     = Math.max(0, (int) (barW * progress));
            boolean nearReady = progress >= 0.9f;
            int barColor = nearReady ? COL_BAR_READY : COL_BAR_FILL;
            if (fillW > 0) {
                g.fill(barX, barY, barX + fillW, barY + 8, barColor);
            }
        }

        // ── Entity panel background + bevel border ────────────────────────────
        final int pL = x + EP_X;
        final int pT = y;
        final int pR = pL + EP_W;
        final int pB = y + mainH;
        g.fill(pL + 1, pT + 1, pR - 1, pB - 1, COL_EP_BG);
        g.fill(pL,     pT,     pR,     pT + 1,  COL_EP_BDR_LT);
        g.fill(pL,     pT,     pL + 1, pB,      COL_EP_BDR_LT);
        g.fill(pL,     pB - 1, pR,     pB,      COL_EP_BDR_DK);
        g.fill(pR - 1, pT,     pR,     pB,      COL_EP_BDR_DK);

        // Decorative rules framing the model area (always relative to panel top/bottom)
        g.fill(pL + 4, pT + 17, pR - 4, pT + 18, COL_DIVIDER);
        g.fill(pL + 4, pB - 18, pR - 4, pB - 17, COL_DIVIDER);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        final int maxW      = MAIN_W - 16;
        final int ellipsisW = this.font.width("\u2026");

        // ── Current Task ──────────────────────────────────────────────────────
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

        // ── Honey labels ──────────────────────────────────────────────────────
        g.drawString(this.font,
                Component.literal("Honey").withStyle(ChatFormatting.GRAY),
                HONEY_SLOT_X, 72, 0xDDB33A, false);

        int honeyLevel = menu.getHoneyLevel();
        String honeyStr = honeyLevel + "/" + CourierEntity.MAX_HONEY_LEVEL;
        g.drawString(this.font,
                Component.literal(honeyStr),
                HONEY_BAR_X + HONEY_BAR_W + 4, HONEY_BAR_Y + 4,
                honeyLevel >= CourierEntity.MAX_HONEY_LEVEL ? 0xFFDD55 : 0xDDB33A,
                false);

        if (honeyLevel > 0) {
            g.drawString(this.font,
                    Component.literal("1.5\u00d7 Speed").withStyle(ChatFormatting.GOLD),
                    HONEY_BAR_X + HONEY_BAR_W + 4, HONEY_BAR_Y + 13, 0xFFAA00, false);
        }

        // ── Ender: teleport cooldown labels (shifted down 28 px) ─────────────
        if (isEnder) {
            g.drawString(this.font,
                    Component.literal("Teleport Cooldown").withStyle(ChatFormatting.GRAY),
                    8, 111, 0xAAAAAA, false);

            // Right-aligned time remaining
            CourierEntity courier = this.menu.getCourier();
            int cooldown = courier != null ? courier.getEnderTeleportCooldown() : 0;
            String timeStr;
            int    timeColor;
            if (cooldown <= 0) {
                timeStr   = "Ready!";
                timeColor = 0x00EE88;
            } else {
                float secs = cooldown / 20.0f;
                timeStr   = String.format("%.1f s", secs);
                boolean nearReady = cooldown <= CourierEntity.ENDER_TELEPORT_INTERVAL * 0.1f;
                timeColor = nearReady ? 0x00EE88 : 0xAAAAAA;
            }
            int timeX = MAIN_W - 8 - this.font.width(timeStr);
            g.drawString(this.font, Component.literal(timeStr), timeX, 111, timeColor, false);
        }

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
            final int pB = this.topPos  + mainH - 19;
            g.enableScissor(pL, pT, pR, pB);
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    g, pL, pT, pR, pB, ENTITY_SCALE, 0.0f, mx, my, courier);
            g.disableScissor();
        }

        this.renderTooltip(g, mx, my);
    }
}
