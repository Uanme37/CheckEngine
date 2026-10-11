Check Engine tells you what's broken in a modpack, in plain English. It never changes gameplay.

## Check a pack without starting the game (easiest)
1. Download **CheckEngine-v0.11.1.zip** below and unzip it anywhere.
2. Drag your modpack folder (for example a CurseForge instance folder) onto **Check Pack.bat**.
3. Read the report. It lists duplicate mods, missing dependencies, version mismatches and client-only mods that would break a server.

It uses the Java that CurseForge or the Minecraft launcher already installed, so there's nothing else to set up. Keep `Check Pack.bat` and `checkengine-cli-0.11.1.jar` in the same folder.

The checker can also explain a crash, build a server copy of a pack without the client-only mods, and compare a pack with a server to find out why players can't join:
```
java -jar checkengine-cli-0.11.1.jar scan <pack folder> [--server]
java -jar checkengine-cli-0.11.1.jar crash <pack folder or crash-*.txt>
java -jar checkengine-cli-0.11.1.jar serverpack <pack folder> [--out <new folder>] [--zip]
java -jar checkengine-cli-0.11.1.jar compare <pack folder> <server folder or server pack .zip>
java -jar checkengine-cli-0.11.1.jar export <pack folder>
java -jar checkengine-cli-0.11.1.jar stripmine <pack folder>
```

## Or use it as a mod
Put the jar for your version in the pack's `mods` folder:
- **checkengine-neoforge-26.1-0.11.1.jar** for NeoForge 26.1.2 and newer 26.1.x
- **checkengine-neoforge-1.21.1-0.11.1.jar** for NeoForge 1.21.1
- **checkengine-forge-1.20.1-0.11.1.jar** for Forge 1.20.1

Problems show on a screen before the title screen and are saved to `checkengine/boot-report.txt`. Server ops can run `/checkengine scan` to find quests, recipes and loot tables that point at mods that aren't installed, and `/checkengine startup` to see how long the last startup took and which mods were slowest.

## What's new in 0.11.1
- **Fixed (Forge 1.20.1):** on a big pack with a slow disk, the startup check could think libraries packed inside other mods (Kotlin for Forge, Puzzles Access API...) were missing and stop the pack from starting. It now waits longer for its scan, and if the scan isn't done it never blocks, it only logs.
- Strip Mine on packs installed from CurseForge: asks you to turn on "Allow content management" first, so the CurseForge app doesn't download switched-off mods again.

## What's new in 0.11.0
**Strip Mine: find the mod behind a crash nobody can explain.**
- When Crash Doctor can't tell which mod crashed the game, press **Strip Mine**. Check Engine switches off half of your mods, you launch, and the result narrows it down: crash means the bad mod is still on, no crash means it's in the half that was off. A 400-mod pack takes about 9 launches instead of hours of guessing.
- Mods a switched-on mod needs stay on, so a test launch doesn't fail for a different reason.
- Nothing is deleted: mods are renamed to .disabled and switched back on at the end. Your own .disabled files are never touched. Stop any time and everything goes back.
- Just keep pressing Play: Check Engine reads each result by itself (crash or title screen) and sets up the next round when the game closes. At the end a window says which mod it was.
- Also in Check Pack.bat, for packs that can't start at all: it walks you through each round.
- Packs installed from CurseForge: the app keeps them locked and would download switched-off mods again, so Strip Mine first asks you to turn on "Allow content management" (three dots next to Play > Profile options) and only starts once it's on.
- Big packs are handled: mods that need a switched-off mod go off with it, hidden old copies of libraries packed inside other mods don't count, and a test round that fails in a different way is recognised and run again with what it needs. Tested on a 394-mod pack: found the faulty mod in 9 rounds.
- When the pack crashes while loading, the pre-launch window offers Strip Mine and explains how it works before it starts (Back / Start Strip Mine). Stopping a search asks first, so one wrong click doesn't throw it away.
- Fixed: a bullet in the pre-launch window showed as "2022" when there was more than one fix.

## What's new in 0.10.0
**Crash Doctor understands three more kinds of crashes:**
- **"Registered twice" crashes** (like "Duplicate client extensions registration for Block{create:belt}"): it says which thing was added twice and why that happens: the mod is installed twice, something made its setup run twice, or an addon made for a different version adds the same thing. With the fix.
- **Mixin clashes:** when two mods patch the same game code, it now names both of them instead of just one, and tells you to update both or remove one.
- **Random timing crashes:** big packs set mods up in parallel, and sometimes two mods break a shared list at the same moment (like ChillOut with Player Animator). Crash Doctor now says it's random and to just launch again, and the pre-launch window no longer warns "will probably fail again" for it.

## What's new in 0.9.3
- **No more false "Startup will probably fail again":** if the game crashed while you were playing (after the title screen), the pre-launch window no longer warns that startup will fail. Startup works, so there's nothing to stop. Crash Doctor still explains that crash.
- **Better advice for crashes inside a mod:** instead of just "Update X", it now says to update X if there's a newer version, and if you already have the newest, to remove it or report the crash.

## What's new in 0.9.2
- **Important fix:** in big packs, Check Engine could make another mod crash while the game was starting (seen as "Duplicate client extensions registration for Block{create:belt}" with Create). The startup timer now waits until every mod is set up before measuring them. Please update.

## What's new in 0.9.1
- Fixed the pre-launch window: panels now fill its width, and arrows no longer show as a box.

