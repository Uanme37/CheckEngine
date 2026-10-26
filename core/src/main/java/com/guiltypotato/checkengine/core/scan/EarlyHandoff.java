package com.guiltypotato.checkengine.core.scan;

import java.util.concurrent.CompletableFuture;

/**
 * Passes the early check's pack scan to the in-game mod, so the pack is only scanned once per launch. The early
 * plugin and the mod share this class at runtime (the mod doesn't bundle its own copy of core).
 */
public final class EarlyHandoff {
    private static volatile CompletableFuture<Report> scan;

    private EarlyHandoff() {}

    public static void offer(CompletableFuture<Report> report) {
        scan = report;
    }

    /** The early scan, or null when the early plugin didn't run (dev runs, or an old jar layout). */
    public static CompletableFuture<Report> scan() {
        return scan;
    }
}
