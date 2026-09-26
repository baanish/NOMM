package com.combat.nomm

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModInstallTest {
    private val catalog = listOf(
        extension("App", "1.0.0", "2.0.0", dependencies = listOf("Lib", "Installed")),
        extension("Lib", "1.0.0", "1.5.0"),
        extension("Installed", "1.0.0", "3.0.0"),
        extension("Addon", "1.0.0", extends = "App"),
    ).associateBy { it.id }

    @Test
    fun `plans missing dependencies first and leaves installed ones alone`() = TestGame().use { game ->
        game.addMod("Installed", version = "1.0.0")

        val plan = planInstall(listOf(PackageReference("App")), catalog, game.mods())

        assertEquals(listOf("Lib" to "1.5.0", "App" to "2.0.0"), plan.map { it.extension.id to it.artifact.version.toString() })
        assertEquals(InstallStep.Reason.DEPENDENCY, plan[0].reason)
    }

    @Test
    fun `plans a pinned version and rejects unknown mods and versions`() {
        val plan = planInstall(listOf(PackageReference("Lib", Version(1, 0, 0))), catalog, emptyMap())
        assertEquals(Version(1, 0, 0), plan.single().artifact.version)

        assertEquals(ModErrorKind.NOT_FOUND, assertFailsWith<ModException> {
            planInstall(listOf(PackageReference("Nope")), catalog, emptyMap())
        }.kind)
        assertEquals(ModErrorKind.NOT_FOUND, assertFailsWith<ModException> {
            planInstall(listOf(PackageReference("Lib", Version(9))), catalog, emptyMap())
        }.kind)
    }

    @Test
    fun `won't plan over a local build unless asked`() = TestGame().use { game ->
        val mods = game.mods()
        registerLocalBuild(mods, game.bepInEx, LocalBuildRequest(game.buildOutput("Lib"), "Lib", Version(0, 1)))

        assertEquals(ModErrorKind.INVALID, assertFailsWith<ModException> {
            planInstall(listOf(PackageReference("Lib")), catalog, mods)
        }.kind)
        assertEquals(1, planInstall(listOf(PackageReference("Lib")), catalog, mods, replaceLocalBuilds = true).size)
    }

    @Test
    fun `skips a mod that is already at the requested version`() = TestGame().use { game ->
        game.addMod("Lib", version = "1.5.0")
        val warnings = mutableListOf<String>()

        assertTrue(planInstall(listOf(PackageReference("Lib")), catalog, game.mods(), warnings = warnings).isEmpty())
        assertEquals(1, warnings.size)
    }

    @Test
    fun `installs new mods enabled, with meta json`() = TestGame().use { game ->
        val mods = game.mods()
        val step = planInstall(listOf(PackageReference("Lib")), catalog, mods).single()

        val installed = runBlocking { installStep(mods, game.bepInEx, step, fakeDownloader, ModLock(File(game.root, "lock"))) }

        assertEquals(true, installed.enabled)
        assertTrue(File(game.plugins, "Lib/Lib.dll").isFile)
        assertEquals(Version(1, 5, 0), scanInstalledMods(game.bepInEx).mods.getValue("Lib").artifact?.version)
        assertFalse(File(game.bepInEx, NOMM_TMP_DIR).exists())
    }

    @Test
    fun `updates keep a disabled mod disabled and leave no old copy`() = TestGame().use { game ->
        game.addMod("Lib", version = "1.0.0", enabled = false)
        val mods = game.mods()
        val step = planInstall(listOf(PackageReference("Lib")), catalog, mods).single()

        val installed = runBlocking { installStep(mods, game.bepInEx, step, fakeDownloader, ModLock(File(game.root, "lock"))) }

        assertEquals(false, installed.enabled)
        val scan = scanInstalledMods(game.bepInEx)
        assertEquals(Version(1, 5, 0), scan.mods.getValue("Lib").artifact?.version)
        assertTrue(scan.duplicates.isEmpty())
    }

    @Test
    fun `updating a mod re-enables the addons that were enabled on it`() = TestGame().use { game ->
        game.addMod("App", version = "1.0.0")
        game.addMod("Addon", extends = "App")
        val mods = game.mods()
        val step = InstallStep(catalog.getValue("App"), catalog.getValue("App").artifacts.last(), InstallStep.Reason.REQUESTED, Version(1))

        runBlocking { installStep(mods, game.bepInEx, step, fakeDownloader, ModLock(File(game.root, "lock"))) }

        assertEquals(true, mods.getValue("Addon").enabled)
        assertTrue(File(game.plugins, "App/addons/Addon/Addon.dll").isFile)
    }

    @Test
    fun `a failed download leaves the installed version untouched`() = TestGame().use { game ->
        game.addMod("Lib", version = "1.0.0")
        val mods = game.mods()
        val step = planInstall(listOf(PackageReference("Lib")), catalog, mods).single()

        val error = assertFailsWith<ModException> {
            runBlocking {
                installStep(mods, game.bepInEx, step, { _, _, _, _ -> error("connection reset") }, ModLock(File(game.root, "lock")))
            }
        }

        assertEquals(ModErrorKind.DOWNLOAD, error.kind)
        assertEquals(true, mods.getValue("Lib").enabled)
        assertEquals("Lib 1.0.0", File(game.plugins, "Lib/Lib.dll").readText())
    }
}
