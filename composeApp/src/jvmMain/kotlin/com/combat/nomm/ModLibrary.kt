package com.combat.nomm

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// Installed mod state and the file operations on it. Nothing in here touches UI state,
// so the GUI (through LocalMods) and the CLI share the same behaviour.

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ModMeta(
    val id: String,
    val artifact: Artifact? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val localBuild: LocalBuild? = null,
    @Transient val enabled: Boolean? = null,
    @Transient val file: File? = null,
    @Transient val addons: List<ModMeta> = listOf(),
    @Transient val isUnidentified: Boolean = false,
    @Transient val hasUpdate: Boolean = false,
    @Transient val problems: List<String> = emptyList(),
)

/** Marks a mod registered from a local build instead of installed from the catalog. */
@Serializable
data class LocalBuild(
    val source: String,
    val name: String? = null,
)

enum class ModErrorKind(val code: String) {
    INVALID("invalid_argument"),
    NOT_FOUND("not_found"),
    ENVIRONMENT("environment"),
    GAME_RUNNING("game_running"),
    DOWNLOAD("download_failed"),
    BUSY("busy"),
    FAILED("failed"),
}

class ModException(val kind: ModErrorKind, message: String) : Exception(message)

const val BEPINEX_DOWNLOAD_URL =
    "https://github.com/BepInEx/BepInEx/releases/download/v5.4.23.4/BepInEx_win_x64_5.4.23.4.zip"

fun writeDefaultBepInExConfig(gameFolder: File) {
    val configDir = File(gameFolder, "BepInEx/config")
    configDir.mkdirs()
    File(configDir, "BepInEx.cfg").writeText(
        """
        [Chainloader]
        HideManagerGameObject = true
        """.trimIndent()
    )
}

data class ModScan(
    val mods: Map<String, ModMeta>,
    /** Older copies of a mod id found alongside a newer one. */
    val duplicates: List<File>,
)

fun readModMeta(file: File): ModMeta? {
    if (!file.isDirectory) return null
    val metaFile = File(file, "meta.json")
    if (!metaFile.exists()) return null
    return runCatching { json.decodeFromString<ModMeta>(metaFile.readText()) }.getOrNull()
}

/** Reads the installed mods without changing anything on disk. */
fun scanInstalledMods(bepInExFolder: File): ModScan {
    val foundMods = linkedMapOf<String, ModMeta>()
    val duplicates = mutableListOf<File>()

    fun scan(root: File, isEnabled: Boolean, depth: Int = 0) {
        if (depth > 10) return
        val children = root.listFiles() ?: return

        for (file in children) {
            if (file.name == "addons" || file.name == "meta.json") continue

            val meta = readModMeta(file)
            val id = meta?.id ?: file.name
            val existing = foundMods[id]

            if (existing != null) {
                val currentVersion = meta?.artifact?.version
                val existingVersion = existing.artifact?.version

                val isNewer = if (currentVersion != null && existingVersion != null) {
                    currentVersion > existingVersion
                } else {
                    file.lastModified() > (existing.file?.lastModified() ?: 0L)
                }

                if (isNewer) {
                    existing.file?.let(duplicates::add)
                } else {
                    duplicates.add(file)
                    continue
                }
            }

            foundMods[id] = (meta ?: ModMeta(id = id)).copy(
                file = file,
                enabled = isEnabled,
                isUnidentified = meta == null
            )

            if (file.isDirectory) {
                val addonFolder = File(file, "addons")
                if (addonFolder.exists()) scan(addonFolder, isEnabled, depth + 1)
            }
        }
    }

    scan(File(bepInExFolder, "plugins"), true)
    scan(File(bepInExFolder, "disabledPlugins"), false)
    return ModScan(foundMods, duplicates)
}

fun ModMeta.problemsIn(mods: Map<String, ModMeta>): List<String> {
    if (enabled != true) return emptyList()

    val foundProblems = mutableListOf<String>()

    artifact?.dependencies?.forEach { dep ->
        val depMod = mods[dep.id]
        if (depMod == null) {
            foundProblems.add("Dependency ${dep.id} not found")
        } else if (depMod.enabled == false) {
            foundProblems.add("Dependency ${dep.id} is disabled")
        }
    }

    artifact?.extends?.id?.let { parentId ->
        val parentMod = mods[parentId]
        if (parentMod == null) {
            foundProblems.add("Extended $parentId not found")
        } else if (parentMod.enabled == false) {
            foundProblems.add("Extended $parentId is disabled")
        }
    }

    return foundProblems
}

/** Local builds never report catalog updates, so updating everything can't overwrite them. */
fun ModMeta.hasUpdateIn(manifest: Map<String, Extension>): Boolean {
    if (localBuild != null) return false
    val latest = manifest[id]?.artifacts?.maxByOrNull { it.version } ?: return false
    return artifact?.version?.let { it < latest.version } ?: true
}

