package com.guiltypotato.checkengine.core.crash;

import com.guiltypotato.checkengine.core.json.Json;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The Known Crash Library: crashes someone already solved, each with its fix, kept in known-crashes.json in the
 * CheckEngine GitHub repo so anyone can add one. Every jar ships with a copy, and a newer copy saved as
 * checkengine/known-crashes.json is used instead. Check Engine never goes online for it.
 */
public final class KnownCrashes {
    /** A newer copy someone saved in the checkengine folder. */
    public static final String FILE = "known-crashes.json";
    public static final String CODE = "known-crash";
    private static final String BUNDLED = "/com/guiltypotato/checkengine/core/crash/known-crashes.json";

    /**
     * One solved crash.
     *
     * @param all  every one of these must appear in the crash text
     * @param any  at least one of these must appear (empty: no condition)
     * @param none none of these may appear
     */
    public record Entry(String id, String title, String detail, String fix, List<String> all, List<String> any,
                        List<String> none, String link) {
        boolean matches(String text) {
            if (all.isEmpty() && any.isEmpty()) return false;
            for (String s : all) if (!text.contains(s)) return false;
            if (!any.isEmpty() && any.stream().noneMatch(text::contains)) return false;
            for (String s : none) if (text.contains(s)) return false;
            return true;
        }

        public Finding finding() {
            String more = link == null || link.isEmpty() ? "" : "\nMore: " + link;
            return new Finding(Severity.ERROR, CODE, "Known crash: " + title,
                    detail + "\nThis crash is in the Check Engine crash library (" + id + ")." + more, fix, List.of());
        }
    }

    private static volatile KnownCrashes current;

    private final int version;
    private final List<Entry> entries;

    private KnownCrashes(int version, List<Entry> entries) {
        this.version = version;
        this.entries = entries;
    }

    public int version() {
        return version;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** The first entry whose text matches, or null. */
    public Entry match(String crashText) {
        if (crashText == null) return null;
        for (Entry e : entries) if (e.matches(crashText)) return e;
        return null;
    }

    /** The list Crash Doctor uses: the newest copy {@link #use} found, else the one in the jar. */
    public static KnownCrashes current() {
        KnownCrashes c = current;
        if (c == null) current = c = bundled();
        return c;
    }

    /** Picks the newer of the jar's list and a downloaded one in {@code checkEngineFolder}. */
    public static KnownCrashes use(Path checkEngineFolder) {
        KnownCrashes best = bundled();
        if (checkEngineFolder != null) {
            Path file = checkEngineFolder.resolve(FILE);
            if (Files.isRegularFile(file)) {
                try {
                    KnownCrashes downloaded = parse(Files.readString(file, StandardCharsets.UTF_8));
                    if (downloaded.version > best.version) best = downloaded;
                } catch (IOException | RuntimeException ignored) {
                    // a broken download: keep the jar's list
                }
            }
        }
        current = best;
        return best;
    }

    public static KnownCrashes bundled() {
        try (InputStream in = KnownCrashes.class.getResourceAsStream(BUNDLED)) {
            if (in == null) return new KnownCrashes(0, List.of());
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return new KnownCrashes(0, List.of());
        }
    }

    public static KnownCrashes parse(String json) {
        Map<String, Object> root = Json.parseObject(json);
        int version = root.get("version") instanceof Number n ? n.intValue() : 0;
        List<Entry> out = new ArrayList<>();
        if (root.get("crashes") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String id = str(m.get("id"));
                String title = str(m.get("title"));
                String fix = str(m.get("fix"));
                if (id.isEmpty() || title.isEmpty() || fix.isEmpty()) continue;
                out.add(new Entry(id, title, str(m.get("detail")), fix, strings(m.get("all")), strings(m.get("any")),
                        strings(m.get("none")), str(m.get("link"))));
            }
        }
        return new KnownCrashes(version, List.copyOf(out));
    }

    /**
     * A short fingerprint of a crash for someone suggesting a fix: the error line and the first few lines of the
     * stack that come from mods, so a list entry can be written from it.
     */
    public static String signature(String crashText) {
        if (crashText == null) return "";
        String[] lines = crashText.replace("\r", "").split("\n");
        StringBuilder sb = new StringBuilder();
        int frames = 0;
        boolean afterDescription = false;
        for (String l : lines) {
            if (l.startsWith("Description: ")) {
                sb.append(l.strip()).append('\n');
                afterDescription = true;
                continue;
            }
            if (!afterDescription) continue;
            String s = l.strip();
            if (s.isEmpty()) continue;
            if (sb.indexOf("\n") == sb.length() - 1 && !s.startsWith("at ")) {
                sb.append(s).append('\n'); // the error itself
                continue;
            }
            if (s.startsWith("at ") && !s.contains("at java.") && !s.contains("at net.minecraft.") && ++frames <= 5) {
                sb.append(s).append('\n');
            }
            if (s.startsWith("A detailed walkthrough")) break;
        }
        return sb.toString().strip();
    }

    private static String str(Object o) {
        return o instanceof String s ? s.strip() : "";
    }

    private static List<String> strings(Object o) {
        if (o instanceof String s) return List.of(s);
        if (!(o instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object x : list) if (x instanceof String s && !s.isEmpty()) out.add(s);
        return List.copyOf(out);
    }
}
