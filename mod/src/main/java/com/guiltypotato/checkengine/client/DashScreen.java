package com.guiltypotato.checkengine.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/**
 * Check Engine's in-game screens, in the same dashboard style as the pre-launch window: a hazard stripe for warnings,
 * the lamp and headline, the fault code readout, screwed-on panels with an icon, the buttons and the UAN footer.
 * Each screen only says what's on it.
 */
public class DashScreen extends Screen {
    static final int RED = 0xFFFF4B3E, AMBER = 0xFFFF9A1F, GREEN = 0xFF3DDC4A, BLUE = 0xFF3B82F6, GREY = 0xFF9A9CA3;
    private static final ResourceLocation LOGO = ResourceLocation.fromNamespaceAndPath("checkengine", "textures/gui/logo.png");
    private static final ResourceLocation UAN = ResourceLocation.fromNamespaceAndPath("checkengine", "textures/gui/uan.png");
    private static final int LINE = 10;

    enum Style { STRONG, TEXT, DIM }

    record Line(String text, Style style) {
        static Line strong(String t) {
            return new Line(t, Style.STRONG);
        }

        static Line text(String t) {
            return new Line(t, Style.TEXT);
        }

        static Line dim(String t) {
            return new Line(t, Style.DIM);
        }
    }

    /** A panel. {@code icon} is one character drawn in the round badge ("!", "✔"...). */
    record Section(String title, int tone, String icon, List<Line> lines) {}

    /** A button. Primary ones sit on the left, the rest are smaller on the right. */
    record Btn(String label, Runnable run, boolean primary) {}

    private final int tone;
    private final String headline;
    private final String code;
    private final String codeLabel;
    private final List<Section> sections;
    private final List<Btn> buttons;
    private final Runnable escape;
    private int cardX, cardY, cardW, cardH;

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
    }

    // ---- layout ----

    private int textWidth() {
        return cardW - 40;
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
        cardW = Math.min(440, width - 16);
        cardX = (width - cardW) / 2;
        int content = 0;
        for (Section s : sections) content += sectionHeight(s) + 5;
        cardH = Math.min(height - 12, 4 + 46 + content + 28 + 16);
        cardY = Math.max(6, (height - cardH) / 2);

        int y = cardY + cardH - 16 - 25;
        int x = cardX + 12;
        for (Btn b : buttons) {
            if (!b.primary()) continue;
            Component label = Component.literal(b.label()).withStyle(ChatFormatting.BOLD);
            int w = Math.max(70, font.width(label) + 20);
            addRenderableWidget(Button.builder(label, btn -> b.run().run()).bounds(x, y, w, 20).build());
            x += w + 6;
        }
        int right = cardX + cardW - 12;
        List<Btn> small = buttons.stream().filter(b -> !b.primary()).toList();
        for (int i = small.size() - 1; i >= 0; i--) {
            Btn b = small.get(i);
            int w = Math.max(50, font.width(b.label()) + 14);
            right -= w;
            addRenderableWidget(Button.builder(Component.literal(b.label()), btn -> b.run().run())
                    .bounds(right, y, w, 20).build());
            right -= 5;
        }
    }

    // ---- drawing ----

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
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
        g.fill(lx - 3, ly - 3, lx + 35, ly + 35, 0xFF55585F);
        g.fill(lx - 2, ly - 2, lx + 34, ly + 34, 0xFF2A2C30);
        g.fill(lx - 1, ly - 1, lx + 33, ly + 33, on ? tone : dim(tone));
        g.blit(LOGO, lx, ly, 32, 32, 0, 0, 64, 64, 64, 64);

        g.drawString(font, Component.literal("CHECK ENGINE").withStyle(ChatFormatting.BOLD), x + 54, y + 14, 0xFFE8E8EA);
        g.drawString(font, Component.literal(headline).withStyle(ChatFormatting.BOLD), x + 54, y + 27, tone);

        if (code != null) { // fault code readout
            int cw = font.width(code) + 12, cx = x + w - 12 - cw;
            g.fill(cx - 1, y + 11, cx + cw + 1, y + 27, 0xFF33353A);
            g.fill(cx, y + 12, cx + cw, y + 26, 0xFF0A0B0C);
            g.drawString(font, code, cx + 6, y + 15, tone, false);
            if (codeLabel != null) small(g, codeLabel, x + w - 12, y + 30, GREY, true);
        }

        int sy = y + 50;
        int limit = y + h - 16 - 30;
        for (Section s : sections) {
            int sh = sectionHeight(s);
            int bottom = Math.min(sy + sh, limit);
            if (bottom - sy < 20) break;
            g.fill(x + 10, sy + 1, x + w - 10, bottom + 1, 0x60000000);
            g.fillGradient(x + 10, sy, x + w - 10, bottom, 0xFF26272B, 0xFF212226);
            g.fill(x + 13, sy, x + w - 13, sy + 1, 0xFF34363B);
            badge(g, x + 16, sy + 6, s.tone(), s.icon());
            screw(g, x + w - 16, sy + 3);
            screw(g, x + w - 16, bottom - 5);
            g.drawString(font, Component.literal(s.title()).withStyle(ChatFormatting.BOLD), x + 30, sy + 7, s.tone());
            int ty = sy + 8 + LINE + 2;
            outer:
            for (Line l : s.lines()) {
                int color = l.style() == Style.DIM ? GREY : 0xFFE8E8EA;
                for (FormattedCharSequence part : wrap(l)) {
                    if (ty + LINE > bottom - 2) break outer;
                    g.drawString(font, part, x + 30, ty, color);
                    ty += LINE;
                }
            }
            sy = bottom + 5;
        }

        // Footer: the UAN mark and "Check Engine".
        int fy = y + h - 15;
        g.fill(x, fy - 1, x + w, fy, 0xFF2A2C30);
        g.fill(x, fy, x + w, y + h, 0xFF0F1012);
        g.blit(UAN, x + 10, fy + 3, 9, 9, 0, 0, 32, 32, 32, 32);
        small(g, "Check Engine", x + 23, fy + 5, 0xFF9A9CA3, false);
    }

    private void badge(GuiGraphics g, int x, int y, int color, String icon) {
        g.fill(x + 1, y, x + 9, y + 10, color);
        g.fill(x, y + 1, x + 10, y + 9, color);
        g.drawString(font, icon, x + 5 - font.width(icon) / 2, y + 1, 0xFF111111, false);
    }

    private static void screw(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 3, y + 3, 0xFF6A6D74);
        g.fill(x + 1, y + 1, x + 2, y + 2, 0xFF2A2C30);
    }

    /** Small (three-quarter size) text, left- or right-aligned at x. */
    private void small(GuiGraphics g, String text, int x, int y, int color, boolean right) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, text, right ? -font.width(text) : 0, 0, color, false);
        g.pose().popPose();
    }

    private static int dim(int color) {
        return 0xFF000000 | ((color >> 1) & 0x7F7F7F);
    }

    @Override
    public void onClose() {
        escape.run();
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
}
