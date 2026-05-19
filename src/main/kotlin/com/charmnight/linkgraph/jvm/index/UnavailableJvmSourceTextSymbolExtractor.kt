package com.charmnight.linkgraph.jvm.index

object UnavailableJvmSourceTextSymbolExtractor : JvmSourceTextSymbolExtractor {
    override fun extract(path: String, text: String): List<JvmSourceTextSymbol> = emptyList()
}
