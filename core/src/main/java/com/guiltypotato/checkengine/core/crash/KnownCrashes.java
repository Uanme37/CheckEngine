package com.guiltypotato.checkengine.core.crash;

import com.guiltypotato.checkengine.core.json.Json;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The Known Crash Library: crashes someone already solved, each with its fix, kept in known-crashes.json in the
 * CheckEngine GitHub repo so anyone can add one. Every jar ships with a copy; players who turn on
 * {@code crash_fix_updates} in checkengine/settings.properties also get newer copies from GitHub.
 */
public final class KnownCrashes {
    /** Where the latest list lives. Raw file, no API, no account. */
    public static final String UPDATE_URL = "https://raw.githubusercontent.com/Uanme37/CheckEngine/main/known-crashes.json";
    /** The downloaded copy, in the checkengine folder. */
    public static final String FILE = "known-crashes.json";
    /** Off by default: Check Engine doesn't go online unless the player says so. */
    public static final String SETTING = "crash_fix_updates";
    public static final String CODE = "known-crash";
    private static final String BUNDLED = "/com/guiltypotato/checkengine/core/crash/known-crashes.json";
    private static final Duration UPDATE_EVERY = Duration.ofDays(1);

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

    /** Whether the player turned on updates (checkengine/settings.properties, crash_fix_updates=true). */
    public static boolean updatesOn(Path checkEngineFolder) {
        Path file = checkEngineFolder.resolve("settings.properties");
        if (!Files.isRegularFile(file)) return false;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
        return "true".equalsIgnoreCase(p.getProperty(SETTING, "false").strip());
    }

    /**
     * Downloads the latest list when updates are on and the copy is more than a day old. Blocking: call it from a
     * background thread. Never throws; a failed download keeps whatever copy there was.
     */
    public static void updateIfDue(Path checkEngineFolder) {
        try {
            if (!updatesOn(checkEngineFolder)) return;
            Path file = checkEngineFolder.resolve(FILE);
            if (Files.isRegularFile(file)
                    && Files.getLastModifiedTime(file).toInstant().plus(UPDATE_EVERY).isAfter(java.time.Instant.now())) {
                return;
            }
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(UPDATE_URL))
                    .timeout(Duration.ofSeconds(10)).header("User-Agent", "CheckEngine").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) return;
            parse(response.body()); // only keep a list that reads
            Files.createDirectories(checkEngineFolder);
            Path tmp = file.resolveSibling(FILE + ".tmp");
            Files.writeString(tmp, response.body(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            use(checkEngineFolder);
        } catch (IOException | RuntimeException e) {
            // offline, blocked, or a bad file: the jar's list still works
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
