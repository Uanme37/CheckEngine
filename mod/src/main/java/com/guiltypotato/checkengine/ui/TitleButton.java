package com.guiltypotato.checkengine.ui;

import com.guiltypotato.checkengine.core.ui.Placement;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;

/**
 * A small square icon button for the title screen, beside the vanilla Language and Accessibility buttons, with a
 * short caption under it and an optional "!" badge. It finds those buttons by their names (not fixed coordinates) and
 * steps aside when another mod's widget is already there. No mod's own logic lives here.
 */
public final class TitleButton {
    public static final int SIZE = 20;
    /** The logo with its ring red, amber and green, one under the other (tools/dev/TitleLogo.java). */
    private static final ResourceLocation TITLE_LOGO = ResourceLocation.fromNamespaceAndPath("checkengine", "textures/gui/title_logo.png");
    private static final int LOGO_CELL = 168;
    /** Room kept free right of the button for the caption ("12:34 ↑"). */
    public static final int CAPTION = 34;

    /** Why the last {@link #place} chose what it did, for the log. */
    private static String note = "";
    /** Whether the last spot had room for the caption beside the button. */
    private static boolean caption = true;
    /** The widget the last {@link #place} put the button beside, or null (a corner). */
    private static AbstractWidget anchor;
    /** Menu mods' "is this widget hidden" method per widget class (none: empty), looked up once. */
    private static final java.util.Map<Class<?>, java.util.Optional<java.lang.reflect.Method>> HIDDEN =
            new java.util.concurrent.ConcurrentHashMap<>();

    private TitleButton() {}

    public static String note() {
        return note;
    }

    /** The widget the last {@link #place} put the button beside, or null when it went in a corner. */
    public static AbstractWidget anchor() {
        return anchor;
    }

    /** Whether the last spot had room for the caption (else it's left out; the tooltip still has it). */
    public static boolean caption() {
        return caption;
    }

    /**
     * Whether a widget really shows: visible, and not hidden by a menu mod. FancyMenu keeps the vanilla buttons it
     * hides "visible" and answers isHiddenFancyMenu() instead; any widget with that method is asked.
     */
    public static boolean shown(AbstractWidget w) {
        if (!w.visible) return false;
        java.util.Optional<java.lang.reflect.Method> m = HIDDEN.computeIfAbsent(w.getClass(), c -> {
            try {
                return java.util.Optional.of(c.getMethod("isHiddenFancyMenu"));
            } catch (NoSuchMethodException | RuntimeException e) {
                return java.util.Optional.empty();
            }
        });
        if (m.isEmpty()) return true; // no menu mod's flag: it shows
        try {
            return !Boolean.TRUE.equals(m.get().invoke(w));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /**
     * Gives the button a fixed name for menu mods: FancyMenu's editor then recognises it and remembers where the pack
     * maker put it (or that they hid it), whatever its label or first position. Nothing happens without FancyMenu.
     */
    public static void identify(AbstractWidget w, String id) {
        try {
            w.getClass().getMethod("setWidgetIdentifierFancyMenu", String.class).invoke(w, id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // no menu mod: nothing to tell
        }
    }

    /** The shown widgets' boxes, leaving out backdrops (anything over a quarter of the screen). */
    private static List<Placement.Box> taken(List<?> widgets, int screenWidth, int screenHeight) {
        List<Placement.Box> taken = new ArrayList<>();
        for (Object o : widgets) {
            if (o instanceof AbstractWidget w && shown(w)
                    && (long) w.getWidth() * w.getHeight() <= (long) screenWidth * screenHeight / 4) {
                taken.add(new Placement.Box(w.getX(), w.getY(), w.getWidth(), w.getHeight()));
            }
        }
        return taken;
    }

    /**
     * Where the button goes on this screen, or null when none of the anchors is there (another mod replaced the title
     * screen) or there's no free spot near them.
     *
     * @param widgets    the screen's widgets
     * @param anchorKeys translation keys of the buttons to sit beside, first one preferred
     */
    public static Placement.Box place(List<?> widgets, List<String> anchorKeys, int screenWidth, int screenHeight) {
        List<Placement.Box> taken = taken(widgets, screenWidth, screenHeight);
        StringBuilder why = new StringBuilder(widgets.size() + " widgets, " + taken.size() + " shown");
        anchor = null;
        for (String key : anchorKeys) {
            String name = I18n.get(key);
            for (Object o : widgets) {
                if (o instanceof AbstractWidget w && shown(w) && w.getMessage().getString().equals(name)) {
                    Placement.Box anchor = new Placement.Box(w.getX(), w.getY(), w.getWidth(), w.getHeight());
                    // with room for the caption beside it, else the button alone
                    Placement.Box spot = Placement.beside(anchor, taken, SIZE, CAPTION, 0, 4, screenWidth);
                    caption = spot != null;
                    if (spot == null) spot = Placement.beside(anchor, taken, SIZE, 0, 0, 4, screenWidth);
                    why.append("; ").append(key).append(" at ").append(anchor).append(spot == null ? ": no free spot"
                            : ": placed at " + spot + (caption ? "" : " (no room for the caption)"));
                    if (spot != null) {
                        note = why.toString();
                        TitleButton.anchor = w;
                        return spot;
                    }
                }
            }
        }
        note = why.append(why.indexOf(";") < 0 ? "; no shown Accessibility or Language button" : "").toString();
        return null;
    }

    /**
     * A spot in a corner of this screen ("bottom_left", ... or "x,y"), past widgets already there, or null.
     */
    public static Placement.Box corner(List<?> widgets, String where, int screenWidth, int screenHeight) {
        List<Placement.Box> taken = taken(widgets, screenWidth, screenHeight);
        anchor = null;
        Placement.Box spot = Placement.corner(where, taken, SIZE, CAPTION, 4, screenWidth, screenHeight);
        caption = spot != null;
        if (spot == null) spot = Placement.corner(where, taken, SIZE, 0, 4, screenWidth, screenHeight);
        note = "corner " + where + (spot == null ? ": no free spot" : ": placed at " + spot
                + (caption ? "" : " (no room for the caption)"));
        return spot;
    }

    /**
     * Draws on the button: the Check Engine logo with its ring in the state's colour, like a vanilla icon button, and the
     * caption beside it (the bottom of the title screen has the copyright and loader text).
     */
    public static void draw(GuiGraphics g, Font font, int x, int y, int lamp, String caption, int captionColor) {
        int cell = (lamp | 0xFF000000) == UanColors.RED ? 0 : (lamp | 0xFF000000) == UanColors.AMBER ? 1 : 2;
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blit(TITLE_LOGO, x + 3, y + 2, 14, 14, 0, cell * LOGO_CELL, LOGO_CELL, LOGO_CELL, LOGO_CELL, LOGO_CELL * 3);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        if (!caption.isEmpty()) g.drawString(font, caption, x + SIZE + 4, y + 6, captionColor);
    }
}
