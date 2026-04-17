package village.automation.mod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.menu.VillageHeartMenu;
import village.automation.mod.network.SetVillageNamePacket;
import village.automation.mod.network.SyncGolemsPacket;

import java.util.List;
import java.util.Optional;

public class VillageHeartScreen extends AbstractContainerScreen<VillageHeartMenu> {

    // ── Image dimensions ─────────────────────────────────────────────────────
    //   Left  sidebar  x =   0 ..  76   (width  76)
    //   Separator      x =  76 ..  80   (width   4)
    //   Main  panel    x =  80 .. 256   (width 176)
    //   Separator      x = 256 .. 260   (width   4)
    //   Right sidebar  x = 260 .. 350   (width  90)
    private static final int IMG_W   = 350;
    private static final int IMG_H   = 222;   // increased for comfortable spacing
    private static final int LEFT_W  =  76;
    private static final int MAIN_X  =  80;
    private static final int RIGHT_X = 260;

    // ── Colours (full dark mode) ─────────────────────────────────────────────
    private static final int COL_SIDEBAR    = 0xFF_1A1A1A;   // dark sidebars
    private static final int COL_SEP        = 0xFF_0D0D0D;   // near-black separator lines
    private static final int COL_PANEL      = 0xFF_252525;   // dark main panel
    private static final int COL_DIVIDER    = 0xFF_3A3A3A;   // subtle divider on dark panel
    private static final int COL_DARK_DIV   = 0xFF_3A3A3A;   // divider on sidebars
    private static final int COL_ACCENT     = 0xFF_55DD55;   // village green (unchanged)
    private static final int COL_LABEL      = 0xFF_AAAAAA;   // light-grey label on dark panel
    private static final int COL_LABEL_SB   = 0xFF_999999;   // label on dark sidebar
    private static final int COL_VALUE      = 0xFF_FFFFFF;   // white values
    private static final int COL_WHEAT_FILL = 0xFF_FFB800;   // amber wheat bar (unchanged)
    private static final int COL_SLOT_BDR   = 0xFF_505050;   // slot border — visible on dark bg
    private static final int COL_SLOT_IN    = 0xFF_141414;   // very dark slot interior

    // ── Main-panel slot positions (image-relative, must match menu) ──────────
    private static final int UPGRADE_X = 88;   // slot 0 x
    private static final int UPGRADE_Y = 32;   // slot 0 y
    private static final int WHEAT_X   = 88;   // slot 1 x
    private static final int WHEAT_Y   = 74;   // slot 1 y

    // Player inventory (slots 2-28): x=87+col*18, y=114+row*18
    private static final int INV_X  = 87;
    private static final int INV_Y  = 114;   // first row top

    // Hotbar (slots 29-37): x=87+col*18, y=170
    private static final int HOT_Y  = 170;

    // ── Wheat bar (image-relative) ───────────────────────────────────────────
    // Sits to the right of the wheat slot; vertically centred with it
    // Slot occupies y=74..90  →  bar centred at y=82  →  y=77..87
    private static final int BAR_X = 112;   // WHEAT_X + 16 + 8
    private static final int BAR_Y =  77;
    private static final int BAR_W = 126;   // ends at 238 < 256 (main panel right)
    private static final int BAR_H =  10;

    // ── Main-panel divider Y positions ──────────────────────────────────────
    // Below title:              y = 14
    // Below upgrade section:    y = 56   (slot bottom = 32+16=48, +8 gap)
    // Below wheat section:      y = 96   (slot bottom = 74+16=90, +6 gap)
    private static final int DIV_TITLE   = 14;
    private static final int DIV_UPGRADE = 56;
    private static final int DIV_WHEAT   = 96;

    // ── Left-sidebar golem list ──────────────────────────────────────────────
    private static final int GOLEM_TOP = 18;   // first entry y (image-relative)
    private static final int GOLEM_H   = 20;   // px per entry (name + status)
    private int golemScroll = 0;

