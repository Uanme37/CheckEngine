package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.LogSpam;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which mods flood the log: told apart by "from mod x", their resources in the message, or their logger. */
class LogSpamTest {
    @Test
    void namesTheModsBehindTheNoise() {
        StringBuilder log = new StringBuilder("""
                [14Oct2026 10:01:20.000] [main/INFO] [LOADING]: Mod list:
                		Minecraft 1.21.1 (minecraft)
                		CreateHavenQualities 1.0 (createhaven)
                		Create: Copycats+ 3.0 (copycats)
                		ModernFix 5.0 (modernfix)
                """);
        for (int i = 0; i < 120; i++) {
            log.append("[14Oct2026 10:02:09.683] [Worker-ResourceReload-8/WARN] [net.minecraft.client.resources.model"
                    + ".ModelBakery/]: Unable to load model: 'createhaven:block/cog_").append(i)
                    .append("' referenced from: createhaven:block/cog_").append(i).append("\n");
        }
        for (int i = 0; i < 60; i++) {
            log.append("[14Oct2026 10:01:38.937] [modloading-worker-0/WARN] [mixin/]: Discarding @Unique public method "
                    + "rotate in copycats-neoforge.mixins.json:CopycatBlockMixin from mod copycats because it already "
                    + "exists\n");
        }
        for (int i = 0; i < 70; i++) {
            log.append("[14Oct2026 10:01:26.524] [main/WARN] [net.minecraft.Util/]: Vanilla noise ").append(i)
                    .append('\n');
        }
        log.append("[14Oct2026 10:01:26.524] [main/ERROR] [ModernFix/]: one error\n");

        LogSpam.Result r = LogSpam.analyze(List.of(log.toString()));
        assertEquals(251, r.lines());
        List<LogSpam.Source> noisy = r.noisy();
        assertEquals(List.of("createhaven", "copycats"), noisy.stream().map(LogSpam.Source::mod).toList(),
                "Minecraft itself and a one-off error aren't named");
        assertEquals("CreateHavenQualities", noisy.get(0).name());
        assertEquals("Unable to load model: '*' referenced from: *", noisy.get(0).top());
        assertEquals(120, noisy.get(0).topCount(), "the numbered names count as one message");
        assertTrue(r.toText().contains("CreateHavenQualities: about 120"), r.toText());
    }

    @Test
    void aQuietLogNamesNobody() {
        LogSpam.Result r = LogSpam.analyze(List.of("[10:01:26] [main/INFO]: all good\n"));
        assertTrue(r.isEmpty());
        assertTrue(r.toText().contains("No mod floods the log"));
    }
}
