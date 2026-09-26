package com.combat.nomm.cli

import com.combat.nomm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.encodeToJsonElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.RandomAccessFile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * NOMM's command line, for scripts and coding agents. Runs headless against the same mod files,
 * lock and catalog cache as the GUI, and tells a running GUI to refresh after each change.
 */
class Cli(
    private val env: CliEnvironment,
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
    fun run(args: List<String>): Int = runBlocking {
        var output = CliOutput("--json" in args, out, err)
        var commandName = "nomm"
        try {
            val parsed = parseArgs(args)
            output = CliOutput(parsed.has("json"), out, err)
            val (command, operands) = resolveCommand(parsed.positionals)
            if (command == null || command.name == "help" || parsed.has("help")) {
                val text = if (command == null || command.name == "help") helpText(operands) else command.helpText()
                output.success("help", result(HelpView(text), text))
                return@runBlocking if (command == null) EXIT_USAGE else EXIT_OK
            }
            commandName = command.name
            parsed.requireOnly(command.name, command.options)
            if (operands.size < command.minOperands || (command.maxOperands != null && operands.size > command.maxOperands)) {
                throw CliUsageException("Usage: nomm ${command.usage}")
            }

            val session = Session(env, parsed, output)
            output.success(command.name, command.run(session, operands))
            EXIT_OK
        } catch (e: CliUsageException) {
            val message = e.message.orEmpty()
            output.failure(commandName, "usage", if (message.startsWith("Usage:")) message else "$message Run `nomm help` for usage.")
            EXIT_USAGE
        } catch (e: ModException) {
            output.failure(commandName, e.kind.code, e.message ?: e.kind.code)
            e.kind.exitCode()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            output.failure(commandName, ModErrorKind.FAILED.code, e.message ?: e.toString())
            ModErrorKind.FAILED.exitCode()
        } finally {
            runCatching { env.close() }
        }
    }

    private fun resolveCommand(positionals: List<String>): Pair<Command?, List<String>> {
        if (positionals.isEmpty()) return null to emptyList()
        val twoWords = positionals.take(2).joinToString(" ")
        commands.find { it.name == twoWords }?.let { return it to positionals.drop(2) }
        commands.find { it.name == positionals[0] }?.let { return it to positionals.drop(1) }
        val subcommands = commands.filter { it.name.startsWith(positionals[0] + " ") }
        if (subcommands.isNotEmpty()) {
            throw CliUsageException("Usage: ${subcommands.joinToString(" | ") { "nomm ${it.usage}" }}")
        }
        throw CliUsageException("Unknown command \"${positionals[0]}\".")
    }
}

private class Command(
    val name: String,
    val usage: String,
    val summary: String,
    val options: OptionSpec = OptionSpec(),
    val optionHelp: List<Pair<String, String>> = emptyList(),
    val minOperands: Int = 0,
    val maxOperands: Int? = 0,
    val run: suspend Session.(operands: List<String>) -> CommandResult,
) {
    fun helpText() = buildString {
        appendLine("Usage: nomm $usage")
        appendLine()
        appendLine(summary)
        if (optionHelp.isNotEmpty()) {
            appendLine()
            appendLine("Options:")
            optionHelp.forEach { (option, help) -> appendLine("  ${option.padEnd(26)} $help") }
        }
        appendLine()
        append("Global options: --json, --game-dir <path>, --offline, --wait-for-game, --verbose")
    }
}

