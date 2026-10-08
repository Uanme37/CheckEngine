package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.BootCheck;
import com.guiltypotato.checkengine.CheckEngine;
import com.guiltypotato.checkengine.StartupTimer;
import com.guiltypotato.checkengine.core.crash.CrashDoctor.LastCrash;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Report;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.ModMismatchDisconnectedScreen;

@EventBusSubscriber(modid = CheckEngine.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    private static boolean checked;

    private ClientEvents() {}

    /** The first time the title screen opens, show the boot check's problems in front of it. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        // Kicked for a mod mismatch: say which mods, once (the Details button opens NeoForge's own screen).
        if (event.getNewScreen() instanceof ModMismatchDisconnectedScreen mismatch
                && !(event.getCurrentScreen() instanceof JoinMismatchScreen)) {
            Screen ours = JoinMismatchScreen.replace(mismatch);
            if (ours != null) event.setNewScreen(ours);
            return;
        }
        if (checked || !(event.getNewScreen() instanceof TitleScreen title)) return;
        checked = true;
        BootCheck.launchSucceeded();
        StartupTimer.finish(false);
        Report report = BootCheck.get();
        Screen next = title;
        if (report != null) {
            List<Finding> findings = BootCheck.worthShowing(report);
            if (!findings.isEmpty() && !BootWarningScreen.dismissed(findings)) next = new BootWarningScreen(title, findings);
        }
        // Crash Doctor first: if the last game crashed, that's what the player wants to know about.
        LastCrash crash = BootCheck.lastCrash();
        if (crash != null) next = new CrashDoctorScreen(next, report == null ? crash : crash.withChanges(report.changes()));
        if (next != title) event.setNewScreen(next);
    }
}
