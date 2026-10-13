package com.guiltypotato.checkengine.core.intercept;

import java.util.List;

/**
 * One page of the Check Engine window. A feature only says what's on it (panels and buttons); {@link Popup} draws
 * every page in the same dashboard frame: hazard stripe, warning lamp and headline, the fault code readout, the
 * panels, the buttons and the UAN footer.
 *
 * @param tone      the colour of the whole page: red = won't start, amber = warning, green = found/fixed, blue = info
 * @param headline  the big coloured line under CHECK ENGINE
 * @param subline   small grey line under the headline, or null
 * @param code      fault code for the readout ("CE-09"), or null for no readout
 * @param codeLabel what the code means ("REPEAT CRASH"), shown under the readout
 * @param sections  the panels, top to bottom
 * @param actions   the buttons, most important first. Coloured ones are big switches on the left, GREY ones small on
 *                  the right
 * @param help      "Still stuck? ..." line above the buttons, or null
 * @param note      small text at the right of the footer ("394 MODS"), or null
 */
record Page(Tone tone, String headline, String subline, String code, String codeLabel, List<Section> sections,
            List<Action> actions, String help, String note) {
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

    /** A panel with a round icon, a coloured title and lines, like a gauge readout. Long ones scroll. */
    record Section(String title, Tone tone, Icon icon, List<Line> lines) {}

    /** The symbol in a panel's round badge. */
    enum Icon { ALERT, CHANGED, CHECK, INFO, NEXT }

    /** A button. GREY ones are the small secondary buttons. */
    record Action(String label, Tone tone, Runnable run) {}
}
