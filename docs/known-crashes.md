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

Every jar ships with a copy of the list, and each Check Engine update brings the newest one. A newer copy saved as `checkengine/known-crashes.json` is used instead. Check Engine never goes online.

## Adding a fix

The help file has `suggest-this-fix.txt` with the crash's error and the mod lines of its stack. Open an issue with it and the fix that worked, or a pull request that adds an entry.
