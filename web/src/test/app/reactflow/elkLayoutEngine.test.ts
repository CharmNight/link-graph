import { describe, expect, it, vi } from "vitest";
import { createElkWorkerFactory } from "../../../app/reactflow/elkLayoutEngine";

describe("createElkWorkerFactory", () => {
  it("creates a URL-backed worker without inlining the ELK worker source into the entry chunk", () => {
    class FakeWorker {
      readonly url: string;

      constructor(url: string) {
        this.url = url;
      }
    }

    const factory = createElkWorkerFactory(
      {
        Worker: FakeWorker as never,
      },
      "./assets/elk-worker-test.js",
    );

    const worker = factory?.();

    expect(worker).toBeInstanceOf(FakeWorker);
    expect((worker as unknown as InstanceType<typeof FakeWorker>).url).toBe("./assets/elk-worker-test.js");
  });

  it("returns null when a real browser worker cannot be constructed", () => {
    const factory = createElkWorkerFactory({
      Worker: undefined,
    });

    expect(factory).toBeNull();
  });
});
