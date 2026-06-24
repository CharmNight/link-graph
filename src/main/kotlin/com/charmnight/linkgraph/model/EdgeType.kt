package com.charmnight.linkgraph.model

/**
 * 定义链路图中边的语义类型。
 *
 * 每种类型描述图中两个节点之间的一种关系，
 * UI 按类型选择不同的连线样式与图标。
 */
enum class EdgeType {
    /** 表示方法调用或一般调用关系。 */
    CALL,

    /** 表示流程容器与流程步骤之间的包含关系。 */
    CONTAINS_FLOW,

    /** 表示流程节点之间的控制流关系。 */
    CONTROL_FLOW,

    /** 表示类型实现关系（class : Interface）。 */
    IMPLEMENTS,

    /** 表示类型继承关系（class : SuperClass）。 */
    EXTENDS,

    /** 表示类型使用关系（字段/参数/返回值引用某类型）。 */
    USES_TYPE,

    /** 表示依赖注入关系（@Autowired / @Inject 等）。 */
    INJECT,

    /** 表示 HTTP 或网关路由指向关系（Controller → Endpoint）。 */
    ROUTES_TO,

    /** 表示代码逻辑映射到 SQL 资源。 */
    MAPS_TO_SQL,

    /** 表示消息发布关系（Producer → Topic）。 */
    PUBLISHES_TO,

    /** 表示消息消费关系（Topic → Consumer）。 */
    CONSUMES_FROM,

    /** 表示配置绑定关系（@Value/@ConfigurationProperties → 配置项）。 */
    BINDS_CONFIG,

    /** 表示文档关联关系（设计文档 → 节点）。 */
    LINKS_DOC,

    /** 表示通过代理间接使用目标（例如 Feign / Dubbo 代理）。 */
    USES_PROXY,

    /** 表示反射调用或反射映射关系。 */
    REFLECTS_TO,

    /** 表示 SPI 解析后的实际落点关系（接口 → 实现类）。 */
    SPI_RESOLVES_TO,

    /** 表示测试方法覆盖生产符号（test → production）。 */
    TESTS,

    /** 表示根据图信息生成代码或产物（图 → 生成结果）。 */
    GENERATES,
}
