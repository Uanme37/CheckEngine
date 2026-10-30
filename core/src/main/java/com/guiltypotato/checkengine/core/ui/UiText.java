package com.guiltypotato.checkengine.core.ui;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** The words every screen shares: headings in sentence case, one way to write a date, one word per way to close. */
public final class UiText {
    /** Names that keep their capitals inside a sentence. */
    private static final List<String> NAMES = List.of("Strip Mine", "Crash Doctor", "Check Engine", "CurseForge", "Crash Assistant", "Pit Stop");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter READ = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** The only words that close a screen; they always sit far right. */
    private static final List<String> CLOSE = List.of("Continue", "Cancel", "Quit");

    private UiText() {}

    /** "WHAT CHANGES" -> "What changes", "STRIP MINE FOUND IT" -> "Strip Mine found it". Text with lower case stays. */
    public static String sentence(String text) {
        if (text == null || text.isEmpty() || !text.equals(text.toUpperCase(Locale.ROOT))) return text;
        String low = text.toLowerCase(Locale.ROOT);
        String out = Character.toUpperCase(low.charAt(0)) + low.substring(1);
        for (String name : NAMES) out = out.replaceAll("(?i)" + name, name);
        return out;
    }

    /** What follows "Check Engine: " in a header: "your last game crashed", "Strip Mine found it", "Demo Pack won't start". */
    public static String afterName(String text) {
        if (text == null || text.isEmpty() || !text.equals(text.toUpperCase(Locale.ROOT))) return text;
        String s = sentence(text);
        for (String name : NAMES) if (s.startsWith(name)) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** "2026-10-30 09:15:02", "2026-10-30T09:15" -> "30 Oct 09:15". Anything else stays as it is. */
    public static String date(String when) {
        if (when == null || when.length() < 16) return when;
        try {
            return LocalDateTime.parse(when.substring(0, 16).replace('T', ' '), READ).format(DATE);
        } catch (RuntimeException e) {
            return when;
        }
    }

    /** Now, the same way. */
    public static String now() {
        return LocalDateTime.now().format(DATE);
    }

    /** A long button label when the row is too narrow: "Go back to when it worked" -> "Go back". */
    public static String shorter(String label) {
        return label.startsWith("Go back to") ? "Go back" : label;
    }

    /** The Help menu's row for the pack maker's link: "Demo Pack's Discord", or a plain name for any other page. */
    public static String helpLinkLabel(String packName, String link) {
        String owner = packName == null ? "This pack" : packName;
        return owner + "'s " + (link.toLowerCase(Locale.ROOT).contains("discord") ? "Discord" : "help page");
    }

    /** Whether this button closes the screen, so it belongs far right. */
    public static boolean closes(String label) {
        return CLOSE.stream().anyMatch(c -> c.equalsIgnoreCase(label));
    }
}
