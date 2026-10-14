package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.forge.client.DashScreen.Btn;
import com.guiltypotato.checkengine.forge.client.DashScreen.Line;
import com.guiltypotato.checkengine.forge.client.DashScreen.Section;
import com.guiltypotato.checkengine.core.scan.Changes;
import com.guiltypotato.checkengine.core.scan.PackInfo;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.fml.loading.FMLPaths;

/** After a pack update, once: what's new, updated and gone, in a blue info window. */
final class PackUpdatedScreen {
    private static final int SHOWN = 12;
    /** Ours while it's open (26.1 may try to open the title screen over it). */
    static Screen showing;
    /** Shown once per game. */
    static boolean shown;

    private PackUpdatedScreen() {}

    /** The screen, or null when the changes don't look like a pack update. */
    static Screen of(Screen next, Changes changes) {
        if (shown) return null;
        List<Changes.Change> mods = com.guiltypotato.checkengine.core.scan.UpdateNotes.forPlayers(changes);
        if (mods.isEmpty()) return null;
        List<Section> sections = new ArrayList<>();
        add(sections, "NEW", DashScreen.GREEN, "+", mods, Changes.Type.ADDED, false);
        add(sections, "UPDATED", DashScreen.BLUE, "»", mods, Changes.Type.UPDATED, false);
        add(sections, "ROLLED BACK", DashScreen.AMBER, "!", mods, Changes.Type.UPDATED, true);
        add(sections, "REMOVED", DashScreen.GREY, "-", mods, Changes.Type.REMOVED, false);
        String name = PackInfo.load(FMLPaths.GAMEDIR.get()).headlineName();
        Runnable leave = () -> {
            showing = null;
            Minecraft.getInstance().setScreen(next);
        };
        shown = true;
        return showing = new DashScreen(DashScreen.BLUE, (name != null ? name : "THE PACK") + " WAS UPDATED", null, null,
                sections, List.of(new Btn("Continue", leave, true)), leave);
    }

    private static void add(List<Section> sections, String title, int tone, String icon, List<Changes.Change> mods,
                            Changes.Type type, boolean downgrades) {
        List<Changes.Change> list = mods.stream().filter(c -> c.type() == type && c.downgrade() == downgrades)
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList();
        if (list.isEmpty()) return;
        List<Line> lines = new ArrayList<>();
        for (Changes.Change c : list.subList(0, Math.min(SHOWN, list.size()))) {
            lines.add(Line.text(switch (type) {
                case ADDED -> c.name() + " " + c.to();
                case UPDATED -> c.name() + " " + c.from() + " → " + c.to();
                default -> c.name();
            }));
        }
        if (list.size() > SHOWN) lines.add(Line.dim("...and " + (list.size() - SHOWN) + " more"));
        sections.add(new Section(title + " (" + list.size() + ")", tone, icon, lines));
    }
}
