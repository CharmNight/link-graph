import { describe, expect, it, vi } from "vitest";
import { createElkWorkerFactory } from "../../../app/reactflow/elkLayoutEngine";

describe("createElkWorkerFactory", () => {
  it("creates a blob-backed worker factory when the browser supports workers", () => {
    const createObjectURL = vi.fn(() => "blob:elk-worker");
    const revokeObjectURL = vi.fn();
    const createdBlobs: Array<{ parts: BlobPart[]; options?: BlobPropertyBag }> = [];

    class FakeBlob {
      constructor(parts: BlobPart[], options?: BlobPropertyBag) {
        createdBlobs.push({ parts, options });
      }
    }

    class FakeWorker {
      readonly url: string;

      constructor(url: string) {
        this.url = url;
      }
    }

    const factory = createElkWorkerFactory(
      {
        Blob: FakeBlob as never,
        Worker: FakeWorker as never,
        URL: {
          createObjectURL,
          revokeObjectURL,
        },
      },
      "self.onmessage = () => undefined;",
    );

    const worker = factory?.();

    expect(worker).toBeInstanceOf(FakeWorker);
    expect((worker as unknown as InstanceType<typeof FakeWorker>).url).toBe("blob:elk-worker");
    expect(createdBlobs).toEqual([
      {
        parts: ["self.onmessage = () => undefined;"],
        options: { type: "text/javascript" },
      },
    ]);
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:elk-worker");
  });

  it("returns null when a real browser worker cannot be constructed", () => {
    const factory = createElkWorkerFactory({
      Blob,
      Worker: undefined,
      URL: {
        createObjectURL: () => "blob:elk-worker",
        revokeObjectURL: vi.fn(),
      },
    });

    expect(factory).toBeNull();
  });
});
