package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.ui.DashScreen;
import com.guiltypotato.checkengine.BootCheck;
import com.guiltypotato.checkengine.ui.DashScreen.Btn;
import com.guiltypotato.checkengine.ui.DashScreen.Line;
import com.guiltypotato.checkengine.ui.DashScreen.Section;
import com.guiltypotato.checkengine.core.crash.JoinFailure;
import com.guiltypotato.checkengine.core.scan.Finding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Kicked with "Invalid player data": in singleplayer the game's own log has the error, so say which mod did it and
 * what to do. On someone else's server the error is in the server's log, so say who can find it.
 */
final class JoinFailScreen {
    /** The time on a log line: "[21Oct2026 12:00:09.500]" or "[12:00:09]". */
    private static final Pattern TIME = Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2})");

    private JoinFailScreen() {}

    /** @param recentOnly only use a kick from the last two minutes (the player was just kicked) */
    static Screen of(Screen back, boolean recentOnly) {
        Runnable close = () -> Minecraft.getInstance().setScreen(back);
        Path logs = FMLPaths.GAMEDIR.get().resolve("logs");
        String log;
        try {
            log = new String(Files.readAllBytes(logs.resolve("latest.log")), StandardCharsets.UTF_8); // lenient: logs can hold odd bytes
        } catch (Exception e) {
            log = null;
        }
        JoinFailure.Result r = log == null ? null : JoinFailure.fromLog(log);
        if (r != null && recentOnly && !recent(log)) r = null;
        if (r == null) {
            return new DashScreen(DashScreen.AMBER, "THE SERVER COULDN'T LOAD YOU", null, null, List.of(new Section(
                    "INVALID PLAYER DATA", DashScreen.AMBER, "!", List.of(
                    Line.text("The server couldn't put you into the world. The reason is in the server's own log,"
                            + " not on your PC."),
                    Line.strong("Ask the server owner to run /checkengine join (needs Check Engine on the server),"
                            + " or to look for \"Couldn't place player in world\" in the server log."),
                    Line.dim("Often a mod that saves player data (permissions, homes, backpacks) can't read yours.")))),
                    List.of(new Btn("Continue", close, true)), close);
        }
        JoinFailure.markShown(BootCheck.outputFolder(), r);
        return explained(r, "YOUR JOIN FAILED", close, "Continue");
    }

    /** On the title screen: the last session had a kick nobody explained (the popup missed it). Null when none. */
    static Screen lastSession(Screen next) {
        JoinFailure.Result r = JoinFailure.lastSession(FMLPaths.GAMEDIR.get(),
                BootCheck.outputFolder());
        if (r == null) return null;
        return explained(r, "LAST TIME, A JOIN FAILED", () -> {
            JoinFailure.markShown(BootCheck.outputFolder(), r);
            Minecraft.getInstance().setScreen(next);
        }, "Continue");
    }

    private static Screen explained(JoinFailure.Result r, String headline, Runnable close, String closeLabel) {
        Path logs = FMLPaths.GAMEDIR.get().resolve("logs");
        Finding f = r.finding();
        List<Line> lines = new ArrayList<>();
        for (String l : f.detail().split("\n")) lines.add(l.startsWith("The error:") ? Line.clip(oneLine(l)) : Line.text(l));
        List<Section> sections = new ArrayList<>();
        // The fix first: it's what the player came for. The full error is in the log (Open logs folder).
        sections.add(new Section("FIX", DashScreen.GREEN, "✓", List.of(Line.strong(f.fix()))));
        sections.add(new Section(f.title().toUpperCase(java.util.Locale.ROOT), DashScreen.RED, "!", lines));
        return new DashScreen(DashScreen.RED, headline, "JOIN", "INVALID PLAYER DATA", sections, List.of(
                new Btn(closeLabel, close, true),
                new Btn("Open logs folder", () -> Util.getPlatform().openFile(logs.toFile()), false,
                        "latest.log has the full error")), close);
    }

    /** "The error: NullPointerException: Cannot invoke ...", without the exception's package (the screen cuts it). */
    private static String oneLine(String error) {
        return error.replaceFirst("^The error: (?:[a-z0-9_]+\\.)+", "The error: ");
    }

    /** True when the last "Couldn't place player in world" line is from the last two minutes. */
    private static boolean recent(String log) {
        int at = log.lastIndexOf("Couldn't place player in world");
        int start = log.lastIndexOf('\n', at) + 1;
        Matcher m = TIME.matcher(log.substring(start, at));
        if (!m.find()) return true; // no time on the line: trust it
        LocalTime t = LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        long seconds = Duration.between(t, LocalTime.now()).getSeconds();
        if (seconds < 0) seconds += 24 * 3600; // past midnight
        return seconds <= 120;
    }
}
