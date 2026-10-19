Check Engine tells you what's broken in a modpack, in plain English. It never changes gameplay.

## Check a pack without starting the game (easiest)
1. Download **CheckEngine-v0.16.0.zip** below and unzip it anywhere.
2. Drag your modpack folder (for example a CurseForge instance folder) onto **Check Pack.bat**.
3. Read the report. It lists duplicate mods, missing dependencies, version mismatches and client-only mods that would break a server.

It uses the Java that CurseForge or the Minecraft launcher already installed, so there's nothing else to set up. Keep `Check Pack.bat` and `checkengine-cli-0.16.0.jar` in the same folder.

The checker can also explain a crash, build a server copy of a pack without the client-only mods, and compare a pack with a server to find out why players can't join:
```
java -jar checkengine-cli-0.16.0.jar scan <pack folder> [--server]
java -jar checkengine-cli-0.16.0.jar crash <pack folder or crash-*.txt>
java -jar checkengine-cli-0.16.0.jar serverpack <pack folder> [--out <new folder>] [--zip]
java -jar checkengine-cli-0.16.0.jar compare <pack folder> <server folder or server pack .zip>
java -jar checkengine-cli-0.16.0.jar export <pack folder>
java -jar checkengine-cli-0.16.0.jar stripmine <pack folder>
java -jar checkengine-cli-0.16.0.jar logs <pack folder>
java -jar checkengine-cli-0.16.0.jar materials <pack folder>
java -jar checkengine-cli-0.16.0.jar notes <pack folder> [done]
java -jar checkengine-cli-0.16.0.jar saferemove <pack folder> <mod id>
java -jar checkengine-cli-0.16.0.jar exportcheck <export .zip or .mrpack>
```

## Or use it as a mod
Put the jar for your version in the pack's `mods` folder:
- **checkengine-neoforge-26.1-0.16.0.jar** for NeoForge 26.1.2 and newer, including 26.2
- **checkengine-neoforge-1.21.1-0.16.0.jar** for NeoForge 1.21.1
- **checkengine-forge-1.20.1-0.16.0.jar** for Forge 1.20.1

Problems show on a screen before the title screen and are saved to `checkengine/boot-report.txt`. Server ops can run `/checkengine scan` to find quests, recipes and loot tables that point at mods that aren't installed, plus recipe clashes and items nothing gives, and `/checkengine startup` to see how long the last startup took and which mods were slowest.

## What's new in 0.16.0
**Recipe check**, in `/checkengine scan`:
- **Recipe clashes:** two crafting recipes that take exactly the same items in the same grid (or its mirror image, or in any order) but make different things. Only one of each pair can ever be crafted. The fix is to remove one with KubeJS or CraftTweaker, or to add Polymorph. Recipes that only share some inputs (any planks vs one kind of planks) are listed as info.
- **Recipes nobody can craft:** a slot asks for a tag that has no items in it.
- Also listed, as info: quest items and crafting ingredients that no recipe, loot table or quest reward gives (often mob drops or spells, so worth a look rather than a fix).
- Fast in big packs: 46,000 crafting recipes scan in seconds.

On 1.21.1 and Forge 1.20.1 it checks the recipes the game actually loaded, so KubeJS and CraftTweaker changes count. On 26.1 and 26.2 it reads the recipe files, so script changes aren't seen there yet.

**Known crashes:** Crash Doctor now knows crashes other people already solved and shows their fix first ("Known crash: ..."). The list ships inside the mod. If you want the newest list every day, add `crash_fix_updates=true` to `checkengine/settings.properties`. It's off by default: Check Engine never goes online on its own.

**Fix it on the can't-join screen:** when a server won't let you in because your mods don't match, press Fix it. Check Engine switches off the mods the server doesn't have and gets the server's versions from Modrinth when it can. Nothing is deleted, and one button puts everything back.

**Also new**
- The 26.1 jar now works on Minecraft 26.2 too.
- If another mod covers the Crash Doctor screen after a crash, it now opens again on top of the title screen.
