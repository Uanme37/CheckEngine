package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.CheckEngine;
import com.guiltypotato.checkengine.core.scan.PackInfo;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Check Engine's in-game screens, in the same dashboard style as the pre-launch window: a hazard stripe for warnings,
 * the lamp and headline, the fault code readout, screwed-on panels with an icon, the buttons and the UAN footer.
 * Each screen only says what's on it.
 */
public class DashScreen extends Screen {
    static final int RED = 0xFFFF4B3E, AMBER = 0xFFFF9A1F, GREEN = 0xFF3DDC4A, BLUE = 0xFF3B82F6, GREY = 0xFF9A9CA3;
    private static final Identifier LOGO = Identifier.fromNamespaceAndPath("checkengine", "textures/gui/lamp.png");
    private static final Identifier UAN = Identifier.fromNamespaceAndPath("checkengine", "textures/gui/uan.png");
    private static final int LINE = 10;

    enum Style { STRONG, TEXT, DIM, MARK }

    record Line(String text, Style style) {
        static Line strong(String t) {
            return new Line(t, Style.STRONG);
        }

        static Line text(String t) {
            return new Line(t, Style.TEXT);
        }

        /** Picked out in amber, e.g. the crash line that names the mod at fault. */
        static Line mark(String t) {
            return new Line(t, Style.MARK);
        }

        static Line dim(String t) {
            return new Line(t, Style.DIM);
        }
    }

    /** A panel. {@code icon} is one character drawn in the round badge ("!", "✔"...). */
    record Section(String title, int tone, String icon, List<Line> lines) {}

    /** A button. Primary ones sit on the left, the rest are smaller on the right. {@code tip} shows on hover, or null. */
    record Btn(String label, Runnable run, boolean primary, String tip) {
        Btn(String label, Runnable run, boolean primary) {
            this(label, run, primary, null);
        }
    }

    /**
     * A side-bar tab: its own panels and its own buttons. {@code icon} is one character, shown alone when the window is
     * too small for the label; {@code count} (or null) is the little number next to it, e.g. how many things changed.
     */
    record Tab(String label, String icon, int tone, String count, List<Section> sections, List<Btn> buttons) {}

    /** Narrower than this, the side bar shows only the tab icons. */
    private static final int FULL_LABELS = 400;
    private static final int RAIL = 92, RAIL_ICONS = 26;

    private final int tone;
    private final String headline;
    private final String code;
    private final String codeLabel;
    private List<Section> sections;
    private List<Btn> buttons;
    private final Runnable escape;
    /** Side-bar tabs, or null for a screen without them; {@code fixed} are the buttons every tab shows (Continue). */
    private final List<Tab> tabs;
    private final List<Btn> fixed;
    private int tab;
    private int cardX, cardY, cardW, cardH, buttonRows = 1, contentHeight, scroll;
    private boolean moreOpen;

    /**
     * @param escape what Esc does (usually the same as the "back" or "continue" button)
     */
    DashScreen(int tone, String headline, String code, String codeLabel, List<Section> sections, List<Btn> buttons,
               Runnable escape) {
        super(Component.literal(headline));
        this.tone = tone;
        this.headline = headline;
        this.code = code;
        this.codeLabel = codeLabel;
        this.sections = sections;
        this.buttons = buttons;
        this.escape = escape;
        this.tabs = null;
        this.fixed = List.of();
    }

    /**
     * A screen with side-bar tabs. It opens on the first tab; {@code fixed} buttons (Continue) are there on every tab.
     * Tabs with nothing in them should be left out by the caller.
     */
    DashScreen(int tone, String headline, String code, String codeLabel, Runnable escape, List<Tab> tabs,
               List<Btn> fixed) {
        super(Component.literal(headline));
        this.tone = tone;
        this.headline = headline;
        this.code = code;
        this.codeLabel = codeLabel;
        this.escape = escape;
        this.tabs = tabs;
        this.fixed = fixed;
        show(0);
    }

    private void show(int i) {
        tab = i;
        sections = tabs.get(i).sections();
        List<Btn> row = new ArrayList<>(tabs.get(i).buttons());
        row.addAll(fixed);
        buttons = row;
        scroll = 0;
    }

    // ---- layout ----

    /** The side bar's width: labels when the card is wide enough, else icons only, none without tabs. */
    private int rail() {
        return tabs == null ? 0 : cardW >= FULL_LABELS ? RAIL : RAIL_ICONS;
    }

    private int textWidth() {
        return cardW - 46 - rail();
    }

    private List<FormattedCharSequence> wrap(Line l) {
        Component c = Component.literal(l.text()).withStyle(l.style() == Style.STRONG ? ChatFormatting.BOLD
                : ChatFormatting.RESET);
        return font.split(c, textWidth());
    }

