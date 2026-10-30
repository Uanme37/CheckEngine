package com.guiltypotato.checkengine.core.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Help > What is this screen?: plain words for every screen, tab and fault code. It ships inside the jar, nothing is
 * looked up online.
 */
public final class ScreenHelp {
    /** Words in a headline -> the screen's short name; first match wins. */
    private static final Map<String, String> PLACES = new LinkedHashMap<>();
    /** A screen's short name, or "name/tab" for one of its tabs -> what it is. */
    private static final Map<String, String> SCREENS = new LinkedHashMap<>();
    /** Fault code -> what it means. */
    private static final Map<String, String> CODES = new LinkedHashMap<>();

    static {
        PLACES.put("STRIP MINE", "Strip Mine");
        PLACES.put("WON'T START", "Before the game");
        PLACES.put("STARTUP WILL", "Before the game");
        PLACES.put("PROBABLY CRASH", "Before the game");
        PLACES.put("WHAT IS THIS", "What is this?");
        PLACES.put("CRASH", "Crash Doctor");
        PLACES.put("GO BACK", "Go back");
        PLACES.put("BACK TO WHEN", "Go back");
        PLACES.put("PUT IT BACK", "Put it back");
        PLACES.put("PUT BACK", "Put it back");
        PLACES.put("RESCUE", "Rescue world");
        PLACES.put("BACKING UP THE WORLD", "Rescue world");
        PLACES.put("BACK UP YOUR WORLDS", "World backup");
        PLACES.put("BACKING UP WORLDS", "World backup");
        PLACES.put("WORLDS BACKED UP", "World backup");
        PLACES.put("SOME BACKUPS", "World backup");
        PLACES.put("WORLDS STILL USE", "Removed mods");
        PLACES.put("COPIED", "Copied");
        PLACES.put("PIT STOP", "Pit Stop");
        PLACES.put("WARNINGS O", "Settings");
        PLACES.put("PROBLEMS FOUND", "Heads-up");
        PLACES.put("HEADS-UP", "Heads-up");
        PLACES.put("CAN'T JOIN", "Join");
        PLACES.put("FIXING YOUR MODS", "Join");
        PLACES.put("MODS FIXED", "Join");
        PLACES.put("NOTHING TO FIX", "Join");
        PLACES.put("COULDN'T LOAD YOU", "Join");
        PLACES.put("JOIN", "Join");
        PLACES.put("WAS UPDATED", "Pack updated");
        PLACES.put("REPORT", "Report");
        PLACES.put("PROBLEM", "Report");
        PLACES.put("WARNING", "Report");

        SCREENS.put("Crash Doctor", "Your last game crashed. This screen says why, as far as Check Engine can tell, "
                + "and what you can do about it.");
        SCREENS.put("Crash Doctor/Fix", "The cause of the crash and the fix. The buttons below do the fix for you when "
                + "they can. Nothing happens until you press one.");
        SCREENS.put("Crash Doctor/Crash lines", "The few lines from the crash report that matter. The line in amber "
                + "names the mod that was running when it crashed. That mod isn't always the cause.");
        SCREENS.put("Crash Doctor/Changed", "What's different since the pack last started and closed normally. A crash "
                + "right after a change is usually caused by that change.");
        SCREENS.put("Crash Doctor/Get help", "How to ask someone for help. The buttons below make a message to paste or a zip with the logs. Your name "
                + "and IP are blanked out, and nothing is uploaded.");
        SCREENS.put("Pit Stop", "Check Engine's own screen: how your pack is doing, even when nothing is wrong.");
        SCREENS.put("Pit Stop/Pit Stop", "How long the game took to start, and your last launches. If a start gets much "
                + "slower, it shows what changed before it.");
        SCREENS.put("Pit Stop/Mods", "Every mod in the pack with its blocks, items and file size. Type to search.");
        SCREENS.put("Pit Stop/Go back", "What changed since the pack last worked. Go back puts the pack as it was then. "
                + "Nothing is deleted, and Put it back undoes it.");
        SCREENS.put("Pit Stop/Warnings", "Everything Check Engine warned about: still there, or fixed. A warning counts "
                + "as fixed once a launch no longer finds it.");
        SCREENS.put("Pit Stop/Last crash", "The last crash Check Engine explained. It stays here until a newer crash "
                + "replaces it.");
        SCREENS.put("Strip Mine", "Finds the mod behind a crash by testing. Half of the mods are switched off each "
                + "launch, so it takes a few launches. Nothing is deleted, and every mod comes back at the end.");
        SCREENS.put("Go back", "Puts the pack back to the last time it started and closed normally. Newer mods are "
                + "renamed to .disabled, not deleted. Put it back undoes it.");
        SCREENS.put("Put it back", "Undoes Go back: your newer mods and configs come back, including whatever made the "
                + "pack crash.");
        SCREENS.put("Rescue world", "For a world that crashes as soon as it opens. Check Engine copies the world first, "
                + "then the broken mob or machine is removed the next time the world loads.");
        SCREENS.put("World backup", "You went back to an older version of a mod or of Minecraft. Worlds saved on the "
                + "newer one can break, so this offers a copy of them first.");
        SCREENS.put("Removed mods", "A mod that's gone had blocks or items in these worlds. They disappear from the "
                + "world when you open it. A backup first keeps them safe.");
        SCREENS.put("Copied", "The message is on your clipboard. Press Ctrl+V where you ask for help. This screen shows "
                + "exactly what was copied.");
        SCREENS.put("Settings", "How often Check Engine speaks up, and whether its button shows on the title screen.");
        SCREENS.put("Heads-up", "Check Engine found something when the game started. The game still runs. Each line says "
                + "what it found and how to fix it. Got it hides a warning until it's fixed and comes back.");
        SCREENS.put("Join", "A server refused you, or your mods don't match it. This says what's different and what can "
                + "be fixed from here.");
        SCREENS.put("Pack updated", "Your pack got a new version. This lists the mods that came, went or changed.");
        SCREENS.put("Report", "The results of /checkengine scan: recipes, loot tables and quests that point at something "
                + "that isn't there. These are items to check, not always real problems.");
        SCREENS.put("Before the game", "Check Engine looked at the pack before the long load started, so you don't wait "
                + "minutes for an error. You choose what happens next.");

        CODES.put("CE-01", "CE-01, missing mod: a mod needs another mod that isn't in the pack.");
        CODES.put("CE-02", "CE-02, wrong build: a mod made for another loader or Minecraft version is in the pack.");
        CODES.put("CE-03", "CE-03, broken file: a mod file can't be read, usually a download that went wrong.");
        CODES.put("CE-04", "CE-04, mod switched off: a mod that others need is switched off.");
        CODES.put("CE-05", "CE-05, wrong version: a mod needs a newer or older version of another mod.");
        CODES.put("CE-06", "CE-06, incompatible: two mods say they don't work together.");
        CODES.put("CE-07", "CE-07, Java version: the game runs on a Java version that may break some mods.");
        CODES.put("CE-08", "CE-08, low memory: the pack was given less memory than it needs.");
        CODES.put("CE-09", "CE-09, repeat crash: the last launch crashed and nothing changed, so it will very likely "
                + "crash the same way.");
        CODES.put("CE-10", "CE-10, Strip Mine result: the search has finished.");
        CODES.put("CE-11", "CE-11, crash: the game crashed during the last launch.");
    }

