import WorkerElk from "elkjs/lib/elk-api";
import type { ELK } from "elkjs/lib/elk-api";
import elkWorkerUrl from "elkjs/lib/elk-worker.min.js?url";

interface ElkWorkerFactoryEnvironment {
  Worker?: typeof Worker;
}

export function createElkWorkerFactory(
  environment: ElkWorkerFactoryEnvironment = globalThis,
  workerUrl: string = elkWorkerUrl,
): ((url?: string) => Worker) | null {
  if (!environment.Worker) {
    return null;
  }
  return () => new environment.Worker!(workerUrl);
}

export async function createElkLayoutEngine(
  environment: ElkWorkerFactoryEnvironment = globalThis,
  workerUrl: string = elkWorkerUrl,
): Promise<ELK> {
  const workerFactory = createElkWorkerFactory(environment, workerUrl);
  if (!workerFactory) {
    if (import.meta.env.DEV || import.meta.env.MODE === "test") {
      return createBundledElkLayoutEngine();
    }
    throw new Error("当前浏览器环境不支持 Web Worker，无法运行 ELK 图布局。");
  }
  try {
    return new WorkerElk({ workerFactory });
  } catch (error) {
    if (!(import.meta.env.DEV || import.meta.env.MODE === "test")) {
      throw error;
    }
    return createBundledElkLayoutEngine();
  }
}

async function createBundledElkLayoutEngine(): Promise<ELK> {
  const { default: BundledElk } = await import("elkjs/lib/elk.bundled.js");
  return new BundledElk();
}
