/**
 * 二叉最小堆优先队列，正交边路由器（A*）使用。
 *
 * 替换了之前的实现：旧版本每次从开放集中弹出节点前都对数组重新排序
 * （`openStates.sort(...)` 后 `shift()`），每次弹出的代价是 O(n log n)。
 * 在密集图上，平移/缩放会触发重新路由，这个代价成为主要 CPU 开销。
 * 二叉堆把 `pop` / `push` 的代价降到 O(log n)。
 *
 * 元素之间按调用方提供的 `priority`（A* 中的 cost + estimate）比较。
 * `key` 字段让调用方可以按身份去重 / 降低优先级。
 */
export interface RouteQueueEntry<State> {
  /** 队列元素携带的状态（例如 A* 中的节点位置）。 */
  state: State;
  /** 优先级数值；越小越优先。 */
  priority: number;
  /** 插入顺序，作为稳定 tie-breaker 让 pop 顺序可复现。 */
  order: number;
}

/**
 * 路由优先队列实现：基于数组的二叉最小堆。
 *
 * 提供 push / pop / clear 三种核心操作，对应 A* 算法中
 * "加入开放集 / 取出最优 / 重置" 的需求。
 * 使用 order 字段做稳定排序，保证同优先级时按插入顺序弹出。
 */
export class RoutePriorityQueue<State> {
  /** 堆数组；下标 0 是堆顶（最小元素）。 */
  private heap: Array<RouteQueueEntry<State>> = [];
  /** 单调递增的插入计数器，用作 order 字段。 */
  private counter = 0;

  /** 当前堆大小。 */
  get size(): number {
    return this.heap.length;
  }

  /**
   * 推入一个状态。
   * @param state 状态对象
   * @param priority 优先级（A* 中的 f 值）
   */
  push(state: State, priority: number): void {
    const entry: RouteQueueEntry<State> = { state, priority, order: this.counter++ };
    this.heap.push(entry);
    // 新元素放在末尾，然后上浮到合适位置
    this.siftUp(this.heap.length - 1);
  }

  /**
   * 弹出优先级最高的状态。
   * 堆为空时返回 undefined。
   */
  pop(): State | undefined {
    if (this.heap.length === 0) {
      return undefined;
    }
    const top = this.heap[0]!;
    // 把末尾元素移到堆顶，然后下沉到合适位置
    const last = this.heap.pop()!;
    if (this.heap.length > 0) {
      this.heap[0] = last;
      this.siftDown(0);
    }
    return top.state;
  }

  /** 清空堆并重置计数器；便于复用避免重复分配。 */
  clear(): void {
    this.heap.length = 0;
    this.counter = 0;
  }

  /**
   * 上浮操作：从给定下标向上调整堆。
   * 用于 push 后把新元素调整到合适位置。
   */
  private siftUp(index: number): void {
    const heap = this.heap;
    let current = index;
    while (current > 0) {
      const parent = (current - 1) >> 1;
      if (this.entryHasPriority(heap[current]!, heap[parent]!)) {
        // 当前比父节点小，交换
        const swap = heap[parent]!;
        heap[parent] = heap[current]!;
        heap[current] = swap;
        current = parent;
        continue;
      }
      break;
    }
  }

  /**
   * 下沉操作：从给定下标向下调整堆。
   * 用于 pop 后把堆顶元素调整到合适位置。
   */
  private siftDown(index: number): void {
    const heap = this.heap;
    const length = heap.length;
    let current = index;
    for (;;) {
      const left = current * 2 + 1;
      const right = left + 1;
      let smallest = current;
      // 找出当前、左子、右子中的最小者
      if (left < length && this.entryHasPriority(heap[left]!, heap[smallest]!)) {
        smallest = left;
      }
      if (right < length && this.entryHasPriority(heap[right]!, heap[smallest]!)) {
        smallest = right;
      }
      // 已是最小，结束
      if (smallest === current) {
        return;
      }
      const swap = heap[smallest]!;
      heap[smallest] = heap[current]!;
      heap[current] = swap;
      current = smallest;
    }
  }

  /**
   * 判断条目 a 是否比 b 更优先（更小）。
   * 优先级相同时按插入顺序决定（更早插入的更优先），
   * 让路由结果在多次运行间可复现，便于快照测试。
   */
  private entryHasPriority(a: RouteQueueEntry<State>, b: RouteQueueEntry<State>): boolean {
    if (a.priority !== b.priority) {
      return a.priority < b.priority;
    }
    // 稳定排序：先插入的元素在同优先级时胜出
    return a.order < b.order;
  }
}
