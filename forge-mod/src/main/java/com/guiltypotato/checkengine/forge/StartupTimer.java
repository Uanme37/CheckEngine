package com.guiltypotato.checkengine.forge;

import com.guiltypotato.checkengine.core.scan.StartupTimes;
import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.registries.RegisterEvent;

/**
 * Times each mod's own startup work: every lifecycle event gets a first and a last listener on each mod's event
 * bus, and the time between them is that mod's handlers. Also splits the whole launch into phases.
 */
public final class StartupTimer {
    private static final long JVM_START = ManagementFactory.getRuntimeMXBean().getStartTime();
    private static final List<Class<? extends Event>> EVENTS = List.of(RegisterEvent.class,
            FMLCommonSetupEvent.class, FMLClientSetupEvent.class, FMLDedicatedServerSetupEvent.class,
            InterModEnqueueEvent.class, InterModProcessEvent.class, FMLLoadCompleteEvent.class);

    private static final Map<String, Long> started = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> nanos = new ConcurrentHashMap<>();
    private static volatile long modsStartedAt;
    private static volatile long loadCompleteAt;
    private static volatile boolean finished;

    private StartupTimer() {}

    /** Called from our mod's constructor, while mods are being built. */
    static void install(IEventBus ourBus) {
        modsStartedAt = System.currentTimeMillis();
        ourBus.addListener(EventPriority.HIGHEST, false, RegisterEvent.class, e -> attachOnce(ourBus));
    }

    private static volatile boolean attached;

    private static synchronized void attachOnce(IEventBus ourBus) {
        if (attached) return;
        attached = true;
        try {
            ModList.get().forEachModContainer((id, container) -> {
                if (!(container instanceof FMLModContainer fml)) return;
                IEventBus bus = fml.getEventBus();
                if (bus == ourBus) return; // ours is busy posting this event right now
                for (Class<? extends Event> type : EVENTS) watch(bus, id, type);
            });
        } catch (RuntimeException e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't time mod startup", e);
        }
    }

    private static <T extends Event> void watch(IEventBus bus, String id, Class<T> type) {
        String key = id + '|' + type.getName();
        bus.addListener(EventPriority.HIGHEST, false, type, e -> started.put(key, System.nanoTime()));
        bus.addListener(EventPriority.LOWEST, false, type, e -> {
            Long start = started.remove(key);
            if (start != null) nanos.computeIfAbsent(id, k -> new LongAdder()).add(System.nanoTime() - start);
            if (type == FMLLoadCompleteEvent.class) loadCompleteAt = Math.max(loadCompleteAt, System.currentTimeMillis());
        });
    }

    /** Called once, when the title screen opens or the dedicated server is ready. */
    public static void finish(boolean server) {
        if (finished) return;
        finished = true;
        try {
            long now = System.currentTimeMillis();
            long loaded = loadCompleteAt > 0 ? loadCompleteAt : now;
            // Every mod pays the same small cost just for the events passing by (about 35 ms); take it off.
            long baseline = nanos.values().stream().mapToLong(LongAdder::sum).min().orElse(0);
            List<StartupTimes.ModTime> mods = new ArrayList<>();
            nanos.forEach((id, n) -> mods.add(new StartupTimes.ModTime(id, ModList.get().getModContainerById(id)
                    .map(c -> c.getModInfo().getDisplayName()).orElse(id), (n.sum() - baseline) / 1_000_000)));
            StartupTimes.Data data = new StartupTimes.Data(now - JVM_START, modsStartedAt - JVM_START,
                    loaded - modsStartedAt, now - loaded, mods,
                    LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).toString());
            StartupTimes.save(BootCheck.outputFolder(), data, server);
            CheckEngine.LOGGER.info("Check Engine: {} in {} (details: {})", server ? "server ready" : "title screen",
                    StartupTimes.time(data.totalMs()), BootCheck.outputFolder().resolve(StartupTimes.REPORT_FILE));
        } catch (Exception e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't save startup times", e);
        }
    }
}
