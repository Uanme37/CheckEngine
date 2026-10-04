package com.guiltypotato.packdoctor.core;

import com.guiltypotato.packdoctor.core.model.Side;
import com.guiltypotato.packdoctor.core.scan.PackFolder;
import com.guiltypotato.packdoctor.core.scan.PackScanner;
import com.guiltypotato.packdoctor.core.scan.Report;
import java.io.IOException;
import java.nio.file.Path;

/** Entry point for the shared scanner, used by both the mod and the CLI. */
public final class PackDoctorCore {
    public static final String NAME = "Pack Doctor";

    private PackDoctorCore() {}

    public static String hello() {
        return NAME + " core is alive";
    }

    /** Scans a pack folder (or its mods folder) and returns everything that's wrong with it. */
    public static Report scan(Path packOrModsFolder, Side side) throws IOException {
        PackFolder pack = PackFolder.locate(packOrModsFolder, side);
        return PackScanner.scan(pack.modsFolder(), pack.options());
    }
}
