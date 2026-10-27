package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.forge.BootCheck;
import com.guiltypotato.checkengine.forge.CheckEngine;
import com.guiltypotato.checkengine.forge.client.DashScreen.Btn;
import com.guiltypotato.checkengine.forge.client.DashScreen.Section;
import com.guiltypotato.checkengine.core.scan.Quiet;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Mods > Check Engine > Config: the player's warnings switch ("Only tell me when it breaks"), on or off. */
final class WarningsScreen {
    private WarningsScreen() {}

    static Screen of(Screen parent) {
        boolean quiet = Quiet.onlyWhenItBreaks(BootCheck.outputFolder());
        Runnable back = () -> Minecraft.getInstance().setScreen(parent);
        return new DashScreen(quiet ? DashScreen.GREY : DashScreen.GREEN, "WARNINGS " + (quiet ? "OFF" : "ON"), null, null,
                List.of(new Section(quiet ? "ONLY WHEN IT BREAKS" : "ALL WARNINGS", quiet ? DashScreen.GREY : DashScreen.GREEN,
                                quiet ? "-" : "✔", DashScreen.lines(quiet
                                        ? "Check Engine only shows up when the game crashed or a problem stops it."
                                        : "Check Engine shows warnings before the title screen, and what a pack update changed.",
                                quiet ? "Warnings and pack update notes stay hidden."
                                        : "Crashes and problems that stop the game always show.")),
                        new Section("GOT IT", DashScreen.GREY, "?", DashScreen.lines(
                                "A warning you said \"Got it\" to stays hidden until it's fixed and comes back."))),
                List.of(new Btn(quiet ? "Turn warnings on" : "Only tell me when it breaks", () -> {
                            try {
                                Quiet.setOnlyWhenItBreaks(BootCheck.outputFolder(), !quiet);
                            } catch (IOException e) {
                                CheckEngine.LOGGER.warn("Check Engine: couldn't save the warnings switch", e);
                            }
                            Minecraft.getInstance().setScreen(of(parent));
                        }, true),
                        new Btn("Done", back, false)),
                back);
    }
}
