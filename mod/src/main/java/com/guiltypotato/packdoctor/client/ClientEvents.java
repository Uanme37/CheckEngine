package com.guiltypotato.packdoctor.client;

import com.guiltypotato.packdoctor.BootCheck;
import com.guiltypotato.packdoctor.PackDoctor;
import com.guiltypotato.packdoctor.core.scan.Finding;
import com.guiltypotato.packdoctor.core.scan.Report;
import java.util.List;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = PackDoctor.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    private static boolean checked;

    private ClientEvents() {}

    /** The first time the title screen opens, show the boot check's problems in front of it. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (checked || !(event.getNewScreen() instanceof TitleScreen title)) return;
        checked = true;
        Report report = BootCheck.get();
        if (report == null) return;
        List<Finding> findings = BootCheck.worthShowing(report);
        if (findings.isEmpty() || BootWarningScreen.dismissed(findings)) return;
        event.setNewScreen(new BootWarningScreen(title, findings));
    }
}
