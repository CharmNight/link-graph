// 读取游标超过此阈值时触发数组压缩，避免无意义频繁 slice。
// 64 次是一个折中：太小会浪费压缩机会，太大会让底层数组持续保留已读元素。
const COMPACT_AFTER_READS = 64;

/**
 * 轻量级先进先出队列。基于数组 + 读游标实现，避免每次出队都调用 shift() 的 O(n) 开销。
 * 适合吞吐量大但消费跟得上的场景（例如事件总线、渲染任务队列）。
 *
 * 压缩策略：当读游标累计超过阈值、且已读数量已经追上数组长度一半时，
 * 一次性 slice 出未读部分重置游标，控制数组膨胀。
 */
export class FifoQueue<T> {
  /** 实际存储元素的可变数组，未读部分位于 [readCursor, items.length)。 */
  private items: T[];
  /** 下一个待读元素的下标。dequeue 时向后推进。 */
  private readCursor = 0;

  /**
   * @param initialItems 初始元素序列，将按传入顺序作为队列初始内容。
   */
  constructor(initialItems: T[] = []) {
    // 拷贝一份避免外部数组与队列内部状态共享引用
    this.items = [...initialItems];
  }

  /**
   * 把若干元素依次追加到队尾。
   * @param nextItems 待入队元素，按参数顺序排列。
   */
  enqueue(...nextItems: T[]): void {
    this.items.push(...nextItems);
  }

  /**
   * 取出并返回队首元素；队列为空时返回 undefined。
   * 读游标会前进一步，并在合适时机触发数组压缩。
   */
  dequeue(): T | undefined {
    // 游标已越过末尾，队列视为空
    if (this.readCursor >= this.items.length) {
      return undefined;
    }
    const nextItem = this.items[this.readCursor];
    this.readCursor += 1;
    this.compactIfNeeded();
    return nextItem;
  }

  /**
   * 触发数组压缩的条件检查与执行。
   * 仅在读次数超过阈值、且已读元素占比足够高时才压缩，
   * 避免在小队列上频繁 slice。
   */
  private compactIfNeeded(): void {
    if (this.readCursor < COMPACT_AFTER_READS) {
      return;
    }
    // 已读数量未达到数组一半，暂时不压缩，等待更多积累
    if (this.readCursor * 2 < this.items.length) {
      return;
    }
    this.items = this.items.slice(this.readCursor);
    this.readCursor = 0;
  }
}
