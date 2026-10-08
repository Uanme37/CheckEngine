package com.guiltypotato.checkengine;

import com.guiltypotato.checkengine.core.scan.EarlyHandoff;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.IssueText;
import com.guiltypotato.checkengine.core.scan.Report;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.ModLoadingIssue;

/**
 * While mods load, Check Engine's warnings sit in NeoForge's list of loading issues. If another mod then breaks
 * loading, they show on NeoForge's error screen as clues ("you have two versions of AppleSkin"). If loading works,
 * they're taken out again before NeoForge would show them, and the title screen popup shows them instead.
 */
final class LoadingClues {
    private static final List<ModLoadingIssue> added = new ArrayList<>();

    private LoadingClues() {}

    /** Called while Check Engine is constructed, before most mods have run any code. */
    static void add() {
        CompletableFuture<Report> scan = EarlyHandoff.scan();
        if (scan == null) return; // dev runs: no early plugin
        try {
            for (Finding f : IssueText.clues(scan.get(5, TimeUnit.SECONDS))) {
                ModLoadingIssue issue = ModLoadingIssue.warning("{0}", IssueText.clue(f));
                added.add(issue);
                ModLoader.addLoadingIssue(issue);
            }
        } catch (Exception e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't add loading clues", e);
        }
    }

    /** Loading finished without errors: take the clues back out (NeoForge has no API for this). */
    static void remove() {
        if (added.isEmpty()) return;
        try {
            Field field = ModLoader.class.getDeclaredField("loadingIssues");
            field.setAccessible(true);
            ((List<?>) field.get(null)).removeIf(issue -> added.stream().anyMatch(a -> a == issue));
        } catch (ReflectiveOperationException | RuntimeException e) {
            CheckEngine.LOGGER.warn("Check Engine: couldn't remove loading clues; NeoForge will list them too", e);
        }
        added.clear();
    }
}
