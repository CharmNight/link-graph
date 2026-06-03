package com.charmnight.linkgraph.jvm.relation

enum class JvmRelationKind {
    MODULE_CONTAINS_PACKAGE,
    PACKAGE_CONTAINS_CLASS,
    EXTENDS,
    IMPLEMENTS,
    USES_TYPE,
    INJECTS,
    CALLS,
    TESTS,
    ANNOTATED_BY,
    SPI_PROVIDES,
    SERVICE_LOADER_LOADS,
    REFLECTS_TO,
    USES_PROXY,
    SPRING_EVENT_PUBLISHES,
    SPRING_EVENT_LISTENS,
    DUBBO_PROVIDES,
    DUBBO_REFERENCES,
    FEIGN_CLIENT_CALLS,
    FEIGN_ROUTES_TO,
    MQ_PUBLISHES,
    MQ_CONSUMES,
    RESOURCE_BINDS,
}

enum class JvmRelationConfidence {
    PROVEN,
    RULE_INFERRED,
    AMBIGUOUS,
    RUNTIME_REQUIRED,
}

enum class JvmRelationSource {
    PSI,
    PROJECT_MODEL,
    RESOURCE_FILE,
    FRAMEWORK_RULE,
    DECOMPILED,
    USER_ATTACHED_JAR,
}

data class JvmEvidenceRef(
    val filePath: String?,
    val virtualFileUrl: String?,
    val startLine: Int?,
    val endLine: Int?,
    val claim: String,
    val decompiled: Boolean = false,
)

data class JvmRelation(
    val id: String,
    val kind: JvmRelationKind,
    val fromSymbolId: String,
    val toSymbolId: String,
    val confidence: JvmRelationConfidence,
    val source: JvmRelationSource,
    val count: Int = 1,
    val samples: List<JvmEvidenceRef> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)
