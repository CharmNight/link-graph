package com.charmnight.linkgraph.services

internal object LinkGraphDebugEnvironment {
    fun isEnabled(
        envName: String,
        environment: Map<String, String> = System.getenv(),
    ): Boolean {
        val directValue = environment[envName]
        if (directValue.isEnabledToken()) {
            return true
        }
        if (directValue != null && directValue.hasAssignment(envName)) {
            return true
        }
        return environment.values.any { value -> value.hasAssignment(envName) }
    }

    fun value(
        envName: String,
        environment: Map<String, String> = System.getenv(),
    ): String? {
        val directValue = environment[envName]?.trim()
        if (!directValue.isNullOrBlank() && !directValue.containsAssignmentSyntax()) {
            return directValue
        }
        return assignmentValue(envName, environment.values)
    }

    private fun String?.isEnabledToken(): Boolean =
        this
            ?.trim()
            ?.split(Regex("""[\s;]+"""))
            ?.firstOrNull()
            ?.equals("true", ignoreCase = true) == true

    private fun String.hasAssignment(envName: String): Boolean =
        assignmentValue(envName, listOf(this))?.equals("true", ignoreCase = true) == true

    private fun String.containsAssignmentSyntax(): Boolean =
        Regex("""\b[A-Z][A-Z0-9_]*=""").containsMatchIn(this)

    private fun assignmentValue(
        envName: String,
        values: Iterable<String>,
    ): String? {
        val assignmentRegex = Regex("""(?:^|\s)${Regex.escape(envName)}=([^\s;]+)""")
        return values
            .asSequence()
            .mapNotNull { value -> assignmentRegex.find(value)?.groupValues?.getOrNull(1)?.trim() }
            .firstOrNull { it.isNotBlank() }
    }
}
