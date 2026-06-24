package com.charmnight.linkgraph

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

/**
 * 项目国际化文案的统一访问入口。
 *
 * 所有面向用户展示的文案（菜单、提示、错误等）都应通过本对象读取，
 * 而不是直接硬编码字符串。这样切换语言只需替换资源包，不必改代码。
 * 继承 DynamicBundle 让 IntelliJ 平台自动处理资源加载与缓存。
 */
object LinkGraphBundle : DynamicBundle("messages.LinkGraphBundle") {
    /** 定义插件文案所使用的资源包名称。 */
    private const val BUNDLE = "messages.LinkGraphBundle"

    /**
     * 根据资源键读取文案，并将可变参数注入到模板中。
     *
     * @param key 资源键（必须在 LinkGraphBundle 中存在）
     * @param params 模板可变参数，按位置替换 {0}/{1}/...
     * @return 替换后的文案
     */
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        // 统一委托给 DynamicBundle 完成消息查找与参数替换。
        return getMessage(key, *params)
    }
}
