package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.JoinMismatch;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.Failure;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.Kind;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.PlayerMod;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.forge.BootCheck;
import com.guiltypotato.checkengine.forge.CheckEngine;
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
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.client.gui.ModMismatchDisconnectedScreen;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.ConnectionData.ModMismatchData;
import net.minecraftforge.network.NetworkRegistry;
import org.apache.commons.lang3.tuple.Pair;

/** Replaces Forge's "mod mismatch" kick screen with which mods to add, remove or update. */
public final class JoinMismatchScreen extends Screen {
    private static final int LINE = 11;

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

    /** Our screen in place of Forge's, or null if we can't read what went wrong (then Forge's stays). */
    static Screen replace(ModMismatchDisconnectedScreen screen) {
        try {
            ModMismatchData data = read(screen, "modMismatchData");
            Screen back = read(screen, "parent");
            if (data == null || !data.containsMismatches()) return null;

            // Forge lists each channel with a version, or ABSENT when one side doesn't have it. "From server" means
            // the server is the side that's missing things; otherwise the player is.
            String absent = NetworkRegistry.ABSENT.version();
            boolean fromServer = data.mismatchedDataFromServer();
            List<Failure> failures = new ArrayList<>();
            data.mismatchedModData().forEach((ResourceLocation id, String version) -> {
                Pair<String, String> present = data.presentModData().get(id);
                String name = present == null ? null : present.getLeft();
                String presentVersion = present == null ? null : present.getRight();
                if (absent.equals(version)) {
                    failures.add(new Failure(id.toString(), fromServer ? Kind.MISSING_ON_SERVER : Kind.MISSING_ON_PLAYER,
                            null, name, fromServer ? null : presentVersion));
                } else {
                    failures.add(new Failure(id.toString(), Kind.DIFFERENT, null, name,
                            fromServer ? version : presentVersion));
                }
            });

            Map<String, PlayerMod> mine = new HashMap<>();
            ModList.get().getMods().forEach(m -> mine.put(m.getModId(),
                    new PlayerMod(m.getDisplayName(), m.getVersion().toString())));
            List<Finding> findings = JoinMismatch.explain(failures, mine, "Forge");

            ServerData server = Minecraft.getInstance().getCurrentServer();
            Files.createDirectories(BootCheck.outputFolder());
            Files.writeString(reportFile(), JoinMismatch.toText(server == null ? null : server.ip, findings),
                    StandardCharsets.UTF_8);
            CheckEngine.LOGGER.warn("Check Engine: couldn't join, {}. Details: {}", Report.summary(findings), reportFile());
            return new JoinMismatchScreen(screen, back == null ? new TitleScreen() : back, findings);
        } catch (ReflectiveOperationException | RuntimeException | IOException e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't explain the mod mismatch, showing Forge's screen", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(Object o, String field) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return (T) f.get(o);
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        g.drawCenteredString(font, Component.translatable("checkengine.join.summary"), width / 2, 30, 0xAAAAAA);

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
                g.drawString(font, part, left, y, 0xFFFFFF);
                y += LINE;
            }
            y += 3;
            shown++;
        }
        if (shown < findings.size()) {
            g.drawString(font, Component.translatable("checkengine.screen.more", findings.size() - shown)
                    .withStyle(ChatFormatting.GRAY), left, y, 0xFFFFFF);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(back);
    }
}