    private int sectionHeight(Section s) {
        int h = 8 + LINE + 2;
        for (Line l : s.lines()) h += wrap(l).size() * LINE;
        return h + 4;
    }

    @Override
    protected void init() {
        // With a side bar the card gets wider, so the panels keep about the same room for text.
        cardW = tabs == null ? Math.min(width - 16, Math.max(320, Math.min(400, (int) (width * 0.72))))
                : Math.min(width - 16, Math.max(320, Math.min(480, (int) (width * 0.85))));
        cardX = (width - cardW) / 2;
        int content = 0;
        for (Section s : sections) content += sectionHeight(s) + 5;
        contentHeight = content;
        if (tabs != null) {
            // The card keeps one size on every tab: as tall as the longest tab, and tall enough for the side bar.
            content = tabs.size() * 24 + 4;
            for (Tab t : tabs) {
                int h = 0;
                for (Section s : t.sections()) h += sectionHeight(s) + 5;
                content = Math.max(content, h);
            }
        }
        // Buttons: one row if they fit, else the small ones get a row of their own under the main ones.
        List<Btn> row = row();
        // Main buttons wrap onto more rows when they don't fit; the small ones go at the right of the last row.
        int avail = cardW - 24;
        List<List<Btn>> lines = new ArrayList<>();
        List<Btn> line = new ArrayList<>();
        int lineW = 0;
        for (Btn b : row) {
            if (!b.primary()) continue;
            int w = buttonWidth(b);
            if (!line.isEmpty() && lineW + 6 + w > avail) {
                lines.add(line);
                line = new ArrayList<>();
                lineW = 0;
            }
            lineW += (line.isEmpty() ? 0 : 6) + w;
            line.add(b);
        }
        lines.add(line);
        int smallW = rowWidth(row.stream().filter(b -> !b.primary()).toList());
        boolean smallOwnRow = smallW > 0 && !line.isEmpty() && lineW + 12 + smallW > avail;
        buttonRows = lines.size() + (smallOwnRow ? 1 : 0);
        cardH = Math.min(height - 12, 4 + 46 + content + 2 + 28 + 16 + (buttonRows - 1) * 24);
        cardY = Math.max(6, (height - cardH) / 2);

        int y = cardY + cardH - 16 - 25 - (buttonRows - 1) * 24;
        for (int i = 0; i < lines.size(); i++) {
            int x = cardX + 12;
            for (Btn b : lines.get(i)) {
                int w = buttonWidth(b);
                addRenderableWidget(button(b, x, y, w));
                x += w + 6;
            }
            if (i < lines.size() - 1) y += 24;
        }
        if (smallOwnRow) y += 24;
        int right = cardX + cardW - 12;
        List<Btn> small = row.stream().filter(b -> !b.primary()).toList();
        for (int i = small.size() - 1; i >= 0; i--) {
            Btn b = small.get(i);
            int w = buttonWidth(b);
            right -= w;
            addRenderableWidget(button(b, right, y, w));
            right -= 5;
        }
        if (tabs != null) {
            // The side bar: one button per tab, the open one in bold with a coloured mark beside it (see drawing).
            boolean labels = rail() == RAIL;
            for (int i = 0; i < tabs.size(); i++) {
                Tab t = tabs.get(i);
                int index = i;
                String text = labels ? t.label() + (t.count() != null ? " " + t.count() : "") : t.icon() + (t.count() != null && t.count().length() == 1 ? t.count() : "");
                Component label = i == tab ? Component.literal(text).withStyle(ChatFormatting.BOLD) : Component.literal(text);
                Button.Builder b = Button.builder(label, btn -> {
                    show(index);
                    rebuildWidgets();
                }).bounds(cardX + 8, cardY + 50 + i * 24, rail() - 6, 20);
                if (!labels) b.tooltip(Tooltip.create(Component.literal(t.label() + (t.count() != null ? " (" + t.count() + ")" : ""))));
                addRenderableWidget(b.build());
            }
        }
    }

