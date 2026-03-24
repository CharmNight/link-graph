package com.charmnight.linkgraph.model

data class GraphUncertainty(
    val reason: String,
    val confidence: Double? = null,
)
