package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle

/**
 * 负责兼容 Kotlin light method 的轻量解码器。
 */
class KotlinLightMethodDecoder {
    /**
     * 当前阶段直接透传句柄，保留后续扩展 light method 解码能力的入口。
     */
    fun decode(handle: CodeSubjectHandle): CodeSubjectHandle = handle
}
