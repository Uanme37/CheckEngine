package com.guiltypotato.packdoctor.forge.client;

import com.guiltypotato.packdoctor.forge.BootCheck;
import com.guiltypotato.packdoctor.forge.PackDoctor;
import com.guiltypotato.packdoctor.core.scan.Finding;
import com.guiltypotato.packdoctor.core.scan.Report;
import java.util.List;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.ScreenEvent;

@Mod.EventBusSubscriber(modid = PackDoctor.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {
    private static boolean checked;

    private ClientEvents() {}

    /** The first time the title screen opens, show the boot check's problems in front of it. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (checked || !(event.getNewScreen() instanceof TitleScreen title)) return;
        checked = true;
        BootCheck.recordMemory();
        Report report = BootCheck.get();
        if (report == null) return;
        List<Finding> findings = BootCheck.worthShowing(report);
        if (findings.isEmpty() || BootWarningScreen.dismissed(findings)) return;
        event.setNewScreen(new BootWarningScreen(title, findings));
    }
}
