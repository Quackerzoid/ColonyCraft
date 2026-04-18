package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import village.automation.mod.menu.CookingBlockMenu;

/**
 * Custom GUI for the Cooking Block.
 *
 * <h3>Layout overview</h3>
 * <pre>
 *   ┌──────────────────────────────────────────┐ ┌────────────────┐
 *   │ Cooking Block                            │ │ Kitchen        │
 *   ├──────────────────────────────────────────┤ ├────────────────┤
 *   │ <Worker> [ Chef ]                        │ │ Status: Idle   │
 *   ├──────────────────────────────────────────┤ ├────────────────┤
 *   │ Ingredients         Output               │ │ Cooking:       │
 *   │ [input 3×3]  ▶▶  [output 3×3]           │ │ [icon] Wheat   │
 *   │                   (progress arrow)       │ ├────────────────┤
 *   ├──────────────────────────────────────────┤ │ Output:        │
 *   │ Inventory                                │ │ [icon] Bread   │
 *   │ [player 3×9]                             │ ├────────────────┤
 *   │ [hotbar  1×9]                            │ │ [progress bar] │
 *   └──────────────────────────────────────────┘ └────────────────┘
 * </pre>
 *
 * <p>Main panel: 176 × 192 px.  Sidebar: 90 × 192 px, 4 px gap.
 * Total imageWidth: 270 px.
 */
public class CookingBlockScreen extends AbstractContainerScreen<CookingBlockMenu> {

    // ── Panel dimensions ──────────────────────────────────────────────────────
    private static final int MAIN_W = 176;
    private static final int MAIN_H = 192;
    private static final int SIDE_GAP = 4;
    private static final int SIDE_W   = 90;
    /** Panel-relative x where the sidebar begins. */
    private static final int SIDE_X   = MAIN_W + SIDE_GAP;   // 180

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int COL_BG          = 0xFF3B3B3B;
    private static final int COL_BORDER_LT   = 0xFF666666;
    private static final int COL_BORDER_DK   = 0xFF1A1A1A;
    private static final int COL_DIVIDER     = 0xFF555555;
    private static final int COL_SLOT_DK     = 0xFF373737;
    private static final int COL_SLOT_LT     = 0xFF8B8B8B;
    /** Orange-warm cook accent colour. */
    private static final int COL_ACCENT      = 0xFFDD6611;
    private static final int COL_SIDE_BG     = 0xFF2A2A2A;
    private static final int COL_SIDE_BDR_LT = 0xFF555555;
    private static final int COL_SIDE_BDR_DK = 0xFF111111;
    private static final int COL_BAR_BG      = 0xFF1A1A1A;
    private static final int COL_BAR_FG      = 0xFFDD6611;

    // ── Constructor ───────────────────────────────────────────────────────────

    public CookingBlockScreen(CookingBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth      = MAIN_W + SIDE_GAP + SIDE_W;  // 270
        this.imageHeight     = MAIN_H;                       // 192
        this.titleLabelX     = -9999;
        this.titleLabelY     = -9999;
        this.inventoryLabelY = -9999;
    }

    // ── Background ────────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        final int x = this.leftPos;
        final int y = this.topPos;

        // ── Main panel ────────────────────────────────────────────────────────
        g.fill(x + 1,          y + 1,          x + MAIN_W - 1, y + MAIN_H - 1, COL_BG);
        g.fill(x,              y,               x + MAIN_W,     y + 1,          COL_BORDER_LT);
        g.fill(x,              y,               x + 1,          y + MAIN_H,     COL_BORDER_LT);
        g.fill(x,              y + MAIN_H - 1, x + MAIN_W,     y + MAIN_H,     COL_BORDER_DK);
        g.fill(x + MAIN_W - 1, y,              x + MAIN_W,     y + MAIN_H,     COL_BORDER_DK);

