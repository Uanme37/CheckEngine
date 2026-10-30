package com.guiltypotato.checkengine.core.scan;

/**
 * What Check Engine's title screen button shows: the last start time ("2:41"), an up arrow when that start was
 * clearly slower than usual, and "!" while there's a problem the player hasn't dealt with yet: a warning without
 * "Got it", or a crash explanation not opened. With "Only tell me when it breaks" on, "!" is only for crashes and
 * problems that stop the game.
 *
 * @param time   "2:41", or "" before the first measured start
 * @param slower the last start was clearly slower than usual
 * @param alert  something to look at
 */
public record TitleButtonState(String time, boolean slower, boolean alert) {
    /**
     * @param lastMs          the last start, or -1 when not measured
     * @param slower          {@link LaunchHistory#slower} found a slowdown
     * @param unseenWarnings  warnings showing this launch without "Got it"
     * @param unseenProblems  problems that stop the game (or crash it) without "Got it"
     * @param crashUnseen     a crash whose explanation hasn't been opened yet
     * @param onlyWhenBreaks  the player's "Only tell me when it breaks"
     */
    public static TitleButtonState of(long lastMs, boolean slower, int unseenWarnings, int unseenProblems,
                                      boolean crashUnseen, boolean onlyWhenBreaks) {
        boolean alert = crashUnseen || unseenProblems > 0 || (!onlyWhenBreaks && unseenWarnings > 0);
        return new TitleButtonState(lastMs < 0 ? "" : clock(lastMs), slower, alert);
    }

    /** 161_000 -> "2:41", 3_725_000 -> "1:02:05". */
    static String clock(long ms) {
        long s = Math.round(ms / 1000.0);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    /** The caption under the button: "2:41", "4:06 ↑". */
    public String caption() {
        return slower && !time.isEmpty() ? time + " ↑" : time;
    }

    /** The tooltip: what each mark means, in a few words. */
    public String tooltip() {
        StringBuilder sb = new StringBuilder("Check Engine");
        if (!time.isEmpty()) sb.append("\nLast start: ").append(time);
        if (slower) sb.append("\nSlower than usual");
        if (alert) sb.append("\nSomething to look at");
        return sb.append("\nClick to open").toString();
    }
}
