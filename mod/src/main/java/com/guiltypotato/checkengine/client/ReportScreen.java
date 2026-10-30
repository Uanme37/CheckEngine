package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.ui.DashScreen;
import com.guiltypotato.checkengine.BootCheck;
import com.guiltypotato.checkengine.ui.DashScreen.Btn;
import com.guiltypotato.checkengine.ui.DashScreen.Line;
import com.guiltypotato.checkengine.ui.DashScreen.Section;
import com.guiltypotato.checkengine.core.scan.ReportSections;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** The /checkengine scan report in game: one panel per finding, opened from "Open report" in chat. */
final class ReportScreen {
    private ReportScreen() {}

    static Screen open() {
        Path file = BootCheck.outputFolder().resolve("scan-report.txt");
        Runnable close = () -> Minecraft.getInstance().setScreen(null);
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new DashScreen(DashScreen.BLUE, "NO REPORT HERE YET", null, null, List.of(new Section("WHERE IS IT",
                    DashScreen.BLUE, "i", List.of(Line.text("Run /checkengine scan first."),
                    Line.dim("On a server the report is saved on the server, in checkengine/scan-report.txt.")))),
                    List.of(new Btn("Continue", close, true)), close);
        }
        ReportSections.Parsed report = ReportSections.parse(text);
        List<Section> sections = new ArrayList<>();
        boolean problems = false;
        boolean warnings = false;
        for (ReportSections.Block b : report.blocks()) {
            int tone = switch (b.severity()) {
                case "ERROR" -> DashScreen.RED;
                case "WARNING" -> DashScreen.AMBER;
                default -> DashScreen.BLUE;
            };
            problems |= b.severity().equals("ERROR");
            warnings |= b.severity().equals("WARNING");
            List<Line> lines = new ArrayList<>();
            for (ReportSections.Entry e : b.lines()) {
                lines.add(switch (e.kind()) {
                    case ITEM -> Line.text("- " + e.text());
                    case MORE -> Line.dim("   " + e.text());
                    case HEADING -> Line.strong(e.text());
                    case FIX -> Line.strong("Fix: " + e.text());
                    case TEXT -> Line.dim(e.text());
                });
            }
            sections.add(new Section(b.title().toUpperCase(Locale.ROOT), tone, tone == DashScreen.BLUE ? "i" : "!", lines));
        }
        if (sections.isEmpty()) {
            sections.add(new Section("ALL GOOD", DashScreen.GREEN, "✓", List.of(Line.text("No problems found."))));
        }
        int tone = problems ? DashScreen.RED : warnings ? DashScreen.AMBER : DashScreen.GREEN;
        String summary = report.summary().isEmpty() ? "SCAN REPORT"
                : report.summary().replaceAll("\\.$", "").toUpperCase(Locale.ROOT);
        return new DashScreen(tone, summary, null, null, sections, List.of(
                new Btn("Continue", close, true),
                new Btn("Open as text", () -> Util.getPlatform().openFile(file.toFile()), false,
                        "The same report in a text file, to copy from or send.")), close);
    }
}
