import { describe, expect, it } from "vitest";
import { RoutePriorityQueue } from "../../../app/reactflow/routePriorityQueue";

describe("RoutePriorityQueue", () => {
  it("pops each queued state once in priority order", () => {
    const queue = new RoutePriorityQueue<string>();

    queue.push("slow", 10);
    queue.push("fast", 1);
    queue.push("middle", 5);

    expect([queue.pop(), queue.pop(), queue.pop()]).toEqual(["fast", "middle", "slow"]);
    expect(queue.pop()).toBeUndefined();
  });

  it("keeps insertion order stable when priorities tie", () => {
    const queue = new RoutePriorityQueue<string>();

    queue.push("first", 1);
    queue.push("second", 1);
    queue.push("third", 1);

    expect([queue.pop(), queue.pop(), queue.pop()]).toEqual(["first", "second", "third"]);
  });
});
