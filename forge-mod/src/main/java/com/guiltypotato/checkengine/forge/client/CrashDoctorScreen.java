package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.forge.BootCheck;
import com.guiltypotato.checkengine.forge.CheckEngine;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.HelpBundle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.fml.loading.FMLPaths;

/** Crash Doctor: shown once before the title screen when the previous game crashed. */
public final class CrashDoctorScreen extends Screen {
    private static final int LINE = 11;

    private final Screen next;
    private final LastCrash crash;

    CrashDoctorScreen(Screen next, LastCrash crash) {
        super(Component.translatable("checkengine.crash.title"));
        this.next = next;
        this.crash = crash;
    }

    @Override
    protected void init() {
        int w = 95;
        int y = height - 30;
        int x = width / 2 - (w * 4 + 15) / 2;
        addRenderableWidget(Button.builder(Component.translatable("checkengine.crash.open_details"), b -> {
            try {
                Path details = crash.save(BootCheck.outputFolder());
                Util.getPlatform().openFile(details.toFile());
            } catch (IOException e) {
                CheckEngine.LOGGER.warn("Check Engine: couldn't save the crash details", e);
            }
        }).bounds(x, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("checkengine.crash.open_crash"),
                b -> Util.getPlatform().openFile(crash.file().toFile())).bounds(x + w + 5, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("checkengine.crash.help"), b -> {
            try {
                HelpBundle.create(FMLPaths.GAMEDIR.get(), BootCheck.get());
                Util.getPlatform().openFile(BootCheck.outputFolder().toFile());
            } catch (IOException e) {
                CheckEngine.LOGGER.warn("Check Engine: couldn't make the help file", e);
            }
        }).bounds(x + (w + 5) * 2, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("checkengine.screen.continue"), b -> onClose())
                .bounds(x + (w + 5) * 3, y, w, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g); // 1.20.1 screens draw their own background
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title.copy().withStyle(ChatFormatting.RED), width / 2, 15, 0xFFFFFF);
        g.drawCenteredString(font, Component.translatable("checkengine.crash.when", crash.when()), width / 2, 30,
                0xAAAAAA);

        int left = Math.max(10, width / 2 - 200);
        int maxWidth = Math.min(400, width - 20);
        int bottom = height - 40;
        int[] y = {50};

        Finding main = crash.main();
        draw(g, Component.literal(main.title()).withStyle(ChatFormatting.YELLOW), left, maxWidth, y, bottom);
        draw(g, Component.literal(main.detail()).withStyle(ChatFormatting.GRAY), left, maxWidth, y, bottom);
        if (main.fix() != null) {
            draw(g, Component.translatable("checkengine.crash.fix").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(main.fix()).withStyle(ChatFormatting.WHITE)), left, maxWidth, y, bottom);
        }
        List<Finding> others = crash.explained().findings().subList(1, crash.explained().findings().size());
        if (!others.isEmpty()) {
            y[0] += 4;
            draw(g, Component.translatable("checkengine.crash.also").withStyle(ChatFormatting.WHITE), left, maxWidth,
                    y, bottom);
            for (Finding f : others) {
                draw(g, Component.literal("- " + f.title()).withStyle(ChatFormatting.GRAY), left, maxWidth, y, bottom);
            }
        }
        if (crash.changes() != null && !crash.changes().isEmpty()) {
            y[0] += 4;
            draw(g, Component.translatable("checkengine.crash.changed").withStyle(ChatFormatting.GOLD), left, maxWidth,
                    y, bottom);
            for (String line : crash.changes().summary().split("\n")) {
                draw(g, Component.literal(line).withStyle(ChatFormatting.GRAY), left, maxWidth, y, bottom);
            }
        }
    }

    /** Wraps and draws one block of text, stopping above the buttons. */
    private void draw(GuiGraphics g, Component text, int left, int maxWidth, int[] y, int bottom) {
        // Keep the text's own line breaks (lists in the explanation), then wrap each line.
        List<Component> lines = !text.getString().contains("\n") ? List.of(text)
                : java.util.Arrays.stream(text.getString().split("\n"))
                        .map(p -> (Component) Component.literal(p).withStyle(text.getStyle())).toList();
        for (Component line : lines) {
            for (FormattedCharSequence part : font.split(line, maxWidth)) {
                if (y[0] + LINE > bottom) return;
                g.drawString(font, part, left, y[0], 0xFFFFFF);
                y[0] += LINE;
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(next);
    }
}