        // Dividers
        g.fill(x + 4, y + 14, x + MAIN_W - 4, y + 15, COL_DIVIDER);   // below title
        g.fill(x + 4, y + 30, x + MAIN_W - 4, y + 31, COL_DIVIDER);   // below worker row
        g.fill(x + 4, y + 100, x + MAIN_W - 4, y + 101, COL_DIVIDER); // above inventory

        // Slot backgrounds
        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, COL_SLOT_DK);
            g.fill(sx,     sy,     sx + 16, sy + 16, COL_SLOT_LT);
        }

        // ── Sidebar ───────────────────────────────────────────────────────────
        final int sX = x + SIDE_X;
        g.fill(sX + 1,          y + 1,          sX + SIDE_W - 1, y + MAIN_H - 1, COL_SIDE_BG);
        g.fill(sX,              y,               sX + SIDE_W,     y + 1,          COL_SIDE_BDR_LT);
        g.fill(sX,              y,               sX + 1,          y + MAIN_H,     COL_SIDE_BDR_LT);
        g.fill(sX,              y + MAIN_H - 1, sX + SIDE_W,     y + MAIN_H,     COL_SIDE_BDR_DK);
        g.fill(sX + SIDE_W - 1, y,              sX + SIDE_W,     y + MAIN_H,     COL_SIDE_BDR_DK);

        // Sidebar dividers
        g.fill(sX + 4, y + 14,  sX + SIDE_W - 4, y + 15,  COL_DIVIDER);  // below header
        g.fill(sX + 4, y + 30,  sX + SIDE_W - 4, y + 31,  COL_DIVIDER);  // below status
        g.fill(sX + 4, y + 60,  sX + SIDE_W - 4, y + 61,  COL_DIVIDER);  // below cooking item
        g.fill(sX + 4, y + 90,  sX + SIDE_W - 4, y + 91,  COL_DIVIDER);  // below output item
        g.fill(sX + 4, y + 140, sX + SIDE_W - 4, y + 141, COL_DIVIDER);  // below progress bar

        // ── Sidebar progress bar ──────────────────────────────────────────────
        float progress = menu.getCookProgress();
        // Track
        g.fill(sX + 6, y + 96, sX + SIDE_W - 6, y + 108, COL_BAR_BG);
        // Fill
        int barW = (int) ((SIDE_W - 12) * progress);
        if (barW > 0) {
            g.fill(sX + 6, y + 96, sX + 6 + barW, y + 108, COL_BAR_FG);
        }
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {

        // ── Main panel ────────────────────────────────────────────────────────

        // Title
        g.drawString(this.font, this.title, 8, 5, COL_ACCENT & 0xFFFFFF, false);

        // Worker row
        if (!menu.hasWorker()) {
            g.drawString(this.font,
                    Component.literal("Unassigned").withStyle(ChatFormatting.DARK_GRAY),
                    8, 18, 0x666666, false);
        } else {
            String workerName = menu.getWorkerName();
            String badge = "[ " + menu.getWorkerJob().getTitle() + " ]";
            int maxNameW = MAIN_W - 16 - this.font.width(badge) - 4;
            if (this.font.width(workerName) > maxNameW) {
                workerName = this.font.plainSubstrByWidth(workerName, maxNameW - this.font.width("…")) + "…";
            }
            g.drawString(this.font, Component.literal(workerName).withStyle(ChatFormatting.WHITE),
                    8, 18, 0xFFFFFF, false);
            g.drawString(this.font, Component.literal(badge),
                    8 + this.font.width(workerName) + 4, 18, COL_ACCENT & 0xFFFFFF, false);
        }

        // Section labels for the two grids
        g.drawString(this.font,
                Component.literal("Ingredients").withStyle(ChatFormatting.GRAY),
                8, 34, 0x888888, false);
        g.drawString(this.font,
                Component.literal("Output").withStyle(ChatFormatting.GRAY),
                98, 34, 0x888888, false);

        // "Inventory" section label
        g.drawString(this.font, "Inventory", 8, 104, 0x777777, false);

        // ── Sidebar ───────────────────────────────────────────────────────────
        final int stx = SIDE_X + 6;

        // Header
        g.drawString(this.font,
                Component.literal("Kitchen").withStyle(ChatFormatting.GRAY),
                stx, 5, 0x888888, false);

        // Status
        String statusLabel;
        int statusColor;
        if (menu.isCooking()) {
            statusLabel = "Cooking…";
            statusColor = COL_ACCENT & 0xFFFFFF;
        } else {
            statusLabel = "Waiting";
            statusColor = 0x666666;
        }
        g.drawString(this.font, statusLabel, stx, 18, statusColor, false);

        // "Cooking:" label + currently cooking item
        g.drawString(this.font,
                Component.literal("Cooking:").withStyle(ChatFormatting.GRAY),
                stx, 34, 0x888888, false);

        if (menu.isCooking()) {
            // Find the first non-empty input slot
            Item cookingItem = findFirstInput();
            if (cookingItem != null) {
                g.renderItem(new ItemStack(cookingItem), stx, 42);
                String itemName = cookingItem.getDescription().getString();
                if (this.font.width(itemName) > SIDE_W - 12 - 20) {
                    itemName = this.font.plainSubstrByWidth(itemName, SIDE_W - 12 - 20 - this.font.width("…")) + "…";
                }
                g.drawString(this.font,
                        Component.literal(itemName).withStyle(ChatFormatting.WHITE),
                        stx + 20, 46, 0xFFFFFF, false);
            } else {
                g.drawString(this.font,
                        Component.literal("—").withStyle(ChatFormatting.DARK_GRAY),
                        stx, 42, 0x555555, false);
            }
        } else {
            g.drawString(this.font,
                    Component.literal("—").withStyle(ChatFormatting.DARK_GRAY),
                    stx, 42, 0x555555, false);
        }

        // "Output:" label + first non-empty output slot
        g.drawString(this.font,
                Component.literal("Output:").withStyle(ChatFormatting.GRAY),
                stx, 64, 0x888888, false);

        Item outputItem = findFirstOutput();
        if (outputItem != null) {
            g.renderItem(new ItemStack(outputItem), stx, 72);
            String outName = outputItem.getDescription().getString();
            if (this.font.width(outName) > SIDE_W - 12 - 20) {
                outName = this.font.plainSubstrByWidth(outName, SIDE_W - 12 - 20 - this.font.width("…")) + "…";
            }
            g.drawString(this.font,
                    Component.literal(outName).withStyle(ChatFormatting.WHITE),
                    stx + 20, 76, 0xFFFFFF, false);
        } else {
            g.drawString(this.font,
                    Component.literal("—").withStyle(ChatFormatting.DARK_GRAY),
                    stx, 72, 0x555555, false);
        }

        // Progress label + percentage
        g.drawString(this.font,
                Component.literal("Progress").withStyle(ChatFormatting.GRAY),
                stx, 112, 0x888888, false);
        if (menu.isCooking()) {
            int pct = (int) (menu.getCookProgress() * 100);
            g.drawString(this.font,
                    Component.literal(pct + "%").withStyle(ChatFormatting.WHITE),
                    SIDE_X + SIDE_W - 6 - this.font.width(pct + "%"), 112, 0xFFFFFF, false);
        }
    }

    // ── Full render pass ──────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);
        this.renderTooltip(g, mx, my);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns the first non-empty item from the input slots (indices 0-8). */
    private Item findFirstInput() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = this.menu.slots.get(i).getItem();
            if (!stack.isEmpty()) return stack.getItem();
        }
        return null;
    }

    /** Returns the first non-empty item from the output slots (indices 9-17). */
    private Item findFirstOutput() {
        for (int i = 9; i < 18; i++) {
            ItemStack stack = this.menu.slots.get(i).getItem();
            if (!stack.isEmpty()) return stack.getItem();
        }
        return null;
    }
}
