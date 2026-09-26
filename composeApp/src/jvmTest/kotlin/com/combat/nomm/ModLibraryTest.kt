package com.combat.nomm

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModLibraryTest {
    @Test
    fun `scan reads enabled, disabled, unidentified and addon mods`() = TestGame().use { game ->
        game.addMod("Base")
        game.addMod("Addon", extends = "Base")
        game.addMod("Off", enabled = false)
        File(game.plugins, "Loose.dll").writeText("")

        val mods = scanInstalledMods(game.bepInEx).mods

        assertEquals(setOf("Base", "Addon", "Off", "Loose.dll"), mods.keys)
        assertEquals(true, mods.getValue("Addon").enabled)
        assertEquals(false, mods.getValue("Off").enabled)
        assertTrue(mods.getValue("Loose.dll").isUnidentified)
    }

    @Test
    fun `scan reports older duplicates without deleting them`() = TestGame().use { game ->
        game.addMod("Dup", version = "2.0.0")
        val old = File(game.disabled, "Dup-old").apply { mkdirs() }
        File(old, "meta.json").writeText(json.encodeToString(ModMeta("Dup", Artifact(version = Version(1, 0, 0)))))

        val scan = scanInstalledMods(game.bepInEx)

        assertEquals(Version(2, 0, 0), scan.mods.getValue("Dup").artifact?.version)
        assertEquals(listOf(old), scan.duplicates)
        assertTrue(old.exists())
    }

    @Test
    fun `disabling a mod disables its addons, enabling an addon enables its parent`() = TestGame().use { game ->
        game.addMod("Base")
        game.addMod("Addon", extends = "Base")
        val mods = game.mods()

        assertTrue(disableMod(mods, game.bepInEx, "Base"))
        assertEquals(false, mods.getValue("Base").enabled)
        assertEquals(false, mods.getValue("Addon").enabled)
        assertTrue(File(game.disabled, "Base").isDirectory)
        assertTrue(File(game.disabled, "Addon").isDirectory)

        assertTrue(enableMod(mods, game.bepInEx, "Addon"))
        assertEquals(true, mods.getValue("Base").enabled)
        assertTrue(File(game.plugins, "Base/addons/Addon/Addon.dll").isFile)
        assertEquals(mods.keys, scanInstalledMods(game.bepInEx).mods.keys)
    }

    @Test
    fun `problems list missing and disabled dependencies`() = TestGame().use { game ->
        game.addMod("Needs", dependencies = listOf("Missing", "Off"))
        game.addMod("Off", enabled = false)

        val problems = withComputedState(game.mods(), emptyMap()).getValue("Needs").problems

        assertEquals(listOf("Dependency Missing not found", "Dependency Off is disabled"), problems)
    }

    @Test
    fun `registering a DLL copies it into plugins with local build metadata`() = TestGame().use { game ->
        val dll = game.buildOutput("MyMod")
        val mods = game.mods()

        val registered = registerLocalBuild(
            mods, game.bepInEx,
            LocalBuildRequest(dll, "MyMod", Version(0, 1, 0), name = "My Mod", dependencies = listOf(PackageReference("Dep"))),
        )

        val installedDll = File(game.plugins, "MyMod/MyMod.dll")
        assertEquals("build", installedDll.readText())
        assertTrue(dll.isFile, "the build output stays where it was")
        assertEquals(true, registered.enabled)
        val onDisk = scanInstalledMods(game.bepInEx).mods.getValue("MyMod")
        assertEquals(Version(0, 1, 0), onDisk.artifact?.version)
        assertEquals(dll.absolutePath, onDisk.localBuild?.source)
        assertEquals("My Mod", onDisk.localBuild?.name)
        assertEquals(listOf("Dep"), onDisk.artifact?.dependencies?.map { it.id })
    }

    @Test
    fun `registering again replaces the files and keeps the disabled state and addons`() = TestGame().use { game ->
        val dll = game.buildOutput("MyMod", content = "v1")
        val mods = game.mods()
        registerLocalBuild(mods, game.bepInEx, LocalBuildRequest(dll, "MyMod", Version(0, 1, 0)))
        disableMod(mods, game.bepInEx, "MyMod")
        File(game.disabled, "MyMod/stale.txt").writeText("old")
        File(game.disabled, "MyMod/addons/Child").mkdirs()

        dll.writeText("v2")
        val registered = registerLocalBuild(mods, game.bepInEx, LocalBuildRequest(dll, "MyMod", Version(0, 2, 0)))

        assertEquals(false, registered.enabled)
        assertEquals("v2", File(game.disabled, "MyMod/MyMod.dll").readText())
        assertFalse(File(game.disabled, "MyMod/stale.txt").exists())
        assertTrue(File(game.disabled, "MyMod/addons/Child").isDirectory)
        assertFalse(File(game.plugins, "MyMod").exists())
        assertEquals(Version(0, 2, 0), scanInstalledMods(game.bepInEx).mods.getValue("MyMod").artifact?.version)
    }

    @Test
    fun `registering again without metadata keeps the previous version, name and dependencies`() = TestGame().use { game ->
        val dll = game.buildOutput("MyMod")
        val mods = game.mods()
        registerLocalBuild(
            mods, game.bepInEx,
            LocalBuildRequest(dll, "MyMod", Version(0, 4), name = "My Mod", dependencies = listOf(PackageReference("Dep"))),
        )

        registerLocalBuild(mods, game.bepInEx, LocalBuildRequest(dll, "MyMod"))

        val onDisk = scanInstalledMods(game.bepInEx).mods.getValue("MyMod")
        assertEquals(Version(0, 4), onDisk.artifact?.version)
        assertEquals("My Mod", onDisk.localBuild?.name)
        assertEquals(listOf("Dep"), onDisk.artifact?.dependencies?.map { it.id })
        assertEquals(ModErrorKind.INVALID, assertFailsWith<ModException> {
            registerLocalBuild(mods, game.bepInEx, LocalBuildRequest(game.buildOutput("New"), "New"))
        }.kind)
    }

    @Test
    fun `registering over a catalog install needs replace`() = TestGame().use { game ->
        game.addMod("Catalog")
        val dll = game.buildOutput("Catalog")
        val mods = game.mods()

        val error = assertFailsWith<ModException> {
            registerLocalBuild(mods, game.bepInEx, LocalBuildRequest(dll, "Catalog", Version(9)))
        }
        assertEquals(ModErrorKind.INVALID, error.kind)

        registerLocalBuild(mods, game.bepInEx, LocalBuildRequest(dll, "Catalog", Version(9), replaceCatalogMod = true))
        assertTrue(scanInstalledMods(game.bepInEx).mods.getValue("Catalog").localBuild != null)
    }

    @Test
    fun `registering a zip finds the plugin folder inside a BepInEx layout`() = TestGame().use { game ->
        val zip = File(game.root, "MyMod-plugin-only.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("BepInEx/plugins/MyMod/MyMod.dll"))
            out.write("zipped".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("BepInEx/plugins/MyMod/README.txt"))
            out.closeEntry()
        }

        registerLocalBuild(game.mods(), game.bepInEx, LocalBuildRequest(zip, "MyMod", Version(1)))

        assertEquals("zipped", File(game.plugins, "MyMod/MyMod.dll").readText())
        assertTrue(File(game.plugins, "MyMod/README.txt").exists())
        assertFalse(File(game.plugins, "MyMod/BepInEx").exists())
    }

    @Test
    fun `registering a loose DLL in plugins adopts it instead of copying`() = TestGame().use { game ->
        val loose = File(game.plugins, "Loose.dll").apply { writeText("loose") }
        val mods = game.mods()

        registerLocalBuild(mods, game.bepInEx, LocalBuildRequest(loose, "Loose", Version(1)))

        assertFalse(loose.exists())
        assertEquals("loose", File(game.plugins, "Loose/Loose.dll").readText())
        assertEquals(setOf("Loose"), mods.keys)
        assertEquals(setOf("Loose"), scanInstalledMods(game.bepInEx).mods.keys)
    }

    @Test
    fun `registering a folder already in place only writes metadata`() = TestGame().use { game ->
        val dir = File(game.plugins, "Manual").apply { mkdirs() }
        File(dir, "Manual.dll").writeText("manual")

        registerLocalBuild(game.mods(), game.bepInEx, LocalBuildRequest(dir, "Manual", Version(3)))

        assertEquals("manual", File(dir, "Manual.dll").readText())
        assertEquals(Version(3), scanInstalledMods(game.bepInEx).mods.getValue("Manual").artifact?.version)
    }

    @Test
    fun `registering a DLL built into its mod folder keeps the files beside it`() = TestGame().use { game ->
        val dir = File(game.plugins, "MyMod").apply { mkdirs() }
        val dll = File(dir, "MyMod.dll").apply { writeText("dll") }
        File(dir, "assets.bundle").writeText("assets")

        registerLocalBuild(game.mods(), game.bepInEx, LocalBuildRequest(dll, "MyMod", Version(1)))

        assertEquals("dll", dll.readText())
        assertEquals("assets", File(dir, "assets.bundle").readText())
        assertEquals(Version(1), scanInstalledMods(game.bepInEx).mods.getValue("MyMod").artifact?.version)
    }

    @Test
    fun `registering an id that differs only in case is refused`() = TestGame().use { game ->
        game.addMod("MyMod")
        val mods = game.mods()

        val error = assertFailsWith<ModException> {
            registerLocalBuild(mods, game.bepInEx, LocalBuildRequest(game.buildOutput("mymod"), "mymod", Version(1), replaceCatalogMod = true))
        }

        assertEquals(ModErrorKind.INVALID, error.kind)
        assertEquals("MyMod 1.0.0", File(game.plugins, "MyMod/MyMod.dll").readText())
    }

    @Test
    fun `local builds never report catalog updates`() = TestGame().use { game ->
        val mods = game.mods()
        registerLocalBuild(mods, game.bepInEx, LocalBuildRequest(game.buildOutput("Shared"), "Shared", Version(0, 1)))

        val computed = withComputedState(mods, mapOf("Shared" to extension("Shared", "5.0.0")))

        assertFalse(computed.getValue("Shared").hasUpdate)
    }

    @Test
    fun `rejects mod ids that can't be folder names`() {
        listOf("", "../escape", "a/b", "a\\b", "con:", "CON", "nul.txt", "com1", "addons", "NOSMR", "trailing.").forEach {
            assertNotNull(validateModId(it), "\"$it\" should be rejected")
        }
        assertNull(validateModId("BaanishUiImprovements"))
        assertNull(validateModId("Console"))
    }

    @Test
    fun `uninstalling a local build leaves its source alone`() = TestGame().use { game ->
        val dll = game.buildOutput("MyMod")
        val mods = game.mods()
        registerLocalBuild(mods, game.bepInEx, LocalBuildRequest(dll, "MyMod", Version(1)))

        assertTrue(uninstallMod(mods, game.bepInEx, "MyMod"))

        assertFalse(File(game.plugins, "MyMod").exists())
        assertFalse(File(game.disabled, "MyMod").exists())
        assertTrue(dll.isFile)
        assertNull(mods["MyMod"])
    }

    @Test
    fun `deleting a linked mod removes the link, not the linked folder`() = TestGame().use { game ->
        val buildDir = File(game.root, "bin").apply { mkdirs() }
        val buildDll = File(buildDir, "Linked.dll").apply { writeText("keep me") }
        val link = File(game.plugins, "Linked")
        try {
            Files.createSymbolicLink(link.toPath(), buildDir.toPath())
        } catch (_: Exception) {
            return@use // Symbolic links need extra rights on Windows.
        }

        assertTrue(deleteModFile(link))

        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep me", buildDll.readText())
    }

    @Test
    fun `modpacks carry the files of enabled local builds`() = TestGame().use { game ->
        game.addMod("Catalog")
        val mods = game.mods()
        registerLocalBuild(mods, game.bepInEx, LocalBuildRequest(game.buildOutput("Local"), "Local", Version(1)))

        val entries = mutableListOf<String>()
        java.util.zip.ZipInputStream(buildModpack(mods.values).inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries.add(it.name) }
        }

        assertTrue("modlist.nomm.json" in entries)
        assertTrue("mods/Local/Local.dll" in entries)
        assertFalse(entries.any { it.startsWith("mods/Catalog") })
    }
}
