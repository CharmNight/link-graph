package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle

/**
 * 负责兼容 Kotlin light method 的轻量解码器。
 *
 * Kotlin light method 是 IntelliJ PSI 中的一种特殊方法表示（通常由 Java 互操作产生），
 * 直接读取其字段可能拿不到完整签名。本解码器负责把这些 light method 解码为标准的
 * [CodeSubjectHandle]，让上层逻辑可以按统一的方式处理 Kotlin 与 Java 方法。
 */
class KotlinLightMethodDecoder {
    /**
     * 当前阶段直接透传句柄，保留后续扩展 light method 解码能力的入口。
     *
     * @param handle 待解码的方法句柄
     * @return 解码后的句柄；当前实现等同于原样返回
     */
    fun decode(handle: CodeSubjectHandle): CodeSubjectHandle = handle
}
