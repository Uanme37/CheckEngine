package checkengine.core;

import checkengine.core.model.Side;
import checkengine.core.scan.PackFolder;
import checkengine.core.scan.PackScanner;
import checkengine.core.scan.Report;
import java.io.IOException;
import java.nio.file.Path;

/** Entry point for the shared scanner, used by both the mod and the CLI. */
public final class CheckEngineCore {
    public static final String NAME = "Check Engine";

    private CheckEngineCore() {}

    /** Scans a pack folder (or its mods folder) and returns everything that's wrong with it. */
    public static Report scan(Path packOrModsFolder, Side side) throws IOException {
        PackFolder pack = PackFolder.locate(packOrModsFolder, side);
        return PackScanner.scan(pack.modsFolder(), pack.options());
    }
}
