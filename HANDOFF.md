# Check Engine: handoff for Claude

Read this instead of re-exploring. Plan and history: `Check Engine - Overview.md` (the 0.13 list has [x] marks). Rules: `MC\CLAUDE.md`.

## State (2026-10-14)
- GitHub: v0.13.0 released 2026-10-14 (all of 0.13). Push only when A3 asks.
- CurseForge: nothing new until all of 0.13 is done (A3's call), then one Craft to Exile 2 launch, upload, outreach.
- 0.13 done: silent crash explainer, config changes + checkpoint/undo, export check, world rescue, round in-game lamp, Crash Doctor reads the game log, undo new mods (window + Crash Doctor + Check Pack.bat + CLI `newmods`), downgrade warning, log spam finder (CLI `logs`, Check Pack.bat, help file), same-job mods (scan warning, SameJob.JOBS list), Pack Journal (checkengine/pack-journal.txt, CLI `journal`), duplicate materials (CLI `materials`, Check Pack.bat; reads item model file names), config typo catcher + missing textures (LogProblems, in CLI `logs`), Strip Mine for configs (StripMine State.configs + ConfigStrip; auto when no mod changed but 2+ configs did), Pack Update Notes (PackUpdatedScreen in-game, ported by port.js; CLI `notes`), Safe Remove (core/world WorldUsage reads region files; SafeRemoveScreen; CLI `saferemove`), quiet mode (PackInfo.quiet/hide, visible(), hidesScreen()), badge + guide (docs/pack-makers.md).
- 0.13 tested in real packs: BTC (1.21.1), CTE2 (Forge 1.20.1), ATM11 (26.1.2). Next: CurseForge upload of everything (A3 decides when), outreach.
- CurseForge page: curseforge-page.html (not in git) uses images from docs/page via jsDelivr pinned to a full commit hash (short hashes 404). Paste it at upload.
- 0.13.1 pre-ship test (2026-10-14): ALL dev tests passed on 1.21.1, 26.1 and Forge (A-E, world rescue, config Strip Mine end to end, quiet mode, join mismatch via runJoinClient, every CLI command). Fixed during it: 26.1 locked-jar switch-off (pending file), button rows wrap, window text wraps, THE BAD CONFIG, round plurals. Left: A3 launches BTC/CTE2/ATM11 with 0.13.1 (jars installed), then tag v0.13.1. Helpers: tools/dev/launch.sh, haswin.ps1, shot/click -Title "Check Engine" (-Hover first if a click misses).
- All screens use the dashboard look (heads-up and join mismatch moved 2026-10-14; join mismatch not yet seen in a game).
- BTC help link (support_link) stays empty: A3 has no Discord.
- A3 installed more CurseForge packs (2026-10-14: 1.20.1, 1.21.1, 26.2.1) for false-alarm checks: loop the CLI over all instances after each feature. The 26.1 jar only allows MC [26.1.2,26.2): check 26.2.1 support.

## Build and test
```bash
export JAVA_HOME="C:/Users/Gaming/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2"
./gradlew build -q -Dorg.gradle.jvmargs="-Xmx3G -Djavax.net.ssl.trustStoreType=Windows-ROOT"
```
- 140 tests (core). Count: `grep -ho 'tests="[0-9]*"...' core/build/test-results/test/*.xml`.
- CLI jar: `cli/build/libs/checkengine-cli-<version>.jar` (pick the current version, older jars are still there). `java -jar ... crash "<instance folder>"` is a quick real-data check.
- Real-data checks across installed packs: loop the CLI over `C:\Users\Gaming\curseforge\minecraft\Instances\*` (BTC, CTE2, FTB Skies 2, Soulrend, test for packdoctor).
- `crash-library/` (gitignored, local only): 481 real crash reports, checked by CrashLibraryTest.

## Layout
| Part | Where |
| --- | --- |
| Core (no Minecraft code) | `core/src/main/java/com/guiltypotato/checkengine/core/`: `crash/` (CrashDoctor, CrashTranslator, NativeCrash, WorldRescue, GameLog), `scan/` (PackScanner, Changes, ConfigCheckpoint, ExportCheck, PackInfo, HelpBundle, IssueText), `strip/` (StripMine, StripMineHelper), `intercept/` (Swing window: Page, Popup, LaunchIntercept, SetupCheck) |
| NeoForge 1.21.1 mod | `mod/` (source of truth for screens) |
| NeoForge 26.1 mod | `mod-26/` (screens generated) |
| Forge 1.20.1 mod | `forge-mod/` (screens generated) |
| Early loaders (pre-launch window, Strip Mine skip) | `early/`, `early-26/`, `early-forge/` |
| CLI + Check Pack.bat | `cli/`, `Check Pack.bat` |
| Dev games | `early-test/` (1.21.1), `early-test-26/`, `early-test-forge/`: `runClient` / `runServer` |

## Rules of the code
- In-game screens: edit only `mod/.../client/DashScreen.java`, `CrashDoctorScreen.java`, `StripMineScreens.java`, then run `node tools/dev/port.js` to write the 26.1 and Forge copies. port.js fails loudly ("MISSING ...") when a replaced line changed: update its rule.
- Lamp texture `assets/checkengine/textures/gui/lamp.png` (5 colours x lit/unlit, 128px cells) comes from `tools/dev/LampAtlas.java <checkengine_logo.png> <out1,out2,out3>`.
- Window colours: red = big problem, amber = warning/changes, green = fix/good, blue = settings.
- Crash Doctor findings: `Finding(severity, code, title, detail, fix, files)`; first finding is the headline.
- Never delete player files: rename to `.disabled`, back up first.
- Commits: short, no Co-Authored-By line.

## Dev testing helpers (`tools/dev/`)
- `shot.ps1 -Out x.png` screenshot of the Minecraft window; `click.ps1 -X -Y` (window pixels); `wheel.ps1 -Ticks -3`.
- `ingame.sh <project> <jar> <png>` fakes a finished Strip Mine and screenshots the dev client.
- `rcon.js <server.properties> <command>` for dev servers (Gradle doesn't forward stdin).
- Kill the dev client: PowerShell `Get-Process | ? MainWindowTitle -like "Minecraft*" | Stop-Process`.
- Old checkengine jars in a dev `run/mods` cause a module conflict: park them in `run/mods-parked`.
- A3 may be clicking in the dev game during tests: if clicks go wrong, finish via the CLI.
- Logs in real packs mix two line formats: `[14Oct2026 10:29:28.182]` and `[10:29:28]`. Normal logs are `logs/*.log.gz`; skip `debug-*.log.gz` (huge).

## Gotchas met so far
- 26.1: early class loader closes at shutdown (preload classes used by shutdown hooks); jars are locked while running (StripMineSkipLocator).
- 1.21.1/Forge need `RenderSystem.enableBlend()` around see-through blits; 26.1 blends by itself (port.js strips those lines).
- Forge 1.20.1: `renderBackground(g)` is manual, `new ResourceLocation(...)`, `mouseScrolled` has 3 args.
- Shell: write edit scripts with Write/Edit, not `node -e` with quotes; there's no python. Many files are CRLF.
