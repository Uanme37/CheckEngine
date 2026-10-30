package com.guiltypotato.checkengine.forge.client;

import com.guiltypotato.checkengine.core.scan.PackInfo;
import com.guiltypotato.checkengine.ui.DashScreen;
import com.guiltypotato.checkengine.ui.DashScreen.Btn;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

/** Check Engine's look for the shared UAN screens: its lamp, the UAN mark, its name and the pack's help link. */
public final class CeLook {
    private CeLook() {}

    public static void apply() {
        DashScreen.brand(new ResourceLocation("checkengine", "textures/gui/lamp.png"),
                new ResourceLocation("checkengine", "textures/gui/uan.png"), "Check Engine", () -> {
                    // The pack maker's help link (config/checkengine-pack.properties), when there is one.
                    String help = PackInfo.load(FMLPaths.GAMEDIR.get()).supportLink();
                    return help == null ? List.of()
                            : List.of(new Btn("Get help", () -> Util.getPlatform().openUri(help), false, help));
                });
    }
}