fun withComputedState(mods: Map<String, ModMeta>, manifest: Map<String, Extension>): Map<String, ModMeta> =
    mods.mapValues { (_, meta) ->
        meta.copy(
            hasUpdate = meta.hasUpdateIn(manifest),
            problems = meta.problemsIn(mods),
        )
    }

/** Moves a mod into plugins, enabling the mod it extends first. Updates [mods] in place. */
fun enableMod(
    mods: MutableMap<String, ModMeta>,
    bepInExFolder: File,
    id: String,
    visited: MutableSet<String> = mutableSetOf(),
): Boolean {
    if (!visited.add(id)) return true
    val current = mods[id] ?: return false
    val currentFile = current.file ?: return false
    if (current.enabled == true && currentFile.exists()) return true

    val parentId = current.artifact?.extends?.id
    val targetDir = if (parentId != null) {
        val parentMod = mods[parentId] ?: return false
        if (parentMod.enabled != true && !enableMod(mods, bepInExFolder, parentId, visited)) return false
        val parentFile = mods[parentId]?.file ?: return false
        resolveArchiveEntry(parentFile, "addons/$id")
    } else {
        File(bepInExFolder, "plugins/${currentFile.name}")
    }

    if (!currentFile.moveTo(targetDir)) return false
    mods[id] = current.copy(file = targetDir, enabled = true)
    return true
}

/** Moves a mod into disabledPlugins, disabling the mods that extend it first. Updates [mods] in place. */
fun disableMod(
    mods: MutableMap<String, ModMeta>,
    bepInExFolder: File,
    id: String,
    visited: MutableSet<String> = mutableSetOf(),
): Boolean {
    if (!visited.add(id)) return true
    val current = mods[id] ?: return false
    val currentFile = current.file ?: return false
    if (current.enabled == false || !currentFile.exists()) return true

    mods.values
        .filter { it.artifact?.extends?.id == id && it.enabled == true }
        .map { it.id }
        .forEach { disableMod(mods, bepInExFolder, it, visited) }

    val destination = File(bepInExFolder, "disabledPlugins/${currentFile.name}")
    if (!currentFile.moveTo(destination)) return false
    mods[id] = (mods[id] ?: current).copy(file = destination, enabled = false)
    return true
}

fun uninstallMod(mods: MutableMap<String, ModMeta>, bepInExFolder: File, id: String): Boolean {
    if (id !in mods) return false
    disableMod(mods, bepInExFolder, id)
    val file = mods[id]?.file
    if (file != null && !deleteModFile(file)) return false
    mods.remove(id)
    return true
}

data class LocalBuildRequest(
    /** A plugin DLL, a folder of build output, or a .zip of either. */
    val source: File,
    val id: String,
    /** Null keeps the version, name or dependencies of an existing registration. */
    val version: Version? = null,
    val name: String? = null,
    val dependencies: List<PackageReference>? = null,
    /** Null keeps an existing registration's state; new registrations default to enabled. */
    val enabled: Boolean? = null,
    val replaceCatalogMod: Boolean = false,
)

private val invalidIdCharacters = Regex("""[<>:"/\\|?*\x00-\x1F]""")
private val windowsReservedNames = Regex("""(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?""")

fun validateModId(id: String): String? = when {
    id.isBlank() -> "The mod id is empty."
    id != id.trim() -> "The mod id has leading or trailing spaces."
    invalidIdCharacters.containsMatchIn(id) -> "The mod id \"$id\" contains characters that can't be in a folder name."
    id == "." || id == ".." || id.endsWith(".") || windowsReservedNames.matches(id) ->
        "The mod id \"$id\" can't be used as a folder name."
    id == "addons" || id == "meta.json" -> "The mod id \"$id\" is reserved by NOMM."
    id in protectedModIds -> "$id is managed by NOMM."
    else -> null
}

/** Mods NOMM installs and toggles itself. Bulk actions and the CLI leave them alone. */
val protectedModIds = setOf("NOMM-Integration", "NOSMR")

/**
 * Copies a local build into the game and writes the meta.json that makes NOMM treat it as a mod:
 * it shows in the library, can be toggled, and can be uninstalled. Registering the same id again
 * replaces the files in place and keeps the mod enabled or disabled as it was.
 */
