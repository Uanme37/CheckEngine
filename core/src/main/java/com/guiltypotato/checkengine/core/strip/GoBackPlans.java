package com.guiltypotato.checkengine.core.strip;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Go back's plan, worked out off the game's thread: it reads every jar in the mods folder, which takes seconds in a
 * big pack. Started once the last session is settled, and again after Go back or Put it back; screens only take what
 * is ready and never wait for it.
 */
public final class GoBackPlans {
    private static volatile CompletableFuture<GoBack.Plan> plan;

    private GoBackPlans() {}

    /** Works the plan out again, in the background. */
    public static void refresh(Path gameDir, Path checkEngineFolder) {
        CompletableFuture<GoBack.Plan> next = CompletableFuture.supplyAsync(() -> {
            try {
                return GoBack.plan(gameDir, checkEngineFolder);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        });
        plan = next;
    }

    /** The plan when it's ready; null while it's still being worked out (or couldn't be). */
    public static GoBack.Plan now() {
        CompletableFuture<GoBack.Plan> f = plan;
        return f == null ? null : f.getNow(null);
    }

    /** Whether it's still being worked out. */
    public static boolean working() {
        CompletableFuture<GoBack.Plan> f = plan;
        return f != null && !f.isDone();
    }
}