private val commands = listOf(
    Command("status", "status", "Show the game folder, BepInEx, catalog and mod health. Always succeeds.") { status() },
    Command(
        "list", "list [--enabled|--disabled] [--updates] [--problems] [--local]", "List installed mods.",
        OptionSpec(flags = setOf("enabled", "disabled", "updates", "problems", "local")),
        listOf(
            "--enabled / --disabled" to "Only enabled or disabled mods",
            "--updates" to "Only mods with a catalog update",
            "--problems" to "Only mods with missing or disabled dependencies",
            "--local" to "Only local builds and other mods not from the catalog",
        ),
    ) { list() },
    Command("info", "info <id>", "Show an installed mod and its catalog entry.", minOperands = 1, maxOperands = 1) { info(it[0]) },
    Command(
        "search", "search <query> [--limit N]", "Search the mod catalog by id, name, author, tag and description.",
        OptionSpec(values = setOf("limit")), listOf("--limit N" to "Maximum results (default 20)"),
        minOperands = 1, maxOperands = null,
    ) { search(it.joinToString(" ")) },
    Command(
        "install", "install <id>[@version]... [--no-deps] [--replace]",
        "Install catalog mods and their missing dependencies. Installs BepInEx first if needed.",
        OptionSpec(flags = setOf("no-deps", "replace")),
        listOf(
            "--no-deps" to "Don't install missing dependencies",
            "--replace" to "Overwrite a registered local build with the catalog version",
        ),
        minOperands = 1, maxOperands = null,
    ) { install(it) },
    Command(
        "update", "update (<id>... | --all)",
        "Update catalog mods to their latest version. Local builds are never updated.",
        OptionSpec(flags = setOf("all")), listOf("--all" to "Update every mod with an update available"),
        maxOperands = null,
    ) { update(it) },
    Command("uninstall", "uninstall <id>...", "Delete installed mods.", minOperands = 1, maxOperands = null) { uninstall(it) },
    Command(
        "enable", "enable <id>...", "Enable mods, and the mods they extend.",
        minOperands = 1, maxOperands = null,
    ) { toggle(it, enable = true) },
    Command(
        "disable", "disable <id>...", "Disable mods, and the mods that extend them.",
        minOperands = 1, maxOperands = null,
    ) { toggle(it, enable = false) },
    Command(
        "dev register", "dev register <path> [--version <v>] [--id <id>] [--name <name>] [--dependency <id>[@v]]... [--disabled] [--replace]",
        "Deploy a local build as a mod NOMM can list, toggle and uninstall. The build can be a plugin DLL, " +
            "a folder of build output, or a .zip; NOMM copies it into the game and writes its metadata. Run it again after each build to redeploy: " +
            "the mod keeps its enabled state, and its version, name and dependencies unless you pass new ones.",
        OptionSpec(flags = setOf("disabled", "replace"), values = setOf("id", "version", "name", "dependency")),
        listOf(
            "--version <v>" to "Version of this build, such as 0.3.0. Required the first time",
            "--id <id>" to "Mod id. Defaults to the DLL's file name; required for folders and zips",
            "--name <name>" to "Display name",
            "--dependency <id>[@v]" to "A mod this build needs. Repeat for more; replaces the previous list",
            "--disabled" to "Keep the mod disabled",
            "--replace" to "Allow replacing a mod installed from the catalog",
        ),
        minOperands = 1, maxOperands = 1,
    ) { devRegister(File(it[0])) },
    Command(
        "launch", "launch [--wait] [--timeout S]", "Start Nuclear Option through Steam.",
        OptionSpec(flags = setOf("wait"), values = setOf("timeout")),
        listOf("--wait" to "Wait until the game process is running", "--timeout S" to "How long --wait waits (default 120)"),
    ) { launch() },
    Command(
        "logs", "logs [--source bepinex|unity] [-n N] [--grep REGEX] [--follow] [--timeout S]",
        "Print the end of the game's BepInEx log (or Unity's Player.log). With --follow, keep printing new lines " +
            "until the game exits or the timeout passes.",
        OptionSpec(flags = setOf("follow"), values = setOf("source", "lines", "grep", "timeout")),
        listOf(
            "--source bepinex|unity" to "BepInEx/LogOutput.log (default) or Unity's Player.log",
            "-n, --lines N" to "Lines from the end to print (default 100, 0 for all)",
            "--grep REGEX" to "Only lines matching REGEX",
            "--follow" to "Keep printing new lines; stops when the game exits",
            "--timeout S" to "Stop following after S seconds",
        ),
    ) { logs() },
    Command("bepinex install", "bepinex install", "Install BepInEx 5 into the game folder.") { installBepInExCommand() },
    Command(
        "skill", "skill [--output <dir>]",
        "Print the guide that teaches coding agents to use this CLI, in the Agent Skills SKILL.md format.",
        OptionSpec(values = setOf("output")), listOf("--output <dir>" to "Write it to <dir>/SKILL.md instead"),
    ) { skill() },
    Command("version", "version", "Print NOMM's version.") { result(VersionView(env.appVersion), env.appVersion) },
    Command("help", "help [command]", "Show help.", maxOperands = 2) { CommandResult(kotlinx.serialization.json.JsonNull, "") },
)

