package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import village.automation.mod.entity.SmithRecipe;
import village.automation.mod.entity.VillagerWorkerEntity;
import village.automation.mod.menu.SmithingBlockMenu;

import java.util.List;

/**
 * Custom GUI for the Smithing Block.
 *
 * <h3>Layout overview</h3>
 * <pre>
 *   ┌──────────────────────────────────────────┐ ┌────────────────┐
 *   │ Smithing Block                           │ │ Forge          │
 *   ├──────────────────────────────────────────┤ ├────────────────┤
 *   │ <Worker> [ Blacksmith ]                  │ │ Crafting:      │
 *   ├──────────────────────────────────────────┤ │ [icon] Tool    │
 *   │ ┌──────────────────────────┐             │ ├────────────────┤
 *   │ │  State: CRAFTING         │             │ │ Needs:         │
 *   │ │  [====progress bar====]  │             │ │ [i] Iron ×3    │
 *   │ └──────────────────────────┘             │ │ [i] Stick ×2   │
 *   │                                          │ ├────────────────┤
 *   ├──────────────────────────────────────────┤ │ [progress bar] │
 *   │ Inventory                                │ └────────────────┘
 *   │ [player 3×9]                             │
 *   │ [hotbar  1×9]                            │
 *   └──────────────────────────────────────────┘
 * </pre>
 *
 * <p>Main panel: 176 × 192 px.  Sidebar: 90 × 192 px, 4 px gap.
 * Total imageWidth: 270 px.
 */
public class SmithingBlockScreen extends AbstractContainerScreen<SmithingBlockMenu> {

    // ── Panel dimensions ──────────────────────────────────────────────────────
    private static final int MAIN_W   = 176;
    private static final int MAIN_H   = 192;
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
    /** Steel-blue accent for the smithing theme. */
    private static final int COL_ACCENT      = 0xFF8899AA;
    private static final int COL_SIDE_BG     = 0xFF2A2A2A;
    private static final int COL_SIDE_BDR_LT = 0xFF555555;
    private static final int COL_SIDE_BDR_DK = 0xFF111111;
    private static final int COL_BAR_BG      = 0xFF1A1A1A;
    private static final int COL_BAR_FG      = 0xFF8899AA;
    private static final int COL_STATUS_BOX  = 0xFF222222;

    // ── Status box (panel-relative) ───────────────────────────────────────────
    private static final int BOX_X  = 8;
    private static final int BOX_Y  = 32;
    private static final int BOX_W  = MAIN_W - 16;   // 160
    private static final int BOX_H  = 60;

    // ── Constructor ───────────────────────────────────────────────────────────

