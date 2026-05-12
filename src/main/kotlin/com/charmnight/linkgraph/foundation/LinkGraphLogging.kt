package com.charmnight.linkgraph.foundation

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
