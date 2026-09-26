package com.combat.nomm

import java.io.File

/** Downloads [url] into [dir], which must not exist yet, after checking [hash]. Throws on failure. */
typealias ModDownloader = suspend (id: String, url: String, dir: File, hash: String?) -> Unit

data class InstallStep(
    val extension: Extension,
    val artifact: Artifact,
    val reason: Reason,
    val previousVersion: Version?,
) {
    enum class Reason { REQUESTED, DEPENDENCY }
}

/**
 * Orders the catalog installs needed for [requests], dependencies first. Missing dependencies
 * are added at their latest version; installed dependencies are left as they are.
 */
fun planInstall(
    requests: List<PackageReference>,
    manifest: Map<String, Extension>,
    installed: Map<String, ModMeta>,
    withDependencies: Boolean = true,
    replaceLocalBuilds: Boolean = false,
    warnings: MutableList<String> = mutableListOf(),
): List<InstallStep> {
    val steps = linkedMapOf<String, InstallStep>()
    val visiting = mutableSetOf<String>()

    fun visit(ref: PackageReference, reason: InstallStep.Reason) {
        if (ref.id in steps || !visiting.add(ref.id)) return
        val current = installed[ref.id]
        if (reason == InstallStep.Reason.DEPENDENCY && current != null) return

        val extension = manifest[ref.id]
        if (extension == null) {
            if (reason == InstallStep.Reason.REQUESTED) {
                throw ModException(ModErrorKind.NOT_FOUND, "No mod with id \"${ref.id}\" in the catalog.")
            }
            warnings.add("Dependency ${ref.id} is not in the catalog, so it was not installed.")
            return
        }

        val artifact = if (ref.version != null) {
            extension.artifacts.find { it.version == ref.version } ?: throw ModException(
                ModErrorKind.NOT_FOUND,
                "${ref.id} has no version ${ref.version}. Available: " +
                    extension.artifacts.sortedByDescending { it.version }.joinToString { it.version.toString() }
            )
        } else {
            extension.artifacts.maxByOrNull { it.version }
                ?: throw ModException(ModErrorKind.NOT_FOUND, "${ref.id} has no downloadable versions.")
        }

        if (current?.localBuild != null && !replaceLocalBuilds) {
            throw ModException(
                ModErrorKind.INVALID,
                "${ref.id} is a registered local build. Pass --replace to overwrite it with the catalog version."
            )
        }
        if (current != null && !current.isUnidentified && current.artifact?.version == artifact.version) {
            warnings.add("${ref.id} ${artifact.version} is already installed.")
            return
        }

        if (withDependencies) {
            artifact.extends?.let { visit(PackageReference(it.id), InstallStep.Reason.DEPENDENCY) }
            artifact.dependencies.forEach { visit(PackageReference(it.id), InstallStep.Reason.DEPENDENCY) }
        }
        steps[ref.id] = InstallStep(extension, artifact, reason, current?.artifact?.version)
    }

    requests.forEach { visit(it, InstallStep.Reason.REQUESTED) }
    return steps.values.toList()
}

/**
 * Installs one catalog artifact. The download finishes before the old version is touched, and a
 * failed swap restores it. New installs are enabled; updates keep the mod enabled or disabled as it
 * was, and re-enable the addons that were enabled on top of it.
 */
suspend fun installStep(
    mods: MutableMap<String, ModMeta>,
    bepInExFolder: File,
    step: InstallStep,
    download: ModDownloader,
    lock: ModLock,
): ModMeta {
    val id = step.extension.id
    val url = step.artifact.resolvedDownloadUrl?.takeIf { it.isNotBlank() }
        ?: throw ModException(ModErrorKind.DOWNLOAD, "$id ${step.artifact.version} has no download link in the catalog.")

    val tmpRoot = File(bepInExFolder, NOMM_TMP_DIR)
    val downloadDir = File(tmpRoot, "install-$id-${System.nanoTime()}")
    try {
        try {
            download(id, url, downloadDir, step.artifact.hash)
        } catch (e: ModException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ModException(ModErrorKind.DOWNLOAD, "Downloading $id ${step.artifact.version} failed: ${e.message}")
        }
        val meta = ModMeta(id = id, artifact = step.artifact)
        File(downloadDir, "meta.json").writeText(json.encodeToString(meta))

        return lock.withLock { swapInstall(mods, bepInExFolder, id, meta, downloadDir, tmpRoot) }
    } finally {
        deleteModFile(downloadDir)
        tmpRoot.delete()
    }
}

private fun swapInstall(
    mods: MutableMap<String, ModMeta>,
    bepInExFolder: File,
    id: String,
    meta: ModMeta,
    downloadDir: File,
    tmpRoot: File,
): ModMeta {
    val disabledDir = File(bepInExFolder, "disabledPlugins").apply { mkdirs() }
    val targetDir = resolveArchiveEntry(disabledDir, id)

    val existing = mods[id]
    val wasEnabled = existing?.enabled == true
    val enabledAddons = mods.values.filter { it.artifact?.extends?.id == id && it.enabled == true }.map { it.id }

    var backup: File? = null
    var backupOrigin: File? = null
    if (existing?.file?.exists() == true) {
        if (!disableMod(mods, bepInExFolder, id)) {
            throw ModException(ModErrorKind.FAILED, "Could not disable the installed $id. A running game keeps its files locked.")
        }
        val disabledFile = mods.getValue(id).file!!
        val backupFile = File(tmpRoot, "backup-$id-${System.nanoTime()}")
        if (!disabledFile.moveTo(backupFile)) {
            throw ModException(ModErrorKind.FAILED, "Could not move the installed $id out of the way.")
        }
        backup = backupFile
        backupOrigin = disabledFile
    }

    fun restore() {
        val saved = backup ?: return
        val origin = backupOrigin ?: return
        if (saved.moveTo(origin)) {
            mods[id] = (existing ?: meta).copy(file = origin, enabled = false)
            if (wasEnabled) enableMod(mods, bepInExFolder, id)
        }
    }

    if (targetDir.exists() && !deleteModFile(targetDir)) {
        restore()
        throw ModException(ModErrorKind.FAILED, "Could not clear ${targetDir.path}.")
    }
    if (!downloadDir.moveTo(targetDir)) {
        restore()
        throw ModException(ModErrorKind.FAILED, "Could not move $id into ${targetDir.path}.")
    }

    mods[id] = meta.copy(file = targetDir, enabled = false)
    if (existing == null || wasEnabled) {
        enableMod(mods, bepInExFolder, id)
        enabledAddons.forEach { enableMod(mods, bepInExFolder, it) }
    }
    backup?.let(::deleteModFile)
    return mods.getValue(id)
}
