package com.charmnight.linkgraph.investigation.application

import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.application.InvestigationRequest

/**
 * 根据风险线程文本规划确定性的取证目标。
 */
class EvidenceGoalPlanner {
    /**
     * 把继续取证请求转换成一组 resolver 可消费的目标。
     */
    fun plan(request: InvestigationRequest): List<EvidenceGoal> {
        val text = request.searchText()
        val structuredGoals = structuredGoals(request, text)
        if (structuredGoals.isNotEmpty()) {
            return structuredGoals.distinctBy { goal ->
                listOf(goal.kind.name, goal.symbolSignature, goal.interfaceName, goal.eventClassName).joinToString("|")
            }
        }
        val enumGoals = enumConstantPattern.findAll(text)
            .mapIndexed { index, match ->
                EvidenceGoal(
                    goalId = "${request.threadId}-enum-$index",
                    kind = EvidenceGoalKind.ENUM_CONSTANT,
                    sourceThreadId = request.threadId,
                    claim = "确认 ${match.groupValues[1]}.${match.groupValues[2]} 是否存在于真实源码。",
                    symbolName = match.groupValues[1],
                    memberName = match.groupValues[2],
                    targetNodeIds = request.targetNodeIds,
                )
            }
            .toList()
        if (enumGoals.isNotEmpty()) {
            return enumGoals.distinctBy { goal -> "${goal.symbolName}.${goal.memberName}" }
        }
        val bareMethodGoals = bareMethodGoals(request, text)
        if (bareMethodGoals.isNotEmpty()) {
            return bareMethodGoals
        }
        return listOf(
            EvidenceGoal(
                goalId = "${request.threadId}-unknown",
                kind = EvidenceGoalKind.UNKNOWN,
                sourceThreadId = request.threadId,
                claim = request.evidenceGap.ifBlank { request.question },
                targetNodeIds = request.targetNodeIds,
            ),
        )
    }

    /**
     * 从结构化签名或明确机制描述中规划目标，避免裸关键词触发代码搜索。
     */
    private fun structuredGoals(
        request: InvestigationRequest,
        text: String,
    ): List<EvidenceGoal> {
        val goals = mutableListOf<EvidenceGoal>()
        methodSignaturePattern.findAll(text).forEachIndexed { index, match ->
            val parsed = parseMethodSignature(match.value) ?: return@forEachIndexed
            val lowerText = text.lowercase()
            val kind = when {
                lowerText.contains("反射") || lowerText.contains("reflection") ->
                    EvidenceGoalKind.REFLECTION_CALL
                lowerText.contains("event") || lowerText.contains("事件") || lowerText.contains("监听") ->
                    EvidenceGoalKind.SPRING_EVENT
                lowerText.contains("重写") || lowerText.contains("实现") || lowerText.contains("override") ->
                    EvidenceGoalKind.METHOD_OVERRIDE
                else -> EvidenceGoalKind.METHOD_SYMBOL
            }
            goals += parsed.toGoal(
                request = request,
                index = index,
                kind = kind,
                claim = "确认方法 ${parsed.signature} 是否存在于真实源码。",
            )
        }
        spiPattern.findAll(text).forEachIndexed { index, match ->
            val interfaceName = match.groupValues[1]
            goals += EvidenceGoal(
                goalId = "${request.threadId}-spi-$index",
                kind = EvidenceGoalKind.SPI_BINDING,
                sourceThreadId = request.threadId,
                claim = "确认 Java SPI $interfaceName 的 provider 绑定。",
                interfaceName = interfaceName,
                targetNodeIds = request.targetNodeIds,
            )
        }
        eventClassPattern.findAll(text).forEachIndexed { index, match ->
            val eventClassName = match.groupValues[1]
            if (goals.any { goal -> goal.kind == EvidenceGoalKind.SPRING_EVENT && goal.eventClassName == eventClassName }) {
                return@forEachIndexed
            }
            goals += EvidenceGoal(
                goalId = "${request.threadId}-event-$index",
                kind = EvidenceGoalKind.SPRING_EVENT,
                sourceThreadId = request.threadId,
                claim = "确认 Spring Event $eventClassName 的发布与监听关系。",
                eventClassName = eventClassName,
                targetNodeIds = request.targetNodeIds,
            )
        }
        return goals
    }

