package com.guiltypotato.checkengine.ui;

import com.guiltypotato.checkengine.core.ui.EngineIcon;
import com.guiltypotato.checkengine.core.ui.ScreenHelp;
import com.guiltypotato.checkengine.core.ui.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/**
 * The one look of every screen, on a fixed dark background. A header bar: the engine light and "Check Engine: state"
 * left, code and date right. A left column: tabs as words on top (or where you came from), Help at the bottom. Right
 * of it the text, and under that the screen's own buttons, the main one first and outlined; Continue, Cancel and Quit
 * always far right. No mod's own logic lives here (that's why it's in ui).
 */
public class DashScreen extends Screen {
    public static final int RED = UanColors.RED, AMBER = UanColors.AMBER, GREEN = UanColors.GREEN,
            BLUE = UanColors.BLUE, GREY = UanColors.GREY;
    private static final ResourceLocation ENGINE_LIGHT = ResourceLocation.fromNamespaceAndPath("checkengine", "textures/gui/engine_light.png");
    private static String NAME = "";
    private static Function<DashScreen, List<Btn>> HELP = s -> List.of();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("UAN UI");
    private static final int LINE = 10, HEAD = 20, SIDE = 90, SIDE_BUTTON = 78, SIDE_ROW = 22, SEARCH_H = 18, ROW = 24,
            COLUMN = 640;
    /** Opened the same on every screen: the room behind it, almost solid, the world only faintly visible. */
    private static final int BACKGROUND = 0xE61C1C1C, PANEL = 0x70000000, SIDE_PANEL = 0x40000000, RULE = 0xFF505050;
    /** Tabs and places that aren't the open one. */
    private static final float DIMMED = 0.65f;

    /**
     * Sets what every screen shares: the name in the header, and the Help menu's rows under "What is this screen?".
     */
    public static void brand(String name, Function<DashScreen, List<Btn>> help) {
        NAME = name;
        HELP = help;
    }

