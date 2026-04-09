package com.charmnight.linkgraph

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

/**
 * 项目国际化文案的统一访问入口。
 */
object LinkGraphBundle : DynamicBundle("messages.LinkGraphBundle") {
    /** 定义插件文案所使用的资源包名称。 */
    private const val BUNDLE = "messages.LinkGraphBundle"

    /**
     * 根据资源键读取文案，并将可变参数注入到模板中。
     */
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        // 统一委托给 DynamicBundle 完成消息查找与参数替换。
        return getMessage(key, *params)
    }
}
