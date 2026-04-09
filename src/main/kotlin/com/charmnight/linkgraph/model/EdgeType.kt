package com.charmnight.linkgraph.model

/**
 * 定义链路图中边的语义类型。
 */
enum class EdgeType {
    /** 表示方法调用或一般调用关系。 */
    CALL,
    /** 表示流程容器与流程步骤之间的包含关系。 */
    CONTAINS_FLOW,
    /** 表示流程节点之间的控制流关系。 */
    CONTROL_FLOW,
    /** 表示类型实现关系。 */
    IMPLEMENTS,
    /** 表示依赖注入关系。 */
    INJECT,
    /** 表示 HTTP 或网关路由指向关系。 */
    ROUTES_TO,
    /** 表示代码逻辑映射到 SQL 资源。 */
    MAPS_TO_SQL,
    /** 表示消息发布关系。 */
    PUBLISHES_TO,
    /** 表示消息消费关系。 */
    CONSUMES_FROM,
    /** 表示配置绑定关系。 */
    BINDS_CONFIG,
    /** 表示文档关联关系。 */
    LINKS_DOC,
    /** 表示通过代理间接使用目标。 */
    USES_PROXY,
    /** 表示反射调用或反射映射关系。 */
    REFLECTS_TO,
    /** 表示 SPI 解析后的实际落点关系。 */
    SPI_RESOLVES_TO,
    /** 表示根据图信息生成代码或产物。 */
    GENERATES,
}
