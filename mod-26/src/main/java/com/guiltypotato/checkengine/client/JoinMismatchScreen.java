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
public final class JoinMismatchScreen extends DashScreen {
    private static final String KEY = "neoforge.network.negotiation.failure.";

    private JoinMismatchScreen(ModMismatchDisconnectedScreen original, Screen back, List<Finding> findings,
                               List<Failure> failures) {
        super(RED, "CAN'T JOIN: MODS DON'T MATCH", null, null, sections(findings), buttons(original, back, failures),
                () -> Minecraft.getInstance().setScreen(back));
    }

    /** What to add, remove or update, each with its fix. */
    private static List<Section> sections(List<Finding> findings) {
        List<Line> lines = new ArrayList<>();
        for (Finding f : findings.subList(0, Math.min(10, findings.size()))) {
            lines.add(Line.strong(f.title()));
            if (f.fix() != null && !f.fix().isBlank()) lines.add(Line.dim(f.fix()));
        }
        if (findings.size() > 10) lines.add(Line.dim("...and " + (findings.size() - 10) + " more in the report"));
        return List.of(new Section("YOUR MODS VS THE SERVER'S", RED, "!", lines));
    }

    private static List<Btn> buttons(ModMismatchDisconnectedScreen original, Screen back, List<Failure> failures) {
        List<Btn> out = new ArrayList<>();
        if (failures.stream().anyMatch(f -> f.kind() != Kind.OTHER)) {
            out.add(new Btn("Fix it", () -> JoinFixScreens.start(failures, back), true,
                    "Switches off mods the server doesn't have, and opens Modrinth for the versions you need to"
                            + " download. Nothing is deleted, and Undo puts it all back."));
        }
        out.addAll(List.of(
                new Btn("Back", () -> Minecraft.getInstance().setScreen(back), out.isEmpty()),
                new Btn("Open mods folder", () -> Util.getPlatform().openFile(FMLPaths.MODSDIR.get().toFile()), false),
                new Btn("Open report", () -> Util.getPlatform().openFile(reportFile().toFile()), false,
                        "The full list, with the server's address, in a text file."),
                new Btn("Details", () -> Minecraft.getInstance().setScreen(original), false,
                        "The loader's own list of the channels that didn't match.")));
        return out;
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
            return new JoinMismatchScreen(screen, back == null ? new TitleScreen() : back, findings, failures);
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
}
