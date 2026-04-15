package com.charmnight.linkgraph.services

internal inline fun debugLazy(
    debugEnabled: Boolean,
    debug: (String) -> Unit,
    message: () -> String,
) {
    if (!debugEnabled) {
        return
    }
    debug(message())
}
