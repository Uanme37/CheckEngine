package com.guiltypotato.packdoctor.core;

/** Entry point for the shared scanner. Phase 2 fills this in (jar reading, dupes, deps, client-only). */
public final class PackDoctorCore {
    public static final String NAME = "Pack Doctor";

    private PackDoctorCore() {}

    public static String hello() {
        return NAME + " core is alive";
    }
}
