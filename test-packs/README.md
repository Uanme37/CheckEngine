# Test packs

Notes only. Never copy whole packs in here (jars/zips are gitignored anyway).

| Pack | Why |
| --- | --- |
| TheCreate | ~450 mods, FTB Quests, loot mods, BisectHosting server |
| ATM10 | biggest 1.21.1 kitchen-sink pack |
| Techopolis 3 | KubeJS + LootJS scripts |
| FTB Skies 2 | quest-heavy, custom loot tables |

## Break a copy on purpose
- [x] two versions of one mod (real catch: ATM10 has CC: Tweaked 1.113.1 + 1.120.2)
- [x] mod missing its dependency (Sophisticated Backpacks without Sophisticated Core)
- [x] client-only mod on the server (ATM10 scanned with --server: 39 flagged, AppleSkin allowed)
- [x] quest item from a removed mod (`ftbquests:missing_item`) (real catch: ATM10 productive_bees.snbt has artifacts:crystal_heart)
- [x] loot table / recipe pointing at a removed mod (dev server + test datapack, /packdoctor scan)
- [ ] client and server on different mod versions
