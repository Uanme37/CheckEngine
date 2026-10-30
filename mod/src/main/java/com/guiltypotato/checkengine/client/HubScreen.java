package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.BootCheck;
import com.guiltypotato.checkengine.core.crash.CrashDoctor;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.scan.LaunchHistory;
import com.guiltypotato.checkengine.core.scan.ModRows;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.scan.StartupTimes;
import com.guiltypotato.checkengine.core.scan.WarningLog;
import com.guiltypotato.checkengine.core.strip.GoBack;
import com.guiltypotato.checkengine.core.ui.SortableList;
import com.guiltypotato.checkengine.ui.DashScreen;
import com.guiltypotato.checkengine.ui.DashScreen.Btn;
import com.guiltypotato.checkengine.ui.DashScreen.Line;
import com.guiltypotato.checkengine.ui.DashScreen.Search;
import com.guiltypotato.checkengine.ui.DashScreen.Section;
import com.guiltypotato.checkengine.ui.DashScreen.Tab;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Pit Stop, opened from Check Engine's title screen button. Tabs: Pit Stop (start times, the last launches, the mod
 * list), Go back (0.21's Go back to when it worked, and Put it back), Warnings (still there or fixed) and Last crash
 * (opens Crash Doctor again).
 */
final class HubScreen {
    /** Rows shown before the list asks for a search. */
    private static final int MAX_ROWS = 120;

    private HubScreen() {}

    static Screen open(Screen back) {
        Path game = FMLPaths.GAMEDIR.get();
        Path ce = BootCheck.outputFolder();
        Screen[] self = new Screen[1];
        Runnable reopen = () -> Minecraft.getInstance().setScreen(open(back));
        List<Tab> tabs = new ArrayList<>();
        tabs.add(pitStop(ce));
        tabs.add(mods(self).withSubtitle("MODS"));
        tabs.add(goBack(game, ce, self, reopen).withSubtitle("GO BACK"));
        List<WarningLog.Entry> warnings = WarningLog.entries(ce);
        long open = warnings.stream().filter(WarningLog.Entry::stillThere).count();
        tabs.add(new Tab("Warnings", "!", open > 0 ? DashScreen.AMBER : DashScreen.GREEN,
                open > 0 ? Long.toString(open) : null, List.of(warningsSection(warnings)), List.of())
                .withSubtitle("WARNINGS"));
        tabs.add(lastCrash(game, ce, self).withSubtitle("LAST CRASH"));
        DashScreen screen = new DashScreen(DashScreen.GREEN, "PIT STOP", null, null,
                () -> Minecraft.getInstance().setScreen(back), tabs,
                List.of(new Btn("Done", () -> Minecraft.getInstance().setScreen(back), false)));
        self[0] = screen;
        return screen;
    }

    // ---- Pit Stop ----

    private static Tab pitStop(Path ce) {
        List<LaunchHistory.Launch> launches = LaunchHistory.load(ce);
        LaunchHistory.Slower slower = LaunchHistory.slower(launches);
        return new Tab("Pit Stop", "»", slower != null ? DashScreen.AMBER : DashScreen.GREEN, null,
                startSections(StartupTimes.load(ce), launches), List.of());
    }

    /** Every mod with its blocks, items and size: a tab of its own, so the search box sits right above the list. */
    private static Tab mods(Screen[] self) {
        Report report = BootCheck.ready(); // never waits: the list fills in once the check is done
        // No time per mod: building a mod (where most of its work happens) can't be timed per mod on NeoForge without
        // changing game code, and a number that points at the wrong mod is worse than none.
        boolean timed = false;
        List<ModRows> rows = report == null ? List.of() : ModRows.of(report.jars(), null,
                ModRows.byNamespace(BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString).toList()),
                ModRows.byNamespace(BuiltInRegistries.ITEM.keySet().stream().map(Object::toString).toList()),
                report.changes());
        SortableList<ModRows> list = new SortableList<>(rows, r -> r.name() + " " + r.id() + " " + r.tag());
        list.sortBy("blocks + items", Comparator.comparingInt(ModRows::blocksAndItems).reversed());
        list.sortBy("size", Comparator.comparingDouble(ModRows::mb).reversed());

        Search search = new Search("Search mods...", query -> {
            list.query(query);
            return List.of(modsSection(list, timed, report != null));
        });
        List<Btn> sorts = new ArrayList<>();
        for (String name : list.sortNames()) {
            sorts.add(new Btn("By " + name, () -> {
                list.sort(name);
                if (self[0] instanceof DashScreen d) d.refresh();
            }, true));
        }
        return new Tab("Mods", "#", DashScreen.BLUE, rows.isEmpty() ? null : Integer.toString(rows.size()),
                List.of(), sorts, search);
    }

    private static List<Section> startSections(StartupTimes.Data data, List<LaunchHistory.Launch> launches) {
        List<Line> start = new ArrayList<>();
        long usual = launches.isEmpty() ? -1 : LaunchHistory.usual(launches.subList(0, launches.size() - 1));
        if (data == null) {
            start.add(Line.text("No start measured yet: it shows after the next launch."));
        } else {
            start.add(Line.text("Last start: " + clock(data.totalMs()) + (usual > 0 ? " · usual: " + clock(usual) : "")));
            start.add(Line.text(StartupTimes.breakdown(data)));
            start.add(Line.dim("Rough split: some textures load while mods are still setting up."));
            if (usual <= 0) start.add(Line.dim("The usual time shows after a few more normal starts."));
        }
        LaunchHistory.Slower slower = LaunchHistory.slower(launches);
        if (slower != null) start.add(Line.mark(slower.text()));
        List<Section> out = new ArrayList<>();
        out.add(new Section("START TIME", slower != null ? DashScreen.AMBER : DashScreen.GREEN, "»", start));
        if (!launches.isEmpty()) {
            List<Line> rows = new ArrayList<>();
            for (int i = launches.size() - 1; i >= 0; i--) {
                LaunchHistory.Launch l = launches.get(i);
                String what = l.first() ? "first launch with Check Engine"
                        : l.changed().isEmpty() ? "no mod changes" : l.changed();
                String text = date(l.when()) + " · " + clock(l.ms()) + " · " + what;
                rows.add(i == launches.size() - 1 ? Line.text(text) : Line.dim(text));
            }
            out.add(new Section("LAST LAUNCHES", DashScreen.GREY, "≡", rows));
        }
        return out;
    }

    /** @param scanned whether the startup check is done (else the list fills in once it is) */
    private static Section modsSection(SortableList<ModRows> list, boolean timed, boolean scanned) {
        List<ModRows> view = list.view();
        List<Line> lines = new ArrayList<>();
        if (view.isEmpty()) lines.add(Line.text(list.size() > 0 ? "No mod matches."
                : scanned ? "No mod jars in the mods folder." : "The mod list isn't ready yet."));
        view.stream().limit(MAX_ROWS).forEach(r -> lines.add(Line.text(r.name() + " · " + r.details(timed))));
        if (view.size() > MAX_ROWS) lines.add(Line.dim("...and " + (view.size() - MAX_ROWS) + " more: search to find one."));
        return new Section("MODS (BY " + list.sort().toUpperCase(Locale.ROOT) + ")", DashScreen.BLUE, "#", lines);
    }

    // ---- Go back ----

    private static Tab goBack(Path game, Path ce, Screen[] self, Runnable reopen) {
        List<Section> sections = new ArrayList<>();
        List<Btn> buttons = new ArrayList<>();
        // Worked out in the background since the game started: reading every jar here would freeze the screen
        GoBack.Plan plan = com.guiltypotato.checkengine.core.strip.GoBackPlans.now();
        if (plan == null && com.guiltypotato.checkengine.core.strip.GoBackPlans.working()) {
            sections.add(new Section("STILL CHECKING", DashScreen.GREY, "i", DashScreen.lines(
                    "Still comparing the pack with the last launch that worked. Open Pit Stop again in a moment.")));
        } else if (plan != null && !plan.isEmpty()) {
            List<Line> lines = new ArrayList<>();
            if (plan.lastWorked() != null) lines.add(Line.strong(plan.lastWorked()));
            lines.add(Line.text(plan.summary() + "."));
            plan.lines().stream().limit(10).forEach(l -> lines.add(Line.dim("- " + l)));
            if (plan.lines().size() > 10) lines.add(Line.dim("...and " + (plan.lines().size() - 10) + " more"));
            sections.add(new Section("CHANGED SINCE IT LAST WORKED", DashScreen.AMBER, "±", lines));
            GoBack.Plan p = plan;
            buttons.add(new Btn("Go back to when it worked",
                    () -> Minecraft.getInstance().setScreen(CrashDoctorScreen.goBack(self[0], p)), true,
                    plan.summary() + ". Nothing is deleted, and you can put it back."));
        } else {
            sections.add(new Section("NOTHING TO GO BACK TO", DashScreen.GREEN, "✔", DashScreen.lines(
                    "Nothing changed since the pack last worked.")));
        }
        String undo = GoBack.done(ce) ? GoBack.undoSummary(ce) : null;
        if (undo != null) {
            sections.add(new Section("WENT BACK TO WHEN IT WORKED", DashScreen.GREY, "±", DashScreen.lines(
                    "Put it back to have your newer mods and configs again: " + undo + ".")));
            buttons.add(new Btn("Put it back", () -> Minecraft.getInstance().setScreen(
                    WarningsScreen.putBack(self[0], undo)), true, "Undoes Go back. Asks first."));
        }
        int tone = plan != null && !plan.isEmpty() ? DashScreen.AMBER : DashScreen.GREEN;
        return new Tab("Go back", "±", tone, null, sections, buttons);
    }

    // ---- Warnings ----

    private static Section warningsSection(List<WarningLog.Entry> entries) {
        if (entries.isEmpty()) {
            return new Section("WARNINGS", DashScreen.GREEN, "✔", DashScreen.lines("No warnings so far."));
        }
        List<Line> lines = new ArrayList<>();
        for (WarningLog.Entry e : entries) {
            if (e.stillThere()) {
                lines.add(Line.text("Still there · " + e.title() + " (first seen " + date(e.firstSeen()) + ")"));
            } else {
                lines.add(Line.dim("Fixed · " + e.title() + " (fixed " + date(e.fixedAt()) + ")"));
            }
        }
        lines.add(Line.dim("\"Got it\" only hides a warning. It shows as fixed once a launch no longer finds it."));
        boolean open = entries.stream().anyMatch(WarningLog.Entry::stillThere);
        return new Section("WARNINGS", open ? DashScreen.AMBER : DashScreen.GREEN, open ? "!" : "✔", lines);
    }

    // ---- Last crash ----

    private static Tab lastCrash(Path game, Path ce, Screen[] self) {
        LastCrash crash = CrashDoctor.lastShown(game, ce);
        if (crash == null) {
            return new Tab("Last crash", "?", DashScreen.GREY, null, List.of(new Section("LAST CRASH", DashScreen.GREEN,
                    "✔", DashScreen.lines("No crash since Check Engine was installed, or its report is gone."))),
                    List.of());
        }
        return new Tab("Last crash", "?", DashScreen.GREY, null, List.of(new Section("LAST CRASH", DashScreen.AMBER,
                "!", List.of(Line.strong(crash.main().title()), Line.dim(crash.when()),
                        Line.dim("It stays here until a newer crash replaces it.")))),
                List.of(new Btn("Open Crash Doctor",
                        () -> Minecraft.getInstance().setScreen(new CrashDoctorScreen(self[0], crash)), true)));
    }

    // ---- words ----

    /** "2026-10-28T18:02" -> "28 Oct 18:02" */
    static String date(String when) {
        if (when == null) return "?";
        try {
            return LocalDateTime.parse(when).format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH));
        } catch (RuntimeException e) {
            return when.replace('T', ' ');
        }
    }

    private static String clock(long ms) {
        return com.guiltypotato.checkengine.core.scan.TitleButtonState.of(ms, false, 0, 0, false, false).time();
    }
}
