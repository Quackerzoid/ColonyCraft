package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.menu.VillageHeartMenu;
import village.automation.mod.network.SetVillageNamePacket;

import village.automation.mod.ItemRequest;

import java.util.List;

public class VillageHeartScreen extends AbstractContainerScreen<VillageHeartMenu> {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            VillageMod.MODID, "textures/gui/village_heart_gui.png");

    // ── Vertical wheat progress bar ───────────────────────────────────────────
    // Coords are relative to the panel origin (leftPos / topPos).
    // The fill strip grows upward from the bottom of this area.
    private static final int VBAR_X =  29;   // left edge of the fill area
    private static final int VBAR_Y =   4;   // top  of the fill area
    private static final int VBAR_W =  12;   // width
    private static final int VBAR_H =  60;   // height (full = 16/16 wheat)

    // ── Upgrade slot column ───────────────────────────────────────────────────
    // Three slots stacked vertically; X is the item-area left edge (slot.x).
    private static final int   UPGRADE_SLOT_X = 52;
    private static final int[] UPGRADE_SLOT_Y = {5, 25, 45};  // slot.y for I / II / III

    // ── Sidebar panel ─────────────────────────────────────────────────────────
    // Positioned so its left edge overlaps the main panel by 4 px, creating the
    // "pokes out" look.  All coords relative to leftPos / topPos.
    private static final int SIDEBAR_X =  172;
    private static final int SIDEBAR_Y =   20;
    private static final int SIDEBAR_W =   74;
    private static final int SIDEBAR_H =   76;   // base height (name + radius + workers)

    // Requests section below the base sidebar (up to MAX_VISIBLE_REQUESTS shown)
    private static final int MAX_VISIBLE_REQUESTS = 5;
    private static final int REQUEST_ENTRY_H      = 11;  // px per request row
    private static final int REQUEST_SECTION_H    = 18 + MAX_VISIBLE_REQUESTS * REQUEST_ENTRY_H;

    // ── Village naming overlay ────────────────────────────────────────────────
    private boolean namingMode = false;
    private EditBox villageNameField;
    private Button  confirmButton;

    public VillageHeartScreen(VillageHeartMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth  = 176;
        this.imageHeight = 166;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();

        namingMode = menu.getVillageName().isEmpty();

        int fieldW = 160, fieldH = 20;
        int fieldX = this.width / 2 - fieldW / 2;
        int fieldY = this.height / 2 - 16;

        villageNameField = new EditBox(this.font, fieldX, fieldY, fieldW, fieldH,
                Component.literal("Village name"));
        villageNameField.setMaxLength(VillageHeartBlockEntity.MAX_NAME_LENGTH);
        villageNameField.setVisible(namingMode);
        villageNameField.setFocused(namingMode);
        this.addRenderableWidget(villageNameField);

        int btnW = 80;
        confirmButton = Button.builder(Component.literal("Confirm"), btn -> confirmName())
                .bounds(this.width / 2 - btnW / 2, fieldY + fieldH + 6, btnW, 20)
                .build();
        confirmButton.visible = namingMode;
        this.addRenderableWidget(confirmButton);
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /**
     * Draws: main panel texture, vertical bar gold fill, sidebar background,
     * and dark overlay tints over upgrade slots that are not yet unlockable.
     *
     * <p>Note: {@code renderBg} is called by the parent WITHOUT a pose translation,
     * so every position here is absolute screen coords (leftPos + X, topPos + Y).
     */
    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {

        // ── Main panel texture ────────────────────────────────────────────────
        g.blit(TEXTURE, this.leftPos, this.topPos, 0, 0,
                this.imageWidth, this.imageHeight, 256, 256);

        // ── Vertical progress bar fill (gold, grows upward) ───────────────────
        int stored = this.menu.getStoredWheat();
        int fillH  = (int)(stored / (float) VillageHeartBlockEntity.MAX_WHEAT * VBAR_H);
        if (fillH > 0) {
            g.fill(this.leftPos + VBAR_X,
                   this.topPos  + VBAR_Y + VBAR_H - fillH,
                   this.leftPos + VBAR_X + VBAR_W,
                   this.topPos  + VBAR_Y + VBAR_H,
                   0xFF_FFB800);
        }

        // ── Sidebar background (base section + requests extension) ───────────
        int sx      = this.leftPos + SIDEBAR_X;
        int sy      = this.topPos  + SIDEBAR_Y;
        int totalH  = SIDEBAR_H + REQUEST_SECTION_H;
        // Panel fill — same grey as the main panel
        g.fill(sx, sy, sx + SIDEBAR_W, sy + totalH, 0xFF_C6C6C6);
        // Top shadow border
        g.fill(sx + 4, sy,                  sx + SIDEBAR_W,     sy + 1,             0xFF_555555);
        // Right highlight border
        g.fill(sx + SIDEBAR_W - 1, sy,      sx + SIDEBAR_W,     sy + totalH,        0xFF_FFFFFF);
        // Bottom highlight border
        g.fill(sx + 4, sy + totalH - 1,     sx + SIDEBAR_W,     sy + totalH,        0xFF_FFFFFF);
        // Divider under the village name
        g.fill(sx + 4, sy + 17,             sx + SIDEBAR_W - 1, sy + 18,            0xFF_909090);
        // Divider between Radius and Workers
        g.fill(sx + 4, sy + 45,             sx + SIDEBAR_W - 1, sy + 46,            0xFF_909090);
        // Divider above the requests section
        g.fill(sx + 4, sy + SIDEBAR_H,      sx + SIDEBAR_W - 1, sy + SIDEBAR_H + 1, 0xFF_909090);

        // ── Locked upgrade slot overlays ──────────────────────────────────────
        // Slot I is always insertable; II requires I; III requires II.
        int radius = this.menu.getRadius();
        boolean[] unlocked = {
            true,
            radius > VillageHeartBlockEntity.BASE_RADIUS,
            radius > VillageHeartBlockEntity.TIER1_RADIUS
        };
        for (int i = 0; i < 3; i++) {
            if (!unlocked[i]) {
                g.fill(this.leftPos + UPGRADE_SLOT_X,
                       this.topPos  + UPGRADE_SLOT_Y[i],
                       this.leftPos + UPGRADE_SLOT_X + 16,
                       this.topPos  + UPGRADE_SLOT_Y[i] + 16,
                       0x99000000);
            }
        }
    }

    /**
     * Draws in-panel labels.
     *
     * <p>Note: the parent translates the pose by (leftPos, topPos) before calling
     * this method, so all coordinates here are panel-relative.
     * We deliberately do NOT call {@code super.renderLabels} to suppress the
     * default "Village Heart" title.
     */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {

        // "Inventory" label above the player inventory grid
        g.drawString(this.font, "Inventory", 8, this.imageHeight - 94, 0x404040, false);

        // Tier labels (I / II / III) to the right of the upgrade slot column
        int radius = this.menu.getRadius();
        boolean[] applied = {
            radius > VillageHeartBlockEntity.BASE_RADIUS,
            radius > VillageHeartBlockEntity.TIER1_RADIUS,
            radius > VillageHeartBlockEntity.TIER2_RADIUS
        };
        String[] tiers  = {"I", "II", "III"};
        int[]    labelY = {11,  31,   51};    // vertically centred alongside each slot
        for (int i = 0; i < 3; i++) {
            int color = applied[i] ? 0xFFD700 : 0x777777;   // gold if applied, grey if locked
            g.drawString(this.font, tiers[i], UPGRADE_SLOT_X + 18, labelY[i], color, false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (namingMode) {
            // ── Naming overlay ────────────────────────────────────────────────
            this.renderBackground(g, mouseX, mouseY, partialTick);
            g.fill(0, 0, this.width, this.height, 0x88000000);

            Component prompt = Component.literal("Enter a name for your village:");
            g.drawString(this.font, prompt,
                    this.width / 2 - this.font.width(prompt) / 2,
                    villageNameField.getY() - this.font.lineHeight - 6,
                    0xFFFFFF, true);

            villageNameField.render(g, mouseX, mouseY, partialTick);
            confirmButton.render(g, mouseX, mouseY, partialTick);

        } else {
            // ── Normal GUI ────────────────────────────────────────────────────
            super.render(g, mouseX, mouseY, partialTick);

            // Sidebar text uses absolute screen coords (sidebar is outside the main panel)
            drawSidebarContent(g);

            // Standard item-slot tooltips
            this.renderTooltip(g, mouseX, mouseY);

            // ── Upgrade slot hover tooltips ───────────────────────────────────
            int radius = this.menu.getRadius();
            boolean[] upgradeApplied = {
                radius > VillageHeartBlockEntity.BASE_RADIUS,
                radius > VillageHeartBlockEntity.TIER1_RADIUS,
                radius > VillageHeartBlockEntity.TIER2_RADIUS
            };
            String[] upgradeNames = {
                "Village Upgrade I", "Village Upgrade II", "Village Upgrade III"
            };
            for (int i = 0; i < 3; i++) {
                int slotSx = this.leftPos + UPGRADE_SLOT_X;
                int slotSy = this.topPos  + UPGRADE_SLOT_Y[i];
                if (mouseX >= slotSx && mouseX < slotSx + 16
                        && mouseY >= slotSy && mouseY < slotSy + 16) {
                    List<Component> tip;
                    if (upgradeApplied[i]) {
                        tip = List.of(
                            Component.literal(upgradeNames[i]).withStyle(ChatFormatting.GOLD),
                            Component.literal("Applied").withStyle(ChatFormatting.GREEN));
                    } else if (i == 0 || upgradeApplied[i - 1]) {
                        tip = List.of(
                            Component.literal(upgradeNames[i]).withStyle(ChatFormatting.YELLOW),
                            Component.literal("Place to unlock").withStyle(ChatFormatting.GRAY));
                    } else {
                        tip = List.of(
                            Component.literal(upgradeNames[i]).withStyle(ChatFormatting.DARK_GRAY),
                            Component.literal("Requires " + upgradeNames[i - 1] + " first")
                                    .withStyle(ChatFormatting.RED));
                    }
                    g.renderTooltip(this.font, tip, java.util.Optional.empty(), mouseX, mouseY);
                }
            }

            // ── Bar hover tooltip ─────────────────────────────────────────────
            int bx = this.leftPos + VBAR_X;
            int by = this.topPos  + VBAR_Y;
            if (mouseX >= bx && mouseX < bx + VBAR_W
                    && mouseY >= by && mouseY < by + VBAR_H) {
                g.renderTooltip(this.font,
                    List.of(Component.literal(
                            this.menu.getStoredWheat() + " / "
                            + VillageHeartBlockEntity.MAX_WHEAT + " Bundles of Wheat")),
                    java.util.Optional.empty(), mouseX, mouseY);
            }
        }
    }

    /**
     * Draws the village name, radius, worker count, and pending request list
     * inside the sidebar.  Uses absolute screen coordinates.
     */
    private void drawSidebarContent(GuiGraphics g) {
        int sx    = this.leftPos + SIDEBAR_X + 6;   // 6 px left padding inside sidebar
        int sy    = this.topPos  + SIDEBAR_Y;
        int maxW  = SIDEBAR_W - 14;                 // usable text width

        // Village name — truncated with "…" if it doesn't fit
        String name = this.menu.getVillageName();
        if (this.font.width(name) > maxW) {
            name = this.font.plainSubstrByWidth(name, maxW - this.font.width("...")) + "...";
        }
        g.drawString(this.font, name, sx, sy + 4, 0x404040, false);

        // Radius (below first divider at sy + 17)
        g.drawString(this.font, "Radius",                          sx, sy + 21, 0x606060, false);
        g.drawString(this.font, this.menu.getRadius() + " blocks", sx, sy + 31, 0xFFFFFF, false);

        // Workers (below second divider at sy + 45)
        g.drawString(this.font, "Workers",                                                       sx, sy + 49, 0x606060, false);
        g.drawString(this.font, this.menu.getWorkerCount() + " / " + this.menu.getWorkerCap(),   sx, sy + 59, 0xFFFFFF, false);

        // ── Requests section (below base sidebar) ─────────────────────────────
        int ry = sy + SIDEBAR_H + 4;   // top of request section content (below divider + gap)
        g.drawString(this.font, "Requests", sx, ry, 0x606060, false);

        List<ItemRequest> reqs = this.menu.getRequests();
        if (reqs.isEmpty()) {
            g.drawString(this.font, "None", sx, ry + 11, 0x888888, false);
        } else {
            int shown = Math.min(reqs.size(), MAX_VISIBLE_REQUESTS);
            for (int i = 0; i < shown; i++) {
                ItemRequest req  = reqs.get(i);
                String itemName  = req.getRequestedItem().getHoverName().getString();
                String entryText = req.getWorkerName() + ": " + itemName;
                if (this.font.width(entryText) > maxW) {
                    entryText = this.font.plainSubstrByWidth(
                            entryText, maxW - this.font.width("...")) + "...";
                }
                g.drawString(this.font, entryText, sx, ry + 11 + i * REQUEST_ENTRY_H, 0xFFFFFF, false);
            }
            if (reqs.size() > MAX_VISIBLE_REQUESTS) {
                String more = "+" + (reqs.size() - MAX_VISIBLE_REQUESTS) + " more";
                int moreY   = ry + 11 + MAX_VISIBLE_REQUESTS * REQUEST_ENTRY_H;
                g.drawString(this.font, more, sx, moreY, 0x888888, false);
            }
        }
    }

    // ── Input handling ─────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (namingMode) {
            if (keyCode == 257 || keyCode == 335) { confirmName(); return true; }
            if (keyCode == 256)                    { confirmName(); return true; }
            if (villageNameField.isFocused())        return villageNameField.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (namingMode) return villageNameField.charTyped(codePoint, modifiers);
        return super.charTyped(codePoint, modifiers);
    }

    // ── Name confirmation ──────────────────────────────────────────────────────

    private void confirmName() {
        String raw  = villageNameField.getValue().trim();
        String name = raw.isEmpty() ? "My Village" : raw;
        menu.setVillageName(name);
        PacketDistributor.sendToServer(new SetVillageNamePacket(menu.getHeartPos(), name));
        namingMode = false;
        villageNameField.setVisible(false);
        villageNameField.setFocused(false);
        confirmButton.visible = false;
    }
}