    /**
     * The buttons on show. With 3 or more small ones, they wait under "More" (which swaps the row to them, plus
     * "Back"), so the main row stays short.
     */
    private List<Btn> row() {
        List<Btn> buttons = new ArrayList<>(this.buttons);
        // The pack maker's help link (config/checkengine-pack.properties), when there is one.
        String help = PackInfo.load(FMLPaths.GAMEDIR.get()).supportLink();
        if (help != null) buttons.add(new Btn("Get help", () -> Util.getPlatform().openUri(help), false, help));
        // With tabs every tab has only a few buttons: they wrap onto a second row instead of hiding under More.
        if (tabs != null) return buttons;
        List<Btn> primary = new ArrayList<>(buttons.stream().filter(Btn::primary).toList());
        List<Btn> small = new ArrayList<>(buttons.stream().filter(b -> !b.primary()).toList());
        // Main buttons that don't fit in one row wait under More too (the first ones are the most important).
        Btn moreButton = new Btn("More...", () -> {}, false);
        while (primary.size() > 1 && rowWidth(primary) + 6 + buttonWidth(moreButton) > cardW - 24) {
            Btn last = primary.remove(primary.size() - 1);
            small.add(0, new Btn(last.label(), last.run(), false, last.tip()));
        }
        boolean moved = primary.size() < buttons.stream().filter(Btn::primary).count();
        if (small.size() < 3 && !moved) return buttons;
        List<Btn> out = new ArrayList<>();
        if (!moreOpen) {
            out.addAll(primary);
            out.add(new Btn("More...", () -> {
                moreOpen = true;
                rebuildWidgets();
            }, false));
        } else {
            small.forEach(b -> out.add(new Btn(b.label(), b.run(), true, b.tip())));
            out.add(new Btn("Back", () -> {
                moreOpen = false;
                rebuildWidgets();
            }, false));
        }
        return out;
    }

    private int rowWidth(List<Btn> row) {
        int w = 0;
        for (Btn b : row) w += buttonWidth(b) + 6;
        return Math.max(0, w - 6);
    }

    private Button button(Btn b, int x, int y, int w) {
        Button.Builder builder = Button.builder(label(b), btn -> safely(b.run())).bounds(x, y, w, 20);
        if (b.tip() != null) builder.tooltip(Tooltip.create(Component.literal(b.tip())));
        return builder.build();
    }

    private Component label(Btn b) {
        return b.primary() ? Component.literal(b.label()).withStyle(ChatFormatting.BOLD) : Component.literal(b.label());
    }

    private int buttonWidth(Btn b) {
        return b.primary() ? Math.max(70, font.width(label(b)) + 20) : Math.max(50, font.width(label(b)) + 14);
    }

