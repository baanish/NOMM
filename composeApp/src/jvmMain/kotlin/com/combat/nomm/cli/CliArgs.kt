package com.combat.nomm.cli

internal class CliUsageException(message: String) : Exception(message)

internal class OptionSpec(
    val flags: Set<String> = emptySet(),
    val values: Set<String> = emptySet(),
)

internal val globalOptions = OptionSpec(
    flags = setOf("json", "offline", "wait-for-game", "verbose", "help"),
    values = setOf("game-dir"),
)

/** Every option that takes a value, so they parse the same way before the command is known. */
private val valueOptions = setOf("game-dir", "id", "version", "name", "dependency", "limit", "source", "lines", "timeout", "grep", "output")

private val shortOptions = mapOf("h" to "help", "n" to "lines")

internal class ParsedArgs(
    val positionals: List<String>,
    private val flags: Set<String>,
    private val values: Map<String, List<String>>,
) {
    val optionNames: Set<String> get() = flags + values.keys

    fun has(flag: String) = flag in flags

    fun value(name: String): String? = values[name]?.lastOrNull()

    fun values(name: String): List<String> = values[name].orEmpty()

    fun int(name: String, default: Int): Int {
        val raw = value(name) ?: return default
        return raw.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw CliUsageException("--$name needs a whole number, got \"$raw\".")
    }

    fun seconds(name: String): Long? {
        val raw = value(name) ?: return null
        return raw.toLongOrNull()?.takeIf { it > 0 }
            ?: throw CliUsageException("--$name needs a number of seconds, got \"$raw\".")
    }
}

internal fun parseArgs(args: List<String>): ParsedArgs {
    val positionals = mutableListOf<String>()
    val flags = mutableSetOf<String>()
    val values = mutableMapOf<String, MutableList<String>>()

    var index = 0
    var optionsEnded = false
    while (index < args.size) {
        val arg = args[index++]
        if (optionsEnded || arg == "-" || !arg.startsWith("-")) {
            positionals.add(arg)
            continue
        }
        if (arg == "--") {
            optionsEnded = true
            continue
        }

        val body = if (arg.startsWith("--")) arg.substring(2) else arg.substring(1)
        val rawName = body.substringBefore('=')
        val name = if (arg.startsWith("--")) rawName else shortOptions[rawName] ?: rawName
        val inlineValue = if ('=' in body) body.substringAfter('=') else null

        if (name in valueOptions) {
            val value = inlineValue ?: args.getOrNull(index++)
                ?: throw CliUsageException("--$name needs a value.")
            values.getOrPut(name) { mutableListOf() }.add(value)
        } else {
            if (inlineValue != null) throw CliUsageException("--$name doesn't take a value.")
            flags.add(name)
        }
    }
    return ParsedArgs(positionals, flags, values)
}

internal fun ParsedArgs.requireOnly(command: String, spec: OptionSpec) {
    val allowed = globalOptions.flags + globalOptions.values + spec.flags + spec.values
    val unknown = optionNames - allowed
    if (unknown.isNotEmpty()) {
        throw CliUsageException("Unknown option for $command: ${unknown.joinToString { "--$it" }}")
    }
}
