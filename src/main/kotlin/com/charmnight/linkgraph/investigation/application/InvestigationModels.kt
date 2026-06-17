package com.charmnight.linkgraph.investigation.application

/**
 * 描述取证请求中来自当前图的结构化目标提示。
 */
data class InvestigationTargetHint(
    /** 保存目标图节点标识。 */
    val nodeId: String,
    /** 保存目标节点标题。 */
    val title: String = "",
    /** 保存目标节点真实符号签名。 */
    val signature: String? = null,
)

/**
 * 描述一次继续取证请求。
 */
data class InvestigationRequest(
    /** 保存风险线程标识，用于把本轮取证结果合并回同一条风险线程。 */
    val threadId: String,
    /** 保存用户本轮继续取证问题。 */
    val question: String,
    /** 保存风险线程标题。 */
    val title: String = "",
    /** 保存风险线程摘要。 */
    val summary: String = "",
    /** 保存当前证据缺口说明。 */
    val evidenceGap: String = "",
    /** 保存系统建议的下一问。 */
    val recommendedQuestion: String = "",
    /** 保存当前风险线程关联的图节点标识。 */
    val targetNodeIds: List<String> = emptyList(),
    /** 保存当前风险线程关联图节点上的结构化符号提示。 */
    val targetHints: List<InvestigationTargetHint> = emptyList(),
    /** 保存当前风险线程已有证据描述。 */
    val evidenceClaims: List<String> = emptyList(),
)

/**
 * 描述取证目标类型。
 */
enum class EvidenceGoalKind {
    /** 表示需要确认枚举常量是否真实存在。 */
    ENUM_CONSTANT,
    /** 表示需要确认普通 Java 方法符号。 */
    METHOD_SYMBOL,
    /** 表示需要确认接口或抽象方法的真实实现。 */
    METHOD_OVERRIDE,
    /** 表示需要确认 Java SPI 绑定。 */
    SPI_BINDING,
    /** 表示需要确认可静态证明的反射调用。 */
    REFLECTION_CALL,
    /** 表示需要确认 Spring Event 发布与监听关系。 */
    SPRING_EVENT,
    /** 表示当前请求无法归类到已支持的确定性目标。 */
    UNKNOWN,
}

/**
 * 描述一个可执行的取证目标。
 */
data class EvidenceGoal(
    /** 保存取证目标标识。 */
    val goalId: String,
    /** 保存取证目标类型。 */
    val kind: EvidenceGoalKind,
    /** 保存上游风险线程标识。 */
    val sourceThreadId: String,
    /** 保存取证目标描述。 */
    val claim: String,
    /** 保存候选类名或符号名提示。 */
    val symbolName: String? = null,
    /** 保存候选成员名提示，例如枚举常量名或方法名。 */
    val memberName: String? = null,
    /** 保存候选所属类全限定名或短类名。 */
    val ownerClassName: String? = null,
    /** 保存候选方法名。 */
    val methodName: String? = null,
    /** 保存候选方法参数类型。 */
    val parameterTypes: List<String> = emptyList(),
    /** 保存候选方法返回类型。 */
    val returnType: String? = null,
    /** 保存完整符号签名。 */
    val symbolSignature: String? = null,
    /** 保存调用点方法签名。 */
    val callsiteSignature: String? = null,
    /** 保存 SPI 接口全限定名。 */
    val interfaceName: String? = null,
    /** 保存事件类型全限定名或短类名。 */
    val eventClassName: String? = null,
    /** 保存关联图节点标识。 */
    val targetNodeIds: List<String> = emptyList(),
)

/**
 * 描述证据强度等级。
 */
enum class EvidenceLevel {
    /** 表示已通过 PSI 精确解析到真实源码符号。 */
    DIRECT_SOURCE_RESOLVED,
    /** 表示已通过框架规则解析到真实绑定。 */
    DIRECT_FRAMEWORK_RESOLVED,
    /** 表示已通过图节点 source mapping 解析到真实源码。 */
    DIRECT_GRAPH_RESOLVED,
    /** 表示已通过配置文件解析到真实绑定。 */
    CONFIG_RESOLVED,
    /** 表示只得到候选，不能进入 LLM 上下文。 */
    CANDIDATE_ONLY,
    /** 表示命中多个候选，不能确认实际目标。 */
    MULTIPLE_CANDIDATES,
    /** 表示没有找到可用证据。 */
    UNRESOLVED,
}

/**
 * 描述一条可进入 LLM 上下文的确定性事实。
 */
