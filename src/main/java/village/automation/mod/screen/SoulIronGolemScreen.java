package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import village.automation.mod.entity.SoulIronGolemEntity;
import village.automation.mod.menu.SoulIronGolemMenu;

/**
 * Display-only GUI shown when a player right-clicks the Soul Iron Golem.
 *
 * <p>Layout (panel-relative coordinates):
 * <pre>
 *  Main panel (176 × 90)               Entity panel (104 × 90, 2 px gap)
 *  ──────────────────────────           ─────────────────────────────────
 *  y= 7  "Status" label (gray)          y= 5  golem name (centred)
 *  y=19  status value (coloured)        y=17  decorative rule
 *  y=32  divider                        y=17–73  3-D golem model
 *  y=39  "Health" label (gray)          y=73  decorative rule
 *  y=51  HP bar  (8 px tall)            y=90  bottom border
 *  y=63  "X / Y HP" text
 *  y=90  bottom border
 * </pre>
 */
public class SoulIronGolemScreen extends AbstractContainerScreen<SoulIronGolemMenu> {

    // ── Colour palette ────────────────────────────────────────────────────────
    private static final int COL_BG        = 0xFF3B3B3B;
    private static final int COL_BORDER_LT = 0xFF666666;
    private static final int COL_BORDER_DK = 0xFF1A1A1A;
    private static final int COL_DIVIDER   = 0xFF555555;
    private static final int COL_SLOT_DK   = 0xFF373737;

    // Entity panel (slightly darker inset)
    private static final int COL_EP_BG     = 0xFF252525;
    private static final int COL_EP_BDR_LT = 0xFF555555;
    private static final int COL_EP_BDR_DK = 0xFF111111;

    // HP bar colours (soul-themed palette)
    private static final int COL_HP_HIGH   = 0xFF2DD8D8;   // soul teal  (> 60 %)
    private static final int COL_HP_MID    = 0xFFFFAA00;   // amber      (25–60 %)
    private static final int COL_HP_LOW    = 0xFFFF4444;   // red        (< 25 %)

    // ── Dimensions ───────────────────────────────────────────────────────────
    private static final int MAIN_W       = 176;
    private static final int MAIN_H       = 90;
    private static final int EP_GAP       = 2;
    private static final int EP_W         = 104;
    private static final int EP_X         = MAIN_W + EP_GAP;   // 178, panel-relative

    private static final int HP_BAR_Y     = 51;
    private static final int HP_BAR_H     = 8;

    /** Scale passed to {@link InventoryScreen#renderEntityInInventoryFollowsMouse}.
     *  Iron golem is 2.7 m tall — kept small so it fits the clipped model area. */
    private static final int ENTITY_SCALE = 22;

    public SoulIronGolemScreen(SoulIronGolemMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth      = MAIN_W + EP_GAP + EP_W;   // 282
        this.imageHeight     = MAIN_H;                    // 90
        // Suppress default title / inventory labels — we draw our own.
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

        // Divider between status and health sections
        g.fill(x + 4, y + 32, x + MAIN_W - 4, y + 33, COL_DIVIDER);

        // ── HP bar ────────────────────────────────────────────────────────────
        final int barX = x + 8;
        final int barY = y + HP_BAR_Y;
        final int barW = MAIN_W - 16;   // 160 px

        // Background track
        g.fill(barX, barY, barX + barW, barY + HP_BAR_H, COL_SLOT_DK);

        SoulIronGolemEntity golem = this.menu.getGolem();
        if (golem != null) {
            float ratio   = Math.max(0f, Math.min(1f, golem.getHealth() / golem.getMaxHealth()));
            int   fillW   = Math.max(1, (int) (barW * ratio));
            int   barFill = hpBarColor(ratio);

            // Filled portion
            g.fill(barX, barY, barX + fillW, barY + HP_BAR_H, barFill);
            // 1-px top-edge highlight for a slight XP-bar depth effect
            g.fill(barX, barY, barX + fillW, barY + 1, 0x40FFFFFF);
        }

        // Bar border (1 px frame around the whole track)
        g.fill(barX - 1,       barY - 1,              barX + barW + 1, barY,              COL_BORDER_DK);
        g.fill(barX - 1,       barY + HP_BAR_H,       barX + barW + 1, barY + HP_BAR_H + 1, COL_BORDER_DK);
        g.fill(barX - 1,       barY - 1,              barX,            barY + HP_BAR_H + 1, COL_BORDER_DK);
        g.fill(barX + barW,    barY - 1,              barX + barW + 1, barY + HP_BAR_H + 1, COL_BORDER_DK);

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
        g.fill(pL + 4, pB - 17, pR - 4, pB - 16, COL_DIVIDER);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        final int ellipsisW = this.font.width("\u2026");

        // ── Status section ────────────────────────────────────────────────────
        g.drawString(this.font,
                Component.literal("Status").withStyle(ChatFormatting.GRAY),
                8, 7, 0xAAAAAA, false);

        String status = this.menu.getStatus();
        ChatFormatting statusColor;
        if (status.startsWith("Attacking")) {
            statusColor = ChatFormatting.RED;
        } else if ("Repairing".equals(status)) {
            statusColor = ChatFormatting.GREEN;
        } else {
            statusColor = ChatFormatting.AQUA;
        }
        int maxW = MAIN_W - 16;
        if (this.font.width(status) > maxW) {
            status = this.font.plainSubstrByWidth(status, maxW - ellipsisW).trim() + "\u2026";
        }
        g.drawString(this.font,
                Component.literal(status).withStyle(statusColor),
                8, 19, 0xFFFFFF, false);

        // ── Health section ────────────────────────────────────────────────────
        g.drawString(this.font,
                Component.literal("Health").withStyle(ChatFormatting.GRAY),
                8, 39, 0xAAAAAA, false);

        SoulIronGolemEntity golem = this.menu.getGolem();
        if (golem != null) {
            int hp    = Math.max(0, (int) golem.getHealth());
            int maxHp = Math.max(1, (int) golem.getMaxHealth());
            g.drawString(this.font,
                    Component.literal(hp + " / " + maxHp + " HP").withStyle(ChatFormatting.WHITE),
                    8, 63, 0xFFFFFF, false);
        }

        // ── Entity panel: golem name (centred) ────────────────────────────────
        String name = golem != null
                ? golem.getDisplayName().getString()
                : "Soul Iron Golem";
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

        // ── 3-D golem model ───────────────────────────────────────────────────
        SoulIronGolemEntity golem = this.menu.getGolem();
        if (golem != null) {
            final int pL = this.leftPos + EP_X + 2;
            final int pT = this.topPos  + 19;
            final int pR = this.leftPos + EP_X + EP_W - 2;
            final int pB = this.topPos  + MAIN_H - 17;
            g.enableScissor(pL, pT, pR, pB);
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    g, pL, pT, pR, pB, ENTITY_SCALE, 0.0f, mx, my, golem);
            g.disableScissor();
        }

        this.renderTooltip(g, mx, my);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns a soul-themed fill colour based on HP ratio. */
    private static int hpBarColor(float ratio) {
        if (ratio > 0.60f) return COL_HP_HIGH;
        if (ratio > 0.25f) return COL_HP_MID;
        return COL_HP_LOW;
    }
}
