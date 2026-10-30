package com.guiltypotato.checkengine.client;

import com.guiltypotato.checkengine.BootCheck;
import com.guiltypotato.checkengine.CheckEngine;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.LaunchHistory;
import com.guiltypotato.checkengine.core.scan.PackInfo;
import com.guiltypotato.checkengine.core.scan.Quiet;
import com.guiltypotato.checkengine.core.scan.Report;
import com.guiltypotato.checkengine.core.scan.StartupTimes;
import com.guiltypotato.checkengine.core.scan.TitleButtonState;
import com.guiltypotato.checkengine.core.strip.StripMine;
import com.guiltypotato.checkengine.core.ui.Placement;
import com.guiltypotato.checkengine.ui.FadeButton;
import com.guiltypotato.checkengine.ui.TitleButton;
import com.guiltypotato.checkengine.ui.UanColors;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Check Engine's button on the title screen: the lamp, the last start time under it ("2:41", "4:06 ↑" when clearly
 * slower than usual) and "!" while something waits to be looked at. Opens the Pit Stop hub.
 */
public final class TitleButtons {
    /** The vanilla buttons to sit beside: Accessibility first, else Language (1.21+ names, then 1.20.1 names). */
    private static final List<String> ANCHORS = List.of("options.accessibility", "narrator.button.accessibility",
            "options.language", "narrator.button.language");
    private static FadeButton button;
    private static TitleButtonState state;
    /** A crash since the pack last worked: the lamp stays red until a launch works again. */
    private static boolean crashed;
    /** Whether there was room for the start time beside the button. */
    private static boolean caption = true;
    /**
     * The vanilla button ours was placed beside, and where ours went: a menu mod (FancyMenu) marks the buttons it hides
     * only after the screen is built, so the first frames check once whether that button got hidden.
     */
    private static net.minecraft.client.gui.components.AbstractWidget anchor;
    private static int placedX, placedY, anchorX, anchorY, checks;
    /** Frames to watch for a menu mod hiding the anchor (a second or so). */
    private static final int CHECK_FRAMES = 60;

    private TitleButtons() {}

    /** On the title screen's init: adds the button, unless the player or pack maker hid it, or Strip Mine runs. */
    public static void init(Screen screen, List<?> widgets, Consumer<AbstractWidget> add, boolean crashUnseen) {
        // nothing kept from an earlier screen (no old widgets held on to)
        button = null;
        anchor = null;
        state = null;
        if (!(screen instanceof TitleScreen)) return;
        Path ce = BootCheck.outputFolder();
        if (!Quiet.titleButton(ce, ce.getParent()) || StripMine.running(ce) != null) return;
        state = state(crashUnseen);
        crashed = com.guiltypotato.checkengine.core.crash.CrashDoctor.crashedSinceItWorked(ce.getParent(), ce);
        // Placed once, when the screen is built (like Create's and Quark's buttons), so a menu mod's layout
        // (FancyMenu) can move or hide it afterwards and keep it that way.
        Placement.Box spot = spot(screen, widgets, ce.getParent());
        CheckEngine.LOGGER.info("Check Engine: title button ({}x{} screen): {}", screen.width, screen.height,
                TitleButton.note());
        if (spot == null) return;
        caption = TitleButton.caption();
        button = new FadeButton(spot.x(), spot.y(), TitleButton.SIZE, TitleButton.SIZE, Component.literal(""),
                b -> Minecraft.getInstance().setScreen(HubScreen.open(screen)));
        button.setTooltip(Tooltip.create(Component.literal(state.tooltip())));
        TitleButton.identify(button, "checkengine_title_button");
        anchor = TitleButton.anchor();
        if (anchor != null) {
            anchorX = anchor.getX();
            anchorY = anchor.getY();
        }
        placedX = spot.x();
        placedY = spot.y();
        checks = 0;
        add.accept(button);
    }

    /**
     * The pack maker's title_button_pos, else beside Accessibility when it shows on this screen. When a menu mod
     * (FancyMenu) hides the vanilla buttons, the bottom-left corner, right after the last corner button that shows.
     */
    private static Placement.Box spot(Screen screen, List<?> widgets, Path game) {
        String pos = PackInfo.titleButtonPos(game);
        if (!pos.equalsIgnoreCase("auto")) return TitleButton.corner(widgets, pos, screen.width, screen.height);
        Placement.Box spot = TitleButton.place(widgets, ANCHORS, screen.width, screen.height);
        return spot != null ? spot : TitleButton.corner(widgets, "bottom_left", screen.width, screen.height);
    }

