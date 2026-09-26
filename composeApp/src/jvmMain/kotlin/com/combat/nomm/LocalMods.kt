package com.combat.nomm

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import io.github.vinceglb.filekit.*
import io.github.vinceglb.filekit.dialogs.*
import io.github.vinceglb.filekit.utils.toKotlinxIoPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.io.path.isSameFileAs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object LocalMods {
    val isBepInExInstalled: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val isGameExeFound: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val mods: Map<String, ModMeta>
        field = mutableStateMapOf<String, ModMeta>()

    val protectedIds = protectedModIds

    val modSync by lazy { ModSync(FileKit.filesDir.file) }

    var nosmrExportJob: Job? = null

    val refreshInProgress = Mutex(false)


    fun exportMods() {
        scope.launch {
            val currentInstant = Clock.System.now()
            val dt = currentInstant.toLocalDateTime(TimeZone.currentSystemDefault())

            val year = dt.year
            val month = dt.month.number.toString().padStart(2, '0')
            val day = dt.day.toString().padStart(2, '0')
            val hour = dt.hour.toString().padStart(2, '0')
            val minute = dt.minute.toString().padStart(2, '0')
            val second = dt.second.toString().padStart(2, '0')

            val file = FileKit.openFileSaver(
                suggestedName = "${year}-${month}-${day}_${hour}-${minute}-${second}",
                defaultExtension = "nommpack",
                directory = null,
                dialogSettings = FileKitDialogSettings.createDefault()
            )
            exportMods(file)
        }
    }

    fun addFilesToPlugins(files: List<File>) {
        val targetError = validateNuclearOptionModTarget()
        if (targetError != null) {
            reportNommError("Cannot add mod files", targetError)
            return
        }

        val bepinexFolder = SettingsManager.bepInExFolder ?: return
        val pluginsDir = File(bepinexFolder, "plugins")
        if (!pluginsDir.exists() && !pluginsDir.mkdirs()) {
            reportNommError("Cannot add mod files", "Could not create ${pluginsDir.absolutePath}.")
            return
        }
        val pluginsError = validateWritableDirectory(pluginsDir)
        if (pluginsError != null) {
            reportNommError("Cannot add mod files", pluginsError)
            return
        }
        files.forEach { file ->
            val destinationFile = File(pluginsDir, file.name)
            file.moveTo(destinationFile)
        }
        refresh()
    }

    fun addFromFile() {
        scope.launch {
            val files = FileKit.openFilePicker(
                mode = FileKitMode.Multiple(),
                dialogSettings = FileKitDialogSettings("Add From Files"),
            )

            if (!files.isNullOrEmpty()) {
                addFilesToPlugins(files.map { it.file })
            }
        }
    }

    fun importMods() {
        scope.launch {
            val file = FileKit.openFilePicker(
                dialogSettings = FileKitDialogSettings("Import Modpack"),
                type = FileKitType.File("nomm.json", "nommpack"),
            )

            importMods(file)
        }
    }

    fun importMods(file: PlatformFile?) {
        scope.launch {
            file?.let { platformFile ->
                val targetError = validateNuclearOptionModTarget()
                if (targetError != null) {
                    reportNommError("Cannot import modpack", targetError)
                    return@let
                }

                val bepinexFolder = SettingsManager.bepInExFolder ?: return@let
                val pluginsDir = File(bepinexFolder, "plugins")
                if (!pluginsDir.exists() && !pluginsDir.mkdirs()) {
                    reportNommError("Cannot import modpack", "Could not create ${pluginsDir.absolutePath}.")
                    return@let
                }
                val pluginsError = validateWritableDirectory(pluginsDir)
                if (pluginsError != null) {
                    reportNommError("Cannot import modpack", pluginsError)
                    return@let
                }

                val jsonString: String? = try {
                    if (platformFile.extension == "json") {
                        platformFile.readString()
                    } else {
                        importZipMods(platformFile.file, pluginsDir)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportNommError("Modpack import failed", e.message ?: "See the terminal for details.")
                    return@let
                }

                val imported = jsonString?.let { json.decodeFromString<List<PackageReference>>(it) }

                RepoMods.fetchManifest()
                imported?.forEach {
                    mods[it.id] ?: run {
                        RepoMods.installMod(it.id, it.version)
                    }
                }

                val importedIds = imported?.map { it.id }
                mods.forEach { (_, meta) ->
                    if (meta.id in protectedIds) return@forEach
                    if (importedIds?.contains(meta.id) == true) {
                        meta.enable()
                    } else {
                        meta.disable()
                    }
                }
            }
        }
    }

    private suspend fun importZipMods(file: File, pluginsDir: File): String? {
        val stagingDir = withContext(Dispatchers.IO) {
            Files.createTempDirectory("nomm-modpack-")
        }.toFile()
        try {
            var modlist: String? = null
            val warnedMods = mutableSetOf<String>()

            ZipInputStream(file.inputStream()).use { zipStream ->
                var entry = zipStream.nextEntry

                while (entry != null) {
                    when {
                        entry.name.endsWith("modlist.nomm.json") -> {
                            if (!entry.isDirectory) {
                                modlist = zipStream.readBytes().decodeToString()
                            }
                        }

                        entry.name.startsWith("mods/") -> {
                            val fileName = entry.name.removePrefix("mods/")
                            if (fileName.isNotEmpty()) {
                                val stagedFile = resolveArchiveEntry(stagingDir, fileName)
                                val rootModName = fileName.substringBefore('/')
                                if (warnedMods.add(rootModName)) {
                                    suspendCancellableCoroutine { continuation ->
                                        SettingsManager.criticalInformation.add(
                                            Triple(
                                                buildAnnotatedString {
                                                    append("Modpack includes the Local Mod ")

                                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                                        append(
                                                            rootModName
                                                        )
                                                    }
                                                },
                                                buildAnnotatedString { append("Make sure you trust the source, or remove the mod to stay safe.") },
                                                continuation
                                            )
                                        )
                                    }
                                }

                                if (entry.isDirectory) {
                                    if (!stagedFile.exists() && !stagedFile.mkdirs()) {
                                        error("Could not create staging directory for $fileName")
                                    }
                                } else {
                                    stagedFile.parentFile?.mkdirs()
                                    stagedFile.outputStream().use { outStream ->
                                        zipStream.copyTo(outStream)
                                    }
                                }
                            }
                        }
                    }
                    entry = zipStream.nextEntry
                }
            }

            applyStagedModpackFiles(stagingDir, pluginsDir)
            refresh()
            return modlist ?: run {
                suspendCancellableCoroutine { continuation ->
                    SettingsManager.criticalInformation.add(
                        Triple(
                            buildAnnotatedString { append("Modpack does not include a Modlist.") },
                            buildAnnotatedString { append("Local Mods from the Modpack have been added.") },
                            continuation
                        )
                    )
                }
                null
            }
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun applyStagedModpackFiles(stagingDir: File, pluginsDir: File) {
        stagingDir.walkTopDown().forEach { source ->
            if (source == stagingDir) return@forEach
            if (Files.isSymbolicLink(source.toPath())) {
                throw SecurityException("Modpack contains a symbolic link: ${source.name}")
            }

            val relativePath = stagingDir.toPath().relativize(source.toPath()).toString()
            val destination = resolveArchiveEntry(pluginsDir, relativePath)
            if (Files.isSymbolicLink(destination.toPath())) {
                throw SecurityException("Cannot replace symbolic link: ${destination.absolutePath}")
            }

            if (source.isDirectory) {
                if (!destination.exists() && !destination.mkdirs()) {
                    error("Could not create ${destination.absolutePath}")
                }
            } else {
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
        }
    }


    fun loadInstalledModMeta(file: File, isEnabled: Boolean) {
        val bepinexFolder = SettingsManager.bepInExFolder
        if (bepinexFolder == null || !isBepInExInstallationComplete(SettingsManager.gameFolder)) {
            isBepInExInstalled.value = false
            mods.clear()
            return
        }
        isBepInExInstalled.value = true

        isGameExeFound.value = SettingsManager.gameFolder?.let { File(it, "NuclearOption.exe").exists() } ?: false


        val metaFile = if (file.isDirectory) File(file, "meta.json") else null
        val meta = if (metaFile?.exists() == true) {
            runCatching { json.decodeFromString<ModMeta>(metaFile.readText()) }.getOrNull()
        } else null

        val id = meta?.id ?: file.name
        val existing = mods[id]

        if (existing != null) {
            val currentVersion = meta?.artifact?.version
            val existingVersion = existing.artifact?.version

            if (existing.file?.toPath()?.isSameFileAs(file.toPath()) ?: false) {


                val isNewer = if (currentVersion != null && existingVersion != null) {
                    currentVersion > existingVersion
                } else {
                    file.lastModified() > existing.file.lastModified()
                }

                if (isNewer) {
                    existing.file.deleteRecursively()
                } else {
                    file.deleteRecursively()
                }
            }
        }

        mods[id] = (meta ?: ModMeta(id = id)).copy(
            file = file,
            enabled = isEnabled,
            isUnidentified = meta == null
        )


        if (file.isDirectory) {
            val addonFolder = File(file, "addons")

        }
    }

    fun loadInstalledModMetas() {
        val bepinexFolder = SettingsManager.bepInExFolder
        if (bepinexFolder == null || !isBepInExInstallationComplete(SettingsManager.gameFolder)) {
            isBepInExInstalled.value = false
            mods.clear()
            return
        }
        isBepInExInstalled.value = true

        isGameExeFound.value = SettingsManager.gameFolder?.let { File(it, "NuclearOption.exe").exists() } ?: false

        File(bepinexFolder, "plugins").mkdirs()
        File(bepinexFolder, "disabledPlugins").mkdirs()
        // Duplicates are only removed from a scan made under the lock, so a move by another
        // NOMM process can't make one mod look like two and lose the real copy.
        val scan = runCatching {
            modSync.lock.withLock(2.seconds) {
                scanInstalledMods(bepinexFolder).also { it.duplicates.forEach(::deleteModFile) }
            }
        }.getOrElse {
            Log.log("Mods are being changed by another NOMM process, rescanning without removing duplicates")
            scanInstalledMods(bepinexFolder)
        }

        // Drop mods that are gone from disk, such as ones another NOMM process uninstalled.
        (mods.keys - scan.mods.keys).forEach { mods.remove(it) }
        mods.putAll(scan.mods)
        recalculateAllProblems()
    }

    fun recalculateAllProblems() {
        val modsWithProblems = withComputedState(mods.toMap(), RepoMods.mods.value)
        mods.clear()
        mods.putAll(modsWithProblems)
    }

    /** Refreshes when a NOMM CLI call changes the installed mods while the GUI is open. */
    fun watchExternalChanges() {
        scope.launch {
            var lastChange = modSync.lastChange()
            while (isActive) {
                delay(1.seconds)
                val change = modSync.lastChange()
                if (change != lastChange) {
                    lastChange = change
                    Log.log("Mods changed outside the GUI, rescanning")
                    refreshInProgress.withLockOrSkip { loadInstalledModMetas() }
                }
            }
        }
    }


    /** Runs a file change on [mods] under the cross-process lock, then refreshes derived state. */
    fun changeMods(
        errorTitle: String,
        change: (mods: MutableMap<String, ModMeta>, bepinexFolder: File) -> Boolean,
    ): Boolean {
        val bepinexFolder = SettingsManager.bepInExFolder ?: return false
        val changed = try {
            modSync.lock.withLock(5.seconds) { change(mods, bepinexFolder) }
        } catch (e: ModException) {
            reportNommError(errorTitle, e.message ?: "")
            return false
        }
        if (!changed) Log.log("$errorTitle: its files could not be moved")
        recalculateAllProblems()
        giveNOSMRnommpack()
        return changed
    }

    fun updateModState(id: String, meta: ModMeta?) {
        if (meta == null) mods.remove(id) else mods[id] = meta
        recalculateAllProblems()
    }

    fun refresh() {
        refreshInProgress.withLockOrSkip {
            loadInstalledModMetas()
            RepoMods.fetchManifest()

            val ver = Version(1, 2, 5)
            val id = "NOSMR"
            val downloadUrl = "https://github.com/RaylaValdez/NOSMR/releases/download/v1.2.5/NOSMR.dll"
            val hash = "sha256:8248fc1bf5d02ddbc38d2c8aafddca68a2669bdc2dc10c706d1206585319dccd"


            val installedMod = mods[id]
            if (installedMod != null) {
                val currentVersion = installedMod.artifact?.version
                if (currentVersion == ver) return
            }


            RepoMods.installMod(
                id,
                downloadUrl,
                hash
            ) { dir ->
                val metaData = ModMeta(
                    id = id,
                    artifact = Artifact(
                        version = ver,
                        downloadUrl = downloadUrl
                    ),
                )

                runCatching {
                    File(dir, "meta.json").writeText(json.encodeToString(metaData))
                    LocalMods.refresh()
                    if (SettingsManager.config.value.nosmr) {
                        LocalMods.mods[id]?.enable()
                    } else {
                        LocalMods.mods[id]?.disable()
                    }
                }
            }
        }
    }

    fun enableAll() {
        mods.forEach { (_, meta) ->
            meta.enable()
        }
    }

    fun disableAll() {
        mods.forEach { (_, meta) ->
            if (meta.id in protectedIds) return@forEach
            meta.disable()
        }
    }

    fun updateAll() {
        mods.forEach { (_, meta) ->
            if (meta.localBuild != null) return@forEach
            meta.update()
        }
    }
}

suspend fun exportMods(file: PlatformFile?) {
    file?.write(buildModpack(LocalMods.mods.values))
}

fun ModMeta.resolveProblems() {
    if (enabled != true) return

    artifact?.dependencies?.forEach { dep ->
        val depMod = LocalMods.mods[dep.id]
        if (depMod == null) {
            RepoMods.installMod(dep.id, null)
        } else if (depMod.enabled == false) {
            depMod.enable()
        }
    }

    artifact?.extends?.id?.let { parentId ->
        val parentMod = LocalMods.mods[parentId]
        if (parentMod == null) {
            RepoMods.installMod(parentId, null)
        } else if (parentMod.enabled == false) {
            parentMod.enable()
        }
    }
    LocalMods.refresh()
}

private fun giveNOSMRnommpack() {
    LocalMods.nosmrExportJob?.cancel()
    LocalMods.nosmrExportJob = scope.launch {
        delay(500.milliseconds)
        val file = LocalMods.mods["NOSMR"]?.file?.toKotlinxIoPath()?.let {
            PlatformFile(it)
        } ?: return@launch
        val modpacks = file / "modpacks"
        modpacks.createDirectories()
        exportMods(modpacks / "current.nommpack")
    }
}

fun ModMeta.enable(): Boolean = LocalMods.changeMods("Cannot enable $id") { mods, bepinexFolder ->
    enableMod(mods, bepinexFolder, id)
}

fun ModMeta.disable(): Boolean = LocalMods.changeMods("Cannot disable $id") { mods, bepinexFolder ->
    disableMod(mods, bepinexFolder, id)
}

fun ModMeta.uninstall(): Boolean = LocalMods.changeMods("Cannot uninstall $id") { mods, bepinexFolder ->
    uninstallMod(mods, bepinexFolder, id)
}

fun ModMeta.update() {
    RepoMods.installMod(id, null)
}

inline fun <T> Mutex.withLockOrSkip(skipped: () -> Unit = {}, action: () -> T): T? {
    return if (tryLock()) {
        try {
            action()
        } finally {
            unlock()
        }
    } else {
        skipped()
        null
    }
}