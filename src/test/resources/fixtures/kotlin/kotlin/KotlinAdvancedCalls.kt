package com.charmnight.linkgraph.fixtures.kotlin

class OrderSnapshotStore {
    var lastOrderId: String = ""
}

fun normalizeOrderId(raw: String): String {
    return raw.trim()
}

fun String.decorateSuffix(): String {
    return "$this-ok"
}

class KotlinAdvancedController(
    private val store: OrderSnapshotStore = OrderSnapshotStore(),
) {
    fun run(raw: String): String {
        val normalized = normalizeOrderId(raw)
        val decorated = normalized.decorateSuffix()
        store.lastOrderId = decorated
        return store.lastOrderId
    }
}
