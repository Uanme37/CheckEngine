# Known Crash Library

`known-crashes.json` (repo root) lists crashes someone already solved, each with its fix. When a crash report or log matches an entry, Crash Doctor and `checkengine crash` show it first as "Known crash: ...".

## How an entry matches

| Field | Meaning |
| --- | --- |
| `all` | every text must appear in the crash report |
| `any` | at least one must appear |
| `none` | none may appear |

The first matching entry wins, so put narrow entries above broad ones. Raise `version` with every change.

## Getting updates

Every jar ships with a copy of the list. Players who add `crash_fix_updates=true` to `checkengine/settings.properties` also get the newest list from GitHub, at most once a day, in the background. It's off by default: Check Engine never goes online on its own.

## Adding a fix

The help file has `suggest-this-fix.txt` with the crash's error and the mod lines of its stack. Open an issue with it and the fix that worked, or a pull request that adds an entry.
