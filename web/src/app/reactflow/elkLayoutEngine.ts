import WorkerElk from "elkjs/lib/elk-api";
import BundledElk from "elkjs/lib/elk.bundled.js";
import elkWorkerSource from "elkjs/lib/elk-worker.min.js?raw";

interface ElkWorkerFactoryEnvironment {
  Blob?: typeof Blob;
  Worker?: typeof Worker;
  URL?: Pick<typeof URL, "createObjectURL" | "revokeObjectURL">;
}

export function createElkWorkerFactory(
  environment: ElkWorkerFactoryEnvironment = globalThis,
  workerSource: string = elkWorkerSource,
): ((url?: string) => Worker) | null {
  if (!environment.Worker || !environment.Blob || !environment.URL?.createObjectURL) {
    return null;
  }
  return () => {
    const workerBlob = new environment.Blob!([workerSource], { type: "text/javascript" });
    const workerUrl = environment.URL!.createObjectURL(workerBlob);
    const worker = new environment.Worker!(workerUrl);
    environment.URL?.revokeObjectURL?.(workerUrl);
    return worker;
  };
}

export function createElkLayoutEngine() {
  const workerFactory = createElkWorkerFactory();
  if (!workerFactory) {
    return new BundledElk();
  }
  return new WorkerElk({ workerFactory });
}
