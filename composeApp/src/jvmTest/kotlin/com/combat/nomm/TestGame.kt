package com.combat.nomm

import java.io.File
import java.nio.file.Files

/** A throwaway game folder with BepInEx installed, for tests that move mod files around. */
class TestGame : AutoCloseable {
    val root: File = Files.createTempDirectory("nomm-test-").toFile()
    val gameFolder = File(root, "Nuclear Option").apply { mkdirs() }
    val bepInEx = File(gameFolder, "BepInEx")
    val plugins = File(bepInEx, "plugins")
    val disabled = File(bepInEx, "disabledPlugins")

    init {
        File(gameFolder, "NuclearOption.exe").writeText("")
        File(bepInEx, "core").mkdirs()
        File(bepInEx, "core/BepInEx.dll").writeText("")
        plugins.mkdirs()
        disabled.mkdirs()
    }

    /** Writes a mod folder with a meta.json, the way NOMM installs catalog mods. */
    fun addMod(
        id: String,
        version: String = "1.0.0",
        enabled: Boolean = true,
        extends: String? = null,
        dependencies: List<String> = emptyList(),
        downloadUrl: String? = "https://example.invalid/$id.zip",
    ): File {
        val parent = when {
            extends != null && enabled -> File(scanInstalledMods(bepInEx).mods.getValue(extends).file, "addons")
            enabled -> plugins
            else -> disabled
        }
        val dir = File(parent, id).apply { mkdirs() }
        File(dir, "$id.dll").writeText("$id $version")
        val meta = ModMeta(
            id = id,
            artifact = Artifact(
                version = Version.fromString(version),
                downloadUrl = downloadUrl,
                extends = extends?.let { PackageReference(it) },
                dependencies = dependencies.map { PackageReference(it) },
            ),
        )
        File(dir, "meta.json").writeText(json.encodeToString(meta))
        return dir
    }

    fun mods(): MutableMap<String, ModMeta> = scanInstalledMods(bepInEx).mods.toMutableMap()

    /** A build output folder outside the game, like a mod project's bin/Release. */
    fun buildOutput(name: String, content: String = "build"): File {
        val dir = File(root, "build-$name").apply { mkdirs() }
        return File(dir, "$name.dll").apply { writeText(content) }
    }

    override fun close() {
        root.deleteRecursively()
    }
}

fun extension(id: String, vararg versions: String, dependencies: List<String> = emptyList(), extends: String? = null) = Extension(
    id = id,
    displayName = "$id mod",
    artifacts = versions.map { version ->
        Artifact(
            version = Version.fromString(version),
            downloadUrl = "https://example.invalid/$id-$version.zip",
            dependencies = dependencies.map { PackageReference(it) },
            extends = extends?.let { PackageReference(it) },
        )
    },
)

/** A downloader that "extracts" a single DLL named after the mod and version. */
val fakeDownloader: ModDownloader = { id, url, dir, _ ->
    check(!dir.exists()) { "download target must not exist" }
    dir.mkdirs()
    File(dir, "$id.dll").writeText(url)
}