private fun helpText(topic: List<String>): String {
    val name = topic.joinToString(" ")
    commands.find { it.name == name && it.name != "help" }?.let { return it.helpText() }
    return buildString {
        appendLine("NOMM command line: manage Nuclear Option mods from a terminal or a coding agent.")
        appendLine()
        appendLine("Usage: nomm <command> [options]")
        appendLine()
        appendLine("Commands:")
        commands.filter { it.name != "help" }.forEach {
            appendLine("  ${it.name.padEnd(16)} ${it.summary.substringBefore(". ").removeSuffix(".")}")
        }
        appendLine()
        appendLine("Global options:")
        appendLine("  --json                 Print one JSON object: {ok, command, data, error, warnings}")
        appendLine("  --game-dir <path>      Use this game folder instead of NOMM's setting")
        appendLine("  --offline              Use the cached catalog instead of downloading it")
        appendLine("  --wait-for-game        Wait for the game to exit instead of failing when it's running")
        appendLine("  --verbose              Print NOMM's log messages to stderr")
        appendLine()
        appendLine("Exit codes: 0 ok, 1 failed, 2 usage, 3 not found, 4 game or BepInEx missing,")
        appendLine("            5 game running, 6 download failed, 7 another NOMM process is busy")
        appendLine()
        append("Run `nomm help <command>` for a command's options.")
    }
}

/** State shared by the parts of one command run: the game folder, installed mods and catalog, loaded once. */
private class Session(val env: CliEnvironment, val args: ParsedArgs, val output: CliOutput) {
    val sync = ModSync(env.dataDir)

    val gameFolder: File? by lazy { args.value("game-dir")?.let { File(it).absoluteFile } ?: env.configuredGameFolder() }

    fun requireGame(): File {
        validateNuclearOptionGameFolder(gameFolder)?.let {
            throw ModException(ModErrorKind.ENVIRONMENT, "$it Pass --game-dir or set the game folder in NOMM.")
        }
        return gameFolder!!
    }

    fun requireBepInEx(): File {
        val game = requireGame()
        if (!isBepInExInstallationComplete(game)) {
            throw ModException(ModErrorKind.ENVIRONMENT, "BepInEx is not installed in ${game.path}. Run `nomm bepinex install`.")
        }
        return File(game, "BepInEx")
    }

    private var catalogResult: CatalogResult? = null

    suspend fun catalogResult(): CatalogResult = catalogResult ?: env.loadCatalog(args.has("offline")).also {
        catalogResult = it
        when (it.source) {
            CatalogResult.Source.UNAVAILABLE -> output.warn("The mod catalog could not be loaded, so catalog details and updates are missing.")
            CatalogResult.Source.CACHED -> if (!args.has("offline")) output.warn("Using NOMM's cached catalog because the download failed.")
            CatalogResult.Source.LIVE -> {}
        }
    }

    suspend fun catalog(): Map<String, Extension> = catalogResult().extensions.distinctBy { it.id }.associateBy { it.id }

    suspend fun requireCatalog(): Map<String, Extension> {
        val catalog = catalog()
        if (catalog.isEmpty()) throw ModException(ModErrorKind.DOWNLOAD, "The mod catalog could not be loaded.")
        return catalog
    }

    private var installed: MutableMap<String, ModMeta>? = null

    /** Installed mods, as a map the mod operations update in place. */
    fun mods(): MutableMap<String, ModMeta> = installed ?: run {
        val scan = scanInstalledMods(requireBepInEx())
        if (scan.duplicates.isNotEmpty()) {
            output.warn(
                "Older copies of installed mods are still in the game, and BepInEx may load them: " +
                    scan.duplicates.joinToString { it.path } + ". Delete them, or open the NOMM app to clean them up."
            )
        }
        scan.mods.toMutableMap().also { installed = it }
    }

    suspend fun computedMods(): Map<String, ModMeta> = withComputedState(mods(), catalog())

    suspend fun awaitGameClosed() {
        if (!env.isGameRunning()) return
        if (!args.has("wait-for-game")) {
            throw ModException(
                ModErrorKind.GAME_RUNNING,
                "Nuclear Option is running and keeps its mod files locked. Close the game or pass --wait-for-game."
            )
        }
        output.progress("Waiting for Nuclear Option to exit...")
        while (env.isGameRunning()) delay(2.seconds)
    }

    /** Runs a change under the mod lock, on a fresh scan so it never acts on files that moved since. */
    fun <T> locked(action: (mods: MutableMap<String, ModMeta>) -> T): T = sync.lock.withLock {
        val fresh = scanInstalledMods(requireBepInEx()).mods.toMutableMap()
        installed = fresh
        action(fresh)
    }

