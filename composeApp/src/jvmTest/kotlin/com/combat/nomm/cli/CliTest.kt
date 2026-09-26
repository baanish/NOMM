package com.combat.nomm.cli

import com.combat.nomm.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CliTest {
    private class FakeEnvironment(val game: TestGame) : CliEnvironment {
        override val appVersion = "9.9.9"
        override val dataDir = File(game.root, "nomm-data")
        var gameFolder: File? = game.gameFolder
        var catalog = listOf(extension("Lib", "1.0.0", "2.0.0"), extension("App", "1.0.0", dependencies = listOf("Lib")))
        @Volatile var running = false
        var launched = false

        override fun configuredGameFolder() = gameFolder
        override suspend fun loadCatalog(offline: Boolean) = CatalogResult(catalog, CatalogResult.Source.LIVE)
        override val downloader: ModDownloader = fakeDownloader
        override suspend fun installBepInEx(gameFolder: File) {
            File(gameFolder, "BepInEx/core").mkdirs()
            File(gameFolder, "BepInEx/core/BepInEx.dll").writeText("")
        }
        override fun isGameRunning() = running
        override fun launchGame(gameFolder: File?): Boolean {
            launched = true
            running = true
            return true
        }
    }

    private class Run(val exitCode: Int, val stdout: String, val stderr: String) {
        val json: JsonObject by lazy { Json.parseToJsonElement(stdout.trim().lines().last()).jsonObject }
        val data get() = json.getValue("data")
    }

    private fun FakeEnvironment.cli(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli(this, PrintStream(out, true), PrintStream(err, true)).run(args.toList())
        return Run(code, out.toString(), err.toString())
    }

    @Test
    fun `list prints a JSON envelope of installed mods`() = TestGame().use { game ->
        game.addMod("Lib", version = "1.0.0")
        game.addMod("Off", enabled = false)
        val env = FakeEnvironment(game)

        val run = env.cli("list", "--json")

        assertEquals(0, run.exitCode)
        assertTrue(run.json.getValue("ok").jsonPrimitive.boolean)
        val mods = run.data.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Lib", "Off"), mods.map { it.getValue("id").jsonPrimitive.content })
        val lib = mods.first()
        assertEquals("catalog", lib.getValue("source").jsonPrimitive.content)
        assertTrue(lib.getValue("updateAvailable").jsonPrimitive.boolean)
        assertEquals("2.0.0", lib.getValue("latestVersion").jsonPrimitive.content)

        val updates = env.cli("list", "--updates", "--json").data.jsonArray
        assertEquals(1, updates.size)
    }

    @Test
    fun `status succeeds without a game folder and says it's not ready`() = TestGame().use { game ->
        val env = FakeEnvironment(game).apply { gameFolder = null }

        val run = env.cli("status", "--json")

        assertEquals(0, run.exitCode)
        assertFalse(run.data.jsonObject.getValue("ready").jsonPrimitive.boolean)
    }

    @Test
    fun `commands that need the game fail with the environment exit code`() = TestGame().use { game ->
        val env = FakeEnvironment(game).apply { gameFolder = File(game.root, "missing") }

        val run = env.cli("list", "--json")

        assertEquals(4, run.exitCode)
        assertEquals("environment", run.json.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `enable and disable report every changed mod and signal the GUI`() = TestGame().use { game ->
        game.addMod("Base")
        game.addMod("Addon", extends = "Base")
        val env = FakeEnvironment(game)
        val sync = ModSync(env.dataDir)
        val before = sync.lastChange()

        val run = env.cli("disable", "Base", "--json")

        assertEquals(0, run.exitCode)
        val changed = run.data.jsonObject.getValue("changed").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf("Addon", "Base"), changed)
        assertTrue(File(game.disabled, "Base").isDirectory)
        assertNotEquals(before, sync.lastChange())

        assertEquals(0, env.cli("enable", "Addon").exitCode)
        assertTrue(File(game.plugins, "Base/addons/Addon").isDirectory)
    }

    @Test
    fun `changes fail while the game runs unless asked to wait`() = TestGame().use { game ->
        game.addMod("Lib")
        val env = FakeEnvironment(game).apply { running = true }

        assertEquals(5, env.cli("disable", "Lib").exitCode)
        assertTrue(File(game.plugins, "Lib").isDirectory)

        thread {
            Thread.sleep(300)
            env.running = false
        }
        val run = env.cli("disable", "Lib", "--wait-for-game")
        assertEquals(0, run.exitCode, run.stderr)
        assertTrue(File(game.disabled, "Lib").isDirectory)
    }

    @Test
    fun `install adds missing dependencies and installs BepInEx first`() = TestGame().use { game ->
        File(game.bepInEx, "core/BepInEx.dll").delete()
        val env = FakeEnvironment(game)

        val run = env.cli("install", "App", "--json")

        assertEquals(0, run.exitCode, run.stdout + run.stderr)
        val installed = run.data.jsonObject.getValue("installed").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf("Lib", "App"), installed)
        assertTrue(File(game.plugins, "App/App.dll").isFile)
        assertTrue(File(game.plugins, "Lib/Lib.dll").isFile)
    }

    @Test
    fun `update skips local builds`() = TestGame().use { game ->
        val env = FakeEnvironment(game)
        val dll = game.buildOutput("Lib")
        assertEquals(0, env.cli("dev", "register", dll.path, "--version", "0.1.0").exitCode)

        val run = env.cli("update", "Lib", "--json")

        assertEquals(0, run.exitCode)
        assertTrue(run.data.jsonObject.getValue("installed").jsonArray.isEmpty())
        assertEquals(1, run.json.getValue("warnings").jsonArray.size)
        assertEquals("build", File(game.plugins, "Lib/Lib.dll").readText())
    }

    @Test
    fun `dev register deploys a build and shows it as a local mod`() = TestGame().use { game ->
        val env = FakeEnvironment(game)
        val dll = game.buildOutput("MyMod")

        val run = env.cli("dev", "register", dll.path, "--version", "0.3.0", "--name", "My Mod", "--dependency", "Lib", "--json")

        assertEquals(0, run.exitCode, run.stdout + run.stderr)
        val view = run.data.jsonObject
        assertEquals("MyMod", view.getValue("id").jsonPrimitive.content)
        assertEquals("local", view.getValue("source").jsonPrimitive.content)
        assertEquals("0.3.0", view.getValue("version").jsonPrimitive.content)
        assertTrue(run.json.getValue("warnings").jsonArray.any { "Lib" in it.jsonPrimitive.content })

        val listed = env.cli("list", "--local", "--json").data.jsonArray.single().jsonObject
        assertEquals("My Mod", listed.getValue("name").jsonPrimitive.content)
        assertEquals(0, env.cli("uninstall", "MyMod").exitCode)
        assertFalse(File(game.plugins, "MyMod").exists())
        assertTrue(dll.exists())
    }

    @Test
    fun `dev register needs an id for folders and a version`() = TestGame().use { game ->
        val env = FakeEnvironment(game)
        val folder = game.buildOutput("MyMod").parentFile

        assertEquals(2, env.cli("dev", "register", folder.path, "--version", "1.0").exitCode)
        assertEquals(2, env.cli("dev", "register", File(folder, "MyMod.dll").path).exitCode)
        assertEquals(0, env.cli("dev", "register", folder.path, "--id", "MyMod", "--version", "1.0").exitCode)
    }

    @Test
    fun `usage errors and unknown mods have their own exit codes`() = TestGame().use { game ->
        val env = FakeEnvironment(game)

        assertEquals(2, env.cli("frobnicate").exitCode)
        assertEquals(2, env.cli("list", "--bogus").exitCode)
        assertEquals(2, env.cli("update").exitCode)
        assertEquals(3, env.cli("enable", "Nope").exitCode)
        assertEquals(3, env.cli("info", "Nope").exitCode)
        assertEquals(0, env.cli("help").exitCode)
        assertEquals(0, env.cli("help", "dev", "register").exitCode)
        assertEquals(0, env.cli("install", "--help").exitCode)
    }

    @Test
    fun `logs prints the end of the BepInEx log, filtered`() = TestGame().use { game ->
        File(game.bepInEx, "LogOutput.log").writeText((1..10).joinToString("\n") { if (it % 2 == 0) "[Error] line $it" else "[Info] line $it" })
        val env = FakeEnvironment(game)

        val run = env.cli("logs", "-n", "2", "--grep", "Error", "--json")

        assertEquals(0, run.exitCode)
        assertEquals(listOf("[Error] line 8", "[Error] line 10"), run.data.jsonObject.getValue("lines").jsonArray.map { it.jsonPrimitive.content })

        File(game.bepInEx, "LogOutput.log").delete()
        assertEquals(3, env.cli("logs").exitCode)
    }

    @Test
    fun `logs follow stops when the game exits`() = TestGame().use { game ->
        val log = File(game.bepInEx, "LogOutput.log").apply { writeText("old\n") }
        val env = FakeEnvironment(game).apply { running = true }
        thread {
            Thread.sleep(700)
            log.appendText("new line\n")
            Thread.sleep(700)
            env.running = false
        }

        val run = env.cli("logs", "--follow", "--timeout", "10", "--json")

        assertEquals(0, run.exitCode)
        val lines = run.stdout.trim().lines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("old", "new line"), lines.dropLast(1).map { it.getValue("line").jsonPrimitive.content })
        assertEquals("game_exited", run.data.jsonObject.getValue("stoppedBecause").jsonPrimitive.content)
        assertEquals(2, run.data.jsonObject.getValue("linesPrinted").jsonPrimitive.int)
    }

    @Test
    fun `skill prints the bundled agent guide or writes it to a folder`() = TestGame().use { game ->
        val env = FakeEnvironment(game)

        val printed = env.cli("skill")
        assertEquals(0, printed.exitCode)
        assertTrue(printed.stdout.startsWith("---\nname: nomm-cli"))

        val target = File(game.root, "skills/nomm-cli")
        assertEquals(0, env.cli("skill", "--output", target.path).exitCode)
        assertEquals(printed.stdout.trimEnd(), File(target, "SKILL.md").readText().trimEnd())
    }

    @Test
    fun `launch waits for the game process`() = TestGame().use { game ->
        val env = FakeEnvironment(game)

        val run = env.cli("launch", "--wait", "--json")

        assertEquals(0, run.exitCode)
        assertTrue(env.launched)
        assertTrue(run.data.jsonObject.getValue("running").jsonPrimitive.boolean)
    }
}