    private ScreenHelp() {}

    /** The fault code of the last crash, shown in Crash Doctor's header. */
    public static final String CRASH_CODE = "CE-11";

    /** "GO BACK TO WHEN IT WORKED?" -> "Go back": the short name in the side column, or null when there's none. */
    public static String place(String headline) {
        if (headline == null) return null;
        String up = headline.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> e : PLACES.entrySet()) if (up.contains(e.getKey())) return e.getValue();
        return null;
    }

    /**
     * What this screen is, in plain words: its tab's text or else the screen's, then what the fault code means.
     *
     * @param place the screen's short name, or null
     * @param tab   the open tab's label, or null
     * @param code  the fault code, or null (anything else in that spot is ignored)
     */
    public static List<String> explain(String place, String tab, String code) {
        List<String> out = new ArrayList<>();
        String text = SCREENS.get(place + "/" + tab);
        if (text == null) text = SCREENS.get(place);
        out.add(text != null ? text : "This is a Check Engine screen. It says what Check Engine found and what you can do "
                + "about it.");
        String meaning = code == null ? null : CODES.get(code);
        if (meaning != null) out.add(meaning);
        return out;
    }

    /** Every fault code with an explanation. */
    public static List<String> codes() {
        return List.copyOf(CODES.keySet());
    }
}