data class EvidenceFact(
    /** 保存证据事实标识。 */
    val factId: String,
    /** 保存证据等级。 */
    val level: EvidenceLevel,
    /** 保存解析器标识。 */
    val resolverId: String,
    /** 保存真实符号签名。 */
    val symbolSignature: String,
    /** 保存源码文件路径。 */
    val filePath: String,
    /** 保存源码起始行号。 */
    val startLine: Int?,
    /** 保存源码结束行号。 */
    val endLine: Int?,
    /** 保存事实说明。 */
    val claim: String,
    /** 保存为什么该事实可被接受。 */
    val whyResolved: String,
)

/**
 * 描述一条不能进入 LLM 上下文的候选证据。
 */
data class EvidenceCandidate(
    /** 保存候选标识。 */
    val candidateId: String,
    /** 保存候选符号。 */
    val symbolSignature: String,
    /** 保存候选来源解析器。 */
    val resolverId: String,
    /** 保存候选原因。 */
    val reason: String,
)

/**
 * 描述单个 resolver 的解析结果。
 */
sealed interface ResolutionOutcome {
    /** 保存执行该解析结果的 resolver 标识。 */
    val resolverId: String

    /**
     * 表示 resolver 拿到了确定性事实。
     */
    data class Resolved(
        override val resolverId: String,
        /** 保存确定性事实列表。 */
        val facts: List<EvidenceFact>,
    ) : ResolutionOutcome

    /**
     * 表示 resolver 只拿到了多个候选。
     */
    data class MultipleCandidates(
        override val resolverId: String,
        /** 保存候选列表。 */
        val candidates: List<EvidenceCandidate>,
        /** 保存不能确认的原因。 */
        val reason: String,
        /** 保存继续确认所需证据。 */
        val requiredEvidence: List<String> = emptyList(),
    ) : ResolutionOutcome

    /**
     * 表示 resolver 只拿到了弱候选。
     */
    data class CandidateOnly(
        override val resolverId: String,
        /** 保存候选列表。 */
        val candidates: List<EvidenceCandidate>,
        /** 保存弱证据原因。 */
        val reason: String,
        /** 保存继续确认所需证据。 */
        val requiredEvidence: List<String> = emptyList(),
    ) : ResolutionOutcome

    /**
     * 表示 resolver 没有解析到任何可用证据。
     */
    data class Unresolved(
        override val resolverId: String,
        /** 保存未解析原因。 */
        val reason: String,
        /** 保存继续确认所需证据。 */
        val requiredEvidence: List<String> = emptyList(),
    ) : ResolutionOutcome

    /**
     * 表示 resolver 执行失败。
     */
    data class Failed(
        override val resolverId: String,
        /** 保存失败说明。 */
        val error: String,
    ) : ResolutionOutcome
}

/**
 * 描述证据闸门裁决结果。
 */
sealed interface GateDecision {
    /**
     * 表示存在可进入 LLM 上下文的直接证据。
     */
    data class Accepted(
        /** 保存被接受的事实列表。 */
        val facts: List<EvidenceFact>,
        /** 保存所有原始 resolver 输出，便于 UI 展示过程。 */
        val outcomes: List<ResolutionOutcome>,
    ) : GateDecision

    /**
     * 表示当前没有可进入 LLM 上下文的直接证据。
     */
    data class NeedsMoreEvidence(
        /** 保存所有原始 resolver 输出。 */
        val outcomes: List<ResolutionOutcome>,
        /** 保存继续确认所需证据。 */
        val requiredEvidence: List<String>,
        /** 保存闸门拒绝原因。 */
        val reason: String,
    ) : GateDecision
}

/**
 * 描述继续取证最终状态。
 */
enum class InvestigationStatus {
    /** 表示本轮已拿到直接证据。 */
    RESOLVED,
    /** 表示本轮没有直接证据，需要补充更多信息。 */
    NEEDS_MORE_EVIDENCE,
}

/**
 * 描述一次继续取证的最终结果。
 */
data class InvestigationTurnResult(
    /** 保存上游风险线程标识。 */
    val threadId: String,
    /** 保存本轮取证状态。 */
    val status: InvestigationStatus,
    /** 保存进入 LLM 上下文或确定性总结的直接事实。 */
    val acceptedFacts: List<EvidenceFact>,
    /** 保存候选证据列表。 */
    val candidates: List<EvidenceCandidate>,
    /** 保存需要补充的证据。 */
    val requiredEvidence: List<String>,
    /** 保存所有 resolver 输出，供 UI 展示真实取证轨迹。 */
    val outcomes: List<ResolutionOutcome>,
    /** 保存本轮总结。 */
    val summary: String,
)
