package com.charmnight.linkgraph.application.runtime

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.charmnight.linkgraph.ui.runtime.IntelliJTaskRunnerAdapter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P4-3 TaskRunner / IntelliJTaskRunnerAdapter 测试。
 *
 * 验证：
 * - background 在非 EDT 线程执行
 * - ui 在 EDT 执行且按 policy 不抛异常
 * - read 在读锁内执行（可访问 PSI）
 * - SameThreadTaskRunner 单线程同步执行
 */
class TaskRunnerTest : BasePlatformTestCase() {
    fun testBackgroundRunsOnPooledThread() {
        val runner = IntelliJTaskRunnerAdapter(project)
        val capturedThread = AtomicReference<String>(null)
        runner.background {
            capturedThread.set(Thread.currentThread().name)
            "ok"
        }.get(5, TimeUnit.SECONDS)
        val thread = capturedThread.get()
        assertTrue(thread != null && !thread.contains("AWT-EventQueue"), "应在后台线程执行；实际 $thread")
    }

    fun testUiRunsOnEdt() {
        val runner = IntelliJTaskRunnerAdapter(project)
        val result = runner.ui(TaskRunner.UiPolicy.ANY) {
            val isEdt = com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread
            if (isEdt) "on-edt" else "off-edt"
        }
        assertEquals("on-edt", result)
    }

    fun testReadRunsInReadAction() {
        val runner = IntelliJTaskRunnerAdapter(project)
        val result = runner.read {
            // ReadAction 内部应处于 read action 上下文
            val hasReadAccess = com.intellij.openapi.application.ApplicationManager.getApplication().isReadAccessAllowed
            if (hasReadAccess) "has-read-access" else "no-read-access"
        }
        assertEquals("has-read-access", result)
    }

    fun testSameThreadTaskRunnerRunsSynchronously() {
        val runner = SameThreadTaskRunner()
        val callerThread = Thread.currentThread().name

        val bgThread = runner.background { Thread.currentThread().name }.get(5, TimeUnit.SECONDS)
        assertEquals(callerThread, bgThread)

        val uiThread = runner.ui(TaskRunner.UiPolicy.ANY) { Thread.currentThread().name }
        assertEquals(callerThread, uiThread)

        val readThread = runner.read { Thread.currentThread().name }
        assertEquals(callerThread, readThread)
    }
}