## What's new in 0.9.0
**Your 400-mod pack is broken? Find out in seconds, not after Minecraft finishes loading.**
- **Pre-launch check:** if the pack can't start, a small Check Engine window opens right after you press Play, before the loading screen: what's wrong, what changed since it last worked, and the fix. **Quit** closes the game immediately, **Load anyway** carries on, **Show full error** opens the full report.
- **"Same crash again" warning:** if the last launch crashed and no mods changed since, the window warns you before you sit through the load again.
- It only says "Startup will fail" when the loader's own rules say so. Turn the window off with `launch_popup=false` in `checkengine/settings.properties`.
- On NeoForge 26.1, NeoForge 1.21.1 and Forge 1.20.1. Servers just log it.

## What's new in 0.8.0
**Every feature now works on all three versions: NeoForge 26.1, NeoForge 1.21.1 and Forge 1.20.1.**
- **NeoForge 26.1 and Forge 1.20.1 now explain why a pack won't start right on the loader's error screen** (like 1.21.1 since 0.3.0): the root problem, everything it stops, what changed since the pack last worked, and the fix. Check Engine uses each loader's own rules, so it only speaks up when the loader is really about to stop.
- **Crash Doctor** ("Your last game crashed: here's why, and the fix") and the **help file** (`/checkengine export`) on 26.1 and Forge 1.20.1 too.
- 26.1: clues under any loading error (duplicate mods, broken downloads...), and Crash Doctor understands 26.1's crash reports.
- Still one jar to install for each version.

## What's new in 0.7.0
- **Help file:** one zip with Check Engine's reports, the mod list, the latest log and the newest crash report, ready to drag into Discord or a GitHub issue. Your Windows user name, player name and IP addresses are blanked out first. Make it from Check Pack.bat (it asks at the end), with `/checkengine export` in game (1.21.1), or with the "Make help file" button on the Crash Doctor screen.

## What's new in 0.6.0
- **Crash Doctor (NeoForge 1.21.1):** the launch after a crash opens with "Your last game crashed": what happened in plain English, the fix, and what changed since the pack last worked. Each crash is explained once, and old crash reports from before Check Engine was installed are ignored. Also saved to `checkengine/last-crash.txt`. On servers it goes to the log.
- Crash reports where a mod fails while loading now name the mod and its error ("X crashed while loading") instead of a generic message.

## What's new in 0.5.0
- **"It worked yesterday": what changed?** After every launch that works, Check Engine remembers the pack's mods (`checkengine/last-good-launch.properties`). When the pack breaks later, it lists what was added, removed or updated since then, and links it to the problem: "create is missing. Changed since it last worked: you removed Create 6.0.4." Shown on NeoForge's error screen (1.21.1), under any loading crash, in the in-game report and in Check Pack.bat.

## What's new in 0.4.0
- **Root causes, not error lists.** One problem per cause ("Create is missing"), with every mod it stops from loading, including mods that only need those mods. The biggest problem comes first.
- **The real reason behind a "missing" mod:** if it's actually there as the Fabric build, a build for another Minecraft version, a broken download or a switched-off `.disabled` file, Check Engine says so (with how sure it is) and how to fix it.
- **Clearer version problems:** "Create is too old: update to 6.0 or newer", "too new", or "no single version works for these mods". Mods made for another Minecraft version are grouped into one problem.
- **Pack health** at the top of every report ("394 jars: 392 OK, 2 with warnings, 0 can't load"), and a **FIX THESE FIRST** list.
- Works on NeoForge's error screen (1.21.1), in the in-game reports (all versions) and in Check Pack.bat.

## What's new in 0.3.0
- **NeoForge 1.21.1: Check Engine now explains why a pack won't start, right on NeoForge's error screen.** It runs just before NeoForge checks dependencies, so even when the game refuses to load you see what's wrong and how to fix it, grouped ("3 mods need Create, but it isn't installed"). It uses NeoForge's own rules, so it only speaks up when NeoForge is really about to stop. Also saved to `checkengine/early-report.txt`.
- **Clues when loading fails:** if any mod breaks loading, Check Engine's warnings (duplicate mods, broken downloads...) show under the error with their fix. When the pack loads fine you just get the usual popup.
- Still one jar to install. 26.1 and Forge 1.20.1 get the same early check in a later version.

## What's new in 0.2.0
- **Minecraft 26.1 support** (NeoForge 26.1.2+): startup check, warning screen, `/checkengine scan` and the join mismatch screen.
- `/checkengine scan` understands the newer recipe format (1.21.2+), where ingredients are plain item names.
- New `/checkengine startup`: how long the last startup took and the slowest mods, also saved to `checkengine/startup-report.txt`. The checker shows the same numbers in its report.

## What's new in 0.1.4
- New logo: the Check Engine block, with its warning light and green check, also shown in the Mods menu.

## What's new in 0.1.3
- Check Engine now has its icon and a link to its GitHub page in the Mods menu.
- GitHub links point at the renamed repo (Uanme37/CheckEngine).

## What's new in 0.1.2
- **New name: Check Engine** (it was Pack Doctor; another mod already had that name). The command is now `/checkengine scan` and reports go to the `checkengine` folder.

## What's new in 0.1.1
Fixes found by testing on 29 real modpacks:
- The mod now loads on any NeoForge 21.1. 0.1.0 needed 21.1.252 or newer, so it refused to start in packs like FTB Skies 2, ATM10 and Better MC.
- No more false "wrong NeoForge version" errors for mods made for 1.21 (NeoForge 1.21.1 loads them anyway).
- Respects `config/fml.toml` dependency overrides, and knows Monocle lets Iris run on Embeddium.
- `/checkengine scan` no longer warns about recipes a mod ships for optional mods you don't have (Farming for Blockheads has hundreds); they're one note now.
- `scan --server` gives server memory advice instead of CurseForge steps.
