const COMPACT_AFTER_READS = 64;

export class FifoQueue<T> {
  private items: T[];
  private readCursor = 0;

  constructor(initialItems: T[] = []) {
    this.items = [...initialItems];
  }

  enqueue(...nextItems: T[]): void {
    this.items.push(...nextItems);
  }

  dequeue(): T | undefined {
    if (this.readCursor >= this.items.length) {
      return undefined;
    }
    const nextItem = this.items[this.readCursor];
    this.readCursor += 1;
    this.compactIfNeeded();
    return nextItem;
  }

  private compactIfNeeded(): void {
    if (this.readCursor < COMPACT_AFTER_READS) {
      return;
    }
    if (this.readCursor * 2 < this.items.length) {
      return;
    }
    this.items = this.items.slice(this.readCursor);
    this.readCursor = 0;
  }
}
