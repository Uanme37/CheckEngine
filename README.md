# Check Engine

Tells players and pack makers what's broken in a pack, in plain English. NeoForge 1.21.1 and 26.1, Forge 1.20.1. No gameplay changes.

**Download:** [Modrinth](https://modrinth.com/mod/check-engine) or [GitHub releases](https://github.com/Uanme37/CheckEngine/releases/latest) (the GitHub zip also has the standalone checker).

**Pack makers:** [make your pack Check Engine ready](docs/pack-makers.md) (config, badge, tools).

## Layout
- `core/` plain Java scanner (reads jars + `META-INF/neoforge.mods.toml`), no Minecraft code
- `mod/` NeoForge mod: boot warning screen + `/checkengine scan`
- `cli/` standalone jar: crash translator, server pack builder

## Build
Java 21.
```
gradlew build              # everything
gradlew :core:test         # core tests
gradlew :mod:runClient     # launch the game with the mod
gradlew :cli:jar           # cli/build/libs/checkengine-cli-*.jar
```

## Use
In game: problems show on a screen before the title screen (saved to `checkengine/boot-report.txt`).
Ops can run `/checkengine scan` to check recipes, loot tables and FTB Quests (saved to `checkengine/scan-report.txt`).

Without the game: drag a modpack folder onto `Check Pack.bat` (keep it next to `checkengine-cli-*.jar`).
It finds the Java 21 that CurseForge or the Minecraft launcher already installed. Or run it yourself with Java 21:
```
java -jar checkengine-cli.jar scan <pack folder> [--server]    # what's broken in the mods folder
java -jar checkengine-cli.jar crash <pack folder or crash-*.txt> # explain the newest crash in plain English
java -jar checkengine-cli.jar serverpack <pack folder> [--out <new folder>] [--zip] [--dry-run]  # server copy without client-only mods
java -jar checkengine-cli.jar compare <pack folder> <server folder or server pack .zip>  # why players can't join
```

## License
MIT, see [LICENSE](LICENSE).