    /** The engine light with x, y its top left (the baked halo goes around that): red, amber, or else green. */
    public static void engine(GuiGraphics g, int x, int y, int color) {
        int cell = (color | 0xFF000000) == RED ? EngineIcon.RED : (color | 0xFF000000) == AMBER ? EngineIcon.AMBER : EngineIcon.GREEN;
        int m = EngineIcon.MARGIN, w = EngineIcon.WIDTH + 2 * m, h = EngineIcon.HEIGHT + 2 * m;
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blit(ENGINE_LIGHT, x - m, y - m, w, h, 0, cell * EngineIcon.CELL_H, EngineIcon.CELL_W, EngineIcon.CELL_H, EngineIcon.CELL_W, EngineIcon.CELL_H * 3);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    public enum Style { STRONG, TEXT, DIM, MARK, CLIP }

    public record Line(String text, Style style) {
        public static Line strong(String t) {
            return new Line(t, Style.STRONG);
        }

        public static Line text(String t) {
            return new Line(t, Style.TEXT);
        }

        /** Picked out in amber, e.g. the crash line that names the mod at fault. */
        public static Line mark(String t) {
            return new Line(t, Style.MARK);
        }

        public static Line dim(String t) {
            return new Line(t, Style.DIM);
        }

        /** Dim, on one line only: cut to the panel's width with "..." (an error message, say). */
        public static Line clip(String t) {
            return new Line(t, Style.CLIP);
        }
    }

    /** A block of text. {@code icon} is one character before its heading ("!", "✓"...); a null title has no heading. */
    public record Section(String title, int tone, String icon, List<Line> lines) {}

    /** A button. Main ones come first; Continue, Cancel and Quit go far right. {@code tip} shows on hover, or null. */
    public record Btn(String label, Runnable run, boolean primary, String tip) {
        public Btn(String label, Runnable run, boolean primary) {
            this(label, run, primary, null);
        }
    }

    /**
     * A tab: its own text and its own buttons. {@code count} (or null) is the little number next to its label, e.g. how
     * many things changed. {@code icon} is kept for callers; tabs always show their words.
     */
    public record Tab(String label, String icon, int tone, String count, List<Section> sections, List<Btn> buttons,
                      Search search, String subtitle) {
        public Tab(String label, String icon, int tone, String count, List<Section> sections, List<Btn> buttons) {
            this(label, icon, tone, count, sections, buttons, null, null);
        }

        public Tab(String label, String icon, int tone, String count, List<Section> sections, List<Btn> buttons,
                   Search search) {
            this(label, icon, tone, count, sections, buttons, search, null);
        }

        /** The same tab with its own headline ("GO BACK") instead of the screen's while it's open. */
        public Tab withSubtitle(String subtitle) {
            return new Tab(label, icon, tone, count, sections, buttons, search, subtitle);
        }
    }

    /**
     * A search box over a tab: {@code sections} turns what's typed into the text to show ("" before anything is
     * typed). Give the list a tab of its own, so nothing else sits under the box.
     */
    public record Search(String hint, Function<String, List<Section>> sections) {}

    private final int tone;
    private final String headline;
    private String code;
    private String codeLabel;
    private List<Section> sections;
    private List<Btn> buttons;
    private final Runnable escape;
    /** Tabs, or null for a screen without them; {@code fixed} are the buttons every tab shows (Continue). */
    private final List<Tab> tabs;
    private final List<Btn> fixed;
    private int tab;
    private String query = "";
    private int colX, colW, top, bottom, contentHeight, scroll;
    /** The main button, outlined. */
    private int mainX, mainY, mainW = -1;
    /** The open tab or place in the column, marked with a bar; -1 = none. */
    private int openY = -1;
    /** Where this screen was opened from, or null. */
    private DashScreen from;
    /** The light's colour when it isn't simply the headline's (the hub shows the pack's real state); 0 = none. */
    private int light;
    /** Copy for help on this screen: its own, or null for what the brand does; {@link #noCopy} leaves the row out. */
    private Runnable copy;
    private boolean noCopy;
    private boolean helpOpen;
    /** The open Help menu's corner: text stays out of just that box (a font mod can draw text over the buttons). */
    private int menuTop, menuRight;
    private boolean isHelpPage;

    /**
     * @param escape what Esc does (usually the same as the "back" or "continue" button)
     */
    public DashScreen(int tone, String headline, String code, String codeLabel, List<Section> sections, List<Btn> buttons,
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
     * A screen with tabs. It opens on the first tab; {@code fixed} buttons (Continue) are there on every tab. Tabs with
     * nothing in them should be left out by the caller.
     */
    public DashScreen(int tone, String headline, String code, String codeLabel, Runnable escape, List<Tab> tabs,
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

    /**
     * Opened from {@code parent}: the column shows the way here and a click goes back. Without a code of its own it
     * shows the parent's, and it copies what the parent copies.
     */
    public DashScreen from(Screen parent) {
        if (parent instanceof DashScreen p) {
            from = p;
            if (code == null && codeLabel == null) {
                code = p.code;
                codeLabel = p.codeLabel;
            }
            if (copy == null && !noCopy) {
                copy = p.copy;
                noCopy = p.noCopy;
            }
            // Only information (Help, what was copied): the pack's state hasn't changed, so the light stays as it was
            if (light == 0 && tone != RED && tone != AMBER && tone != GREEN) light = p.lamp();
        }
        return this;
    }

    /** The colour the light shows now. */
    private int lamp() {
        boolean own = tabs != null && tabs.get(tab).subtitle() != null;
        int headTone = own ? tabs.get(tab).tone() : tone;
        return light != 0 ? light : headTone == RED || headTone == AMBER ? headTone : GREEN;
    }

    /** The light's colour, whatever the headline's is. */
    public DashScreen light(int color) {
        light = color;
        return this;
    }

    /** What Help > Copy for help does here; null leaves the row out (another mod already has it). */
    public DashScreen copies(Runnable action) {
        copy = action;
        noCopy = action == null;
        return this;
    }

    /** This screen's own Copy for help, or null when the brand's general one applies. */
    public Runnable copyAction() {
        return copy;
    }

    public boolean hidesCopy() {
        return noCopy;
    }

    /** The screen's short name for the column and for Help ("Crash Doctor"), or null. */
    public String place() {
        return ScreenHelp.place(headline);
    }

    /** The screen's text, one string per line, for Copy for help. */
    public List<String> plainText() {
        List<String> out = new ArrayList<>();
        for (Section s : sections) {
            if (s.title() != null) out.add(UiText.sentence(s.title()));
            for (Line l : s.lines()) out.add(l.text());
        }
        return out;
    }

    /** What the header says, without the name. */
    public String state() {
        boolean own = tabs != null && tabs.get(tab).subtitle() != null;
        return UiText.afterName(own ? tabs.get(tab).subtitle() : headline);
    }

    public String code() {
        return code;
    }

    private void show(int i) {
        tab = i;
        Search search = tabs.get(i).search();
        sections = search != null ? search.sections().apply(query) : tabs.get(i).sections();
        List<Btn> row = new ArrayList<>(tabs.get(i).buttons());
        row.addAll(fixed);
        buttons = row;
        scroll = 0;
    }

    /** The open tab's search, or null. */
    private Search search() {
        return tabs == null ? null : tabs.get(tab).search();
    }

    /** Runs the open tab's search again, e.g. after a sort button changed the order. */
    public void refresh() {
        Search search = search();
        if (search == null) return;
        sections = search.sections().apply(query);
        measure();
        scroll = 0;
    }

    // ---- layout ----

    private List<FormattedCharSequence> wrap(Line l) {
        if (l.style() == Style.CLIP) {
            String t = l.text();
            if (font.width(t) > colW) t = font.plainSubstrByWidth(t, colW - font.width("...")) + "...";
            return List.of(Component.literal(t).getVisualOrderText());
        }
        Component c = Component.literal(l.text()).withStyle(l.style() == Style.STRONG ? ChatFormatting.BOLD
                : ChatFormatting.RESET);
        return font.split(c, colW);
    }

    private int sectionHeight(Section s) {
        int h = s.title() == null ? 0 : LINE + 2;
        for (Line l : s.lines()) h += wrap(l).size() * LINE;
        return h;
    }

    private void measure() {
        int h = 0;
        for (Section s : sections) h += sectionHeight(s) + 6;
        contentHeight = h;
    }

    @Override
    protected void init() {
        colX = SIDE + 8;
        colW = Math.min(width - colX - 10, COLUMN);
        // The Help menu takes clicks first, and a click anywhere else closes it; it's drawn last, over everything
        List<Button> menu = helpOpen ? helpMenu() : List.of();
        for (Button b : menu) addWidget(b);
        if (helpOpen) addWidget(Button.builder(Component.empty(), b -> closeHelp()).bounds(0, 0, width, height).build());

        sideColumn();
        addRenderableWidget(Button.builder(Component.literal("Help"), b -> {
            helpOpen = !helpOpen;
            rebuildWidgets();
        }).bounds(6, height - 24, SIDE_BUTTON, 20).build());

        top = HEAD + 1;
        Search search = search();
        if (search != null) {
            EditBox box = new EditBox(font, colX, top + 3, colW, 14, Component.literal(search.hint()));
            box.setHint(Component.literal(search.hint()));
            box.setValue(query);
            box.setResponder(text -> {
                query = text;
                refresh();
            });
            addRenderableWidget(box);
            top += SEARCH_H;
        }
        buttonRow();
        for (Button b : menu) addRenderableOnly(b);
        measure();
    }

    /** Top of the column: the tabs, or the way to this screen with the current one bright. */
    private void sideColumn() {
        openY = -1;
        int y = HEAD + 5;
        if (tabs != null) {
            for (int i = 0; i < tabs.size(); i++) {
                Tab t = tabs.get(i);
                int index = i;
                MutableComponent label = Component.literal(t.label());
                if (t.count() != null) label.append(Component.literal(" " + t.count()).withStyle(ChatFormatting.YELLOW));
                sideButton(label, y, i == tab, () -> {
                    query = "";
                    show(index);
                    rebuildWidgets();
                });
                y += SIDE_ROW;
            }
            return;
        }
        List<DashScreen> way = new ArrayList<>();
        for (DashScreen s = this; s != null; s = s.from) way.add(0, s);
        for (DashScreen s : way) {
            String name = s.place();
            if (name == null) continue;
            sideButton(Component.literal(name), y, s == this, () -> {
                if (s != this) Minecraft.getInstance().setScreen(s);
            });
            y += SIDE_ROW;
        }
    }

    private void sideButton(Component label, int y, boolean open, Runnable run) {
        Button b = Button.builder(open ? label : label.copy().withStyle(ChatFormatting.GRAY), x -> run.run())
                .bounds(6, y, SIDE_BUTTON, 20).build();
        if (open) openY = y;
        else b.setAlpha(DIMMED);
        addRenderableWidget(b);
    }

    /** The main button first, then the others, wrapping when they don't fit; what closes the screen far right. */
    private void buttonRow() {
        List<Btn> left = new ArrayList<>(buttons.stream().filter(b -> !UiText.closes(b.label()) && b.primary()).toList());
        left.addAll(buttons.stream().filter(b -> !UiText.closes(b.label()) && !b.primary()).toList());
        List<Btn> right = buttons.stream().filter(b -> UiText.closes(b.label())).toList();
        int startX = SIDE + 6, avail = width - startX - 6, rightW = 0;
        for (Btn b : right) rightW += buttonWidth(b.label()) + 4;
        // A long label says less rather than taking a second row
        int total = rightW;
        for (Btn b : left) total += buttonWidth(b.label()) + 4;
        boolean shorten = total > avail;
        List<List<Btn>> rows = new ArrayList<>();
        List<Btn> row = new ArrayList<>();
        int rowW = 0;
        for (Btn b : left) {
            int w = buttonWidth(label(b, shorten));
            if (!row.isEmpty() && rowW + w > avail) {
                rows.add(row);
                row = new ArrayList<>();
                rowW = 0;
            }
            row.add(b);
            rowW += w + 4;
        }
        rows.add(row);
        boolean ownRow = rowW + rightW > avail;
        int count = rows.size() + (ownRow ? 1 : 0);
        bottom = height - count * ROW - 4;
        int by = bottom + 5;
        mainW = -1;
        for (List<Btn> r : rows) {
            int x = startX;
            for (Btn b : r) {
                int w = buttonWidth(label(b, shorten));
                if (mainW < 0 && b.primary()) {
                    mainX = x;
                    mainY = by;
                    mainW = w;
                }
                addRenderableWidget(button(b, label(b, shorten), x, by, w));
                x += w + 4;
            }
            by += ROW;
        }
        if (!ownRow) by -= ROW;
        int x = width - 6;
        for (int i = right.size() - 1; i >= 0; i--) {
            Btn b = right.get(i);
            int w = buttonWidth(b.label());
            x -= w;
            if (mainW < 0 && b.primary()) {
                mainX = x;
                mainY = by;
                mainW = w;
            }
            addRenderableWidget(button(b, b.label(), x, by, w));
            x -= 4;
        }
    }

    private static String label(Btn b, boolean shorten) {
        return shorten ? UiText.shorter(b.label()) : b.label();
    }

    private Button button(Btn b, String label, int x, int y, int w) {
        Button.Builder builder = Button.builder(Component.literal(label), btn -> safely(b.run())).bounds(x, y, w, 20);
        if (b.tip() != null) builder.tooltip(Tooltip.create(Component.literal(b.tip())));
        return builder.build();
    }

    private int buttonWidth(String label) {
        return Math.max(50, font.width(label) + 14);
    }

    // ---- Help ----

    /** The menu over the Help button: What is this screen? first, then the brand's rows. */
    private List<Button> helpMenu() {
        List<Btn> rows = new ArrayList<>();
        if (!isHelpPage) rows.add(new Btn("What is this screen?", this::explain, false));
        rows.addAll(HELP.apply(this));
        int w = SIDE_BUTTON;
        for (Btn b : rows) w = Math.max(w, font.width(b.label()) + 14);
        w = Math.min(w, width - 12);
        List<Button> out = new ArrayList<>();
        int y = height - 24 - rows.size() * 20;
        menuTop = y;
        menuRight = 6 + w;
        for (Btn b : rows) {
            int fw = w;
            out.add(Button.builder(Component.literal(b.label()), x -> {
                helpOpen = false;
                safely(b.run());
                if (Minecraft.getInstance().screen == this) rebuildWidgets();
            }).bounds(6, y, fw, 20).build());
            y += 20;
        }
        return out;
    }

    private void closeHelp() {
        helpOpen = false;
        rebuildWidgets();
    }

    /** What is this screen?: plain words, from inside the jar. */
    private void explain() {
        String tabName = tabs == null ? null : tabs.get(tab).label();
        List<Line> lines = new ArrayList<>();
        for (String s : ScreenHelp.explain(place(), tabName, code)) lines.add(Line.text(s));
        Runnable back = () -> Minecraft.getInstance().setScreen(this);
        DashScreen page = new DashScreen(BLUE, "WHAT IS THIS SCREEN?", null, null, List.of(new Section(null, BLUE, "", lines)),
                List.of(new Btn("Cancel", back, false)), back);
        page.isHelpPage = true;
        Minecraft.getInstance().setScreen(page.from(this));
    }

    private int maxScroll() {
        return Math.max(0, contentHeight + 4 - (bottom - top));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (scrollY * LINE * 2)));
        return true;
    }

    // ---- drawing ----

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BACKGROUND);
        g.fill(SIDE + 1, HEAD + 1, width, bottom, PANEL);
        g.fill(0, HEAD + 1, SIDE, height, SIDE_PANEL);
        g.fill(0, HEAD, width, HEAD + 1, RULE);
        g.fill(SIDE, HEAD + 1, SIDE + 1, height, RULE);
        g.fill(SIDE + 1, bottom, width, bottom + 1, RULE);

