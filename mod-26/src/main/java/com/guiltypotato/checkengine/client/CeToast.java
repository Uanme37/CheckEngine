package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.ui.DashScreen;
import com.guiltypotato.checkengine.ui.UanColors;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** The one toast: the engine light, then ✓ Fixed lines in green and ↑ slower lines in amber. */
public final class CeToast implements Toast {
    private static final int WIDTH = 220, SHOWN_MS = 7000;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private final List<Integer> colors = new ArrayList<>();
    private final int light;

    /** @param message one line per notice, as StartNotice writes it */
    public CeToast(String message) {
        Font font = Minecraft.getInstance().font;
        boolean slower = false;
        for (String line : message.split("\n")) {
            boolean fixed = line.startsWith("Fixed") || line.startsWith("Looks fixed") || line.startsWith("Might be fixed");
            boolean slow = line.startsWith("Started");
            slower |= slow;
            String text = (fixed ? "✓ " : slow ? "↑ " : "") + line;
            int color = fixed ? UanColors.GREEN : slow ? UanColors.AMBER : 0xFFF0F0F0;
            for (FormattedCharSequence part : font.split(Component.literal(text), WIDTH - 14)) {
                lines.add(part);
                colors.add(color);
            }
        }
        light = slower ? UanColors.AMBER : UanColors.GREEN;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return 18 + lines.size() * 10;
    }

    private void draw(GuiGraphicsExtractor g, Font font) {
        int w = width(), h = height();
        g.fill(0, 0, w, h, 0xFF505050);
        g.fill(1, 1, w - 1, h - 1, 0xF0101010);
        DashScreen.engine(g, 7, 4, light);
        g.text(font, "Check Engine", 28, 5, 0xFFF0F0F0);
        for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), 7, 16 + i * 10, colors.get(i));
    }

    private Visibility visibility = Visibility.SHOW;

    @Override
    public Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager toasts, long ms) {
        visibility = ms < SHOWN_MS ? Visibility.SHOW : Visibility.HIDE;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, Font font, long ms) {
        draw(g, font);
    }
}
