package com.guiltypotato.checkengine.core.scan;

import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explains a "mod mismatch" kick. The loader only says which network channels didn't line up; channels are named
 * after the mod that owns them, so this turns them back into "add / remove / update mod X".
 */
public final class JoinMismatch {
    public static final String MISSING_ON_PLAYER = ServerCompare.MISSING_ON_PLAYER;
    public static final String MISSING_ON_SERVER = ServerCompare.MISSING_ON_SERVER;
    public static final String VERSION_MISMATCH = ServerCompare.VERSION_MISMATCH;
    public static final String PLATFORM_MISMATCH = ServerCompare.PLATFORM_MISMATCH;
    public static final String PACK_VERSION = "pack-version-mismatch";
    public static final String OTHER = "join-other";

    /** Channels owned by the loader or Minecraft itself, not by a mod. */
    private static final Set<String> PLATFORM = Set.of("minecraft", "neoforge", "forge", "fml", "c");
    /** This many mods out of line almost always means the player and server have different pack versions. */
    static final int PACK_VERSION_HINT = 5;

    private JoinMismatch() {}

    public enum Kind {
        /** The server needs this channel and the player doesn't have it. */
        MISSING_ON_PLAYER,
        /** The player needs this channel and the server doesn't have it. */
        MISSING_ON_SERVER,
        /** Both have it, but at different versions or set up differently. */
        DIFFERENT,
        /** Anything else; {@link Failure#text()} has the loader's own words. */
        OTHER
    }

    /**
     * One channel the loader rejected.
     *
     * @param modName       the mod's display name when the loader knows it (Forge does), else null
     * @param serverVersion the server's version of the mod when known, else null
     */
    public record Failure(String channel, Kind kind, String text, String modName, String serverVersion) {
        public Failure(String channel, Kind kind, String text) {
            this(channel, kind, text, null, null);
        }

        public String namespace() {
            int colon = channel.indexOf(':');
            return colon < 0 ? channel : channel.substring(0, colon);
        }
    }

    /** A mod the player has installed. */
    public record PlayerMod(String name, String version) {}

    /**
     * @param failures   what the loader reported
     * @param playerMods the player's mods by id
     * @param loader     "NeoForge" or "Forge", for messages
     */
    public static List<Finding> explain(List<Failure> failures, Map<String, PlayerMod> playerMods, String loader) {
        // One finding per mod, even when it has several channels.
        Map<String, Failure> byMod = new LinkedHashMap<>();
        for (Failure f : failures) {
            String id = PLATFORM.contains(f.namespace()) ? "" : f.namespace();
            Failure old = byMod.get(id);
            if (old == null || rank(f.kind()) < rank(old.kind())) byMod.put(id, f);
        }

        List<Finding> out = new ArrayList<>();
        byMod.forEach((id, f) -> out.add(id.isEmpty() ? platform(loader) : explain(id, f, playerMods.get(id))));
        out.sort(Comparator.comparing(Finding::severity).thenComparing(Finding::title));

        long mods = out.stream().filter(f -> !f.code().equals(PLATFORM_MISMATCH) && !f.code().equals(OTHER)).count();
        if (mods >= PACK_VERSION_HINT) {
            out.add(0, new Finding(Severity.WARNING, PACK_VERSION,
                    "Your pack is probably a different version than the server's",
                    mods + " mods don't match the server. That usually means the server was updated to a new "
                            + "pack version and your game wasn't (or the other way round).",
                    "Update (or roll back) the pack in your launcher to the version the server runs, then try again.",
                    List.of()));
        }
        return out;
    }

    /** Lower is more useful to report when one mod has several broken channels. */
    private static int rank(Kind k) {
        return switch (k) {
            case MISSING_ON_PLAYER -> 0;
            case MISSING_ON_SERVER -> 1;
            case DIFFERENT -> 2;
            case OTHER -> 3;
        };
    }

    private static Finding explain(String id, Failure f, PlayerMod mine) {
        String name = mine != null ? mine.name() : f.modName() != null && !f.modName().isBlank() ? f.modName() : id;
        String serverHas = f.serverVersion() == null || f.serverVersion().isBlank() ? null : f.serverVersion();
        // The server has a channel we don't, but we do have the mod: it's a newer or older copy.
        Kind kind = f.kind() == Kind.MISSING_ON_PLAYER && mine != null ? Kind.DIFFERENT : f.kind();
        return switch (kind) {
            case MISSING_ON_PLAYER -> new Finding(Severity.ERROR, MISSING_ON_PLAYER, "You don't have: " + name,
                    "The server runs " + (name.equals(id) ? "a mod called \"" + id + "\"" : name)
                            + (serverHas == null ? "" : " " + serverHas) + ", and it isn't in your game.",
                    "Install " + name + (serverHas == null ? " (the same version the server uses)" : " " + serverHas)
                            + ", or get the pack version the server runs.",
                    List.of());
            case MISSING_ON_SERVER -> new Finding(Severity.ERROR, MISSING_ON_SERVER, "The server doesn't have: " + name,
                    "You have " + describe(name, mine) + ", but the server doesn't have it (or has a version that "
                            + "can't talk to yours).",
                    "Remove " + name + " from your mods folder, or ask the server owner to add the same version.",
                    List.of());
            case DIFFERENT -> new Finding(Severity.ERROR, VERSION_MISMATCH, "Different version than the server: " + name,
                    "You have " + describe(name, mine) + ", but the server has "
                            + (serverHas == null ? "a different version of it." : serverHas + "."),
                    serverHas == null ? "Use the same version of " + name + " as the server."
                            : "Install " + name + " " + serverHas + " to match the server.", List.of());
            case OTHER -> new Finding(Severity.ERROR, OTHER, name + " doesn't match the server",
                    f.text() == null || f.text().isBlank() ? "The game didn't say why." : f.text(),
                    "Make sure you and the server have the same version of " + name + ".", List.of());
        };
    }

    private static String describe(String name, PlayerMod mine) {
        return mine == null || mine.version() == null ? name : name + " " + mine.version();
    }

    private static Finding platform(String loader) {
        return new Finding(Severity.ERROR, PLATFORM_MISMATCH, "Different " + loader + " version than the server",
                "Some of " + loader + "'s own connection settings don't match the server, which usually means a "
                        + "different " + loader + " version.",
                "Install the " + loader + " version the server uses (your launcher's profile settings).", List.of());
    }

    /** The report file text: what happened, then each finding. */
    public static String toText(String server, List<Finding> findings) {
        StringBuilder sb = new StringBuilder("Check Engine: why you couldn't join");
        if (server != null && !server.isBlank()) sb.append(' ').append(server);
        sb.append("\n\nThe server turned you away because your mods don't match its mods.\n\n");
        Report.appendFindings(sb, findings);
        return sb.toString();
    }
}