    /** Keeps NOSMR's mod list current and tells an open GUI to rescan. */
    fun finishChange() {
        installed?.let { mods ->
            runCatching { writeNosmrModpack(mods) }.onFailure { output.warn("Could not update NOSMR's modpack: ${it.message}") }
        }
        sync.markChanged()
    }

    fun warnLinuxLaunchOptions() {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("win") && !os.contains("mac")) {
            output.warn("Under Proton, BepInEx only loads with this in Nuclear Option's Steam launch options: WINEDLLOVERRIDES=\"winhttp.dll=n,b\" %command%")
        }
    }
}

private suspend fun Session.status(): CommandResult {
    val game = gameFolder
    val gameProblem = validateNuclearOptionGameFolder(game)
    val bepInEx = gameProblem == null && isBepInExInstallationComplete(game)
    val catalog = catalogResult()
    val mods = if (bepInEx) computedMods() else null
    val running = env.isGameRunning()

    val counts = mods?.values?.let { all ->
        ModCounts(
            total = all.size,
            enabled = all.count { it.enabled == true },
            disabled = all.count { it.enabled != true },
            updatesAvailable = all.count { it.hasUpdate },
            withProblems = all.count { it.problems.isNotEmpty() },
        )
    }
    val view = StatusView(
        nommVersion = env.appVersion,
        gameFolder = game?.path,
        gameFolderProblem = gameProblem,
        bepInExInstalled = bepInEx,
        gameRunning = running,
        catalogSource = catalog.source.name.lowercase(),
        catalogMods = catalog.extensions.size,
        mods = counts,
        bepInExLog = game?.let { bepInExLogFile(it).path },
        unityLog = runCatching { unityPlayerLogFile(game).path }.getOrNull(),
        dataFolder = env.dataDir.path,
        ready = gameProblem == null && bepInEx,
    )

    val text = buildString {
        appendLine("Game folder:  ${game?.path ?: "not found"}${gameProblem?.let { "  ($it)" } ?: ""}")
        appendLine("BepInEx:      ${if (bepInEx) "installed" else "not installed"}")
        appendLine("Game running: ${if (running) "yes" else "no"}")
        appendLine("Catalog:      ${catalog.extensions.size} mods (${catalog.source.name.lowercase()})")
        if (counts != null) {
            append("Mods:         ${counts.total} installed, ${counts.enabled} enabled")
            if (counts.updatesAvailable > 0) append(", ${counts.updatesAvailable} with updates")
            if (counts.withProblems > 0) append(", ${counts.withProblems} with problems")
            appendLine()
        }
        view.bepInExLog?.let { appendLine("BepInEx log:  $it") }
        append("NOMM data:    ${view.dataFolder}")
    }
    return result(view, text)
}

private suspend fun Session.list(): CommandResult {
    val catalog = catalog()
    val views = computedMods().values
        .filter { !args.has("enabled") || it.enabled == true }
        .filter { !args.has("disabled") || it.enabled != true }
        .filter { !args.has("updates") || it.hasUpdate }
        .filter { !args.has("problems") || it.problems.isNotEmpty() }
        .map { it.toView(catalog) }
        .filter { !args.has("local") || it.source != "catalog" }
        .sortedBy { it.id.lowercase() }

    val text = if (views.isEmpty()) "No mods found." else table(
        listOf("ENABLED", "ID", "VERSION", "SOURCE", "NOTES"),
        views.map { mod ->
            val notes = buildList {
                if (mod.updateAvailable) add("update: ${mod.latestVersion}")
                mod.problems.forEach { add(it) }
                if (mod.managedByNomm) add("managed by NOMM")
            }.joinToString("; ")
            listOf(if (mod.enabled) "yes" else "no", mod.id, mod.version ?: "-", mod.source, notes)
        }
    )
    return result(views, text)
}

