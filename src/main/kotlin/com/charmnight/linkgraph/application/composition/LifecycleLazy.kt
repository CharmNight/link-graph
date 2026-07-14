package com.charmnight.linkgraph.application.composition

/**
 * 带明确销毁边界的 lazy bean。
 *
 * 初始化与 dispose 共享同一状态锁：未使用的值不会因销毁而初始化，正在初始化的值会在
 * 发布后被 dispose 取得并释放，销毁后的再次访问会立即失败。
 */
internal class LifecycleLazy<T>(
    private val initializer: () -> T,
    private val disposer: (T) -> Unit,
) : Lazy<T> {
    private sealed interface State<out T> {
        data object Uninitialized : State<Nothing>
        data class Initialized<T>(val value: T) : State<T>
        data object Disposed : State<Nothing>
    }

    private val lock = Any()

    @Volatile
    private var state: State<T> = State.Uninitialized

    override val value: T
        get() = synchronized(lock) {
            when (val current = state) {
                State.Uninitialized -> initializer().also { initialized ->
                    state = State.Initialized(initialized)
                }
                is State.Initialized -> current.value
                State.Disposed -> error("Lifecycle lazy bean has already been disposed")
            }
        }

    override fun isInitialized(): Boolean = state is State.Initialized<*>

    fun dispose() {
        val initializedState = synchronized(lock) {
            when (val current = state) {
                State.Uninitialized -> {
                    state = State.Disposed
                    null
                }
                is State.Initialized -> {
                    state = State.Disposed
                    current
                }
                State.Disposed -> null
            }
        }
        initializedState?.let { initialized -> disposer(initialized.value) }
    }
}