        // The light: red or amber when something's wrong, else green. The words keep the screen's own colour.
        boolean own = tabs != null && tabs.get(tab).subtitle() != null;
        int headTone = own ? tabs.get(tab).tone() : tone;
        String name = NAME + ": ";
        String head = state();
        String meta = code == null ? codeLabel == null ? "" : codeLabel
                : codeLabel == null ? code : code + " · " + codeLabel;
        int textX = 6 + EngineIcon.WIDTH + 5, room = width - textX - font.width(name) - 6;
        if (font.width(head) + font.width(meta) + 8 > room) meta = code == null ? "" : code;
        int metaW = meta.isEmpty() ? 0 : font.width(meta);
        if (font.width(head) + metaW + 8 > room) {
            head = font.plainSubstrByWidth(head, room - metaW - 8 - font.width("...")) + "...";
        }
        engine(g, 6, (HEAD - EngineIcon.HEIGHT) / 2, lamp());
        g.drawString(font, name, textX, 6, 0xFFF0F0F0);
        g.drawString(font, head, textX + font.width(name), 6, headTone);
        if (metaW > 0) g.drawString(font, meta, width - metaW - 6, 6, GREY);

        if (openY >= 0) g.fill(2, openY, 4, openY + 20, 0xFF000000 | headTone); // the open tab, in the header's colour
        if (mainW > 0) { // the main button, outlined
            g.fill(mainX - 1, mainY - 1, mainX + mainW + 1, mainY + 21, 0xFFFFFFFF);
        }

        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        if (helpOpen && menuTop < bottom) {
            g.enableScissor(SIDE + 1, top, width, menuTop);
            drawText(g);
            g.disableScissor();
            g.enableScissor(menuRight, menuTop, width, bottom);
        } else {
            g.enableScissor(SIDE + 1, top, width, bottom);
        }
        drawText(g);
        g.disableScissor();
        if (maxScroll() > 0) { // thin scrollbar at the panel's right edge
            int track = bottom - top, thumb = Math.max(12, track * track / (track + maxScroll()));
            int ty = top + (track - thumb) * scroll / maxScroll(), bx = colX + colW + 4;
            g.fill(bx, top, bx + 2, bottom, 0x40808080);
            g.fill(bx, ty, bx + 2, ty + thumb, 0xFFA0A0A0);
        }
    }

    private void drawText(GuiGraphics g) {
        int y = top + 4 - scroll;
        for (Section s : sections) {
            if (s.title() != null) {
                g.drawString(font, s.icon() + " " + UiText.sentence(s.title()), colX, y, s.tone());
                y += LINE + 2;
            }
            for (Line l : s.lines()) {
                int color = l.style() == Style.DIM || l.style() == Style.CLIP ? GREY : l.style() == Style.MARK ? AMBER : 0xFFF0F0F0;
                for (FormattedCharSequence part : wrap(l)) {
                    g.drawString(font, part, colX, y, color);
                    y += LINE;
                }
            }
            y += 6;
        }
    }

    @Override
    public void onClose() {
        if (helpOpen) {
            closeHelp();
            return;
        }
        safely(escape);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Helpers for building screens. */
    public static List<Line> lines(String... texts) {
        List<Line> out = new ArrayList<>();
        for (String t : texts) out.add(Line.text(t));
        return out;
    }

    /** A button that fails (a file gone, a folder that can't open) is logged, never a crash. */
    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOGGER.warn("{}: that button didn't work", NAME, e);
        }
    }
}