    /**
     * 规划只有类名和方法名的弱方法目标；该目标只允许进入 resolver 多候选判断。
     */
    private fun bareMethodGoals(
        request: InvestigationRequest,
        text: String,
    ): List<EvidenceGoal> {
        return bareMethodPattern.findAll(text)
            .mapIndexed { index, match ->
                val ownerName = match.groupValues[1]
                val methodName = match.groupValues[2]
                EvidenceGoal(
                    goalId = "${request.threadId}-method-name-$index",
                    kind = EvidenceGoalKind.METHOD_SYMBOL,
                    sourceThreadId = request.threadId,
                    claim = "确认方法 $ownerName.$methodName 是否存在于真实源码。",
                    ownerClassName = ownerName,
                    methodName = methodName,
                    targetNodeIds = request.targetNodeIds,
                )
            }
            .distinctBy { goal -> "${goal.ownerClassName}.${goal.methodName}" }
            .toList()
    }

    /**
     * 解析 `Owner.method(args):returnType` 方法签名。
     */
    private fun parseMethodSignature(signature: String): ParsedMethodSignature? {
        val match = methodSignaturePattern.matchEntire(signature.trim()) ?: return null
        val ownerClassName = match.groupValues[1]
        val methodName = match.groupValues[2]
        val parameterTypes = match.groupValues[3]
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
        val returnType = match.groupValues[4]
        return ParsedMethodSignature(
            signature = signature,
            ownerClassName = ownerClassName,
            methodName = methodName,
            parameterTypes = parameterTypes,
            returnType = returnType,
        )
    }

    /**
     * 把解析后的方法签名转换为取证目标。
     */
    private fun ParsedMethodSignature.toGoal(
        request: InvestigationRequest,
        index: Int,
        kind: EvidenceGoalKind,
        claim: String,
    ): EvidenceGoal {
        return EvidenceGoal(
            goalId = "${request.threadId}-${kind.name.lowercase()}-$index",
            kind = kind,
            sourceThreadId = request.threadId,
            claim = claim,
            ownerClassName = ownerClassName,
            methodName = methodName,
            parameterTypes = parameterTypes,
            returnType = returnType,
            symbolSignature = signature,
            callsiteSignature = if (kind == EvidenceGoalKind.REFLECTION_CALL || kind == EvidenceGoalKind.SPRING_EVENT) {
                signature
            } else {
                null
            },
            targetNodeIds = request.targetNodeIds,
        )
    }

    /**
     * 汇总风险线程内可用于规划的文本。
     */
    private fun InvestigationRequest.searchText(): String {
        return listOf(
            question,
            title,
            summary,
            evidenceGap,
            recommendedQuestion,
        )
            .plus(evidenceClaims)
            .plus(targetHints.flatMap { hint -> listOfNotNull(hint.title, hint.signature) })
            .joinToString("\n")
    }

    private companion object {
        /** 匹配 Java/Kotlin 代码里的枚举常量写法，例如 TaskQueueEventType.ADD。 */
        private val enumConstantPattern = Regex("""\b([A-Z][A-Za-z0-9_]*)(?:\s*\.\s*|\s+)([A-Z][A-Z0-9_]*)\b""")
        /** 匹配完整 Java 方法签名。 */
        private val methodSignaturePattern =
            Regex("""\b([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.([A-Za-z_$][\w$]*)\(([^)]*)\):([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\[\])?|void|boolean|byte|char|double|float|int|long|short)\b""")
        /** 匹配没有参数列表的方法名提示。 */
        private val bareMethodPattern =
            Regex("""\b([A-Z][A-Za-z0-9_$]*(?:\.[A-Z][A-Za-z0-9_$]*)*)\.([a-z_$][\w$]*)\b""")
        /** 匹配明确 SPI 接口提示。 */
        private val spiPattern =
            Regex("""(?i)\bSPI\s+([a-zA-Z_$][\w$]*(?:\.[a-zA-Z_$][\w$]*)+)\b""")
        /** 匹配事件类型提示。 */
        private val eventClassPattern =
            Regex("""\b([A-Z][A-Za-z0-9_$]*(?:\.[A-Z][A-Za-z0-9_$]*)*Event)\b""")
    }

    /**
     * 保存解析后的方法签名结构。
     */
    private data class ParsedMethodSignature(
        /** 保存原始完整签名。 */
        val signature: String,
        /** 保存所属类名。 */
        val ownerClassName: String,
        /** 保存方法名。 */
        val methodName: String,
        /** 保存参数类型列表。 */
        val parameterTypes: List<String>,
        /** 保存返回类型。 */
        val returnType: String,
    )
}
