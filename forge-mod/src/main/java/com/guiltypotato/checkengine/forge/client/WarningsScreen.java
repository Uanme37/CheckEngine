package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.ui.DashScreen;
import com.guiltypotato.checkengine.forge.BootCheck;
import com.guiltypotato.checkengine.forge.CheckEngine;
import com.guiltypotato.checkengine.ui.DashScreen.Btn;
import com.guiltypotato.checkengine.ui.DashScreen.Section;
import com.guiltypotato.checkengine.core.scan.Quiet;
import com.guiltypotato.checkengine.core.strip.GoBack;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Mods > Check Engine > Config: the player's warnings switch ("Only tell me when it breaks"), on or off. After a Go
 * back, also "Put it back" (undoes it).
 */
final class WarningsScreen {
    private WarningsScreen() {}

    static Screen of(Screen parent) {
        boolean quiet = Quiet.onlyWhenItBreaks(BootCheck.outputFolder());
        Runnable back = () -> Minecraft.getInstance().setScreen(parent);
        String undo = GoBack.done(BootCheck.outputFolder()) ? GoBack.undoSummary(BootCheck.outputFolder()) : null;
        List<Section> sections = new ArrayList<>(List.of(new Section(quiet ? "ONLY WHEN IT BREAKS" : "ALL WARNINGS", quiet ? DashScreen.GREY : DashScreen.BLUE,
                                quiet ? "-" : "i", DashScreen.lines(quiet
                                        ? "Check Engine only shows up when the game crashed or a problem stops it."
                                        : "Check Engine shows warnings before the title screen, and what a pack update changed.",
                                quiet ? "Warnings and pack update notes stay hidden."
                                        : "Crashes and problems that stop the game always show.")),
                        new Section("GOT IT", DashScreen.GREY, "?", DashScreen.lines(
                                "A warning you said \"Got it\" to stays hidden until it's fixed and comes back."))));
        List<Btn> buttons = new ArrayList<>(List.of(new Btn(quiet ? "Turn warnings on" : "Only tell me when it breaks", () -> {
                            try {
                                Quiet.setOnlyWhenItBreaks(BootCheck.outputFolder(), !quiet);
                            } catch (IOException e) {
                                CheckEngine.LOGGER.warn("Check Engine: couldn't save the warnings switch", e);
                            }
                            Minecraft.getInstance().setScreen(of(parent));
                        }, true),
                        new Btn("Continue", back, false)));
        // The Pit Stop screen, also for packs whose title screen hides the button (FancyMenu and the like)
        buttons.add(1, new Btn("Open Check Engine", () -> Minecraft.getInstance().setScreen(HubScreen.open(of(parent))),
                true, "Start times, mods, Go back, warnings and the last crash."));
        // The Pit Stop button on the title screen: shown unless the player (or the pack maker's default) hides it
        boolean shown = Quiet.titleButton(BootCheck.outputFolder(), BootCheck.outputFolder().getParent());
        buttons.add(buttons.size() - 1, new Btn(shown ? "Hide title button" : "Show title button", () -> {
            try {
                Quiet.setTitleButton(BootCheck.outputFolder(), !shown);
            } catch (IOException e) {
                CheckEngine.LOGGER.warn("Check Engine: couldn't save the title button switch", e);
            }
            Minecraft.getInstance().setScreen(of(parent));
        }, false, "The Check Engine button on the title screen, with the last start time."));
        if (undo != null) {
            sections.add(new Section("WENT BACK TO WHEN IT WORKED", DashScreen.AMBER, "±", DashScreen.lines(
                    "Put it back to have your newer mods and configs again: " + undo + ".")));
            buttons.add(1, new Btn("Put it back", () -> Minecraft.getInstance().setScreen(putBack(parent, undo)),
                    true, "Undoes Go back. Asks first."));
        }
        return new DashScreen(quiet ? DashScreen.GREY : DashScreen.BLUE, "WARNINGS " + (quiet ? "OFF" : "ON"), null,
                null, sections, buttons, back);
    }

    /** "Put it back?": what it does, then Put it back or Cancel. */
    static Screen putBack(Screen parent, String undo) {
        Runnable back = () -> Minecraft.getInstance().setScreen(of(parent));
        return new DashScreen(DashScreen.AMBER, "PUT IT BACK?", null, null, List.of(
                new Section("WHAT CHANGES", DashScreen.AMBER, "±", DashScreen.lines(undo + ".",
                        "That's the pack as it was before Go back, including what made it crash.")),
                new Section("SAFE", DashScreen.GREEN, "✓", DashScreen.lines(
                        "Nothing is deleted: the old versions are renamed to .disabled."))),
                List.of(new Btn("Put it back", () -> {
                    String text;
                    try {
                        List<String> done = GoBack.putBack(FMLPaths.GAMEDIR.get(), BootCheck.outputFolder());
                        com.guiltypotato.checkengine.core.strip.GoBackPlans.refresh(FMLPaths.GAMEDIR.get(), BootCheck.outputFolder());
                        text = done.isEmpty() ? "Nothing to put back." : "Put back how you had it (" + done.size()
                                + " change" + (done.size() == 1 ? "" : "s") + ").";
                    } catch (IOException | RuntimeException e) {
                        CheckEngine.LOGGER.warn("Check Engine: couldn't put it back", e);
                        text = "Couldn't put it back: " + e.getMessage();
                    }
                    Minecraft.getInstance().setScreen(new DashScreen(DashScreen.GREY, "PUT BACK", null, null, List.of(
                            new Section("DONE", DashScreen.GREY, "i", DashScreen.lines(text,
                                    "Restart the game so it loads your mods again."))),
                            List.of(new Btn("Quit", () -> Minecraft.getInstance().stop(), true),
                                    new Btn("Continue", () -> Minecraft.getInstance().setScreen(of(parent)), false)),
                            () -> Minecraft.getInstance().setScreen(of(parent))).light(DashScreen.AMBER));
                }, true), new Btn("Cancel", back, false)), // not "Back": beside "Put it back" it reads alike
                back).from(parent);
    }
}