    private int panelsBottom() {
        return cardY + cardH - 16 - 30 - (buttonRows - 1) * 24;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight + 2 - (panelsBottom() - (cardY + 48)));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (scrollY * LINE * 2)));
        return true;
    }

    // ---- drawing ----

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        int x = cardX, y = cardY, w = cardW, h = cardH;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF34363B); // edge
        g.fillGradient(x, y, x + w, y + h, 0xFF232428, 0xFF131416);
        for (int i = 0; i < w; i += 3) g.fill(x + i, y, x + i + 1, y + h, 0x07FFFFFF); // brushed metal

        boolean warning = tone == RED || tone == AMBER;
        if (warning) { // hazard tape
            g.fill(x, y, x + w, y + 4, 0xFF141414);
            for (int row = 0; row < 4; row++) {
                for (int sx = -8; sx < w; sx += 12) {
                    int a = Math.max(0, sx + (3 - row)), b = Math.min(w, sx + (3 - row) + 6);
                    if (b > a) g.fill(x + a, y + row, x + b, y + row + 1, tone);
                }
            }
        } else {
            g.fill(x, y, x + w, y + 2, tone);
        }

        // Lamp: the logo in a bezel whose ring blinks.
        int lx = x + 12, ly = y + 12;
        boolean on = (Util.getMillis() / 550) % 2 == 0;
        // Drawn once, smooth, like the window's lamp (lamp.png: each colour lit and unlit, 128 px each).
        int cell = (tone == RED ? 0 : tone == AMBER ? 1 : tone == GREEN ? 2 : tone == BLUE ? 3 : 4) * 2 + (on ? 0 : 1);
        g.blit(RenderPipelines.GUI_TEXTURED, LOGO, lx - 6, ly - 6, cell * 128, 0, 44, 44, 128, 128, 1280, 128);

        g.text(font, Component.literal("CHECK ENGINE").withStyle(ChatFormatting.BOLD), x + 54, y + 14, 0xFFE8E8EA);
        g.text(font, Component.literal(headline).withStyle(ChatFormatting.BOLD), x + 54, y + 27, tone);

        if (code != null) { // fault code readout
            int cw = font.width(code) + 12, cx = x + w - 12 - cw;
            g.fill(cx - 1, y + 11, cx + cw + 1, y + 27, 0xFF33353A);
            g.fill(cx, y + 12, cx + cw, y + 26, 0xFF0A0B0C);
            g.text(font, code, cx + 6, y + 15, tone, false);
            if (codeLabel != null) small(g, codeLabel, x + w - 12, y + 30, GREY, true);
        }

        // Panels: they scroll with the mouse wheel when they don't all fit.
        int top = y + 48, limit = panelsBottom();
        if (tabs != null) { // side bar: a divider, and the open tab's colour beside its button
            g.fill(x + rail() + 3, top, x + rail() + 4, limit, 0xFF34363B);
            g.fill(x + 3, top + 2 + tab * 24, x + 6, top + 22 + tab * 24, tabs.get(tab).tone());
        }
        int px = x + rail(); // the panels start right of the side bar
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        g.enableScissor(px, top, x + w, limit);
        int sy = top + 2 - scroll;
        for (Section s : sections) {
            int sh = sectionHeight(s), bottom = sy + sh;
            if (bottom > top && sy < limit) {
                g.fill(px + 10, sy + 1, x + w - 10, bottom + 1, 0x60000000);
                g.fillGradient(px + 10, sy, x + w - 10, bottom, 0xFF26272B, 0xFF212226);
                g.fill(px + 13, sy, x + w - 13, sy + 1, 0xFF34363B);
                badge(g, px + 16, sy + 6, s.tone(), s.icon());
                screw(g, x + w - 16, sy + 3);
                screw(g, x + w - 16, bottom - 5);
                g.text(font, Component.literal(s.title()).withStyle(ChatFormatting.BOLD), px + 30, sy + 7, s.tone());
                int ty = sy + 8 + LINE + 2;
                for (Line l : s.lines()) {
                    int color = l.style() == Style.DIM ? GREY : l.style() == Style.MARK ? AMBER : 0xFFE8E8EA;
                    for (FormattedCharSequence part : wrap(l)) {
                        g.text(font, part, px + 30, ty, color);
                        ty += LINE;
                    }
                }
            }
            sy = bottom + 5;
        }
        g.disableScissor();
        if (maxScroll() > 0) { // thin scrollbar on the right edge
            int track = limit - top, thumb = Math.max(12, track * track / (track + maxScroll()));
            int ty = top + (track - thumb) * scroll / maxScroll();
            g.fill(x + w - 6, top, x + w - 4, limit, 0xFF26272B);
            g.fill(x + w - 6, ty, x + w - 4, ty + thumb, 0xFF6A6D74);
        }

        // Footer: the UAN mark and "Check Engine".
        int fy = y + h - 15;
        g.fill(x, fy - 1, x + w, fy, 0xFF2A2C30);
        g.fill(x, fy, x + w, y + h, 0xFF0F1012);
        g.blit(RenderPipelines.GUI_TEXTURED, UAN, x + 10, fy + 3, 0, 0, 9, 9, 32, 32, 32, 32);
        small(g, "Check Engine", x + 23, fy + 5, 0xFF9A9CA3, false);
    }

    /** A filled circle, drawn as one line of pixels per row (screens only draw rectangles). */
    private static void disc(GuiGraphicsExtractor g, int cx, int cy, double r, int color) {
        int ri = (int) Math.ceil(r);
        for (int dy = -ri; dy < ri; dy++) {
            double yy = dy + 0.5;
            if (Math.abs(yy) > r) continue;
            int half = (int) Math.round(Math.sqrt(r * r - yy * yy));
            g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
        }
    }

    private void badge(GuiGraphicsExtractor g, int x, int y, int color, String icon) {
        g.fill(x + 1, y, x + 9, y + 10, color);
        g.fill(x, y + 1, x + 10, y + 9, color);
        g.text(font, icon, x + 5 - font.width(icon) / 2, y + 1, 0xFF111111, false);
    }

    private static void screw(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + 3, y + 3, 0xFF6A6D74);
        g.fill(x + 1, y + 1, x + 2, y + 2, 0xFF2A2C30);
    }

    /** Small (three-quarter size) text, left- or right-aligned at x. */
    private void small(GuiGraphicsExtractor g, String text, int x, int y, int color, boolean right) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.75f, 0.75f);
        g.text(font, text, right ? -font.width(text) : 0, 0, color, false);
        g.pose().popMatrix();
    }

    private static int dim(int color) {
        return 0xFF000000 | ((color >> 1) & 0x7F7F7F);
    }

    @Override
    public void onClose() {
        safely(escape);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Helpers for building screens. */
    static List<Line> lines(String... texts) {
        List<Line> out = new ArrayList<>();
        for (String t : texts) out.add(Line.text(t));
        return out;
    }

    /** A button that fails (a file gone, a folder that can't open) is logged, never a crash. */
    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            CheckEngine.LOGGER.warn("Check Engine: that button didn't work", e);
        }
    }
}
