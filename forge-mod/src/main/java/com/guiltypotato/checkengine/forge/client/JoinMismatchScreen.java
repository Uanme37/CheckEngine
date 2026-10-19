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
public final class JoinMismatchScreen extends DashScreen {


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
            return new JoinMismatchScreen(screen, back == null ? new TitleScreen() : back, findings, failures);
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
}
