package com.charmnight.linkgraph.jvm.relation

/**
 * JVM 关系种类枚举。
 *
 * 覆盖：
 * - 结构关系（模块包含包、包包含类）；
 * - 类型关系（继承、实现、类型使用）；
 * - 调用关系（CALLS、TESTS）；
 * - 注解关系（ANNOTATED_BY）；
 * - 框架特化关系（Spring/Dubbo/Feign/MQ 等）；
 * - 反射、SPI、代理等"非显式"关系。
 */
enum class JvmRelationKind {
    /** 模块包含包。 */
    MODULE_CONTAINS_PACKAGE,
    /** 包包含类。 */
    PACKAGE_CONTAINS_CLASS,
    /** 类继承（extends）。 */
    EXTENDS,
    /** 类实现接口（implements）。 */
    IMPLEMENTS,
    /** 类使用某类型（字段/参数/返回值）。 */
    USES_TYPE,
    /** 依赖注入。 */
    INJECTS,
    /** 方法调用。 */
    CALLS,
    /** 测试方法覆盖生产符号。 */
    TESTS,
    /** 被某注解标注。 */
    ANNOTATED_BY,
    /** SPI 提供实现（META-INF/services）。 */
    SPI_PROVIDES,
    /** ServiceLoader 加载接口。 */
    SERVICE_LOADER_LOADS,
    /** 反射调用目标。 */
    REFLECTS_TO,
    /** 通过代理使用目标（Feign/Dubbo 代理）。 */
    USES_PROXY,
    /** Spring 事件发布。 */
    SPRING_EVENT_PUBLISHES,
    /** Spring 事件监听。 */
    SPRING_EVENT_LISTENS,
    /** Dubbo 服务提供。 */
    DUBBO_PROVIDES,
    /** Dubbo 服务引用。 */
    DUBBO_REFERENCES,
    /** Feign 客户端调用。 */
    FEIGN_CLIENT_CALLS,
    /** Feign 路由指向。 */
    FEIGN_ROUTES_TO,
    /** Spring MVC 路由指向。 */
    SPRING_ROUTES_TO,
    /** MQ 消息发布。 */
    MQ_PUBLISHES,
    /** MQ 消息消费。 */
    MQ_CONSUMES,
    /** 资源绑定（与具体资源文件关联）。 */
    RESOURCE_BINDS,
}

/**
 * 关系置信度。
 *
 * - PROVEN：直接来自代码事实；
 * - RULE_INFERRED：基于规则推断；
 * - AMBIGUOUS：多候选，需要进一步确认；
 * - RUNTIME_REQUIRED：需要运行时才能确认。
 */
enum class JvmRelationConfidence {
    PROVEN,
    RULE_INFERRED,
    AMBIGUOUS,
    RUNTIME_REQUIRED,
}

/** 关系来源：描述该关系是从哪种数据源提取的。 */
enum class JvmRelationSource {
    /** 直接来自 PSI。 */
    PSI,
    /** 来自项目模型（模块/依赖结构）。 */
    PROJECT_MODEL,
    /** 来自资源文件（XML/YAML 等）。 */
    RESOURCE_FILE,
    /** 来自框架规则推断（Spring 注解等）。 */
    FRAMEWORK_RULE,
    /** 来自反编译。 */
    DECOMPILED,
    /** 来自用户附带的 jar。 */
    USER_ATTACHED_JAR,
}

/**
 * 关系证据引用。
 *
 * 指向具体源码位置，便于事后核查"这条关系是怎么得到的"。
 */
data class JvmEvidenceRef(
    /** 文件路径。 */
    val filePath: String?,
    /** 虚拟文件 URL。 */
    val virtualFileUrl: String?,
    /** 起始行号。 */
    val startLine: Int?,
    /** 结束行号。 */
    val endLine: Int?,
    /** 证据说明文本。 */
    val claim: String,
    /** 是否来自反编译。 */
    val decompiled: Boolean = false,
)

/**
 * 一条 JVM 关系。
 *
 * 关系是不可变的；唯一性由 ID 决定。
 * 携带置信度、来源、证据样本与扩展元数据，让 UI 与下游消费方有足够信息判断可信度。
 */
data class JvmRelation(
    /** 关系唯一 ID。 */
    val id: String,
    /** 关系种类。 */
    val kind: JvmRelationKind,
    /** 源符号 ID。 */
    val fromSymbolId: String,
    /** 目标符号 ID。 */
    val toSymbolId: String,
    /** 置信度。 */
    val confidence: JvmRelationConfidence,
    /** 来源。 */
    val source: JvmRelationSource,
    /** 关系出现次数（同一调用点多次出现时累加）。 */
    val count: Int = 1,
    /** 证据样本列表（不会保留全部，避免膨胀）。 */
    val samples: List<JvmEvidenceRef> = emptyList(),
    /** 扩展元数据。 */
    val metadata: Map<String, String> = emptyMap(),
)
