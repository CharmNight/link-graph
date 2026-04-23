package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.workbench.RiskResolutionStatus
import com.charmnight.linkgraph.workbench.StepGranularity
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object GraphBrowserPayloadParser {
    private const val PAYLOAD_SEPARATOR: String = "\u001F"

    data class GenerationPlanDiscussionPayload(
        val question: String,
        val focusItemId: String?,
    )

    data class AuditRequestPayload(
        val question: String,
        val selectedNodeIds: List<String>,
        val sourceThreadId: String?,
    )

    data class ResolveInvestigationThreadPayload(
        val threadId: String,
        val resolutionStatus: RiskResolutionStatus,
        val note: String,
    )

    data class BeautificationPayload(
        val goal: String,
        val preferredStyle: String?,
        val explanationFocus: String?,
        val followUp: GraphBeautificationFollowUpContext?,
        val granularity: StepGranularity,
    )

    fun parseQuestionWithIds(payload: String): Pair<String, List<String>> {
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 2)
        val question = decodePayloadValue(parts.firstOrNull().orEmpty())
        val selectedNodeIds = parseEncodedList(parts.getOrNull(1).orEmpty())
        return question to selectedNodeIds
    }

    fun parseGenerationPlanDiscussionPayload(payload: String): GenerationPlanDiscussionPayload {
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 2)
        return GenerationPlanDiscussionPayload(
            question = decodePayloadValue(parts.firstOrNull().orEmpty()),
            focusItemId = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue),
        )
    }

    fun parseAuditRequestPayload(payload: String): AuditRequestPayload {
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 3)
        return AuditRequestPayload(
            question = decodePayloadValue(parts.firstOrNull().orEmpty()),
            selectedNodeIds = parseEncodedList(parts.getOrNull(1).orEmpty()),
            sourceThreadId = parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue),
        )
    }

    fun parseResolveInvestigationThreadPayload(payload: String): ResolveInvestigationThreadPayload {
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 3)
        val threadId = decodePayloadValue(parts.firstOrNull().orEmpty()).ifBlank {
            error("风险线程标识不能为空")
        }
        val resolutionStatus = parts.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodePayloadValue)
            ?.let(RiskResolutionStatus::valueOf)
            ?: error("风险决策状态不能为空")
        val note = parts.getOrNull(2)?.let(::decodePayloadValue).orEmpty()
        return ResolveInvestigationThreadPayload(
            threadId = threadId,
            resolutionStatus = resolutionStatus,
            note = note,
        )
    }

    fun parseBeautificationPayload(payload: String): BeautificationPayload {
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 7)
        val goal = decodePayloadValue(parts.getOrNull(0).orEmpty())
        val preferredStyle = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val explanationFocus = parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val granularity = parts.getOrNull(3)
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodePayloadValue)
            ?.let { raw -> runCatching { StepGranularity.valueOf(raw) }.getOrDefault(StepGranularity.BUSINESS) }
            ?: StepGranularity.BUSINESS
        val followUpStepId = parts.getOrNull(4)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val followUpStepTitle = parts.getOrNull(5)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val followUpQuestion = parts.getOrNull(6)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val followUp = if (
            followUpStepId != null &&
            followUpStepTitle != null &&
            followUpQuestion != null
        ) {
            GraphBeautificationFollowUpContext(
                stepId = followUpStepId,
                stepTitle = followUpStepTitle,
                question = followUpQuestion,
            )
        } else {
            null
        }
        return BeautificationPayload(goal, preferredStyle, explanationFocus, followUp, granularity)
    }

    fun parseNullableRevision(payload: String): Long? = payload.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()

    fun parseEncodedList(payload: String): List<String> {
        if (payload.isBlank()) {
            return emptyList()
        }
        return payload
            .split(',')
            .mapNotNull { raw ->
                raw.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
            }
    }

    fun parseLayoutPositions(payload: String): Map<String, GraphLayoutPosition> {
        if (payload.isBlank()) {
            return emptyMap()
        }
        return payload
            .split('\u001e')
            .mapNotNull { entry ->
                val parts = entry.split(PAYLOAD_SEPARATOR)
                if (parts.size != 3) {
                    return@mapNotNull null
                }
                val nodeId = decodePayloadValue(parts[0])
                val x = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                val y = parts[2].toDoubleOrNull() ?: return@mapNotNull null
                nodeId to GraphLayoutPosition(x = x, y = y)
            }
            .toMap()
    }

    private fun decodePayloadValue(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)
}
