package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;
import village.automation.mod.menu.VillagerWorkerMenu;

import java.util.ArrayList;
import java.util.List;

public class VillagerWorkerScreen extends AbstractContainerScreen<VillagerWorkerMenu> {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            VillageMod.MODID, "textures/gui/villager_worker_gui.png"
    );

    // ── Main panel dimensions (unchanged texture) ─────────────────────────────
    private static final int MAIN_W = 176;
    private static final int MAIN_H = 166;

    // ── Food bar layout (relative to GUI top-left) ────────────────────────────
    private static final int BAR_X      = 8;
    private static final int BAR_Y      = 88;   // sits between worker grid (y≤71) and new inventory (y=102)
    private static final int BAR_WIDTH  = 160;
    private static final int BAR_HEIGHT = 6;

    // ── Entity preview panel (relative to GUI top-left) ───────────────────────
    /** Left edge of the entity panel — 4 px gap after the 176 px main panel. */
    private static final int EP_X = 180;
    /** Top of the entity panel — vertically centred with a small top margin. */
    private static final int EP_Y = 8;
    /** Width of the entity panel. */
    private static final int EP_W = 68;
    /** Height of the entity panel — fills most of the taller GUI. */
    private static final int EP_H = 168;

    // ── Entity render scale inside the preview panel ──────────────────────────
    private static final int ENTITY_SCALE = 38;

    public VillagerWorkerScreen(VillagerWorkerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        // 176 main + 4 gap + 68 entity panel + 4 right margin
        this.imageWidth  = EP_X + EP_W + 4;  // = 252
        // 166 original + 18 bottom padding  (slots also shifted down 18 px in the menu)
        this.imageHeight = MAIN_H + 18;       // = 184
        // "Inventory" label just above the new player-inventory y=102
        this.inventoryLabelY = 96;
    }

    // ── Background ────────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        int x = (this.width  - this.imageWidth)  / 2;
        int y = (this.height - this.imageHeight) / 2;

        // ── 1. Original 176×166 main panel texture ────────────────────────────
        gg.blit(TEXTURE, x, y, 0, 0, MAIN_W, MAIN_H, 256, 256);

        // ── 2. Extend the main panel bottom by 18 px ──────────────────────────
        //    Vanilla GUI panel colours:  bg ≈ 0xFFC6C6C6, border ≈ 0xFF373737
        int extTop = y + MAIN_H - 1;   // start 1 px before texture border so it joins cleanly
        int extBot = y + imageHeight;
        // side borders
        gg.fill(x,           extTop, x + 1,       extBot, 0xFF373737);
        gg.fill(x + MAIN_W - 1, extTop, x + MAIN_W, extBot, 0xFF373737);
        // fill
        gg.fill(x + 1, extTop, x + MAIN_W - 1, extBot - 1, 0xFFC6C6C6);
        // bottom border
        gg.fill(x, extBot - 1, x + MAIN_W, extBot, 0xFF373737);

        // ── 3. Entity preview panel ───────────────────────────────────────────
        int pL = x + EP_X;
        int pT = y + EP_Y;
        int pR = pL + EP_W;
        int pB = pT + EP_H;

        // outer near-black frame
        gg.fill(pL - 1, pT - 1, pR + 1, pB + 1, 0xFF111111);
        // dark background
        gg.fill(pL, pT, pR, pB, 0xFF2A2A2A);
        // inner top+left highlight (gives inset depth)
        gg.fill(pL,     pT,     pR,     pT + 1, 0xFF555555);
        gg.fill(pL,     pT,     pL + 1, pB,     0xFF555555);
        // inner bottom+right shadow
        gg.fill(pL,     pB - 1, pR,     pB,     0xFF171717);
        gg.fill(pR - 1, pT,     pR,     pB,     0xFF171717);

        // ── 4. Food bar ───────────────────────────────────────────────────────
        int foodLevel = this.menu.getFoodLevel();
        int bL = x + BAR_X;
        int bT = y + BAR_Y;
        int bR = bL + BAR_WIDTH;
        int bB = bT + BAR_HEIGHT;

        gg.fill(bL,     bT,     bR,     bB,     0xFF222222);
        gg.fill(bL + 1, bT + 1, bR - 1, bB - 1, 0xFF444444);

        float fraction = (float) foodLevel / VillagerWorkerEntity.MAX_FOOD;
        int   fillPx   = Math.round((BAR_WIDTH - 2) * fraction);
        if (fillPx > 0) {
            int fillColor;
            if (fraction > 0.5f) {
                fillColor = 0xFF44AA22;  // green  — well fed
            } else if (foodLevel >= VillagerWorkerEntity.WORK_FOOD_THRESHOLD) {
                fillColor = 0xFFFFAA00;  // amber  — hungry but still working
            } else {
                fillColor = 0xFFCC2222;  // red    — too hungry to work
            }
            gg.fill(bL + 1, bT + 1, bL + 1 + fillPx, bB - 1, fillColor);
        }
    }

    // ── Labels (rendered over the background, in GUI-local coordinates) ───────

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        // GUI title ("Farmer Alice" etc.) and "Inventory" label drawn by parent.
        super.renderLabels(gg, mouseX, mouseY);

        // ── Job badge (left panel, below tool slot) ───────────────────────────
        JobType job = this.menu.getJob();
        Component badge = Component.literal("[ " + job.getTitle() + " ]")
                .withStyle(job == JobType.UNEMPLOYED ? ChatFormatting.GRAY : ChatFormatting.GOLD);
        gg.drawString(this.font, badge, 8, 58, 0xFFFFFF, false);

        // ── Food label ────────────────────────────────────────────────────────
        gg.drawString(this.font,
                Component.literal("Food").withStyle(ChatFormatting.DARK_GRAY),
                BAR_X, BAR_Y - 10, 0xFFFFFF, false);

        // ── Entity panel name (centred, just inside the top of the panel) ─────
        VillagerWorkerEntity worker = this.menu.getEntity();
        String label = worker != null ? worker.getDisplayName().getString() : "Worker";
        // Truncate if wider than the panel interior (EP_W − 6 px padding)
        int maxW = EP_W - 6;
        while (this.font.width(label) > maxW && label.length() > 4) {
            label = label.substring(0, label.length() - 1);
        }
        if (this.font.width(label) > maxW) label = label.substring(0, label.length() - 1) + "…";

        int labelX = EP_X + (EP_W - this.font.width(label)) / 2;
        gg.drawString(this.font,
                Component.literal(label).withStyle(ChatFormatting.WHITE),
                labelX, EP_Y + 3,
                0xFFFFFF, false);
    }

    // ── Full render (entity preview + tooltips on top of everything) ──────────

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Draw background (texture, entity-panel fill, food bar).
        super.render(gg, mouseX, mouseY, partialTick);

        // ── Entity preview ────────────────────────────────────────────────────
        VillagerWorkerEntity worker = this.menu.getEntity();
        if (worker != null) {
            int guiLeft = (this.width  - this.imageWidth)  / 2;
            int guiTop  = (this.height - this.imageHeight) / 2;

            int pL = guiLeft + EP_X;
            int pT = guiTop  + EP_Y;
            int pR = pL + EP_W;
            int pB = pT + EP_H;

            // Clip so the model never bleeds outside the panel border.
            gg.enableScissor(pL + 1, pT + 12, pR - 1, pB - 1);

            // Render centre — 72 % down the panel so the head clears the name label.
            float cx = (pL + pR) * 0.5f;
            float cy = pT + EP_H * 0.72f;

            // The mouse offset drives the ambient lighting direction, giving a
            // subtle "light tracks the cursor" feel without mutating entity state.
            Vector3f lightDelta = new Vector3f(cx - mouseX, cy - mouseY, 0f);

            // rotateZ(π) flips the entity to face the viewer.
            Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);

            // A 15° upward camera tilt gives a natural three-quarter view.
            Quaternionf camAngle = new Quaternionf().rotateX((float) Math.toRadians(-15.0));

            InventoryScreen.renderEntityInInventory(
                    gg, cx, cy,
                    ENTITY_SCALE,
                    lightDelta,
                    pose, camAngle,
                    worker);

            gg.disableScissor();
        }

        // ── Slot tooltips ─────────────────────────────────────────────────────
        this.renderTooltip(gg, mouseX, mouseY);

        // ── Food bar tooltip ──────────────────────────────────────────────────
        int guiLeft = (this.width  - this.imageWidth)  / 2;
        int guiTop  = (this.height - this.imageHeight) / 2;

        int bL = guiLeft + BAR_X;
        int bT = guiTop  + BAR_Y;
        int bR = bL + BAR_WIDTH;
        int bB = bT + BAR_HEIGHT;

        if (mouseX >= bL && mouseX < bR && mouseY >= bT && mouseY < bB) {
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
            gg.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }
    }
}