private suspend fun Session.info(id: String): CommandResult {
    val catalog = catalog()
    val installedMods = if (gameFolder != null && isBepInExInstallationComplete(gameFolder)) computedMods() else emptyMap()
    val installed = installedMods[id]?.toView(catalog)
    val extension = catalog[id]
    if (installed == null && extension == null) {
        throw ModException(ModErrorKind.NOT_FOUND, "No installed or catalog mod with id \"$id\". Try `nomm search`.")
    }
    val view = InfoView(installed, extension?.toView())

    val text = buildString {
        appendLine("${installed?.name ?: extension?.displayName} ($id)")
        if (installed != null) {
            appendLine("Installed:    ${installed.version ?: "unknown version"}, ${if (installed.enabled) "enabled" else "disabled"}, ${installed.source}")
            installed.path?.let { appendLine("Path:         $it") }
            installed.localBuildSource?.let { appendLine("Built from:   $it") }
            if (installed.updateAvailable) appendLine("Update:       ${installed.latestVersion}")
            installed.problems.forEach { appendLine("Problem:      $it") }
        } else {
            appendLine("Installed:    no")
        }
        view.catalog?.let { entry ->
            appendLine("Latest:       ${entry.latestVersion ?: "-"}")
            if (entry.authors.isNotEmpty()) appendLine("Authors:      ${entry.authors.joinToString()}")
            if (entry.tags.isNotEmpty()) appendLine("Tags:         ${entry.tags.joinToString()}")
            entry.versions.firstOrNull()?.let { latest ->
                if (latest.dependencies.isNotEmpty()) appendLine("Dependencies: ${latest.dependencies.joinToString()}")
                latest.extends?.let { appendLine("Extends:      $it") }
            }
            entry.links.forEach { (name, url) -> appendLine("Link:         $name $url") }
            if (entry.description.isNotBlank()) {
                appendLine()
                appendLine(entry.description)
            }
        }
    }.trimEnd()
    return result(view, text)
}

private suspend fun Session.search(query: String): CommandResult {
    val limit = args.int("limit", 20)
    val needle = query.lowercase()
    val installedVersions = runCatching { mods().mapValues { it.value.artifact?.version?.toString() } }.getOrDefault(emptyMap())

    fun score(extension: Extension): Int {
        val id = extension.id.lowercase()
        val name = extension.displayName.lowercase()
        return when {
            id == needle || name == needle -> 100
            id.startsWith(needle) || name.startsWith(needle) -> 70
            id.contains(needle) || name.contains(needle) -> 50
            extension.authors.any { it.lowercase().contains(needle) } -> 35
            extension.tags.any { it.lowercase().contains(needle) } -> 30
            extension.description.lowercase().contains(needle) -> 10
            else -> 0
        }
    }

    val hits = requireCatalog().values
        .map { it to score(it) }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<Extension, Int>> { it.second }.thenByDescending { it.first.downloadCount ?: 0 })
        .let { if (limit > 0) it.take(limit) else it }
        .map { (extension, _) ->
            SearchHit(
                id = extension.id,
                name = extension.displayName,
                latestVersion = extension.artifacts.maxByOrNull { it.version }?.version?.toString(),
                authors = extension.authors,
                tags = extension.tags,
                description = extension.description,
                downloads = extension.downloadCount,
                installedVersion = installedVersions[extension.id],
            )
        }

    val text = if (hits.isEmpty()) "No catalog mods match \"$query\"." else table(
        listOf("ID", "LATEST", "INSTALLED", "NAME"),
        hits.map { listOf(it.id, it.latestVersion ?: "-", it.installedVersion ?: "-", it.name) }
    )
    return result(hits, text)
}

private fun parseReference(raw: String): PackageReference {
    val id = raw.substringBeforeLast('@')
    val version = if ('@' in raw) parseVersion(raw.substringAfterLast('@')) else null
    if (id.isBlank()) throw CliUsageException("\"$raw\" is not a mod id.")
    return PackageReference(id, version)
}

private fun parseVersion(raw: String): Version =
    raw.takeIf { it.isNotBlank() }?.let { runCatching { Version.fromString(it) }.getOrNull() }
        ?: throw CliUsageException("\"$raw\" is not a version like 1.2.3.")

private suspend fun Session.install(operands: List<String>): CommandResult {
    val requests = operands.map(::parseReference)
    val game = requireGame()
    awaitGameClosed()
    val catalog = requireCatalog()

    if (!isBepInExInstallationComplete(game)) {
        output.progress("BepInEx is not installed; installing it first...")
        env.installBepInEx(game)
        warnLinuxLaunchOptions()
    }
    return runInstall(requests, catalog, withDependencies = !args.has("no-deps"))
}

