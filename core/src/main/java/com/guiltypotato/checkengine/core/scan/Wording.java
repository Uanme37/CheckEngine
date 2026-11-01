package com.guiltypotato.checkengine.core.scan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text saved on disk by older versions (warnings a player dismissed, the Warnings tab, a modpack's hide_warnings) still
 * has the old words. Everything that compares such text goes through {@link #now} on both sides, so a reworded warning
 * doesn't look fixed and a dismissed one doesn't come back.
 */
public final class Wording {
    /** "pack" on its own: not modpack, datapack, pack_name, Check Pack.bat, pack.png or resource and shader packs. */
    private static final Pattern PACK = Pattern.compile(
            "(?<![\\w-])(?<!Check )(?<!(?:[Rr]esource|[Ss]hader|[Tt]exture|[Dd]ata|[Gg]un|Compatibility) )(pack|Pack|PACK)(s?)(?![\\w-]|\\.\\w)");

    private Wording() {}

    public static String now(String old) {
        if (old == null) return null;
        String t = old.replace("Crash Doctor", "Mechanic").replace("CRASH DOCTOR", "MECHANIC")
                .replace("Strip Mine", "Misfire").replace("STRIP MINE", "MISFIRE");
        Matcher m = PACK.matcher(t);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String word = switch (m.group(1)) {
                case "Pack" -> "Modpack";
                case "PACK" -> "MODPACK";
                default -> "modpack";
            };
            m.appendReplacement(out, word + m.group(2));
        }
        return m.appendTail(out).toString();
    }
}