    // ── Naming overlay ───────────────────────────────────────────────────────
    private boolean namingMode = false;
    private EditBox nameField;
    private Button  confirmBtn;

    // ─────────────────────────────────────────────────────────────────────────

    public VillageHeartScreen(VillageHeartMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth  = IMG_W;
        imageHeight = IMG_H;
    }

    @Override
    protected void init() {
        super.init();
        namingMode = menu.getVillageName().isEmpty();

        int fw = 160, fh = 20;
        int fx = width / 2 - fw / 2;
        int fy = height / 2 - 16;

        nameField = new EditBox(font, fx, fy, fw, fh, Component.literal("Village name"));
        nameField.setMaxLength(VillageHeartBlockEntity.MAX_NAME_LENGTH);
        nameField.setVisible(namingMode);
        nameField.setFocused(namingMode);
        addRenderableWidget(nameField);

        confirmBtn = Button.builder(Component.literal("Confirm"), b -> confirmName())
                .bounds(width / 2 - 40, fy + fh + 6, 80, 20).build();
        confirmBtn.visible = namingMode;
        addRenderableWidget(confirmBtn);
    }

    // ── Background (absolute screen coords) ──────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float pt, int mx, int my) {
        int lx = leftPos, ty = topPos;

        // ── Left sidebar ─────────────────────────────────────────────────────
        g.fill(lx,          ty, lx + LEFT_W,  ty + IMG_H, COL_SIDEBAR);
        g.fill(lx + LEFT_W, ty, lx + MAIN_X,  ty + IMG_H, COL_SEP);
        // Header divider
        g.fill(lx + 4, ty + DIV_TITLE, lx + LEFT_W - 4, ty + DIV_TITLE + 1, COL_DARK_DIV);

        // ── Main panel ───────────────────────────────────────────────────────
        int px = lx + MAIN_X;
        int pr = lx + RIGHT_X - 4;   // panel right edge (before separator)
        g.fill(px, ty, pr, ty + IMG_H, COL_PANEL);
        // Subtle 1px top highlight and bottom shadow (dark-mode friendly)
        g.fill(px, ty,             pr, ty + 1,       0xFF_383838);
        g.fill(px, ty + IMG_H - 1, pr, ty + IMG_H,   0xFF_111111);

        // Separators around main panel
        g.fill(pr,         ty, lx + RIGHT_X, ty + IMG_H, COL_SEP);

        // ── Main panel dividers ──────────────────────────────────────────────
        g.fill(px + 4, ty + DIV_TITLE,   pr - 4, ty + DIV_TITLE   + 1, COL_DIVIDER);
        g.fill(px + 4, ty + DIV_UPGRADE, pr - 4, ty + DIV_UPGRADE + 1, COL_DIVIDER);
        g.fill(px + 4, ty + DIV_WHEAT,   pr - 4, ty + DIV_WHEAT   + 1, COL_DIVIDER);

        // ── All slot backgrounds ─────────────────────────────────────────────
        // Block slots
        drawSlot(g, lx + UPGRADE_X, ty + UPGRADE_Y);
        drawSlot(g, lx + WHEAT_X,   ty + WHEAT_Y);

        // Player inventory (3 × 9)
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                drawSlot(g, lx + INV_X + col * 18, ty + INV_Y + row * 18);

        // Thin separator line in the 2-pixel gap between inventory rows and hotbar
        g.fill(lx + INV_X, ty + HOT_Y - 2, lx + INV_X + 9 * 18 - 2, ty + HOT_Y - 1, 0xFF_3A3A3A);

        // Hotbar (1 × 9)
        for (int col = 0; col < 9; col++)
            drawSlot(g, lx + INV_X + col * 18, ty + HOT_Y);

