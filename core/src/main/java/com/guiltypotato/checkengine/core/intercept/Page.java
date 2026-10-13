package com.guiltypotato.checkengine.core.intercept;

import java.util.List;

/**
 * One page of the Check Engine window. A feature only says what's on it (panels and buttons); {@link Popup} draws
 * every page in the same frame: the warning light and headline on top, the panels, an optional help line, the buttons
 * (the first ones, the rest under MORE) and the UAN footer.
 *
 * @param tone     the colour of the whole page: red = won't start, amber = warning, green = found/fixed, blue = info
 * @param headline the big coloured line under CHECK ENGINE
 * @param subline  the small grey line under it (fault code, date...)
 * @param sections the panels, top to bottom
 * @param actions  the buttons, most important first
 * @param help     "Still stuck? ..." line above the buttons, or null
 */
record Page(Tone tone, String headline, String subline, List<Section> sections, List<Action> actions, String help) {
    enum Tone { RED, AMBER, GREEN, BLUE, GREY }

    enum Style { STRONG, TEXT, DIM }

    record Line(String text, Style style) {
        static Line strong(String t) {
            return new Line(t, Style.STRONG);
        }

        static Line text(String t) {
            return new Line(t, Style.TEXT);
        }

        static Line dim(String t) {
            return new Line(t, Style.DIM);
        }
    }

    /** A panel with a coloured stripe and title, like a gauge readout. Long ones scroll. */
    record Section(String title, Tone tone, List<Line> lines) {}

    /** A button. {@code tone} GREY is the plain dark button. */
    record Action(String label, Tone tone, Runnable run) {}
}
