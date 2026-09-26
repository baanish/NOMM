# NOMM - Nuclear Option Mod Manager

A Mod Manager for the Game Nuclear Option

# Features
- Automatic BepInEx installation
- Mod searching
- Mod installing and updating
- Mod conflict warnings
- Mod automatic dependency resolution
- Adding Mods from Files
- Mod toggling
- Mod Uninstalling
- Modpack Sharing
- Server List
- Direct Server Connect
- Automatic Server Modpack resolution
- Customizable Theme


## Installation

Download the appropriate file for your platform from the [Latest Release](https://github.com/Combat787/NOMM/releases/latest).

### Windows
* **Portable:** `portable.exe`
* **Installer:** `.msi`

### Linux
* **Debian:** `.deb`
* **Fedora:** `.rpm`
* **Standalone:** `.AppImage`
* **Flatpak:** `.flatpak` *(Flatpak may or may not work)*

To work NOMM retrieves a manifest from [NOMNOM](https://github.com/KopterBuzz/NOMNOM) to get the list of mods. To add your own mods go there.

## Command Line

NOMM also runs as a command line tool, `nomm` (`nomm.exe` next to the app on Windows). It works on the same mods
as the app, and an open app refreshes by itself when the command line changes something. It's meant for scripts
and for coding agents that build and test mods.

```shell
nomm list                                   # installed mods, updates and problems
nomm install SomeMod                        # install from the catalog, with dependencies
nomm enable SomeMod / nomm disable SomeMod
nomm update --all
nomm dev register bin/Release/MyMod.dll --version 0.1.0   # deploy a local build as a mod
nomm launch --wait
nomm logs --follow --grep MyMod             # BepInEx log until the game exits
```

Every command takes `--json` for machine-readable output. `nomm help` lists the commands and exit codes.
`nomm skill` prints a guide that teaches a coding agent to use the command line, in the
[Agent Skills](https://agentskills.io) `SKILL.md` format; `nomm skill --output <dir>` saves it where your
agent looks for skills.

From a checkout, run it with `./gradlew :composeApp:run --args="cli list"`.

## Credits
- [RaylaValdez](https://github.com/RaylaValdez): `Server List Backend and NOSMR`
- Shumatsu: `App Icon`
- [Combat](https://github.com/Combat787): `The Rest`

This is a Kotlin Multiplatform project targeting Desktop (JVM).

### Build and Run Desktop (JVM) Application

To build and run the development version of the desktop app, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :composeApp:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:run
  ```

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