fun registerLocalBuild(
    mods: MutableMap<String, ModMeta>,
    bepInExFolder: File,
    request: LocalBuildRequest,
): ModMeta {
    validateModId(request.id)?.let { throw ModException(ModErrorKind.INVALID, it) }
    val source = request.source.absoluteFile
    if (!source.exists()) throw ModException(ModErrorKind.NOT_FOUND, "${source.path} does not exist.")

    val pluginsDir = File(bepInExFolder, "plugins")
    val disabledDir = File(bepInExFolder, "disabledPlugins")
    val existing = mods[request.id]

    val catalogArtifact = existing?.artifact?.takeIf {
        existing.localBuild == null && !existing.isUnidentified && !it.resolvedDownloadUrl.isNullOrBlank()
    }
    if (catalogArtifact != null && !request.replaceCatalogMod) {
        throw ModException(
            ModErrorKind.INVALID,
            "${request.id} ${catalogArtifact.version} is installed from the catalog. " +
                "Pass --replace to overwrite it with the local build."
        )
    }

    // Windows folders ignore case, so "mymod" would land on top of an installed "MyMod".
    mods.keys.find { it != request.id && it.equals(request.id, ignoreCase = true) }?.let { other ->
        throw ModException(ModErrorKind.INVALID, "$other is already installed. Mod ids can't differ only in case; use --id $other.")
    }

    val existingDir = existing?.file?.takeIf { it.isDirectory }
    val targetDir = existingDir ?: if (request.enabled == false) {
        resolveArchiveEntry(disabledDir, request.id)
    } else {
        resolveArchiveEntry(pluginsDir, request.id)
    }
    if (existingDir == null && targetDir.exists()) {
        val owner = mods.values.find { it.file?.canonicalFile == targetDir.canonicalFile }
        if (owner != null) {
            throw ModException(ModErrorKind.INVALID, "${targetDir.path} already holds ${owner.id}. Use --id ${owner.id}, or uninstall it first.")
        }
    }

    // A loose DLL or folder dropped straight into plugins is adopted rather than copied, so BepInEx
    // doesn't load it twice.
    val sourceParent = source.parentFile?.canonicalFile
    val adoptsLooseMod = sourceParent == pluginsDir.canonicalFile || sourceParent == disabledDir.canonicalFile
    // A build output straight into the mod's folder is registered where it is. Replacing the folder
    // with just the DLL would delete everything built beside it.
    val metadataOnly = targetDir.exists() &&
        source.canonicalFile.toPath().startsWith(targetDir.canonicalFile.toPath())

    val previous = existing?.takeIf { it.localBuild != null }
    val version = request.version ?: previous?.artifact?.version
        ?: throw ModException(ModErrorKind.INVALID, "A version is needed the first time ${request.id} is registered.")
    val meta = ModMeta(
        id = request.id,
        artifact = Artifact(
            fileName = source.name,
            version = version,
            dependencies = request.dependencies ?: previous?.artifact?.dependencies.orEmpty(),
        ),
        localBuild = LocalBuild(source = source.path, name = request.name ?: previous?.localBuild?.name),
    )

    if (metadataOnly) {
        File(targetDir, "meta.json").writeText(json.encodeToString(meta))
    } else {
        val tmpRoot = File(bepInExFolder, NOMM_TMP_DIR)
        val staging = File(tmpRoot, "register-${request.id}-${System.nanoTime()}")
        try {
            staging.mkdirs()
            stageLocalBuild(source, staging)
            File(targetDir, "addons").takeIf { it.isDirectory }?.let { addons ->
                if (!addons.moveTo(File(staging, "addons"))) {
                    throw ModException(ModErrorKind.FAILED, "Could not keep the addons folder of ${request.id}.")
                }
            }
            File(staging, "meta.json").writeText(json.encodeToString(meta))

            if (targetDir.exists() && !deleteModFile(targetDir)) {
                throw ModException(
                    ModErrorKind.FAILED,
                    "Could not replace ${targetDir.path}. A running game keeps its plugin files locked."
                )
            }
            if (!staging.moveTo(targetDir)) {
                throw ModException(ModErrorKind.FAILED, "Could not move the build into ${targetDir.path}.")
            }
            if (adoptsLooseMod && source.canonicalFile != targetDir.canonicalFile) deleteModFile(source)
        } finally {
            deleteModFile(staging)
            tmpRoot.delete()
        }
    }

    if (adoptsLooseMod) {
        val sourcePath = source.canonicalFile
        mods.entries.removeIf { (id, mod) -> id != request.id && mod.file?.canonicalFile == sourcePath }
    }
    val isEnabled = !targetDir.canonicalPath.startsWith(disabledDir.canonicalPath + File.separator)
    mods[request.id] = meta.copy(file = targetDir, enabled = isEnabled)

    when (request.enabled) {
        true -> if (!isEnabled) enableMod(mods, bepInExFolder, request.id)
        false -> if (isEnabled) disableMod(mods, bepInExFolder, request.id)
        null -> {}
    }
    return mods.getValue(request.id)
}

/** Scratch space inside BepInEx, so moves into plugins stay on one volume. Never scanned for mods. */
const val NOMM_TMP_DIR = ".nomm-tmp"

