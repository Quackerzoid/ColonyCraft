package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.FarmBlockMenu;

public class FarmBlockScreen extends AbstractContainerScreen<FarmBlockMenu> {

    // ── Layout overview (relative to panel top-left) ──────────────────────────
    //
    //   y=  6  title ("Farm Block")
    //
    //   y= 18  "Seeds:" label (x=8)        "Crops:" label (x=90)
    //   y= 27  seed slots 0,1,2            output slots 0..2  (row 1 of 3)
    //   y= 45                              output slots 3..5  (row 2 of 3)
    //   y= 63                              output slots 6..8  (row 3 of 3)
    //
    //   y= 82  "Worker:" info (x=8)        (below last output slot bottom at 79)
    //
    //   y= 94  divider
    //   y= 96  "Inventory" label
    //   y= 98  player inventory (3 rows × 9)
    //   y=156  player hotbar
    //   y=178  bottom of panel

    private static final int W = 176;
    private static final int H = 182;

    // Panel colours
    private static final int COL_BG        = 0xFF3B3B3B;
    private static final int COL_BORDER_LT = 0xFF666666;
    private static final int COL_BORDER_DK = 0xFF1A1A1A;
    private static final int COL_DIVIDER   = 0xFF555555;
    private static final int COL_SLOT_DK   = 0xFF373737;
    private static final int COL_SLOT_LT   = 0xFF8B8B8B;

    public FarmBlockScreen(FarmBlockMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth      = W;
        this.imageHeight     = H;
        this.inventoryLabelY = 96;   // "Inventory" text above player inv section
    }

    // ── Background ────────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        int x = this.leftPos;
        int y = this.topPos;

        // Outer panel body
        g.fill(x + 1, y + 1, x + W - 1, y + H - 1, COL_BG);

        // Bevel border
        g.fill(x,         y,         x + W,     y + 1,     COL_BORDER_LT);
        g.fill(x,         y,         x + 1,     y + H,     COL_BORDER_LT);
        g.fill(x,         y + H - 1, x + W,     y + H,     COL_BORDER_DK);
        g.fill(x + W - 1, y,         x + W,     y + H,     COL_BORDER_DK);

        // Divider between farm section and player inventory
        g.fill(x + 4, y + 94, x + W - 4, y + 95, COL_DIVIDER);

        // Draw sunken backgrounds for every slot
        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, COL_SLOT_DK);
            g.fill(sx,     sy,     sx + 16, sy + 16, COL_SLOT_LT);
        }
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // Title
        g.drawString(this.font, this.title, 8, 6, 0xFFFFFF, false);

        // "Seeds:" label (above seed slots)
        g.drawString(this.font,
                Component.literal("Seeds:").withStyle(ChatFormatting.GRAY),
                8, 18, 0xAAAAAA, false);

        // "Crops:" label (above output slots)
        g.drawString(this.font,
                Component.literal("Crops:").withStyle(ChatFormatting.GRAY),
                90, 18, 0xAAAAAA, false);

        // Player inventory label
        g.drawString(this.font,
                Component.translatable("container.inventory"),
                8, this.inventoryLabelY, 0x404040, false);

        // ── Worker info (below output slots, above divider) ───────────────────
        int infoX = 8;
        int infoY = 82;

        if (!menu.hasWorker()) {
            g.drawString(this.font,
                    Component.literal("No worker assigned").withStyle(ChatFormatting.DARK_GRAY),
                    infoX, infoY, 0x777777, false);
        } else {
            g.drawString(this.font,
                    Component.literal(menu.getWorkerName()).withStyle(ChatFormatting.WHITE),
                    infoX, infoY, 0xFFFFFF, false);

            JobType job = menu.getWorkerJob();
            int badgeColour = (job == JobType.UNEMPLOYED) ? 0x888888 : 0xD4A800;
            g.drawString(this.font,
                    Component.literal("[ " + job.getTitle() + " ]"),
                    infoX + this.font.width(menu.getWorkerName()) + 4, infoY, badgeColour, false);
        }
    }

    // ── Full render ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        super.render(g, mx, my, partialTick);
        this.renderTooltip(g, mx, my);
    }
}
