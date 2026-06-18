/**
 * Binary min-heap priority queue used by the orthogonal edge router (A*).
 *
 * Replaces the previous implementation which re-sorted a plain array on every
 * expansion (`openStates.sort(...)` followed by `shift()`), an O(n log n) cost
 * paid on *every* node popped from the open set. On dense graphs this was the
 * dominant CPU cost during pan/zoom because routing re-runs on every viewport
 * change. A binary heap brings `pop` / `push` down to O(log n).
 *
 * Entries are compared by the caller-supplied `priority` (the A* `cost +
 * estimate`). The `key` field lets callers dedupe / decrease-key by identity.
 */
export interface RouteQueueEntry<State> {
  state: State;
  priority: number;
  /** insertion order, used as a stable tie-breaker so pops are deterministic */
  order: number;
}

export class RoutePriorityQueue<State> {
  private heap: Array<RouteQueueEntry<State>> = [];
  private counter = 0;

  get size(): number {
    return this.heap.length;
  }

  push(state: State, priority: number): void {
    const entry: RouteQueueEntry<State> = { state, priority, order: this.counter++ };
    this.heap.push(entry);
    this.siftUp(this.heap.length - 1);
  }

  pop(): State | undefined {
    if (this.heap.length === 0) {
      return undefined;
    }
    const top = this.heap[0]!;
    const last = this.heap.pop()!;
    if (this.heap.length > 0) {
      this.heap[0] = last;
      this.siftDown(0);
    }
    return top.state;
  }

  clear(): void {
    this.heap.length = 0;
    this.counter = 0;
  }

  private siftUp(index: number): void {
    const heap = this.heap;
    let current = index;
    while (current > 0) {
      const parent = (current - 1) >> 1;
      if (this.entryHasPriority(heap[current]!, heap[parent]!)) {
        const swap = heap[parent]!;
        heap[parent] = heap[current]!;
        heap[current] = swap;
        current = parent;
        continue;
      }
      break;
    }
  }

  private siftDown(index: number): void {
    const heap = this.heap;
    const length = heap.length;
    let current = index;
    for (;;) {
      const left = current * 2 + 1;
      const right = left + 1;
      let smallest = current;
      if (left < length && this.entryHasPriority(heap[left]!, heap[smallest]!)) {
        smallest = left;
      }
      if (right < length && this.entryHasPriority(heap[right]!, heap[smallest]!)) {
        smallest = right;
      }
      if (smallest === current) {
        return;
      }
      const swap = heap[smallest]!;
      heap[smallest] = heap[current]!;
      heap[current] = swap;
      current = smallest;
    }
  }

  private entryHasPriority(a: RouteQueueEntry<State>, b: RouteQueueEntry<State>): boolean {
    if (a.priority !== b.priority) {
      return a.priority < b.priority;
    }
    // Stable ordering: earlier-inserted entries win ties so the route is
    // deterministic across runs (important for snapshot tests).
    return a.order < b.order;
  }
}