private fun stageLocalBuild(source: File, staging: File) {
    when {
        source.isDirectory -> source.copyRecursively(staging, overwrite = true)
        source.extension.equals("zip", ignoreCase = true) -> {
            val extracted = File(staging.parentFile, staging.name + "-zip")
            try {
                extractZip(source, extracted)
                pluginContentRoot(extracted).copyRecursively(staging, overwrite = true)
            } finally {
                deleteModFile(extracted)
            }
        }
        else -> source.copyTo(File(staging, source.name), overwrite = true)
    }
}

private fun extractZip(zip: File, target: File) {
    ZipInputStream(zip.inputStream()).use { zipStream ->
        var entry = zipStream.nextEntry
        while (entry != null) {
            val file = resolveArchiveEntry(target, entry.name)
            if (entry.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile?.mkdirs()
                file.outputStream().use { zipStream.copyTo(it) }
            }
            entry = zipStream.nextEntry
        }
    }
}

/**
 * The folder inside an extracted archive that holds the plugin itself. Handles archives laid out
 * as BepInEx/plugins/<Mod>/..., a single wrapping folder, or files at the root.
 */
internal fun pluginContentRoot(extracted: File): File {
    val plugins = File(extracted, "BepInEx/plugins")
    if (plugins.isDirectory) {
        val children = plugins.listFiles().orEmpty()
        return children.singleOrNull()?.takeIf { it.isDirectory } ?: plugins
    }
    val children = extracted.listFiles().orEmpty()
    return children.singleOrNull()?.takeIf { it.isDirectory } ?: extracted
}

/** A modpack of the enabled mods: the mod list, plus the files of mods the catalog can't provide. */
fun buildModpack(mods: Collection<ModMeta>): ByteArray {
    val byteStream = ByteArrayOutputStream()
    ZipOutputStream(byteStream).use { zipStream ->
        val enabledMods = mods.filter { it.enabled == true }
        val modList = json.encodeToString(enabledMods.map { PackageReference(it.id, it.artifact?.version) })
        zipStream.putNextEntry(ZipEntry("modlist.nomm.json"))
        zipStream.write(modList.toByteArray())
        zipStream.closeEntry()

        enabledMods
            .asSequence()
            .filter { it.isUnidentified || it.localBuild != null }
            .mapNotNull { it.file }
            .filter { it.exists() }
            .forEach { modFile ->
                if (modFile.isDirectory) {
                    modFile.walkTopDown().forEach { file ->
                        val relativePath = file.relativeTo(modFile.parentFile).path.replace('\\', '/')
                        if (file.isDirectory) {
                            zipStream.putNextEntry(ZipEntry("mods/$relativePath/"))
                        } else {
                            zipStream.putNextEntry(ZipEntry("mods/$relativePath"))
                            file.inputStream().use { fileStream -> fileStream.copyTo(zipStream) }
                        }
                        zipStream.closeEntry()
                    }
                } else {
                    zipStream.putNextEntry(ZipEntry("mods/${modFile.name}"))
                    modFile.inputStream().use { fileStream -> fileStream.copyTo(zipStream) }
                    zipStream.closeEntry()
                }
            }
    }
    return byteStream.toByteArray()
}

/** NOSMR reads the enabled mod list from modpacks/current.nommpack inside its own folder. */
fun writeNosmrModpack(mods: Map<String, ModMeta>) {
    val nosmrFolder = mods["NOSMR"]?.file?.takeIf { it.isDirectory } ?: return
    val modpacks = File(nosmrFolder, "modpacks")
    modpacks.mkdirs()
    File(modpacks, "current.nommpack").writeBytes(buildModpack(mods.values))
}

/**
 * Deletes a mod file or folder. A symbolic link or junction is removed without following it,
 * so a mod linked to a build folder can't take the build folder with it.
 */
fun deleteModFile(file: File): Boolean = runCatching {
    deleteTree(file.toPath())
    true
}.getOrDefault(false)

private fun deleteTree(path: Path) {
    val attributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: java.nio.file.NoSuchFileException) {
        return
    }
    // Junctions read as directories on Windows, so links are checked first.
    if (!attributes.isSymbolicLink && !attributes.isOther && attributes.isDirectory) {
        Files.newDirectoryStream(path).use { children -> children.forEach(::deleteTree) }
    }
    Files.delete(path)
}

fun File.moveTo(destination: File): Boolean {
    if (!this.exists()) return false
    if (this.canonicalPath == destination.canonicalPath) return true

    return try {
        deleteModFile(destination)
        destination.parentFile?.mkdirs()
        Files.move(
            toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
        true
    } catch (_: AtomicMoveNotSupportedException) {
        // Only across volumes. Any other failure, such as a file the running game has open, must not
        // turn into a copy that deletes part of the source and then gives up.
        runCatching {
            this.copyRecursively(destination, overwrite = true)
            deleteModFile(this)
        }.getOrDefault(false)
    } catch (_: Exception) {
        false
    }
}