private suspend fun Session.update(operands: List<String>): CommandResult {
    if (operands.isEmpty() && !args.has("all")) throw CliUsageException("Name the mods to update, or pass --all.")
    if (operands.isNotEmpty() && args.has("all")) throw CliUsageException("Pass mod ids or --all, not both.")
    requireBepInEx()
    awaitGameClosed()
    val catalog = requireCatalog()
    val mods = computedMods()

    val targets = if (args.has("all")) {
        mods.values.filter { it.hasUpdate }.map { it.id }
    } else {
        operands.filter { id ->
            val mod = mods[id] ?: throw ModException(ModErrorKind.NOT_FOUND, "$id is not installed.")
            when {
                mod.localBuild != null -> {
                    output.warn("$id is a local build; register a new build instead of updating it.")
                    false
                }
                id !in catalog -> {
                    output.warn("$id is not in the catalog, so it can't be updated.")
                    false
                }
                !mod.hasUpdate -> {
                    output.warn("$id is already at the latest version.")
                    false
                }
                else -> true
            }
        }
    }
    if (targets.isEmpty()) return result(InstallView(emptyList(), emptyList()), "Everything is up to date.")

    return runInstall(targets.map { PackageReference(it) }, catalog, withDependencies = true)
}

private suspend fun Session.runInstall(
    requests: List<PackageReference>,
    catalog: Map<String, Extension>,
    withDependencies: Boolean,
): CommandResult {
    val bepInEx = requireBepInEx()
    val mods = mods()
    val warnings = mutableListOf<String>()
    val plan = planInstall(requests, catalog, mods, withDependencies, args.has("replace"), warnings)
    warnings.forEach(output::warn)

    val installed = mutableListOf<InstalledView>()
    try {
        for (step in plan) {
            output.progress("Installing ${step.extension.id} ${step.artifact.version}...")
            val meta = installStep(mods, bepInEx, step, env.downloader, sync.lock)
            installed.add(
                InstalledView(
                    id = meta.id,
                    version = step.artifact.version.toString(),
                    previousVersion = step.previousVersion?.toString(),
                    reason = step.reason.name.lowercase(),
                    enabled = meta.enabled == true,
                    path = meta.file?.path,
                )
            )
        }
    } catch (e: ModException) {
        if (installed.isNotEmpty()) finishChange()
        val done = if (installed.isEmpty()) "" else " Installed before the failure: ${installed.joinToString { "${it.id} ${it.version}" }}."
        throw ModException(e.kind, (e.message ?: "") + done)
    }

    // Requested mods need their installed-but-disabled dependencies enabled too.
    val enabledDependencies = mutableListOf<String>()
    if (withDependencies) {
        locked { current ->
            requests.mapNotNull { current[it.id] }.forEach { mod ->
                val needed = mod.artifact?.dependencies.orEmpty().map { it.id } + listOfNotNull(mod.artifact?.extends?.id)
                needed.filter { current[it]?.enabled == false }.forEach { dependency ->
                    if (enableMod(current, bepInEx, dependency)) enabledDependencies.add(dependency)
                }
            }
        }
    }
    if (installed.isNotEmpty() || enabledDependencies.isNotEmpty()) finishChange()

    val view = InstallView(installed, enabledDependencies)
    val text = buildList {
        if (installed.isEmpty()) add("Nothing to install.")
        installed.forEach { add("Installed ${it.id} ${it.version}${it.previousVersion?.let { previous -> " (was $previous)" } ?: ""}${if (it.reason == "dependency") " as a dependency" else ""}") }
        enabledDependencies.forEach { add("Enabled dependency $it") }
    }.joinToString("\n")
    return result(view, text)
}

private suspend fun Session.uninstall(ids: List<String>): CommandResult {
    val bepInEx = requireBepInEx()
    ids.forEach { id ->
        if (id in protectedModIds) throw ModException(ModErrorKind.INVALID, "$id is managed by NOMM and can't be uninstalled here.")
    }
    awaitGameClosed()

    var enabledBefore = emptySet<String>()
    var mods = mutableMapOf<String, ModMeta>()
    val removed = mutableListOf<String>()
    try {
        locked { current ->
            mods = current
            ids.forEach { if (it !in current) throw ModException(ModErrorKind.NOT_FOUND, "$it is not installed.") }
            enabledBefore = current.filterValues { it.enabled == true }.keys
            ids.forEach { id ->
                if (!uninstallMod(current, bepInEx, id)) {
                    throw ModException(ModErrorKind.FAILED, "Could not delete $id. A running game keeps its files locked.")
                }
                removed.add(id)
            }
        }
    } finally {
        if (removed.isNotEmpty()) finishChange()
    }
    val disabledAddons = enabledBefore.filter { it !in ids && mods[it]?.enabled == false }

    val view = UninstallView(removed, disabledAddons)
    val text = (removed.map { "Uninstalled $it" } + disabledAddons.map { "Disabled addon $it" }).joinToString("\n")
    return result(view, text)
}

