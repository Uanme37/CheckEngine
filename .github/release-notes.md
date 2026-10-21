Check Engine tells you what's broken in a modpack, in plain English. It never changes gameplay.

## Check a pack without starting the game (easiest)
1. Download **CheckEngine-v0.18.0.zip** below and unzip it anywhere.
2. Drag your modpack folder (for example a CurseForge instance folder) onto **Check Pack.bat**.
3. Read the report. It lists duplicate mods, missing dependencies, version mismatches and client-only mods that would break a server.

It uses the Java that CurseForge or the Minecraft launcher already installed, so there's nothing else to set up. Keep `Check Pack.bat` and `checkengine-cli-0.18.0.jar` in the same folder.

The checker can also explain a crash, build a server copy of a pack without the client-only mods, and compare a pack with a server to find out why players can't join:
```
java -jar checkengine-cli-0.18.0.jar scan <pack folder> [--server]
java -jar checkengine-cli-0.18.0.jar crash <pack folder or crash-*.txt>
java -jar checkengine-cli-0.18.0.jar serverpack <pack folder> [--out <new folder>] [--zip]
java -jar checkengine-cli-0.18.0.jar compare <pack folder> <server folder or server pack .zip>
java -jar checkengine-cli-0.18.0.jar export <pack folder>
java -jar checkengine-cli-0.18.0.jar stripmine <pack folder>
java -jar checkengine-cli-0.18.0.jar logs <pack folder>
java -jar checkengine-cli-0.18.0.jar materials <pack folder>
java -jar checkengine-cli-0.18.0.jar notes <pack folder> [done]
java -jar checkengine-cli-0.18.0.jar saferemove <pack folder> <mod id>
java -jar checkengine-cli-0.18.0.jar exportcheck <export .zip or .mrpack>
```

## Or use it as a mod
Put the jar for your version in the pack's `mods` folder:
- **checkengine-neoforge-26.1-0.18.0.jar** for NeoForge 26.1.2 and newer, including 26.2
- **checkengine-neoforge-1.21.1-0.18.0.jar** for NeoForge 1.21.1
- **checkengine-forge-1.20.1-0.18.0.jar** for Forge 1.20.1

Problems show on a screen before the title screen and are saved to `checkengine/boot-report.txt`. Server ops can run `/checkengine scan` to find quests, recipes and loot tables that point at mods that aren't installed, plus recipe clashes and items nothing gives, and `/checkengine startup` to see how long the last startup took and which mods were slowest.

## What's new in 0.18.0
**"Invalid player data" explained**
- Kicked with "Invalid player data"? Check Engine now says which mod broke the join and how to fix it, right on the kick screen. Known causes so far: old LuckPerms builds, FTB Essentials / FTB Library player data, and a broken player save file (it tells you which file to swap with its .dat_old copy).
- If the kick screen didn't explain it, the next launch shows it on the title screen.
- Kicked from someone else's server: it tells you the reason is in the server's log and what to ask the owner.
- Server owners and singleplayer: `/checkengine join` explains the last kick. Check Pack.bat finds it in the log too.

**Also new**
- **"Fixed!" toast:** when a problem from your last launch is gone, a small popup says what got fixed.
- **Back up before going back a version:** when a mod is older than the last time the pack worked, Check Engine offers a copy of your most recently played worlds before you open one.
