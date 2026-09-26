package com.combat.nomm.cli

import com.combat.nomm.ModErrorKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.PrintStream

internal val cliJson = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = false
}

/** A command's result: [data] for --json, [text] for people. */
internal class CommandResult(val data: JsonElement, val text: String)

internal inline fun <reified T> result(data: T, text: String) =
    CommandResult(cliJson.encodeToJsonElement(data), text)

/**
 * stdout carries only the result, as text or one JSON envelope, so it can be parsed. Progress and,
 * in text mode, warnings go to stderr.
 */
internal class CliOutput(val json: Boolean, private val out: PrintStream, private val err: PrintStream) {
    private val warnings = mutableListOf<String>()

    fun warn(message: String) {
        warnings.add(message)
        if (!json) err.println("warning: $message")
    }

    fun progress(message: String) {
        err.println(message)
    }

    /** A line printed as it happens, such as a followed log line. */
    fun stream(data: JsonElement, text: String) {
        out.println(if (json) data.toString() else text)
        out.flush()
    }

    fun success(command: String, result: CommandResult) {
        if (json) {
            out.println(envelope(command, ok = true, data = result.data, error = JsonNull))
        } else if (result.text.isNotEmpty()) {
            out.println(result.text)
        }
        out.flush()
    }

    fun failure(command: String, code: String, message: String) {
        if (json) {
            val error = buildJsonObject {
                put("code", code)
                put("message", message)
            }
            out.println(envelope(command, ok = false, data = JsonNull, error = error))
            out.flush()
        } else {
            err.println("error: $message")
        }
    }

    private fun envelope(command: String, ok: Boolean, data: JsonElement, error: JsonElement) = buildJsonObject {
        put("ok", ok)
        put("command", command)
        put("data", data)
        put("error", error)
        put("warnings", buildJsonArray { warnings.forEach { add(JsonPrimitive(it)) } })
    }
}

internal const val EXIT_OK = 0
internal const val EXIT_USAGE = 2

internal fun ModErrorKind.exitCode(): Int = when (this) {
    ModErrorKind.FAILED -> 1
    ModErrorKind.INVALID -> EXIT_USAGE
    ModErrorKind.NOT_FOUND -> 3
    ModErrorKind.ENVIRONMENT -> 4
    ModErrorKind.GAME_RUNNING -> 5
    ModErrorKind.DOWNLOAD -> 6
    ModErrorKind.BUSY -> 7
}

/** Pads columns to line up; the last column is left ragged. */
internal fun table(header: List<String>, rows: List<List<String>>): String {
    val all = listOf(header) + rows
    val widths = header.indices.map { column -> all.maxOf { it.getOrElse(column) { "" }.length } }
    return all.joinToString("\n") { row ->
        row.mapIndexed { column, cell ->
            if (column == row.lastIndex) cell else cell.padEnd(widths[column])
        }.joinToString("  ").trimEnd()
    }
}
