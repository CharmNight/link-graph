package com.charmnight.linkgraph

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

object LinkGraphBundle : DynamicBundle("messages.LinkGraphBundle") {
    private const val BUNDLE = "messages.LinkGraphBundle"

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        return getMessage(key, *params)
    }
}
