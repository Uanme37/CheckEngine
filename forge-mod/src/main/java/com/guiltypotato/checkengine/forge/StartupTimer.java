package com.guiltypotato.checkengine.forge;

import com.guiltypotato.checkengine.core.scan.StartupTimes;
import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;

/**
 * Splits the whole launch into phases: finding mods, setting them up, the rest. No time per mod: most of a mod's work
 * happens while it's built, which can't be timed per mod.
 */
public final class StartupTimer {
    private static final long JVM_START = ManagementFactory.getRuntimeMXBean().getStartTime();
    private static volatile long modsStartedAt;
    private static volatile long loadCompleteAt;
    private static volatile boolean finished;

    private StartupTimer() {}

    /** Called from our mod's constructor. */
    static void install(IEventBus ourBus) {
        modsStartedAt = System.currentTimeMillis();
        ourBus.addListener(EventPriority.LOWEST, false, FMLLoadCompleteEvent.class, e -> loadCompleteAt = System.currentTimeMillis());
    }

    /** Called once, when the title screen opens or the dedicated server is ready. */
    public static void finish(boolean server) {
        if (finished) return;
        finished = true;
        try {
            long now = System.currentTimeMillis();
            long loaded = loadCompleteAt > 0 ? loadCompleteAt : now;
            StartupTimes.Data data = new StartupTimes.Data(now - JVM_START, modsStartedAt - JVM_START,
                    loaded - modsStartedAt, now - loaded, List.of(),
                    LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).toString());
            StartupTimes.save(BootCheck.outputFolder(), data, server);
            // The last ~10 starts, for the usual time and the slower notice (not during Strip Mine: half the pack is off)
            // once the scan is done (never waiting for it on the game's thread)
            BootCheck.whenReady(report -> {
                if (com.guiltypotato.checkengine.core.strip.StripMine.running(BootCheck.outputFolder()) != null) return;
                try {
                    com.guiltypotato.checkengine.core.scan.LaunchHistory.record(BootCheck.outputFolder(),
                            com.guiltypotato.checkengine.core.scan.PackSnapshot.of(report.jars(), report.options()),
                            data.totalMs(), data.when());
                } catch (java.io.IOException e) {
                    CheckEngine.LOGGER.warn("Check Engine: couldn't save the launch history", e);
                }
            });
            CheckEngine.LOGGER.info("Check Engine: {} in {} (details: {})", server ? "server ready" : "title screen",
                    StartupTimes.time(data.totalMs()), BootCheck.outputFolder().resolve(StartupTimes.REPORT_FILE));
        } catch (Exception e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't save startup times", e);
        }
    }
}
