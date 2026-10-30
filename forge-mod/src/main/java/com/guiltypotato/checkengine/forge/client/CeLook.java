package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.forge.BootCheck;
import com.guiltypotato.checkengine.core.crash.CrashHelp;
import com.guiltypotato.checkengine.core.scan.PackInfo;
import com.guiltypotato.checkengine.ui.DashScreen;
import com.guiltypotato.checkengine.ui.DashScreen.Btn;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.FMLPaths;

/** Check Engine's words on the shared UAN screens: its name, and the Help menu. */
public final class CeLook {
    private static final String ISSUES = "https://github.com/Uanme37/CheckEngine/issues";

    private CeLook() {}

    public static void apply() {
        DashScreen.brand("Check Engine", CeLook::helpRows);
    }

    /** Copy for help, the pack's help link (only when the pack maker set one), Report to Check Engine. */
    private static List<Btn> helpRows(DashScreen screen) {
        List<Btn> rows = new ArrayList<>();
        if (!screen.hidesCopy()) {
            Runnable own = screen.copyAction();
            rows.add(new Btn("Copy for help", own != null ? own : () -> copy(screen), false));
        }
        PackInfo pack = PackInfo.load(FMLPaths.GAMEDIR.get());
        String link = pack.supportLink();
        if (link != null) {
            rows.add(new Btn(com.guiltypotato.checkengine.core.ui.UiText.helpLinkLabel(pack.headlineName(), link),
                    () -> Util.getPlatform().openUri(link), false));
        }
        rows.add(new Btn("Report to Check Engine", () -> Util.getPlatform().openUri(ISSUES), false));
        return rows;
    }

    /** On a screen without a crash: what the screen says and the versions, then the same "copied" screen. */
    private static void copy(DashScreen screen) {
        String text = CrashHelp.forScreen(screen.state(), screen.code(), screen.plainText(), BootCheck.ready(),
                Minecraft.getInstance().getUser().getName(), BootCheck.outputFolder());
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        Minecraft.getInstance().setScreen(CrashDoctorScreen.copied(screen, text));
    }
}
