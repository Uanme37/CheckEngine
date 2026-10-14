package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.forge.BootCheck;
import com.guiltypotato.checkengine.forge.CheckEngine;
import com.guiltypotato.checkengine.forge.StartupTimer;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.strip.StripMine;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.ModMismatchDisconnectedScreen;

@Mod.EventBusSubscriber(modid = CheckEngine.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {
    private static boolean checked;

    private ClientEvents() {}

    /** The first time the title screen opens, show the boot check's problems in front of it. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        // During a Strip Mine round, the loader's warnings screen only lists what the test switched off.
        if (StripMineScreens.skipWarnings(event.getNewScreen())) {
            event.setCanceled(true);
            return;
        }
        // Kicked for a mod mismatch: say which mods, once (the Details button opens Forge's own screen).
        if (event.getNewScreen() instanceof ModMismatchDisconnectedScreen mismatch
                && !(event.getCurrentScreen() instanceof JoinMismatchScreen)) {
            Screen ours = JoinMismatchScreen.replace(mismatch);
            if (ours != null) event.setNewScreen(ours);
            return;
        }
        if (!(event.getNewScreen() instanceof TitleScreen title)) return;
        boolean first = !checked;
        checked = true;
        if (first) {
            BootCheck.launchSucceeded();
            StartupTimer.finish(false);
        }
        // Strip Mine: each round's result, or the answer, goes in front of everything else.
        Screen strip = StripMineScreens.onTitle(title, event.getCurrentScreen());
        if (strip != null) {
            event.setNewScreen(strip);
            return;
        }
        if (!first || StripMine.running(BootCheck.outputFolder()) != null) return;
        Report report = BootCheck.get();
        Screen next = title;
        if (report != null) {
            // Quiet mode: the pack maker can hide warnings players don't need
            List<Finding> findings = com.guiltypotato.checkengine.core.scan.PackInfo.load(
                    BootCheck.outputFolder().getParent()).visible(BootCheck.worthShowing(report));
            if (!findings.isEmpty() && !BootWarningScreen.dismissed(findings)) next = new BootWarningScreen(title, findings);
        }
        // Crash Doctor first: if the last game crashed, that's what the player wants to know about.
        LastCrash crash = BootCheck.lastCrash();
        // After a pack update, once: what's new, updated and gone (Crash Doctor comes first if the last game crashed).
        if (crash == null && report != null) {
            Screen updated = PackUpdatedScreen.of(next, report.changes());
            if (updated != null) next = updated;
        }
        // A world still uses a mod that's gone: say what it loses before it's opened (in front of the update notes).
        if (crash == null && report != null) {
            Screen worlds = SafeRemoveScreen.of(next, BootCheck.worldsUsingRemovedMods(), report.changes());
            if (worlds != null) next = worlds;
        }
        if (crash != null) next = new CrashDoctorScreen(next, report == null ? crash : crash.withChanges(report.changes()));
        if (next != title) event.setNewScreen(next);
    }
}
