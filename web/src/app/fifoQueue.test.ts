import { describe, expect, it } from "vitest";
import { FifoQueue } from "./fifoQueue";

describe("FifoQueue", () => {
  it("dequeues values in insertion order across interleaved pushes", () => {
    const queue = new FifoQueue<number>([1, 2]);

    expect(queue.dequeue()).toBe(1);

    queue.enqueue(3, 4);

    expect(queue.dequeue()).toBe(2);
    expect(queue.dequeue()).toBe(3);
    expect(queue.dequeue()).toBe(4);
    expect(queue.dequeue()).toBeUndefined();
  });

  it("continues draining correctly after the read cursor advances far into the queue", () => {
    const queue = new FifoQueue<number>();

    for (let index = 0; index < 256; index += 1) {
      queue.enqueue(index);
    }
    for (let index = 0; index < 192; index += 1) {
      expect(queue.dequeue()).toBe(index);
    }

    queue.enqueue(256, 257);

    for (let index = 192; index < 258; index += 1) {
      expect(queue.dequeue()).toBe(index);
    }
    expect(queue.dequeue()).toBeUndefined();
  });
});