private suspend fun Session.toggle(ids: List<String>, enable: Boolean): CommandResult {
    val bepInEx = requireBepInEx()
    awaitGameClosed()

    var before = emptyMap<String, Boolean>()
    var mods = mutableMapOf<String, ModMeta>()
    try {
        locked { current ->
            mods = current
            ids.forEach { if (it !in current) throw ModException(ModErrorKind.NOT_FOUND, "$it is not installed.") }
            before = current.mapValues { it.value.enabled == true }
            ids.forEach { id ->
                val changed = if (enable) enableMod(current, bepInEx, id) else disableMod(current, bepInEx, id)
                if (!changed) {
                    val parent = current[id]?.artifact?.extends?.id
                    val reason = if (enable && parent != null && parent !in current) {
                        "it extends $parent, which is not installed"
                    } else {
                        "its files could not be moved. A running game keeps them locked"
                    }
                    throw ModException(ModErrorKind.FAILED, "Could not ${if (enable) "enable" else "disable"} $id: $reason.")
                }
            }
        }
    } finally {
        if (mods.any { (id, mod) -> before[id] != (mod.enabled == true) }) finishChange()
    }

    val changes = mods.filter { (id, mod) -> before[id] != (mod.enabled == true) }
        .map { (id, mod) -> StateChange(id, mod.enabled == true) }
        .sortedBy { it.id }
    if (enable) {
        withComputedState(mods, emptyMap()).filterKeys { it in ids }.values.forEach { mod ->
            mod.problems.forEach { output.warn("${mod.id}: $it") }
        }
    }

    val text = if (changes.isEmpty()) {
        "Already ${if (enable) "enabled" else "disabled"}."
    } else {
        changes.joinToString("\n") { "${if (it.enabled) "Enabled" else "Disabled"} ${it.id}" }
    }
    return result(ToggleView(changes), text)
}

private suspend fun Session.devRegister(source: File): CommandResult {
    val sourceFile = source.absoluteFile
    if (!sourceFile.exists()) throw ModException(ModErrorKind.NOT_FOUND, "${source.path} does not exist.")
    val id = args.value("id") ?: if (sourceFile.isFile && !sourceFile.extension.equals("zip", ignoreCase = true)) {
        sourceFile.nameWithoutExtension
    } else {
        throw CliUsageException("--id is required when registering a folder or a zip.")
    }
    val version = args.value("version")?.let(::parseVersion)
    val dependencies = args.values("dependency").map(::parseReference).takeIf { it.isNotEmpty() }

    val bepInEx = requireBepInEx()
    awaitGameClosed()
    if (version == null && mods()[id]?.localBuild == null) {
        throw CliUsageException("--version is required the first time a build is registered.")
    }

    val request = LocalBuildRequest(
        source = sourceFile,
        id = id,
        version = version,
        name = args.value("name"),
        dependencies = dependencies,
        enabled = if (args.has("disabled")) false else null,
        replaceCatalogMod = args.has("replace"),
    )
    var mods = mutableMapOf<String, ModMeta>()
    try {
        locked { current ->
            mods = current
            registerLocalBuild(current, bepInEx, request)
        }
    } finally {
        finishChange()
    }

    val catalog = catalog()
    if (id in catalog) output.warn("$id is also in the catalog. NOMM won't offer catalog updates for it while the local build is installed.")
    val view = withComputedState(mods, emptyMap()).getValue(id).toView(catalog)
    view.problems.forEach { output.warn("$id: $it") }

    val text = "Registered $id ${view.version} (${if (view.enabled) "enabled" else "disabled"}) at ${view.path}"
    return result(view, text)
}

private suspend fun Session.launch(): CommandResult {
    if (env.isGameRunning()) return result(LaunchView(alreadyRunning = true, running = true), "Nuclear Option is already running.")
    if (!env.launchGame(gameFolder)) {
        throw ModException(ModErrorKind.FAILED, "Could not start Nuclear Option through Steam or NuclearOption.exe.")
    }
    if (!args.has("wait")) return result(LaunchView(alreadyRunning = false, running = false), "Asked Steam to start Nuclear Option.")

    val timeout = (args.seconds("timeout") ?: 120L).seconds
    val start = System.nanoTime()
    while (!env.isGameRunning()) {
        if ((System.nanoTime() - start).nanoseconds >= timeout) {
            throw ModException(ModErrorKind.FAILED, "Nuclear Option did not start within ${timeout.inWholeSeconds} seconds.")
        }
        delay(1.seconds)
    }
    return result(LaunchView(alreadyRunning = false, running = true), "Nuclear Option is running.")
}

