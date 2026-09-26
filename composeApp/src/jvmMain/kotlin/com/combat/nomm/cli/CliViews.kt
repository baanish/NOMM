package com.combat.nomm.cli

import com.combat.nomm.Extension
import com.combat.nomm.ModMeta
import com.combat.nomm.protectedModIds
import kotlinx.serialization.Serializable

// The JSON shapes the CLI prints with --json. Treat field names as a stable interface for agents
// and scripts: add fields freely, but don't rename or remove them.

@Serializable
internal data class ModView(
    val id: String,
    val name: String,
    val version: String?,
    val enabled: Boolean,
    /** "catalog", "local" (registered build or other mod with metadata) or "unidentified" (no meta.json). */
    val source: String,
    val path: String?,
    val latestVersion: String?,
    val updateAvailable: Boolean,
    val problems: List<String>,
    val dependencies: List<String>,
    val extends: String?,
    val localBuildSource: String?,
    val managedByNomm: Boolean,
)

internal fun ModMeta.toView(catalog: Map<String, Extension>): ModView {
    val extension = catalog[id]
    val source = when {
        localBuild != null -> "local"
        isUnidentified -> "unidentified"
        extension != null -> "catalog"
        else -> "local"
    }
    return ModView(
        id = id,
        name = localBuild?.name ?: extension?.displayName ?: id,
        version = artifact?.version?.toString(),
        enabled = enabled == true,
        source = source,
        path = file?.path,
        latestVersion = if (source == "catalog") extension?.artifacts?.maxByOrNull { it.version }?.version?.toString() else null,
        updateAvailable = hasUpdate,
        problems = problems,
        dependencies = artifact?.dependencies?.map { it.id }.orEmpty(),
        extends = artifact?.extends?.id,
        localBuildSource = localBuild?.source,
        managedByNomm = id in protectedModIds,
    )
}

@Serializable
internal data class CatalogVersionView(
    val version: String,
    val gameVersion: String?,
    val category: String?,
    val dependencies: List<String>,
    val extends: String?,
    val incompatibilities: List<String>,
)

@Serializable
internal data class CatalogView(
    val id: String,
    val name: String,
    val description: String,
    val authors: List<String>,
    val tags: List<String>,
    val links: Map<String, String>,
    val side: String?,
    val downloads: Int?,
    val latestVersion: String?,
    val versions: List<CatalogVersionView>,
)

internal fun Extension.toView() = CatalogView(
    id = id,
    name = displayName,
    description = description,
    authors = authors,
    tags = tags,
    links = urls.associate { it.name to it.url },
    side = isClientOrServer?.name?.lowercase(),
    downloads = downloadCount,
    latestVersion = artifacts.maxByOrNull { it.version }?.version?.toString(),
    versions = artifacts.sortedByDescending { it.version }.map { artifact ->
        CatalogVersionView(
            version = artifact.version.toString(),
            gameVersion = artifact.gameVersion,
            category = artifact.category,
            dependencies = artifact.dependencies.map { it.id },
            extends = artifact.extends?.id,
            incompatibilities = artifact.incompatibilities.map { it.id },
        )
    },
)

@Serializable
internal data class InfoView(val installed: ModView?, val catalog: CatalogView?)

@Serializable
internal data class SearchHit(
    val id: String,
    val name: String,
    val latestVersion: String?,
    val authors: List<String>,
    val tags: List<String>,
    val description: String,
    val downloads: Int?,
    val installedVersion: String?,
)

@Serializable
internal data class ModCounts(val total: Int, val enabled: Int, val disabled: Int, val updatesAvailable: Int, val withProblems: Int)

@Serializable
internal data class StatusView(
    val nommVersion: String,
    val gameFolder: String?,
    val gameFolderProblem: String?,
    val bepInExInstalled: Boolean,
    val gameRunning: Boolean,
    val catalogSource: String,
    val catalogMods: Int,
    val mods: ModCounts?,
    val bepInExLog: String?,
    val unityLog: String?,
    val dataFolder: String,
    val ready: Boolean,
)

@Serializable
internal data class InstalledView(
    val id: String,
    val version: String,
    val previousVersion: String?,
    val reason: String,
    val enabled: Boolean,
    val path: String?,
)

@Serializable
internal data class InstallView(val installed: List<InstalledView>, val enabledDependencies: List<String>)

@Serializable
internal data class StateChange(val id: String, val enabled: Boolean)

@Serializable
internal data class ToggleView(val changed: List<StateChange>)

@Serializable
internal data class UninstallView(val removed: List<String>, val disabledAddons: List<String>)

@Serializable
internal data class BepInExView(val installed: Boolean, val alreadyInstalled: Boolean, val gameFolder: String)

@Serializable
internal data class LaunchView(val alreadyRunning: Boolean, val running: Boolean)

@Serializable
internal data class LogView(val source: String, val file: String, val lines: List<String>)

@Serializable
internal data class LogFollowView(val source: String, val file: String, val linesPrinted: Int, val stoppedBecause: String)

@Serializable
internal data class LogLine(val line: String)

@Serializable
internal data class VersionView(val version: String)

@Serializable
internal data class SkillView(val content: String?, val file: String?)

@Serializable
internal data class HelpView(val text: String)
