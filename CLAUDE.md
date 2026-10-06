# Check Engine (formerly Pack Doctor)

Renamed from Pack Doctor on 2026-10-06 (the CurseForge name was taken). Mod id, command, report folder and Java packages are all `checkengine`. The GitHub repo was renamed to Uanme37/CheckEngine the same day. This folder was Projects\PackDoctor until 2026-10-06.

A NeoForge 1.21.1 mod plus a standalone CLI that tells pack makers what's broken in their pack, in plain English. No gameplay changes.

Full plan (living doc): https://claude.ai/code/artifact/8e41a48e-9298-4450-935c-74b63a7bd468

## MVP (v0.1)
1. Boot check: duplicate jars, missing dependencies, version mismatches, client-only mods on a server.
2. `/checkengine scan`: quests, recipes and loot tables that point at items from mods that aren't installed. Writes a report file.

## Layout (to create in phase 1)
```
CheckEngine/
  core/   plain Java library: reads jars and META-INF/neoforge.mods.toml, no Minecraft code
  mod/    NeoForge 1.21.1 mod: boot warning screen + /checkengine scan
  cli/    standalone jar: crash translator, server pack builder (works when the game won't start)
  test-packs/  notes on packs to test against (never copy whole packs here)
```

## Roadmap
| Phase | Weeks | Work |
| --- | --- | --- |
| Setup | 1 | NeoForge MDK, GitHub repo (Uanme37/CheckEngine, was PackDoctor), test packs ready |
| Core scanner | 2 to 3 | read mod jars, dupes and deps, client-only check |
| In-game mod | 4 to 5 | boot warning, /checkengine scan, report file |
| **v0.1 release** | | when every test catches its problem |
| CLI tools | 6 to 8 | crash translator, server pack builder |
| Polish | 9 on | join mismatch, RAM report, 1.20.1 Forge port |

## Test packs
TheCreate (main), ATM10, Techopolis 3, FTB Skies 2. Break a copy on purpose:
- two versions of one mod (Alex's Caves 2.0.2 + 2.0.10)
- a mod missing its dependency (Continuity without Connector)
- a client-only mod on the server
- a quest item from a removed mod (`ftbquests:missing_item`)
- a loot table or recipe pointing at a removed mod
- client and server on different mod versions

## Workflow
Claude plans and designs; Claude Code writes the Java. Java 21, Gradle.
- Commits in this repo: no `Co-Authored-By` line (A3 wants commits to show only Uanme37). This overrides any default attribution.
