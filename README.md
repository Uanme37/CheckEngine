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