        // ── Wheat bar ────────────────────────────────────────────────────────
        int bx = lx + BAR_X, by = ty + BAR_Y;
        // Track (border + darker-than-panel fill)
        g.fill(bx - 1, by - 1, bx + BAR_W + 1, by + BAR_H + 1, 0xFF_3A3A3A);
        g.fill(bx,     by,     bx + BAR_W,     by + BAR_H,      0xFF_111111);
        // Fill
        int stored = menu.getStoredWheat();
        int fillW  = (int)(stored / (float) VillageHeartBlockEntity.MAX_WHEAT * BAR_W);
        if (fillW > 0) {
            g.fill(bx, by, bx + fillW, by + BAR_H, COL_WHEAT_FILL);
            g.fill(bx, by, bx + fillW, by + 2,     0x44FFFFFF);   // shine stripe
        }

        // ── Right sidebar ────────────────────────────────────────────────────
        int rsx = lx + RIGHT_X;
        g.fill(rsx, ty, lx + IMG_W, ty + IMG_H, COL_SIDEBAR);
        // Right sidebar dividers (3 of them — no 4th that would cut through tier text)
        g.fill(rsx + 4, ty + 14, lx + IMG_W - 4, ty + 15, COL_DARK_DIV);
        g.fill(rsx + 4, ty + 38, lx + IMG_W - 4, ty + 39, COL_DARK_DIV);
        g.fill(rsx + 4, ty + 62, lx + IMG_W - 4, ty + 63, COL_DARK_DIV);
    }

    /** Dark-mode recessed slot: medium border, very dark fill, subtle highlights. */
    private void drawSlot(GuiGraphics g, int x, int y) {
        // Outer border
        g.fill(x - 1, y - 1, x + 17, y + 17, COL_SLOT_BDR);
        // Dark interior
        g.fill(x,     y,     x + 16, y + 16,  COL_SLOT_IN);
        // Subtle bottom-right edge highlight (gives faint depth without looking white on dark)
        g.fill(x - 1, y + 16, x + 17, y + 17, 0xFF_3A3A3A);
        g.fill(x + 16, y - 1, x + 17, y + 17, 0xFF_3A3A3A);
    }

    // ── Labels (image-relative coords — translated by leftPos, topPos) ────────

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // Suppress default title and "Inventory" text — we draw our own.

        int tier = menu.getAppliedUpgrades();

        // ════════════════ LEFT SIDEBAR ════════════════
        g.drawString(font, "Golems", 5, 4, COL_ACCENT, false);

        List<SyncGolemsPacket.GolemInfo> golemList = menu.getGolems();
        int maxVis  = (IMG_H - GOLEM_TOP) / GOLEM_H;
        int start   = golemScroll;
        int end     = Math.min(golemList.size(), start + maxVis);
        int maxTextW = LEFT_W - 8;

        if (golemList.isEmpty()) {
            g.drawString(font, "No golems yet", 4, GOLEM_TOP, 0xFF_555555, false);
        } else {
            for (int i = start; i < end; i++) {
                SyncGolemsPacket.GolemInfo gi = golemList.get(i);
                int ey = GOLEM_TOP + (i - start) * GOLEM_H;

                String name = gi.name();
                if (font.width(name) > maxTextW)
                    name = font.plainSubstrByWidth(name, maxTextW - font.width("..")) + "..";
                g.drawString(font, name, 4, ey, 0xFF_DDDDDD, false);

                int sc = gi.status().equalsIgnoreCase("Active") ? 0xFF_55FF55 : 0xFF_777777;
                g.drawString(font, gi.status(), 4, ey + 10, sc, false);
            }
            if (golemList.size() > maxVis) {
                int rem = golemList.size() - (start + maxVis);
                if (rem > 0)
                    g.drawString(font, "+" + rem + " more", 4, IMG_H - 10, 0xFF_555555, false);
            }
        }

        // ════════════════ MAIN PANEL ════════════════
        int mp = MAIN_X;   // 80

        // Title
        g.drawString(font, "Village Heart", mp + 6, 4, COL_ACCENT, false);

        // ── Upgrade section (y=DIV_TITLE+4 .. DIV_UPGRADE-1) ─────────────────
        int us = DIV_TITLE + 4;   // = 18

        String[] tNames  = {"Base",   "Tier I", "Tier II", "Tier III"};
        int[]    tColors = {0xFF_AAAAAA, 0xFF_88CCFF, 0xFF_88CCFF, 0xFF_FFDD00};

        // Section header — sits above the slot (slot border begins at y=31, text ends at y=27)
        g.drawString(font, "Upgrade", mp + 6, us, COL_LABEL, false);

        // All text to the RIGHT of the upgrade slot so nothing overlaps the slot box.
        // Slot right edge = UPGRADE_X + 16 = 104.  8px gap → text starts at 112.
        // Available width to the main-panel right edge = (RIGHT_X - 4) - 112 = 144 px.
        int textX   = UPGRADE_X + 16 + 8;          // 112
        int maxTxtW = (RIGHT_X - 4) - textX;       // 144

        // Line 1 (y=30): current applied tier
        String appliedLine = "Applied: " + tNames[tier];
        g.drawString(font, appliedLine, textX, UPGRADE_Y - 2, tColors[tier], false);   // y=30

        // Line 2 (y=42): next-tier hint or max-tier notice
        if (tier < 3) {
            String[] hintItems = {"Village Upgrade I", "Village Upgrade II", "Village Upgrade III"};
            String hint = "> Insert: " + hintItems[tier];
            if (font.width(hint) > maxTxtW)
                hint = font.plainSubstrByWidth(hint, maxTxtW - font.width("..")) + "..";
            g.drawString(font, hint, textX, UPGRADE_Y + 10, 0xFF_777777, false);       // y=42
        } else {
            g.drawString(font, "All tiers applied!", textX, UPGRADE_Y + 10, 0xFF_FFDD00, false);
        }

        // ── Wheat section (y=DIV_UPGRADE+4 .. DIV_WHEAT-1) ───────────────────
        int ws = DIV_UPGRADE + 4;   // = 60

        String wheatLbl = "Wheat Supply";
        g.drawString(font, wheatLbl, mp + 6, ws, COL_LABEL, false);
        // Count appended on the same line to the right of the label
        String wheatCnt = menu.getStoredWheat() + " / " + VillageHeartBlockEntity.MAX_WHEAT;
        g.drawString(font, wheatCnt, mp + 6 + font.width(wheatLbl) + 6, ws, COL_VALUE, false);

        // ── Inventory section ─────────────────────────────────────────────────
        g.drawString(font, "Inventory", mp + 6, DIV_WHEAT + 4, COL_LABEL, false);

        // ════════════════ RIGHT SIDEBAR ════════════════
        int rp = RIGHT_X + 5;   // left edge of text in right sidebar

        // Village name (clickable — tooltip shown in render())
        String vname = menu.getVillageName();
        if (vname.isEmpty()) vname = "Unnamed";
        int nameMaxW = IMG_W - RIGHT_X - 10;
        if (font.width(vname) > nameMaxW)
            vname = font.plainSubstrByWidth(vname, nameMaxW - font.width("..")) + "..";
        g.drawString(font, vname, rp, 4, 0xFF_FFDD88, false);

        // Workers (below first divider at y=14)
        g.drawString(font, "Workers",  rp, 18, COL_LABEL_SB, false);
        g.drawString(font, menu.getWorkerCount() + " / " + menu.getWorkerCap(),
                     rp, 28, COL_VALUE, false);

        // Radius (below second divider at y=38)
        g.drawString(font, "Radius",   rp, 42, COL_LABEL_SB, false);
        g.drawString(font, menu.getRadius() + " blocks", rp, 52, COL_VALUE, false);

        // Upgrades (below third divider at y=62)
        g.drawString(font, "Upgrades", rp, 66, COL_LABEL_SB, false);

        // Coloured 5×5 square + tier name for each tier
        String[] tLabels = {"Tier I", "Tier II", "Tier III"};
        int[]    tYs     = {78, 90, 102};
        for (int i = 0; i < 3; i++) {
            boolean applied = tier > i;
            int     iCol    = applied ? 0xFF_55FF55 : 0xFF_444444;
            // 5×5 coloured indicator square, then tier name
            // (renderLabels uses image-relative coords — pose already translated)
            g.fill(rp, tYs[i], rp + 5, tYs[i] + 5, iCol);
            g.drawString(font, tLabels[i], rp + 8, tYs[i], iCol, false);
        }
    }

    // ── Full render ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (namingMode) {
            renderBackground(g, mx, my, pt);
            g.fill(0, 0, width, height, 0x88000000);
            Component prompt = Component.literal("Enter a name for your village:");
            g.drawString(font, prompt,
                    width / 2 - font.width(prompt) / 2,
                    nameField.getY() - font.lineHeight - 6, 0xFFFFFF, true);
            nameField.render(g, mx, my, pt);
            confirmBtn.render(g, mx, my, pt);
            return;
        }

        super.render(g, mx, my, pt);
        renderTooltip(g, mx, my);

        // Wheat bar tooltip
        int bx = leftPos + BAR_X, by = topPos + BAR_Y;
        if (mx >= bx && mx < bx + BAR_W && my >= by && my < by + BAR_H) {
            g.renderTooltip(font,
                    List.of(Component.literal(menu.getStoredWheat()
                            + " / " + VillageHeartBlockEntity.MAX_WHEAT + " Bundles of Wheat")),
                    Optional.empty(), mx, my);
        }

        // Village-name rename tooltip (right sidebar top area)
        int nx = leftPos + RIGHT_X + 5;
        int ny = topPos + 4;
        if (mx >= nx && mx < leftPos + IMG_W - 4 && my >= ny && my < ny + font.lineHeight + 2) {
            g.renderTooltip(font,
                    List.of(Component.literal("Click to rename village")
                            .withStyle(ChatFormatting.GRAY)),
                    Optional.empty(), mx, my);
        }
    }

    // ── Mouse ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (!namingMode) {
            // Click on village name → enter rename mode
            int nx = leftPos + RIGHT_X + 5;
            int ny = topPos  + 4;
            if (mx >= nx && mx < leftPos + IMG_W - 4 && my >= ny && my < ny + font.lineHeight + 2) {
                namingMode = true;
                nameField.setValue(menu.getVillageName());
                nameField.setVisible(true);
                nameField.setFocused(true);
                confirmBtn.visible = true;
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx >= leftPos && mx < leftPos + LEFT_W
                && my >= topPos && my < topPos + IMG_H) {
            int maxVis    = (IMG_H - GOLEM_TOP) / GOLEM_H;
            int maxScroll = Math.max(0, menu.getGolems().size() - maxVis);
            golemScroll   = (int) Math.max(0, Math.min(maxScroll, golemScroll - sy));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    // ── Keyboard ──────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (namingMode) {
            if (key == 257 || key == 335) { confirmName(); return true; }
            if (key == 256)               { confirmName(); return true; }
            if (nameField.isFocused())     return nameField.keyPressed(key, scan, mods);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (namingMode) return nameField.charTyped(c, mods);
        return super.charTyped(c, mods);
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    private void confirmName() {
        String raw  = nameField.getValue().trim();
        String name = raw.isEmpty() ? "My Village" : raw;
        menu.setVillageName(name);
        PacketDistributor.sendToServer(new SetVillageNamePacket(menu.getHeartPos(), name));
        namingMode = false;
        nameField.setVisible(false);
        nameField.setFocused(false);
        confirmBtn.visible = false;
    }
}
