package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.crash.GameLog;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * What log noise is normal for this pack: how much each mod wrote while the pack worked. Big packs are noisy, mostly
 * harmlessly, and no list of harmless lines can keep up with every mod. So the log checks only name noise that's new
 * since the pack last worked (or grew a lot), which works for mods nobody has looked at yet.
 */
public final class LogBaseline {
    static final String FILE = "log-baseline.properties";
    /** Noise more than this many times the normal amount counts as new. */
    static final int GREW = 2;

    /**
     * @param spam   mod id -> warnings and errors in one launch that worked
     * @param assets mod name -> missing textures and models in one launch that worked
     * @param when   where "normal" comes from, for the report: "when the pack worked" or "in earlier launches"
     */
    public record Normal(Map<String, Integer> spam, Map<String, Integer> assets, String when) {
        public static final Normal NONE = new Normal(Map.of(), Map.of(), "");

        public boolean isEmpty() {
            return spam.isEmpty() && assets.isEmpty();
        }
    }

    private LogBaseline() {}

    /** Whether {@code now} is new noise next to {@code normal} (null: none was seen while the pack worked). */
    public static boolean isNew(Integer normal, int now) {
        return normal == null || now > normal * GREW;
    }

    /**
     * Saves this launch's noise as normal. Call once the pack reached the title screen or the server started: the log
     * so far is the loading, where nearly all the noise is. Keeps the most seen per mod over the launches that worked.
     */
    public static void save(Path folder, Path gameDir) throws IOException {
        String log = GameLog.read(gameDir.resolve("logs").resolve("latest.log"));
        if (log == null) return;
        Normal now = of(List.of(log)), old = load(folder);
        Properties p = new Properties();
        merge(old.spam(), now.spam()).forEach((k, v) -> p.setProperty("spam." + k, Integer.toString(v)));
        merge(old.assets(), now.assets()).forEach((k, v) -> p.setProperty("assets." + k, Integer.toString(v)));
        Files.createDirectories(folder);
        try (Writer out = Files.newBufferedWriter(folder.resolve(FILE), StandardCharsets.UTF_8)) {
            p.store(out, "Check Engine: log noise while the pack worked (normal for this pack)");
        }
    }

    public static Normal load(Path folder) {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(folder.resolve(FILE), StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            return Normal.NONE;
        }
        Map<String, Integer> spam = new TreeMap<>(), assets = new TreeMap<>();
        for (String k : p.stringPropertyNames()) {
            try {
                int v = Integer.parseInt(p.getProperty(k).strip());
                if (k.startsWith("spam.")) spam.put(k.substring(5), v);
                else if (k.startsWith("assets.")) assets.put(k.substring(7), v);
            } catch (NumberFormatException ignored) {
                // a hand-edited line: skip it
            }
        }
        return new Normal(spam, assets, "when the pack worked");
    }

    /**
     * Normal for this pack: the saved launches that worked, or else the launches before the newest (when the pack was
     * never started with Check Engine). With one log there's nothing to compare, so nothing counts as normal.
     */
    public static Normal forPack(Path gameDir, List<String> logsNewestFirst) {
        Normal saved = gameDir == null ? Normal.NONE : load(gameDir.resolve("checkengine"));
        if (!saved.isEmpty() || logsNewestFirst.size() < 2) return saved;
        Normal earlier = of(logsNewestFirst.subList(1, logsNewestFirst.size()));
        return new Normal(earlier.spam(), earlier.assets(), "in earlier launches");
    }

    /** The most noise per mod in any one of these logs (each one launch). */
    static Normal of(List<String> logs) {
        Map<String, Integer> spam = new TreeMap<>(), assets = new TreeMap<>();
        for (String log : logs) {
            LogSpam.analyze(List.of(log)).sources().forEach(s -> spam.merge(s.mod(), s.lines(), Math::max));
            LogProblems.analyze(List.of(log), null).missingAssets().forEach((k, v) -> assets.merge(k, v, Math::max));
        }
        return new Normal(spam, assets, "when the pack worked");
    }

    private static Map<String, Integer> merge(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> out = new TreeMap<>(a);
        b.forEach((k, v) -> out.merge(k, v, Math::max));
        return out;
    }
}
