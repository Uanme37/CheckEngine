# Pack Doctor

Tells pack makers what's broken in their pack, in plain English. NeoForge 1.21.1. No gameplay changes.

## Layout
- `core/` plain Java scanner (reads jars + `META-INF/neoforge.mods.toml`), no Minecraft code
- `mod/` NeoForge mod: boot warning screen + `/packdoctor scan`
- `cli/` standalone jar: crash translator, server pack builder
- `test-packs/` notes on packs we test against

## Build
Java 21.
```
gradlew build              # everything
gradlew :core:test         # core tests
gradlew :mod:runClient     # launch the game with the mod
gradlew :cli:jar           # cli/build/libs/packdoctor-cli-*.jar
```

## Use
In game: problems show on a screen before the title screen (saved to `packdoctor/boot-report.txt`).
Ops can run `/packdoctor scan` to check recipes, loot tables and FTB Quests (saved to `packdoctor/scan-report.txt`).

Without the game: drag a modpack folder onto `Check Pack.bat` (keep it next to `packdoctor-cli-*.jar`).
It finds the Java 21 that CurseForge or the Minecraft launcher already installed. Or run it yourself with Java 21:
```
java -jar packdoctor-cli.jar scan <pack folder> [--server]    # what's broken in the mods folder
java -jar packdoctor-cli.jar crash <pack folder or crash-*.txt> # explain the newest crash in plain English
java -jar packdoctor-cli.jar serverpack <pack folder> [--out <new folder>] [--zip] [--dry-run]  # server copy without client-only mods
java -jar packdoctor-cli.jar compare <pack folder> <server folder or server pack .zip>  # why players can't join
```
