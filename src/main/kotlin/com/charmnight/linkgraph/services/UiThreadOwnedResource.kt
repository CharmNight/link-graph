package com.charmnight.linkgraph.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import java.util.concurrent.atomic.AtomicReference

internal interface UiThreadExecutor {
    /**
     * 在 UI 线程同步执行动作并返回结果。
     */
    fun <T> invokeAndWait(action: () -> T): T

    /**
     * 在 UI 线程异步执行动作。
     */
    fun invokeLater(action: () -> Unit)
}

/**
 * 基于 IntelliJ 应用线程模型实现的 UI 线程执行器。
 */
internal class IntelliJUiThreadExecutor : UiThreadExecutor {
    /**
     * 同步在 UI 线程执行动作。
     */
    override fun <T> invokeAndWait(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            return action()
        }
        // 用原子引用跨线程回传结果和异常。
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable?>()
        application.invokeAndWait(
            {
                try {
                    result.set(action())
                } catch (throwable: Throwable) {
                    error.set(throwable)
                }
            },
            ModalityState.any(),
        )
        error.get()?.let { throw it }
        return result.get()
    }

    /**
     * 异步在 UI 线程执行动作。
     */
    override fun invokeLater(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            action()
            return
        }
        application.invokeLater(action, ModalityState.any())
    }
}

/**
 * Swing/JCEF 资源必须归属 UI 线程。这个宿主把创建、存在性判断和后续访问都收敛到同一线程边界，
 * 避免出现“后台线程读一个 UI 字段，EDT 再去写它”的隐式数据竞争。
 */
internal class UiThreadOwnedResource<T : Any>(
    /** 保存 UI 线程执行器。 */
    private val uiThreadExecutor: UiThreadExecutor,
    /** 保存资源创建工厂。 */
    private val factory: () -> T,
    /** 保存资源释放动作。 */
    private val disposer: (T) -> Unit = {},
) {
    /** 保存当前持有的资源实例。 */
    private var resource: T? = null

    /**
     * 获取资源；不存在时在 UI 线程内创建。
     */
    fun getOrCreate(
        onCreated: (T) -> Unit = {},
    ): T {
        return uiThreadExecutor.invokeAndWait {
            // 只在首次访问时创建资源，并把创建回调也放在同一线程边界内。
            resource ?: factory().also { created ->
                resource = created
                onCreated(created)
            }
        }
    }

    /**
     * 判断给定实例是否就是当前持有的资源。
     */
    fun isCurrent(candidate: T): Boolean {
        return uiThreadExecutor.invokeAndWait { resource === candidate }
    }

    /**
     * 如果资源已经存在，则在 UI 线程异步执行操作。
     */
    fun withExisting(action: (T) -> Unit) {
        uiThreadExecutor.invokeLater {
            resource?.let(action)
        }
    }

    /**
     * 释放当前资源，并清空宿主引用。
     */
    fun release() {
        uiThreadExecutor.invokeAndWait {
            val current = resource ?: return@invokeAndWait
            resource = null
            disposer(current)
        }
    }
}
