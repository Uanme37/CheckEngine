package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.forge.BootCheck;
import com.guiltypotato.checkengine.forge.CheckEngine;
import com.guiltypotato.checkengine.forge.StartupTimer;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Report;
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
        // Kicked for a mod mismatch: say which mods, once (the Details button opens Forge's own screen).
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
        if (report == null) return;
        List<Finding> findings = BootCheck.worthShowing(report);
        if (findings.isEmpty() || BootWarningScreen.dismissed(findings)) return;
        event.setNewScreen(new BootWarningScreen(title, findings));
    }
}
