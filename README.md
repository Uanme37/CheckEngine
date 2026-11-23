# Check Engine

A warning light for Minecraft modpacks. Before the game loads it tells you what will stop it from starting. After a crash it explains the cause in plain words and offers a fix: undo the new mods, go back to the last version that worked, or find the mod behind the crash. No gameplay changes.

Loaders: NeoForge 1.21.1, NeoForge 26.1.2, Forge 1.20.1.

**Download:** [CurseForge](https://www.curseforge.com/minecraft/mc-mods/check-engine). Each GitHub release also has the files and the standalone checker.

## Use
- In game: problems show before the loading screen and after a crash. Reports are saved in the modpack's `checkengine/` folder.
- `/checkengine scan` (ops): recipes, loot tables and quests that point to missing mods.
- `/checkengine export`: one zip with the reports and logs to share when asking for help. Names, addresses and logins are removed from the copy. Nothing is sent anywhere.

## Without the game
Drag a modpack folder onto `Check Pack.bat` (keep it next to `checkengine-cli-*.jar`). Or run it with Java 21:
```
java -jar checkengine-cli.jar scan <modpack folder> [--server]
java -jar checkengine-cli.jar crash <modpack folder or crash report>
java -jar checkengine-cli.jar compare <modpack folder> <server folder or server .zip>
java -jar checkengine-cli.jar serverpack <modpack folder> [--out <folder>] [--zip]
java -jar checkengine-cli.jar exportcheck <export .zip or .mrpack>
```
Run it with `--help` for the full list.

## For modpack makers
Put `config/checkengine-pack.properties` in your modpack. `config/checkengine-pack.example.properties` lists every setting. You can:
- switch parts off: `ignition`, `mechanic`, `pit_pass`, `misfire`, `scrapyard`, `pit_crew`
- `mode=modpack`: players only see what stops the game, crashes and world backup warnings
- `pack_name` and `support_link`: your modpack's name and where players get help

## Build
Java 21, plus Java 25 for the 26.1 build.
```
gradlew build        # all jars
gradlew :core:test   # tests
```
Release jars: `early/`, `early-26/` and `early-forge/` `build/libs` (the mod is packed inside).

## License
MIT, see [LICENSE](LICENSE).
