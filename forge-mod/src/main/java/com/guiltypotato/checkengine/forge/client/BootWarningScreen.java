package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.forge.BootCheck;
import com.guiltypotato.checkengine.forge.CheckEngine;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Report;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Shown once before the title screen when the boot check found problems. */
public final class BootWarningScreen extends Screen {
    private static final int LINE = 11;

    private final Screen next;
    private final List<Finding> findings;

    BootWarningScreen(Screen next, List<Finding> findings) {
        super(Component.translatable(findings.stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR)
                ? "checkengine.screen.title" : "checkengine.screen.title_warnings"));
        this.next = next;
        this.findings = findings;
    }

    /** Remembers which findings the player dismissed, so the screen only comes back when something changes. */
    static Path dismissedFile() {
        return BootCheck.outputFolder().resolve("dismissed.txt");
    }

    static String fingerprint(List<Finding> findings) {
        return Integer.toHexString(findings.stream().map(Finding::title).sorted().toList().hashCode());
    }

    static boolean dismissed(List<Finding> findings) {
        try {
            return Files.readString(dismissedFile(), StandardCharsets.UTF_8).strip().equals(fingerprint(findings));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    protected void init() {
        int w = 120;
        int y = height - 30;
        int x = width / 2 - (w * 3 + 10) / 2;
        addRenderableWidget(Button.builder(Component.translatable("checkengine.screen.open_report"),
                b -> Util.getPlatform().openFile(BootCheck.reportFile().toFile())).bounds(x, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("checkengine.screen.dismiss"), b -> {
            try {
                Files.writeString(dismissedFile(), fingerprint(findings), StandardCharsets.UTF_8);
            } catch (IOException e) {
                CheckEngine.LOGGER.warn("Check Engine: couldn't save dismissal", e);
            }
            onClose();
        }).bounds(x + w + 5, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("checkengine.screen.continue"), b -> onClose())
                .bounds(x + (w + 5) * 2, y, w, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g); // 1.20.1 screens draw their own background
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        g.drawCenteredString(font, Component.translatable("checkengine.screen.summary", Report.summary(findings)),
                width / 2, 30, 0xAAAAAA);

        int left = Math.max(10, width / 2 - 200);
        int maxWidth = Math.min(400, width - 20);
        int y = 50;
        int bottom = height - 40 - LINE;
        int shown = 0;
        for (Finding f : findings) {
            ChatFormatting color = f.severity() == Finding.Severity.ERROR ? ChatFormatting.RED : ChatFormatting.YELLOW;
            Component line = Component.literal(f.severity() == Finding.Severity.ERROR ? "Problem: " : "Warning: ")
                    .withStyle(color).append(Component.literal(f.title()).withStyle(ChatFormatting.WHITE));
            List<FormattedCharSequence> wrapped = font.split(line, maxWidth);
            if (y + wrapped.size() * LINE > bottom) break;
            for (FormattedCharSequence part : wrapped) {
                g.drawString(font, part, left, y, 0xFFFFFF);
                y += LINE;
            }
            shown++;
        }
        if (shown < findings.size()) {
            g.drawString(font, Component.translatable("checkengine.screen.more", findings.size() - shown)
                    .withStyle(ChatFormatting.GRAY), left, y, 0xFFFFFF);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(next);
    }
}
