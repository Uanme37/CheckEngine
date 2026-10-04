package com.guiltypotato.packdoctor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.packdoctor.core.scan.Finding;
import com.guiltypotato.packdoctor.core.scan.JoinMismatch;
import com.guiltypotato.packdoctor.core.scan.JoinMismatch.Failure;
import com.guiltypotato.packdoctor.core.scan.JoinMismatch.Kind;
import com.guiltypotato.packdoctor.core.scan.JoinMismatch.PlayerMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JoinMismatchTest {
    private static List<String> codes(List<Finding> f) {
        return f.stream().map(x -> x.code() + ": " + x.title()).toList();
    }

    @Test
    void turnsChannelsIntoMods() {
        List<Finding> f = JoinMismatch.explain(List.of(
                new Failure("create:main", Kind.MISSING_ON_PLAYER, null),
                new Failure("jei:sync", Kind.MISSING_ON_SERVER, null),
                new Failure("sophisticatedcore:main", Kind.DIFFERENT, null)), Map.of(
                "jei", new PlayerMod("Just Enough Items", "19.21.0"),
                "sophisticatedcore", new PlayerMod("Sophisticated Core", "1.2.3")), "NeoForge");
        assertEquals(List.of(
                "missing-on-player: You don't have: create",
                "missing-on-server: The server doesn't have: Just Enough Items",
                "version-mismatch: Different version than the server: Sophisticated Core"),
                codes(f).stream().sorted().toList());
        assertTrue(f.stream().anyMatch(x -> x.detail().contains("Just Enough Items 19.21.0")));
    }

    @Test
    void oneFindingPerModAndKnownModMeansDifferentVersion() {
        // The server's copy of Create has a channel ours doesn't: we have Create, so it's a version difference.
        List<Finding> f = JoinMismatch.explain(List.of(
                new Failure("create:main", Kind.DIFFERENT, null),
                new Failure("create:new_thing", Kind.MISSING_ON_PLAYER, null)),
                Map.of("create", new PlayerMod("Create", "6.0.4")), "NeoForge");
        assertEquals(List.of("version-mismatch: Different version than the server: Create"), codes(f));
    }

    @Test
    void loaderChannelsBecomeOnePlatformFinding() {
        List<Finding> f = JoinMismatch.explain(List.of(
                new Failure("neoforge:register", Kind.DIFFERENT, null),
                new Failure("c:tags", Kind.DIFFERENT, null)), Map.of(), "NeoForge");
        assertEquals(List.of("platform-mismatch: Different NeoForge version than the server"), codes(f));
    }

    @Test
    void manyModsMeansDifferentPackVersion() {
        List<Failure> failures = new ArrayList<>();
        for (int i = 0; i < 6; i++) failures.add(new Failure("mod" + i + ":main", Kind.MISSING_ON_PLAYER, null));
        List<Finding> f = JoinMismatch.explain(failures, Map.of(), "Forge");
        assertEquals("pack-version-mismatch", f.get(0).code());
        assertEquals(7, f.size());
    }

    @Test
    void reportText() {
        String text = JoinMismatch.toText("play.example.net", JoinMismatch.explain(
                List.of(new Failure("odd:thing", Kind.OTHER, "Something strange")), Map.of(), "NeoForge"));
        assertTrue(text.startsWith("Pack Doctor: why you couldn't join play.example.net"), text);
        assertTrue(text.contains("Something strange"), text);
    }
}