    /** After the screen draws: the lamp, the start time and the "!" on top of the plain button. */
    public static void render(Screen screen, GuiGraphics g) {
        // Only ever on the title screen, only for our own button there, and not when a menu mod hid it
        if (!(screen instanceof TitleScreen) || button == null || state == null || !screen.children().contains(button)
                || !TitleButton.shown(button)) {
            return;
        }
        settle(screen);
        // The first title screen fades its buttons in: the lamp, time and "!" come with our button, not before it
        if (button.fade() < 0.5f) return;
        boolean red = state.broken() || crashed;
        boolean amber = state.slower() || state.alert();
        int lamp = red ? UanColors.RED : amber ? UanColors.AMBER : UanColors.GREEN;
        TitleButton.draw(g, Minecraft.getInstance().font, button.getX(), button.getY(), lamp,
                caption ? state.caption() + (state.alert() ? " !" : "") : "", red || amber ? lamp : UanColors.TEXT);
    }

    /**
     * Once, in the first frames: a menu mod (FancyMenu) lays out the title screen after it's built. If it hid the
     * vanilla button ours sits beside, ours goes to the bottom-left corner, after the last corner button that shows; if
     * it moved that button, or ours now covers another button, ours is placed again beside where it went. Only while
     * ours is still where Check Engine put it: a spot the pack maker chose in FancyMenu's editor is already applied and
     * wins.
     */
    private static void settle(Screen screen) {
        if (anchor == null || checks >= CHECK_FRAMES) return;
        checks++;
        if (button.getX() != placedX || button.getY() != placedY) { // the menu mod placed it: leave it there
            checks = CHECK_FRAMES;
            return;
        }
        List<Object> others = new java.util.ArrayList<>(screen.children());
        others.remove(button);
        boolean hidden = !TitleButton.shown(anchor);
        boolean moved = anchor.getX() != anchorX || anchor.getY() != anchorY;
        if (!hidden && !moved && !covers(others)) return;
        checks = CHECK_FRAMES;
        Placement.Box spot = hidden ? null : TitleButton.place(others, ANCHORS, screen.width, screen.height);
        if (spot == null) spot = TitleButton.corner(others, "bottom_left", screen.width, screen.height);
        CheckEngine.LOGGER.info("Check Engine: title button: a menu mod {} the button beside it, {}",
                hidden ? "hid" : moved ? "moved" : "laid out widgets over", TitleButton.note());
        if (spot == null) return;
        button.setX(spot.x());
        button.setY(spot.y());
        caption = TitleButton.caption();
    }

    /** Whether our button (with its time beside it) now covers a button that shows. */
    private static boolean covers(List<Object> others) {
        int w = TitleButton.SIZE + (caption ? TitleButton.CAPTION : 0);
        for (Object o : others) {
            if (o instanceof net.minecraft.client.gui.components.AbstractWidget x && TitleButton.shown(x)
                    && x.getX() < button.getX() + w && button.getX() < x.getX() + x.getWidth()
                    && x.getY() < button.getY() + TitleButton.SIZE && button.getY() < x.getY() + x.getHeight()) {
                return true;
            }
        }
        return false;
    }

    static TitleButtonState state(boolean crashUnseen) {
        Path ce = BootCheck.outputFolder();
        Report report = BootCheck.ready(); // never waits on the title screen
        int warnings = 0, problems = 0;
        if (report != null) {
            List<Finding> unseen = Quiet.toShow(ce, PackInfo.load(ce.getParent()).visible(BootCheck.worthShowing(report)));
            for (Finding f : unseen) {
                if (f.severity() == Finding.Severity.ERROR) problems++;
                else warnings++;
            }
        }
        StartupTimes.Data data = StartupTimes.load(ce);
        boolean slower = LaunchHistory.slower(LaunchHistory.load(ce)) != null;
        return TitleButtonState.of(data == null ? -1 : data.totalMs(), slower, warnings, problems, crashUnseen,
                Quiet.onlyWhenItBreaks(ce));
    }

    /**
     * The slower-start notice for a toast, once per slowdown: "Started 40 s slower than usual. Changed since then:
     * added Create +2 more." Null when there's none, or the player only wants to hear about breakage.
     */
    public static String slowerNotice() {
        Path ce = BootCheck.outputFolder();
        if (Quiet.onlyWhenItBreaks(ce) || StripMine.running(ce) != null) return null;
        LaunchHistory.Slower s = LaunchHistory.noticeOnce(ce);
        return s == null ? null : s.text();
    }
}
