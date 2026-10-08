package com.guiltypotato.checkengine.core.scan;

import java.util.List;

/**
 * One thing to fix, with everything it breaks: "Create is missing, so 15 mods can't load".
 *
 * @param code      the kind of problem (a {@link Finding} code)
 * @param title     the cause in one line, e.g. "Create is missing"
 * @param detail    what's wrong, in plain English, including which mods it stops
 * @param fix       what to do
 * @param certainty null when it's a plain fact, otherwise how sure Check Engine is ("very likely", "likely")
 * @param affected  display names of every mod this stops from loading, including mods that need those mods
 * @param files     jar file names involved
 * @param ids       ids of the mods the problem is about (the ones with the broken rule, and the mod the rule is about)
 */
public record RootProblem(String code, String title, String detail, String fix, String certainty,
                          List<String> affected, List<String> files, List<String> ids) {

    /** The same thing as a {@link Finding}, for reports and screens that list findings. */
    public Finding toFinding() {
        String text = certainty == null ? detail : detail + "\nHow sure: " + certainty + ".";
        return new Finding(Finding.Severity.ERROR, code, title, text, fix, files);
    }
}