private suspend fun Session.logs(): CommandResult {
    val source = args.value("source") ?: "bepinex"
    val file = when (source) {
        "bepinex" -> bepInExLogFile(requireGame())
        "unity" -> unityPlayerLogFile(gameFolder)
        else -> throw CliUsageException("--source must be bepinex or unity.")
    }
    val lineCount = args.int("lines", 100)
    val pattern = args.value("grep")?.let {
        runCatching { Regex(it) }.getOrElse { e -> throw CliUsageException("--grep is not a valid regex: ${e.message}") }
    }
    val follow = args.has("follow")
    if (!file.exists() && !follow) {
        throw ModException(ModErrorKind.NOT_FOUND, "There is no log at ${file.path} yet. Start the game once with BepInEx installed.")
    }

    val existing = if (file.exists()) {
        file.readLines().filter { pattern == null || pattern.containsMatchIn(it) }
            .let { if (lineCount > 0) it.takeLast(lineCount) else it }
    } else {
        emptyList()
    }
    if (!follow) return result(LogView(source, file.path, existing), existing.joinToString("\n"))

    existing.forEach { output.stream(cliJson.encodeToJsonElement(LogLine(it)), it) }
    var printed = existing.size
    val stoppedBecause = followLog(file, args.seconds("timeout")) { line ->
        if (pattern == null || pattern.containsMatchIn(line)) {
            output.stream(cliJson.encodeToJsonElement(LogLine(line)), line)
            printed++
        }
    }
    val text = if (output.json) "" else "(stopped: ${stoppedBecause.replace('_', ' ')})"
    return result(LogFollowView(source, file.path, printed, stoppedBecause), text)
}

/**
 * Prints lines appended to [file] until the game exits (after having run while following) or the
 * timeout passes. Handles the log being recreated when the game starts again.
 */
private suspend fun Session.followLog(file: File, timeoutSeconds: Long?, onLine: (String) -> Unit): String {
    var position = if (file.exists()) file.length() else 0L
    val pending = ByteArrayOutputStream()
    var sawRunning = env.isGameRunning()
    val start = System.nanoTime()

    fun readNew() {
        val length = if (file.exists()) file.length() else 0L
        if (length < position) {
            position = 0L
            pending.reset()
        }
        if (length <= position) return
        val bytes = RandomAccessFile(file, "r").use { raf ->
            raf.seek(position)
            ByteArray((length - position).toInt()).also { raf.readFully(it) }
        }
        position = length
        for (byte in bytes) {
            if (byte == '\n'.code.toByte()) {
                onLine(pending.toString(Charsets.UTF_8).trimEnd('\r'))
                pending.reset()
            } else {
                pending.write(byte.toInt())
            }
        }
    }

    while (true) {
        if (timeoutSeconds != null && (System.nanoTime() - start) / 1_000_000_000 >= timeoutSeconds) {
            readNew()
            return "timeout"
        }
        delay(500.milliseconds)
        readNew()
        if (env.isGameRunning()) {
            sawRunning = true
        } else if (sawRunning) {
            delay(500.milliseconds)
            readNew()
            if (pending.size() > 0) onLine(pending.toString(Charsets.UTF_8))
            return "game_exited"
        }
    }
}

private suspend fun Session.installBepInExCommand(): CommandResult {
    val game = requireGame()
    if (isBepInExInstallationComplete(game)) {
        return result(BepInExView(installed = true, alreadyInstalled = true, gameFolder = game.path), "BepInEx is already installed.")
    }
    awaitGameClosed()
    env.installBepInEx(game)
    if (!isBepInExInstallationComplete(game)) {
        throw ModException(ModErrorKind.FAILED, "BepInEx did not install correctly into ${game.path}.")
    }
    warnLinuxLaunchOptions()
    sync.markChanged()
    return result(BepInExView(installed = true, alreadyInstalled = false, gameFolder = game.path), "Installed BepInEx into ${game.path}.")
}

private fun Session.skill(): CommandResult {
    val guide = Cli::class.java.getResourceAsStream("/nomm-cli/SKILL.md")?.use { it.readBytes().decodeToString() }
        ?: throw ModException(ModErrorKind.FAILED, "This build of NOMM doesn't include the agent guide.")
    val outputDir = args.value("output") ?: return result(SkillView(guide, null), guide.trimEnd())

    val file = File(outputDir, "SKILL.md").absoluteFile
    file.parentFile.mkdirs()
    file.writeText(guide)
    return result(SkillView(null, file.path), "Wrote ${file.path}")
}
