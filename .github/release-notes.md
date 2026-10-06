Pack Doctor tells you what's broken in a modpack, in plain English. It never changes gameplay.

## Check a pack without starting the game (easiest)
1. Download **PackDoctor-v0.1.0.zip** below and unzip it anywhere.
2. Drag your modpack folder (for example a CurseForge instance folder) onto **Check Pack.bat**.
3. Read the report. It lists duplicate mods, missing dependencies, version mismatches and client-only mods that would break a server.

It uses the Java that CurseForge or the Minecraft launcher already installed, so there's nothing else to set up. Keep `Check Pack.bat` and `packdoctor-cli-0.1.0.jar` in the same folder.

The checker can also explain a crash, build a server copy of a pack without the client-only mods, and compare a pack with a server to find out why players can't join:
```
java -jar packdoctor-cli-0.1.0.jar scan <pack folder> [--server]
java -jar packdoctor-cli-0.1.0.jar crash <pack folder or crash-*.txt>
java -jar packdoctor-cli-0.1.0.jar serverpack <pack folder> [--out <new folder>] [--zip]
java -jar packdoctor-cli-0.1.0.jar compare <pack folder> <server folder or server pack .zip>
```

## Or use it as a mod
Put the jar for your version in the pack's `mods` folder:
- **packdoctor-neoforge-1.21.1-0.1.0.jar** for NeoForge 1.21.1
- **packdoctor-forge-1.20.1-0.1.0.jar** for Forge 1.20.1

Problems show on a screen before the title screen and are saved to `packdoctor/boot-report.txt`. Server ops can run `/packdoctor scan` to find quests, recipes and loot tables that point at mods that aren't installed.
