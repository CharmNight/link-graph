package com.charmnight.linkgraph.foundation

/**
 * 调试环境变量解析工具。
 *
 * 支持多种常见格式：
 * 1) 直接赋值：`NAME=true`；
 * 2) 单值开关：`NAME=true`（值只要首段为 true 即视为开启）；
 * 3) 复合赋值：`OTHER_VAR=... NAME=true ...`（一条环境变量里塞多个开关）。
 *
 * 这种宽松解析让用户可以用 IDE 启动参数、shell export、运行配置等方式打开调试，
 * 不需要严格遵循单一格式。
 */
object LinkGraphDebugEnvironment {
    /**
     * 判断某个环境变量名是否被开启（值为 true）。
     *
     * @param envName 环境变量名
     * @param environment 环境变量映射；默认取系统 env
     * @return true 表示该变量被显式开启
     */
    fun isEnabled(
        envName: String,
        environment: Map<String, String> = System.getenv(),
    ): Boolean {
        val directValue = environment[envName]
        // 形式 1：直接是 "true"
        if (directValue.isEnabledToken()) {
            return true
        }
        // 形式 2：直接赋值 NAME=true
        if (directValue != null && directValue.hasAssignment(envName)) {
            return true
        }
        // 形式 3：复合赋值，其他 env 值里包含 NAME=true
        return environment.values.any { value -> value.hasAssignment(envName) }
    }

    /**
     * 取某个环境变量的字符串值。
     *
     * 优先取直接赋值；其次从复合赋值中解析；都没有时返回 null。
     */
    fun value(
        envName: String,
        environment: Map<String, String> = System.getenv(),
    ): String? {
        val directValue = environment[envName]?.trim()
        // 直接值不含赋值语法时直接用
        if (!directValue.isNullOrBlank() && !directValue.containsAssignmentSyntax()) {
            return directValue
        }
        // 否则尝试从复合赋值中解析
        return assignmentValue(envName, environment.values)
    }

    /** 判断字符串首段（按空白/分号切分）是否等于 "true"。 */
    private fun String?.isEnabledToken(): Boolean =
        this
            ?.trim()
            ?.split(Regex("""[\s;]+"""))
            ?.firstOrNull()
            ?.equals("true", ignoreCase = true) == true

    /** 判断字符串中是否含有 "NAME=true" 形式的赋值并取其值。 */
    private fun String.hasAssignment(envName: String): Boolean =
        assignmentValue(envName, listOf(this))?.equals("true", ignoreCase = true) == true

    /** 判断字符串是否包含赋值语法（NAME=... 形式）。 */
    private fun String.containsAssignmentSyntax(): Boolean =
        Regex("""\b[A-Z][A-Z0-9_]*=""").containsMatchIn(this)

    /**
     * 从多个候选字符串中解析 "NAME=value" 形式的赋值。
     * 返回第一个匹配且非空白的值。
     */
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
