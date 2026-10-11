package com.guiltypotato.checkengine.cli;

import com.guiltypotato.checkengine.core.model.Side;
import com.guiltypotato.checkengine.core.scan.PackFolder;
import com.guiltypotato.checkengine.core.strip.StripMine;
import com.guiltypotato.checkengine.core.strip.StripMine.Outcome;
import com.guiltypotato.checkengine.core.strip.StripMine.State;
import com.guiltypotato.checkengine.core.strip.StripMine.Status;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * {@code stripmine <pack folder> [stop]}: walks the player through a Strip Mine search, one launch per round. Runs
 * while the game is closed, so it can switch mods off straight away.
 */
final class StripMineCli {
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

    private StripMineCli() {}

    static int run(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java -jar checkengine-cli.jar stripmine <pack folder> [start|status|stop]");
            return 2;
        }
        try {
            PackFolder pack = PackFolder.locate(Path.of(args[1]), Side.CLIENT);
            Path mods = pack.modsFolder();
            Path game = mods.getParent();
            Path folder = game.resolve("checkengine");
            if (args.length > 2 && args[2].equals("start")) {
                // Scripted start: the game is closed and the crash happens while loading.
                State s = StripMine.start(mods, folder, StripMine.crashTitle(game, 0) == null ? "the crash"
                        : StripMine.crashTitle(game, 0), true, true);
                System.out.println("Strip Mine started: round 1, " + s.disabled().size() + " of " + s.suspects().size()
                        + " mods switched off.");
                return 0;
            }
            if (args.length > 2 && args[2].equals("replan")) {
                State s = StripMine.replan(mods, folder);
                System.out.println(s == null ? "No search." : "Round " + s.round() + ": " + s.disabled().size()
                        + " mods switched off, " + s.suspects().size() + " suspects.");
                return 0;
            }
            if (args.length > 2 && args[2].equals("status")) {
                State s = StripMine.load(folder);
                System.out.println(s == null ? "No search." : s.status() + " round " + s.round() + ", suspects "
                        + s.suspects() + ", off " + s.disabled() + (s.found() != null ? ", FOUND " + s.found() : ""));
                return 0;
            }
            if (args.length > 2 && args[2].equals("stop")) {
                State s = StripMine.stop(mods, folder);
                System.out.println(s == null ? "No Strip Mine search is running." : "Stopped. Every mod is back on.");
                return 0;
            }
            return new StripMineCli().walk(game, mods, folder);
        } catch (IOException e) {
            System.err.println("Strip Mine couldn't run: " + e.getMessage());
            return 2;
        }
    }

    private int walk(Path game, Path mods, Path folder) throws IOException {
        State s = StripMine.load(folder);
        if (s != null && s.active()) {
            System.out.println("Carrying on the Strip Mine search from round " + s.round() + ".");
            s = StripMine.advance(game, mods, folder, null); // puts a pending round in place, or reads the last one
        } else {
            s = begin(game, mods, folder);
            if (s == null) return 0;
        }
        while (s.active()) {
            System.out.println();
            System.out.println("=== Round " + s.round() + " (about " + s.roundsLeft() + " to go) ===");
            System.out.println(s.disabled().size() + " mods are switched off for this test, " + s.suspects().size()
                    + " are still suspects.");
            System.out.println(s.startup()
                    ? "Launch the pack now. If it crashes, or once it reaches the title screen, close it and come back."
                    : "Launch the pack and do what made it crash before. Then close it and come back.");
            String answer = ask("Press Enter when you're done (or type stop to put everything back): ");
            if (answer.equals("stop")) {
                StripMine.stop(mods, folder);
                System.out.println("Stopped. Every mod is back on.");
                return 0;
            }
            State before = s;
            s = StripMine.advance(game, mods, folder, null);
            if (s.active() && s.round() == before.round()) {
                String c = ask("Did it crash? (y = it crashed, n = no crash, stop = put everything back): ");
                if (c.equals("stop")) {
                    StripMine.stop(mods, folder);
                    System.out.println("Stopped. Every mod is back on.");
                    return 0;
                }
                if (c.equals("y") || c.equals("n")) {
                    s = StripMine.advance(game, mods, folder, c.equals("y") ? Outcome.CRASHED : Outcome.WORKED);
                }
            }
        }
        report(s);
        StripMine.clear(folder);
        return s.status() == Status.FOUND ? 1 : 0;
    }

    private State begin(Path game, Path mods, Path folder) throws IOException {
        System.out.println("Strip Mine finds the mod behind a crash by switching off half the mods at a time.");
        System.out.println("You launch the pack once per round; a 400-mod pack takes about 9 rounds.");
        System.out.println("Nothing is deleted: mods are renamed to .disabled and switched back on at the end.");
        String crash = StripMine.crashTitle(game, 0);
        if (crash != null) System.out.println("The crash it's looking for: " + crash);
        String when = ask("Does it crash while loading, before the title screen? (y/n): ");
        if (!when.equals("y") && !when.equals("n")) return null;
        if (!ask("Make sure the game is closed, then start? (y/n): ").equals("y")) return null;
        State s = StripMine.start(mods, folder, crash == null ? "the crash" : crash, when.equals("y"), true);
        System.out.println(s.suspects().size() + " mods to search.");
        return s;
    }

    private static void report(State s) {
        System.out.println();
        if (s.status() == Status.FOUND) {
            System.out.println("FOUND IT: " + s.found());
            System.out.println("With it switched off the pack doesn't crash" + (s.crash().isEmpty() ? "" : " (" + s.crash() + ")") + ".");
            System.out.println("Fix: update it, or remove it. If it's the newest version, send the crash report to its author.");
        } else if (s.status() == Status.STUCK) {
            System.out.println("Strip Mine narrowed it down to these mods, but they need each other, so it can't split them:");
            s.suspects().forEach(f -> System.out.println("  " + f));
        }
        System.out.println("Every mod is switched back on.");
        s.notes().forEach(n -> System.out.println("  " + n));
    }

    private String ask(String prompt) throws IOException {
        System.out.print(prompt);
        String line = in.readLine();
        return line == null ? "stop" : line.strip().toLowerCase();
    }
}
