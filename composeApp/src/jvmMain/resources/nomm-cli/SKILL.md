---
name: nomm-cli
description: Manage Nuclear Option mods with the NOMM command line. List installed mods, install, update, uninstall, enable and disable catalog mods, deploy a local mod build for testing, launch the game, and read its BepInEx log. Use when developing or testing a Nuclear Option (BepInEx) mod, or when changing which mods are installed.
---

# NOMM command line

NOMM (Nuclear Option Mod Manager) manages the game's BepInEx mods. Use its CLI, `nomm`, instead of editing
`BepInEx/plugins` yourself. NOMM keeps metadata beside each mod, moves disabled mods to `BepInEx/disabledPlugins`,
resolves dependencies, keeps the NOMM app in sync, and refuses changes while the game has the files locked.

## Running it

- Installed NOMM: `nomm` sits beside the NOMM app (`nomm.exe` in the install folder on Windows,
  `bin/nomm` in the install folder of the Linux packages). Add that folder to `PATH` or call it by full path.
- Any NOMM executable also works as `<NOMM executable> cli <command>`.
- From a NOMM checkout: `./gradlew :composeApp:run --args="cli <command>"`.

Pass `--json` whenever you parse the output. stdout then holds exactly one JSON object:

```json
{"ok": true, "command": "list", "data": ..., "error": null, "warnings": []}
{"ok": false, "command": "enable", "data": null, "error": {"code": "not_found", "message": "..."}, "warnings": []}
```

Progress messages go to stderr. Read `warnings` too: they report problems that didn't stop the command,
such as a missing dependency.

| Exit code | `error.code`       | Meaning                                                       |
|-----------|--------------------|---------------------------------------------------------------|
| 0         |                    | Success                                                       |
| 1         | `failed`           | The operation failed (see the message)                        |
| 2         | `usage`, `invalid_argument` | Bad command, option or value                         |
| 3         | `not_found`        | No such mod, version, file or log                             |
| 4         | `environment`      | Game folder not found, or BepInEx not installed               |
| 5         | `game_running`     | The game is running; retry with `--wait-for-game` or later    |
| 6         | `download_failed`  | Catalog or mod download failed                                |
| 7         | `busy`             | Another NOMM process holds the mod lock; retry                |

## Commands

| Command | What it does |
|---|---|
| `nomm status` | Game folder, BepInEx, whether the game runs, catalog and mod counts. Always exits 0; check `data.ready`. |
| `nomm list [--enabled\|--disabled] [--updates] [--problems] [--local]` | Installed mods with version, source, update and problems. |
| `nomm info <id>` | One mod: install state plus its catalog entry (versions, dependencies, links). |
| `nomm search <query> [--limit N]` | Search the catalog. |
| `nomm install <id>[@version]... [--no-deps] [--replace]` | Install catalog mods and missing dependencies. Installs BepInEx first if needed. |
| `nomm update <id>... \| --all` | Update catalog mods. Never touches local builds. |
| `nomm uninstall <id>...` | Delete mods. For a local build, deletes the installed copy, never the build output. |
| `nomm enable <id>...` / `nomm disable <id>...` | Toggle mods. Enabling also enables the mod it extends; disabling also disables its addons. |
| `nomm dev register <path> [--version V] [--id ID] [--name N] [--dependency ID[@V]]... [--disabled] [--replace]` | Deploy a local build (see below). |
| `nomm launch [--wait] [--timeout S]` | Start the game through Steam. `--wait` waits for the process. |
| `nomm logs [--source bepinex\|unity] [-n N] [--grep REGEX] [--follow] [--timeout S]` | Read `BepInEx/LogOutput.log` or Unity's `Player.log`. |
| `nomm bepinex install` | Install BepInEx 5. |
| `nomm skill [--output DIR]` | Print this guide, or write it to `DIR/SKILL.md`. |

Global options: `--json`, `--game-dir <path>` (override NOMM's game folder), `--offline` (use the cached
catalog), `--wait-for-game` (wait for the game to exit instead of failing), `--verbose` (NOMM's log on stderr).
`nomm help <command>` shows a command's options.

## Testing a mod you're building

`dev register` copies a build into the game and writes the metadata that makes NOMM treat it as a mod. The
build can be a plugin DLL, a folder of build output, or a `.zip` (including a `BepInEx/plugins/<Mod>/` layout).
The mod id defaults to the DLL's file name. Pass `--id` for a folder or zip, and match the id you plan to use
in the NOMM catalog.

1. Build the mod with its own tooling (for example `dotnet build -c Release`).
2. First time:
   `nomm dev register path/to/MyMod.dll --version 0.1.0 --name "My Mod" --dependency SomeLib --json --wait-for-game`
3. After each rebuild: `nomm dev register path/to/MyMod.dll --json --wait-for-game`. This keeps the version, name,
   dependencies and enabled state from the first registration unless you pass new values. Pass `--version`
   when the version changes.
4. `nomm launch --wait --json`, then play or ask the user to test.
5. `nomm logs --follow --timeout 300 --grep "MyMod|Exception" --json` streams matching lines (one JSON object
   per line, then the result envelope) until the game exits or the timeout passes. Without `--follow`, `nomm logs -n 200`
   prints the end of the last session's log.
6. Fix, rebuild, and go back to step 3.

The game locks plugin files while it runs, so changes fail with exit code 5. `--wait-for-game` queues the change
until the user closes the game. Run it in the background if the user is still playing, and keep only one such
command waiting at a time.

Check `nomm list --json` afterwards: local builds show `"source": "local"` and `localBuildSource`, and any
unmet dependencies appear in `problems`. Local builds never get catalog updates, so `nomm update --all` can't
overwrite work in progress. `install --replace` swaps a local build for the catalog version when you're done.

## Rules

- Change mods through `nomm`, not by moving files in `BepInEx/plugins` or `disabledPlugins`.
- Don't disable or uninstall `NOSMR` or `NOMM-Integration`; NOMM manages them.
- The NOMM app, if open, refreshes by itself after each CLI change. There is no need to restart it.
- If the NOMM app is on its Servers screen, Steam may report the game as already running. `nomm launch`
  then reports success but the game doesn't start. Ask the user to leave that screen or launch the game themselves.
- Under Linux (Proton), BepInEx needs `WINEDLLOVERRIDES="winhttp.dll=n,b" %command%` in the game's Steam
  launch options. NOMM warns when it installs BepInEx.
