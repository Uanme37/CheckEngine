package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.BootCheck;
import com.guiltypotato.checkengine.CheckEngine;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.JoinMismatch;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.Failure;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.Kind;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.PlayerMod;
import com.guiltypotato.checkengine.core.scan.Report;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.gui.ModMismatchDisconnectedScreen;

/** Replaces NeoForge's "mod mismatch" kick screen with which mods to add, remove or update. */
public final class JoinMismatchScreen extends Screen {
    private static final int LINE = 11;
    private static final String KEY = "neoforge.network.negotiation.failure.";

    private final ModMismatchDisconnectedScreen original;
    private final Screen back;
    private final List<Finding> findings;

    private JoinMismatchScreen(ModMismatchDisconnectedScreen original, Screen back, List<Finding> findings) {
        super(Component.translatable("checkengine.join.title"));
        this.original = original;
        this.back = back;
        this.findings = findings;
    }

    static Path reportFile() {
        return BootCheck.outputFolder().resolve("join-report.txt");
    }

    /** Our screen in place of NeoForge's, or null if we can't read what went wrong (then NeoForge's stays). */
    static Screen replace(ModMismatchDisconnectedScreen screen) {
        try {
            Map<Identifier, Component> channels = read(screen, "mismatchedChannelData");
            Screen back = read(screen, "parent");
            List<Failure> failures = new ArrayList<>();
            channels.forEach((id, reason) -> failures.add(failure(id.toString(), reason)));
            if (failures.isEmpty()) return null;

            Map<String, PlayerMod> mine = new HashMap<>();
            ModList.get().getMods().forEach(m -> mine.put(m.getModId(),
                    new PlayerMod(m.getDisplayName(), m.getVersion().toString())));
            List<Finding> findings = JoinMismatch.explain(failures, mine, "NeoForge");

            ServerData server = Minecraft.getInstance().getCurrentServer();
            Files.createDirectories(BootCheck.outputFolder());
            Files.writeString(reportFile(), JoinMismatch.toText(server == null ? null : server.ip, findings),
                    StandardCharsets.UTF_8);
            CheckEngine.LOGGER.warn("Check Engine: couldn't join, {}. Details: {}", Report.summary(findings), reportFile());
            return new JoinMismatchScreen(screen, back == null ? new TitleScreen() : back, findings);
        } catch (ReflectiveOperationException | RuntimeException | IOException e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't explain the mod mismatch, showing NeoForge's screen", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(Object o, String field) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    /** NeoForge's reason is a translation key; it may be wrapped in "Channel of mod X failed: ...". */
    private static Failure failure(String channel, Component reason) {
        Component inner = reason;
        if (reason.getContents() instanceof TranslatableContents t && t.getKey().equals(KEY + "mod")
                && t.getArgs().length > 1 && t.getArgs()[1] instanceof Component c) {
            inner = c;
        }
        String key = inner.getContents() instanceof TranslatableContents t ? t.getKey() : "";
        Kind kind;
        if (key.equals(KEY + "missing.server.client")) kind = Kind.MISSING_ON_PLAYER;
        else if (key.equals(KEY + "missing.client.server")) kind = Kind.MISSING_ON_SERVER;
        else if (key.startsWith(KEY + "flow.") || key.startsWith(KEY + "version.")) kind = Kind.DIFFERENT;
        else kind = Kind.OTHER;
        return new Failure(channel, kind, inner.getString());
    }

    @Override
    protected void init() {
        int w = 100;
        int y = height - 30;
        int x = width / 2 - (w * 4 + 15) / 2;
        addRenderableWidget(Button.builder(Component.translatable("checkengine.screen.open_report"),
                b -> Util.getPlatform().openFile(reportFile().toFile())).bounds(x, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("checkengine.join.mods_folder"),
                b -> Util.getPlatform().openFile(FMLPaths.MODSDIR.get().toFile())).bounds(x + w + 5, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("checkengine.join.details"),
                b -> minecraft.setScreen(original)).bounds(x + (w + 5) * 2, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(x + (w + 5) * 3, y, w, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
        g.centeredText(font, Component.translatable("checkengine.join.summary"), width / 2, 30, 0xFFAAAAAA);

        int left = Math.max(10, width / 2 - 200);
        int maxWidth = Math.min(400, width - 20);
        int y = 50;
        int bottom = height - 40 - LINE;
        int shown = 0;
        for (Finding f : findings) {
            ChatFormatting color = f.severity() == Finding.Severity.ERROR ? ChatFormatting.RED : ChatFormatting.YELLOW;
            List<FormattedCharSequence> lines = new ArrayList<>(font.split(Component.literal(f.title()).withStyle(color), maxWidth));
            lines.addAll(font.split(Component.literal("  " + f.fix()).withStyle(ChatFormatting.GRAY), maxWidth));
            if (y + lines.size() * LINE > bottom) break;
            for (FormattedCharSequence part : lines) {
                g.text(font, part, left, y, 0xFFFFFFFF);
                y += LINE;
            }
            y += 3;
            shown++;
        }
        if (shown < findings.size()) {
            g.text(font, Component.translatable("checkengine.screen.more", findings.size() - shown)
                    .withStyle(ChatFormatting.GRAY), left, y, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(back);
    }
}
