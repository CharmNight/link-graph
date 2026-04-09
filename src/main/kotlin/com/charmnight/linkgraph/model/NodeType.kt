package com.charmnight.linkgraph.model

/**
 * 定义链路图中节点的语义类型。
 */
enum class NodeType {
    /** 表示方法节点。 */
    METHOD,
    /** 表示流程作用域节点，例如 if、loop 等结构。 */
    FLOW_SCOPE,
    /** 表示流程中的具体动作节点。 */
    FLOW_ACTION,
    /** 表示终止节点，例如 return、throw。 */
    TERMINAL,
    /** 表示流程汇合节点。 */
    MERGE,
    /** 表示类节点。 */
    CLASS,
    /** 表示 SQL 资源节点。 */
    SQL,
    /** 表示 HTTP 接口节点。 */
    HTTP_ENDPOINT,
    /** 表示 Feign 客户端节点。 */
    FEIGN_CLIENT,
    /** 表示 Dubbo 服务节点。 */
    DUBBO_SERVICE,
    /** 表示消息主题节点。 */
    MQ_TOPIC,
    /** 表示消息消费者节点。 */
    MQ_CONSUMER,
    /** 表示配置项节点。 */
    CONFIG_ITEM,
    /** 表示 XML 资源节点。 */
    XML_RESOURCE,
    /** 表示文档页面节点。 */
    DOC_PAGE,
    /** 表示待确认的链路节点。 */
    UNCERTAIN_LINK,
}
