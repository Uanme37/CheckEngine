package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.BootCheck;
import com.guiltypotato.checkengine.CheckEngine;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.HelpBundle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/** Crash Doctor: shown once before the title screen when the previous game crashed. */
public final class CrashDoctorScreen extends DashScreen {
    CrashDoctorScreen(Screen next, LastCrash crash) {
        this(next, crash, new Screen[1]);
    }

    private CrashDoctorScreen(Screen next, LastCrash crash, Screen[] self) {
        super(AMBER, tr("checkengine.crash.title").toUpperCase(), "CRASH", crash.when(), sections(crash),
                buttons(next, crash, self), () -> leave(next));
        self[0] = this;
    }

    private static List<Section> sections(LastCrash crash) {
        Finding main = crash.main();
        List<Section> out = new ArrayList<>();
        List<Line> what = new ArrayList<>(List.of(Line.strong(main.title())));
        if (main.detail() != null) for (String l : main.detail().split("\n")) what.add(Line.dim(l));
        out.add(new Section("WHAT HAPPENED", AMBER, "!", what));
        List<Finding> others = crash.explained().findings().subList(1, crash.explained().findings().size());
        if (!others.isEmpty()) {
            out.add(new Section("ALSO IN THE CRASH REPORT", GREY, "+",
                    others.stream().map(f -> Line.text("- " + f.title())).toList()));
        }
        if (crash.changes() != null && !crash.changes().isEmpty()) {
            out.add(new Section("CHANGED SINCE IT LAST WORKED", AMBER, "~",
                    List.of(crash.changes().summary().split("\n")).stream().map(Line::text).toList()));
        }
        if (main.fix() != null) out.add(new Section("FIX", GREEN, "✔", List.of(Line.text(main.fix()))));
        return out;
    }

    private static List<Btn> buttons(Screen next, LastCrash crash, Screen[] self) {
        return List.of(
                new Btn(tr("checkengine.screen.continue"), () -> leave(next), true),
                new Btn(tr("checkengine.crash.strip_mine"),
                        () -> Minecraft.getInstance().setScreen(StripMineScreens.confirmStart(self[0], crash)), true),
                new Btn(tr("checkengine.crash.open_details"), () -> {
                    try {
                        Path details = crash.save(BootCheck.outputFolder());
                        Util.getPlatform().openFile(details.toFile());
                    } catch (IOException e) {
                        CheckEngine.LOGGER.warn("Check Engine: couldn't save the crash details", e);
                    }
                }, false),
                new Btn(tr("checkengine.crash.open_crash"), () -> Util.getPlatform().openFile(crash.file().toFile()), false),
                new Btn(tr("checkengine.crash.help"), () -> {
                    try {
                        HelpBundle.create(FMLPaths.GAMEDIR.get(), BootCheck.get());
                        Util.getPlatform().openFile(BootCheck.outputFolder().toFile());
                    } catch (IOException e) {
                        CheckEngine.LOGGER.warn("Check Engine: couldn't make the help file", e);
                    }
                }, false));
    }

    /** 26.1 can open the title screen more than once; the last of our screens to close marks the check done. */
    private static void leave(Screen next) {
        if (!(next instanceof BootWarningScreen)) ClientEvents.done = true;
        Minecraft.getInstance().setScreen(next);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }
}
