package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.BootCheck;
import com.guiltypotato.checkengine.CheckEngine;
import com.guiltypotato.checkengine.StartupTimer;
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
    /** True once the player closed the warning screen (or there was nothing to show). */
    static boolean done;
    private static boolean memoryRecorded;

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
        if (done || !(event.getNewScreen() instanceof TitleScreen title)) return;
        CheckEngine.LOGGER.debug("Check Engine: title screen opening over {}", event.getCurrentScreen());
        if (!memoryRecorded) {
            memoryRecorded = true;
            BootCheck.launchSucceeded();
            StartupTimer.finish(false);
        }
        Report report = BootCheck.get();
        List<Finding> findings = report == null ? List.of() : BootCheck.worthShowing(report);
        if (findings.isEmpty() || BootWarningScreen.dismissed(findings)) {
            done = true;
            return;
        }
        // 26.1 can open the title screen more than once while starting up, so keep showing ours until it's closed.
        if (!(event.getCurrentScreen() instanceof BootWarningScreen)) event.setNewScreen(new BootWarningScreen(title, findings));
    }
}
