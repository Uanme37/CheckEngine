package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.LogProblems;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Config values the loader threw away (not new or dropped options), and missing textures by mod. */
class LogProblemsTest {
    @Test
    void realTyposOnlyAndMissingTexturesByMod() {
        StringBuilder log = new StringBuilder("""
                [10:00:00] [main/INFO]: Mod list:
                		Create 6.0.4 (create)
                [10:00:01] [modloading-worker-0/WARN]: Configuration file C:\\Packs\\My Pack\\config\\create-server.toml is not correct. Correcting
                [10:00:01] [modloading-worker-0/WARN]: Incorrect key kinetics.maxSpeed was corrected from 99999 to its default, 256.\s
                [10:00:01] [modloading-worker-0/WARN]: Incorrect key kinetics.newOption was corrected from null to its default, true.\s
                [10:00:01] [modloading-worker-0/WARN]: Incorrect key kinetics.oldOption was corrected from true to its default, null.\s
                [10:00:01] [modloading-worker-0/WARN]: Incorrect key kinetics.volume was corrected from 1.0 to its default, 1.\s
                """);
        for (int i = 0; i < 4; i++) {
            log.append("[10:00:05] [Worker-ResourceReload-3/WARN]: Missing textures in model create:cog_").append(i)
                    .append("#inventory:\n");
        }
        log.append("[10:00:05] [Worker-ResourceReload-3/WARN]: Missing textures in model other:thing#inventory:\n");

        LogProblems.Result r = LogProblems.analyze(List.of(log.toString()), null);
        assertEquals(1, r.resets().size(), r.resets().toString());
        assertEquals("config/create-server.toml", r.resets().get(0).file());
        assertEquals("kinetics.maxSpeed", r.resets().get(0).key());
        assertEquals("99999", r.resets().get(0).was());
        assertEquals(Map.of("Create", 4), r.missingAssets(), "one missing texture is noise");
        assertTrue(r.toText().contains("kinetics.maxSpeed was 99999, now 256"), r.toText());
    }
}