    public SmithingBlockScreen(SmithingBlockMenu menu, Inventory inventory, Component title) {
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
        g.fill(x + 4, y + 14,  x + MAIN_W - 4, y + 15,  COL_DIVIDER);  // below title
        g.fill(x + 4, y + 30,  x + MAIN_W - 4, y + 31,  COL_DIVIDER);  // below worker row
        g.fill(x + 4, y + 100, x + MAIN_W - 4, y + 101, COL_DIVIDER);  // above inventory

        // ── Status box ────────────────────────────────────────────────────────
        g.fill(x + BOX_X, y + BOX_Y, x + BOX_X + BOX_W, y + BOX_Y + BOX_H, COL_STATUS_BOX);
        // Box bevel
        g.fill(x + BOX_X,             y + BOX_Y,              x + BOX_X + BOX_W, y + BOX_Y + 1,      0xFF444444);
        g.fill(x + BOX_X,             y + BOX_Y,              x + BOX_X + 1,     y + BOX_Y + BOX_H,  0xFF444444);
        g.fill(x + BOX_X,             y + BOX_Y + BOX_H - 1, x + BOX_X + BOX_W, y + BOX_Y + BOX_H,  0xFF111111);
        g.fill(x + BOX_X + BOX_W - 1, y + BOX_Y,             x + BOX_X + BOX_W, y + BOX_Y + BOX_H,  0xFF111111);

        // Progress bar inside status box (when crafting)
        if (menu.isCrafting()) {
            float progress = menu.getSmithProgress();
            int barX  = x + BOX_X + 8;
            int barY  = y + BOX_Y + 38;
            int barW  = BOX_W - 16;
            int barH  = 10;
            g.fill(barX, barY, barX + barW, barY + barH, COL_BAR_BG);
            int fillW = (int) (barW * progress);
            if (fillW > 0) {
                g.fill(barX, barY, barX + fillW, barY + barH, COL_BAR_FG);
            }
        }

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
        g.fill(sX + 4, y + 14, sX + SIDE_W - 4, y + 15, COL_DIVIDER);  // below header
        g.fill(sX + 4, y + 46, sX + SIDE_W - 4, y + 47, COL_DIVIDER);  // below result item
        g.fill(sX + 4, y + 130, sX + SIDE_W - 4, y + 131, COL_DIVIDER); // below ingredients

        // ── Sidebar progress bar ──────────────────────────────────────────────
        if (menu.isCrafting()) {
            float progress = menu.getSmithProgress();
            int barX = sX + 6;
            int barY = y + 136;
            int barW = SIDE_W - 12;
            int barH = 10;
            g.fill(barX, barY, barX + barW, barY + barH, COL_BAR_BG);
            int fillW = (int) (barW * progress);
            if (fillW > 0) {
                g.fill(barX, barY, barX + fillW, barY + barH, COL_BAR_FG);
            }
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

        // ── Status box labels ─────────────────────────────────────────────────
        int state = menu.getSmithState();
        String stateLabel = switch (state) {
            case VillagerWorkerEntity.SMITH_IDLE     -> "Idle";
            case VillagerWorkerEntity.SMITH_AWAITING -> "Awaiting materials…";
            case VillagerWorkerEntity.SMITH_CRAFTING -> "Crafting…";
            case VillagerWorkerEntity.SMITH_READY    -> "Ready for pickup";
            default -> "Unknown";
        };
        int stateColor = switch (state) {
            case VillagerWorkerEntity.SMITH_CRAFTING -> COL_ACCENT & 0xFFFFFF;
            case VillagerWorkerEntity.SMITH_READY    -> 0x55FF55;
            case VillagerWorkerEntity.SMITH_AWAITING -> 0xFFAA00;
            default                                  -> 0x666666;
        };

        g.drawString(this.font,
                Component.literal("State:").withStyle(ChatFormatting.GRAY),
                BOX_X + 8, BOX_Y + 8, 0x888888, false);
        g.drawString(this.font,
                Component.literal(stateLabel),
                BOX_X + 8, BOX_Y + 18, stateColor, false);

        // Result item inside status box (when there is an active recipe)
        Item resultItem = menu.getSmithResultItem();
        if (resultItem != null) {
            g.renderItem(new ItemStack(resultItem), BOX_X + 8, BOX_Y + BOX_H - 24);
            g.drawString(this.font,
                    Component.literal(resultItem.getDescription().getString())
                              .withStyle(ChatFormatting.WHITE),
                    BOX_X + 28, BOX_Y + BOX_H - 20, 0xCCCCCC, false);
        }

        // Progress percentage inside status box
        if (menu.isCrafting()) {
            int pct = (int) (menu.getSmithProgress() * 100);
            String pctStr = pct + "%";
            g.drawString(this.font,
                    Component.literal(pctStr).withStyle(ChatFormatting.WHITE),
                    BOX_X + BOX_W - 8 - this.font.width(pctStr), BOX_Y + 40, 0xFFFFFF, false);
        }

        // "Inventory" section label
        g.drawString(this.font, "Inventory", 8, 104, 0x777777, false);

        // ── Sidebar ───────────────────────────────────────────────────────────
        final int stx = SIDE_X + 6;

        // Header
        g.drawString(this.font,
                Component.literal("Forge").withStyle(ChatFormatting.GRAY),
                stx, 5, 0x888888, false);

        // Result item section
        g.drawString(this.font,
                Component.literal("Crafting:").withStyle(ChatFormatting.GRAY),
                stx, 18, 0x888888, false);

        if (resultItem != null) {
            g.renderItem(new ItemStack(resultItem), stx, 27);
            String name = resultItem.getDescription().getString();
            if (this.font.width(name) > SIDE_W - 12 - 20) {
                name = this.font.plainSubstrByWidth(name, SIDE_W - 12 - 20 - this.font.width("…")) + "…";
            }
            g.drawString(this.font,
                    Component.literal(name).withStyle(ChatFormatting.WHITE),
                    stx + 20, 31, 0xFFFFFF, false);
        } else {
            g.drawString(this.font,
                    Component.literal("—").withStyle(ChatFormatting.DARK_GRAY),
                    stx, 27, 0x555555, false);
        }

        // Ingredients section
        g.drawString(this.font,
                Component.literal("Needs:").withStyle(ChatFormatting.GRAY),
                stx, 50, 0x888888, false);

        SmithRecipe recipe = menu.getSmithRecipe();
        if (recipe != null) {
            int iy = 60;
            for (SmithRecipe.Ingredient ing : recipe.ingredients) {
                if (iy + 16 > 130) {
                    // Too many ingredients to fit — show ellipsis
                    g.drawString(this.font,
                            Component.literal("…").withStyle(ChatFormatting.DARK_GRAY),
                            stx, iy, 0x555555, false);
                    break;
                }
                g.renderItem(new ItemStack(ing.displayItem), stx, iy);
                String ingName = ing.displayItem.getDescription().getString();
                int availW = SIDE_W - 12 - 20;
                if (this.font.width(ingName) > availW) {
                    ingName = this.font.plainSubstrByWidth(ingName, availW - this.font.width("…")) + "…";
                }
                g.drawString(this.font,
                        Component.literal(ingName + " ×" + ing.count)
                                  .withStyle(ChatFormatting.WHITE),
                        stx + 20, iy + 4, 0xCCCCCC, false);
                iy += 20;
            }
        } else {
            g.drawString(this.font,
                    Component.literal("—").withStyle(ChatFormatting.DARK_GRAY),
                    stx, 60, 0x555555, false);
        }

        // Progress bar label + percentage
        if (menu.isCrafting()) {
            g.drawString(this.font,
                    Component.literal("Progress").withStyle(ChatFormatting.GRAY),
                    stx, 133, 0x888888, false);
            int pct = (int) (menu.getSmithProgress() * 100);
            String pctStr = pct + "%";
            g.drawString(this.font,
                    Component.literal(pctStr).withStyle(ChatFormatting.WHITE),
                    SIDE_X + SIDE_W - 6 - this.font.width(pctStr), 133, 0xFFFFFF, false);
        }
    }

    // ── Full render pass ──────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);
        this.renderTooltip(g, mx, my);
    }
}
