package com.guiltypotato.checkengine.core.intercept;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.scan.PackSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchInterceptTest {
    @TempDir
    Path folder;

    private void write(String file, long minutesAgo) throws Exception {
        Path p = folder.resolve(file);
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - minutesAgo * 60_000));
    }

    @Test
    void launchThatReachedTheTitleScreenThenCrashedIsNotAStartupProblem() throws Exception {
        // Test Pack: started 19:04, title screen 19:06, crashed later while playing.
        write(LaunchIntercept.LAST_LAUNCH_FILE, 10);
        write(PackSnapshot.FILE, 8);
        assertTrue(LaunchIntercept.reachedTitleScreen(folder));
    }

    @Test
    void launchThatCrashedDuringLoadingNeverSavedAGoodLaunch() throws Exception {
        write(PackSnapshot.FILE, 60); // an older launch that worked
        write(LaunchIntercept.LAST_LAUNCH_FILE, 10);
        assertFalse(LaunchIntercept.reachedTitleScreen(folder));
    }

    @Test
    void noRecordsMeansNotStarted() {
        assertFalse(LaunchIntercept.reachedTitleScreen(folder));
    }

    @Test
    void crashWhileTheGoodLaunchWasStillSavingStillReachedTheTitleScreen() throws Exception {
        // The title screen writes its marker at once; the last good launch (jars, configs) is saved after
        write(PackSnapshot.FILE, 60);
        write(LaunchIntercept.LAST_LAUNCH_FILE, 10);
        write(com.guiltypotato.checkengine.core.scan.GoodLaunch.TITLE, 8);
        assertTrue(LaunchIntercept.reachedTitleScreen(folder));
    }
}
