package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.forge.BootCheck;
import com.guiltypotato.checkengine.forge.CheckEngine;
import com.guiltypotato.checkengine.core.scan.Finding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Shown once before the title screen when the boot check found problems or warnings: the heads-up. */
public final class BootWarningScreen extends DashScreen {
    /** How many findings fit before "...and N more" (the full list is in the report). */
    private static final int SHOWN = 8;

    BootWarningScreen(Screen next, List<Finding> findings) {
        super(errors(findings) ? RED : AMBER, errors(findings) ? "PROBLEMS FOUND" : "HEADS-UP", String.valueOf(findings.size()),
                (errors(findings) ? "PROBLEM" : "WARNING") + (findings.size() == 1 ? "" : "S"), sections(findings),
                buttons(next, findings),
                () -> leave(next));
    }

    private static boolean errors(List<Finding> findings) {
        return findings.stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR);
    }

    private static List<Section> sections(List<Finding> findings) {
        List<Section> out = new ArrayList<>();
        List<Finding> problems = findings.stream().filter(f -> f.severity() == Finding.Severity.ERROR).toList();
        List<Finding> warnings = findings.stream().filter(f -> f.severity() != Finding.Severity.ERROR).toList();
        int room = SHOWN;
        room = add(out, "PROBLEMS", RED, "!", problems, room);
        add(out, "WARNINGS", AMBER, "?", warnings, room);
        if (findings.size() > SHOWN) {
            out.add(new Section("AND " + (findings.size() - SHOWN) + " MORE", GREY, "+",
                    List.of(Line.dim("Open report for the full list."))));
        }
        return out;
    }

    /** One section: each finding's title, with its fix under it. Returns how many more fit. */
    private static int add(List<Section> out, String title, int tone, String icon, List<Finding> list, int room) {
        if (list.isEmpty() || room <= 0) return room;
        List<Line> lines = new ArrayList<>();
        for (Finding f : list.subList(0, Math.min(room, list.size()))) {
            lines.add(Line.strong(f.title()));
            if (f.fix() != null && !f.fix().isBlank()) lines.add(Line.dim(f.fix()));
        }
        out.add(new Section(title, tone, icon, lines));
        return room - Math.min(room, list.size());
    }

    private static List<Btn> buttons(Screen next, List<Finding> findings) {
        return List.of(
                new Btn("Continue", () -> leave(next), true),
                new Btn("Don't show again", () -> {
                    try {
                        Files.writeString(dismissedFile(), fingerprint(findings), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        CheckEngine.LOGGER.warn("Check Engine: couldn't save dismissal", e);
                    }
                    leave(next);
                }, false, "Hides these until something in the pack changes."),
                new Btn("Open report", () -> Util.getPlatform().openFile(BootCheck.reportFile().toFile()), false,
                        "Opens the full check of the pack in a text file."));
    }

    private static void leave(Screen next) {
        Minecraft.getInstance().setScreen(next);
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
}
