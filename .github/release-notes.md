Check Engine tells you what's broken in a modpack, in plain English. It never changes gameplay.

## Check a pack without starting the game (easiest)
1. Download **CheckEngine-v0.14.0.zip** below and unzip it anywhere.
2. Drag your modpack folder (for example a CurseForge instance folder) onto **Check Pack.bat**.
3. Read the report. It lists duplicate mods, missing dependencies, version mismatches and client-only mods that would break a server.

It uses the Java that CurseForge or the Minecraft launcher already installed, so there's nothing else to set up. Keep `Check Pack.bat` and `checkengine-cli-0.14.0.jar` in the same folder.

The checker can also explain a crash, build a server copy of a pack without the client-only mods, and compare a pack with a server to find out why players can't join:
```
java -jar checkengine-cli-0.14.0.jar scan <pack folder> [--server]
java -jar checkengine-cli-0.14.0.jar crash <pack folder or crash-*.txt>
java -jar checkengine-cli-0.14.0.jar serverpack <pack folder> [--out <new folder>] [--zip]
java -jar checkengine-cli-0.14.0.jar compare <pack folder> <server folder or server pack .zip>
java -jar checkengine-cli-0.14.0.jar export <pack folder>
java -jar checkengine-cli-0.14.0.jar stripmine <pack folder>
java -jar checkengine-cli-0.14.0.jar logs <pack folder>
java -jar checkengine-cli-0.14.0.jar materials <pack folder>
java -jar checkengine-cli-0.14.0.jar notes <pack folder> [done]
java -jar checkengine-cli-0.14.0.jar saferemove <pack folder> <mod id>
java -jar checkengine-cli-0.14.0.jar exportcheck <export .zip or .mrpack>
```

## Or use it as a mod
Put the jar for your version in the pack's `mods` folder:
- **checkengine-neoforge-26.1-0.14.0.jar** for NeoForge 26.1.2 and newer 26.1.x
- **checkengine-neoforge-1.21.1-0.14.0.jar** for NeoForge 1.21.1
- **checkengine-forge-1.20.1-0.14.0.jar** for Forge 1.20.1

Problems show on a screen before the title screen and are saved to `checkengine/boot-report.txt`. Server ops can run `/checkengine scan` to find quests, recipes and loot tables that point at mods that aren't installed, plus recipe clashes and items nothing gives, and `/checkengine startup` to see how long the last startup took and which mods were slowest.

## What's new in 0.14.0
**Recipe check**, in `/checkengine scan`:
- **Recipe clashes:** two crafting recipes that take exactly the same items in the same grid (or its mirror image, or in any order) but make different things. Only one of each pair can ever be crafted. The fix is to remove one with KubeJS or CraftTweaker, or to add Polymorph. Recipes that only share some inputs (any planks vs one kind of planks) are listed as info.
- **Recipes nobody can craft:** a slot asks for a tag that has no items in it.
- Also listed, as info: quest items and crafting ingredients that no recipe, loot table or quest reward gives (often mob drops or spells, so worth a look rather than a fix).
- Fast in big packs: ATM10 (46,000 crafting recipes) scans in seconds.

On 1.21.1 and Forge 1.20.1 it checks the recipes the game actually loaded, so KubeJS and CraftTweaker changes count. On 26.1 it reads the recipe files, so script changes aren't seen there yet.
