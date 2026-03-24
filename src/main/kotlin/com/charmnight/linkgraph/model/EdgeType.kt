package com.charmnight.linkgraph.model

enum class EdgeType {
    CALL,
    IMPLEMENTS,
    INJECT,
    ROUTES_TO,
    MAPS_TO_SQL,
    PUBLISHES_TO,
    CONSUMES_FROM,
    BINDS_CONFIG,
    LINKS_DOC,
    USES_PROXY,
    REFLECTS_TO,
    SPI_RESOLVES_TO,
    GENERATES,
}
