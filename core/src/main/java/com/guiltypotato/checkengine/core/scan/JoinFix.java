package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.json.Json;
import com.guiltypotato.checkengine.core.model.ModInfo;
import com.guiltypotato.checkengine.core.model.ModJar;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.Failure;
import com.guiltypotato.checkengine.core.scan.JoinMismatch.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Fix" button on the can't-join screen. Mods the server doesn't have are switched off (renamed to .disabled,
 * nothing deleted). For mods the server has in another version, or that the player lacks, the player gets the Modrinth
 * page of the server's exact version when Modrinth has it, else the mod's page. Check Engine never downloads mods
 * itself (mod sites don't allow mods that install other mods). Only looks things up when the player presses the button.
 */
public final class JoinFix {
    static final String API = "https://api.modrinth.com/v2/";
    /** Lists what the last fix changed, so it can be undone. */
    public static final String UNDO_FILE = "join-fix-undo.txt";

    private JoinFix() {}

    public enum Action { SWITCH_OFF, OPEN_PAGE }

    /**
     * One thing the fix will do.
     *
     * @param jar the player's jar to switch off (SWITCH_OFF), else null
     * @param url the page to open (OPEN_PAGE)
     */
    public record Step(Action action, String modId, String name, String version, Path jar, String url) {
        public String describe() {
            return switch (action) {
                case SWITCH_OFF -> "Switch off " + name + " (the server doesn't have it)";
                case OPEN_PAGE -> "Get " + name + (version != null ? " " + version : "") + " yourself: " + url;
            };
        }
    }

    /** The internet, swappable for tests. */
    public interface Web {
        /** The body of a 200 response, or null for anything else. */
        String get(String url) throws IOException;
    }

    /**
     * Works out the steps. Needs the internet for anything but switching mods off.
     *
     * @param jars             the player's mod jars by mod id
     * @param minecraftVersion e.g. "1.21.1"
     * @param loader           "neoforge" or "forge", as Modrinth names them
     */
    public static List<Step> plan(List<Failure> failures, Map<String, Path> jars, Map<String, String> names,
                                  String minecraftVersion, String loader, Web web) {
        Map<String, Failure> byMod = new LinkedHashMap<>();
        for (Failure f : failures) byMod.putIfAbsent(f.namespace(), f);
        List<Step> steps = new ArrayList<>();
        byMod.forEach((id, f) -> {
            if (id.isEmpty() || List.of("minecraft", "neoforge", "forge", "fml", "c").contains(id)) return;
            Path jar = jars.get(id);
            String name = names.getOrDefault(id, f.modName() != null && !f.modName().isBlank() ? f.modName() : id);
            String serverVersion = f.serverVersion() == null || f.serverVersion().isBlank() ? null : f.serverVersion();
            Kind kind = f.kind() == Kind.MISSING_ON_PLAYER && jar != null ? Kind.DIFFERENT : f.kind();
            if (kind == Kind.MISSING_ON_SERVER) {
                if (jar != null) steps.add(new Step(Action.SWITCH_OFF, id, name, null, jar, null));
                return;
            }
            if (kind != Kind.MISSING_ON_PLAYER && kind != Kind.DIFFERENT) return;
            String project = null;
            try {
                project = jar != null ? projectByHash(jar, web) : null;
                if (project == null) project = projectBySlug(id, web);
            } catch (IOException | RuntimeException e) {
                project = null;
            }
            if (project != null && serverVersion != null) {
                String versionPage = versionPage(project, serverVersion, minecraftVersion, loader, web);
                if (versionPage != null) {
                    steps.add(new Step(Action.OPEN_PAGE, id, name, serverVersion, null, versionPage));
                    return;
                }
            }
            String page = project != null ? "https://modrinth.com/mod/" + project
                    : "https://modrinth.com/mods?q=" + URLEncoder.encode(id, StandardCharsets.UTF_8);
            steps.add(new Step(Action.OPEN_PAGE, id, name, serverVersion, null, page));
        });
        return steps;
    }

    /** The Modrinth project a jar came from, by its SHA-1, or null. */
    static String projectByHash(Path jar, Web web) throws IOException {
        String body = web.get(API + "version_file/" + sha1(jar) + "?algorithm=sha1");
        if (body == null) return null;
        return Json.parseObject(body).get("project_id") instanceof String s ? s : null;
    }

    /** Many mods use their mod id as the Modrinth slug; the download is checked against the id anyway. */
    static String projectBySlug(String modId, Web web) throws IOException {
        String body = web.get(API + "project/" + URLEncoder.encode(modId, StandardCharsets.UTF_8));
        if (body == null) return null;
        return Json.parseObject(body).get("id") instanceof String s ? s : null;
    }

    /** The Modrinth page of the server's exact version for this Minecraft and loader, or null. */
    private static String versionPage(String project, String serverVersion, String minecraftVersion, String loader, Web web) {
        try {
            String url = API + "project/" + project + "/version?loaders="
                    + URLEncoder.encode("[\"" + loader + "\"]", StandardCharsets.UTF_8) + "&game_versions="
                    + URLEncoder.encode("[\"" + minecraftVersion + "\"]", StandardCharsets.UTF_8);
            String body = web.get(url);
            if (body == null) return null;
            for (Object o : (List<?>) Json.parse(body)) {
                if (!(o instanceof Map<?, ?> v) || !(v.get("version_number") instanceof String number)) continue;
                if (!sameVersion(number, serverVersion) || !(v.get("id") instanceof String versionId)) continue;
                return "https://modrinth.com/mod/" + project + "/version/" + versionId;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    /** "6.0.6" matches "6.0.6", "mc1.21.1-6.0.6" and "6.0.6+mc1.21.1", but not "6.0.60". */
    static boolean sameVersion(String modrinth, String server) {
        if (modrinth.equals(server)) return true;
        for (String part : modrinth.split("[-+_ ]")) if (part.equals(server)) return true;
        return false;
    }

    /**
     * Does the steps that need no player: switch-offs. Writes an undo list.
     *
     * @return one line per step: what was done, or why not
     */
    public static List<String> apply(List<Step> steps, Path modsDir, Path checkEngineFolder) throws IOException {
        List<String> done = new ArrayList<>();
        List<String> undo = new ArrayList<>();
        for (Step s : steps) {
            try {
                switch (s.action()) {
                    case SWITCH_OFF -> {
                        undo.add("enable\t" + switchOff(s.jar()).getFileName());
                        done.add("Switched off " + s.name());
                    }
                    case OPEN_PAGE -> done.add("Get " + s.name() + (s.version() != null ? " " + s.version() : "") + " yourself: " + s.url());
                }
            } catch (IOException | RuntimeException e) {
                done.add("Couldn't fix " + s.name() + ": " + e.getMessage());
            }
        }
        if (!undo.isEmpty()) {
            Files.createDirectories(checkEngineFolder);
            Files.write(checkEngineFolder.resolve(UNDO_FILE), undo, StandardCharsets.UTF_8);
        }
        return done;
    }

    /** Puts back what the last fix changed: re-enables switched-off jars (and switches off jars older versions downloaded). */
    public static int undo(Path modsDir, Path checkEngineFolder) throws IOException {
        Path file = checkEngineFolder.resolve(UNDO_FILE);
        if (!Files.isRegularFile(file)) return 0;
        int n = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", 2);
            if (p.length < 2) continue;
            Path jar = modsDir.resolve(p[1]);
            if (p[0].equals("enable") && Files.isRegularFile(jar)) {
                String name = p[1].endsWith(".disabled") ? p[1].substring(0, p[1].length() - ".disabled".length()) : p[1];
                Files.move(jar, modsDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                n++;
            } else if (p[0].equals("remove") && Files.isRegularFile(jar)) {
                switchOff(jar);
                n++;
            }
        }
        Files.delete(file);
        return n;
    }

    /** Never deletes a player's file: renames it to .disabled. */
    private static Path switchOff(Path jar) throws IOException {
        Path off = jar.resolveSibling(jar.getFileName() + ".disabled");
        return Files.move(jar, off, StandardCopyOption.REPLACE_EXISTING);
    }

    static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** The real internet: Modrinth asks for a User-Agent that names the app. */
    public static Web internet(String checkEngineVersion) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        String agent = "Uanme37/CheckEngine/" + checkEngineVersion + " (github.com/Uanme37/CheckEngine)";
        return new Web() {
            @Override
            public String get(String url) throws IOException {
                try {
                    HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                            .header("User-Agent", agent).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    return r.statusCode() == 200 ? r.body() : null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
        };
    }
}
